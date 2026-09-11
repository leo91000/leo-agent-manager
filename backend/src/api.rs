use crate::{
    config::now,
    error::{Error, Result},
    http::Input,
    service::{Service, next_occurrences},
    validation::{text, uuid},
};
use serde_json::{Value, json};
use std::{path::PathBuf, sync::Arc};
pub async fn dispatch(s: &Arc<Service>, input: &Input) -> Result<Value> {
    if input.path == "/api/mcps" || input.path.starts_with("/api/mcps/") {
        return crate::mcp_server::routes(s, input).await;
    }
    if let Some(result) = crate::worker::routes(s, input).await {
        return result;
    }
    let segments = input
        .path
        .trim_start_matches("/api/")
        .split('/')
        .collect::<Vec<_>>();
    match (input.method.as_str(), segments.as_slice()) {
        ("GET", ["overview"]) => {
            let concurrency = s.config.concurrency;
            s.store
                .read(move |db| {
                    Ok(json!({
                    "counts":db.stats()?,"agents":db.list("agents")?.len(),"projects":db.list("projects")?.len(),"tasks":db.list("tasks")?,"runs":db.runs(None,None,8,0,false)?,"concurrency":concurrency}
                    ))
                })
                .await
        }
        ("GET", [kind @ ("agents" | "projects" | "tasks")]) => Ok(s.store.list(kind).await?.into()),
        ("POST", [kind @ ("agents" | "projects" | "tasks")]) => save(s, kind, input.body.clone(), None).await,
        ("PUT", [kind @ ("agents" | "projects" | "tasks"), id]) => save(s, kind, input.body.clone(), Some(id)).await,
        ("DELETE", [kind @ ("agents" | "projects" | "tasks"), id]) => {
            s.remove(kind, id).await?;
            Ok(json!({
            "deleted":true}
            ))
        }
        ("POST", ["tasks", id, "run"]) => s.enqueue(id, "manual", None).await,
        ("POST", ["schedule", "preview"]) => Ok(json!({
        "occurrences":next_occurrences(input.string("cron",500)?,input.string("timezone",100)?,now(),3)?}
        )),
        ("GET", ["tasks", "activity"]) => s.store.read(|db| Ok(db.json_rows("SELECT json_set(json_remove(data,'$.snapshot','$.summary'),'$.taskName',json_extract(data,'$.snapshot.task.name'),'$.agentName',json_extract(data,'$.snapshot.agent.name')) FROM runs WHERE id IN (SELECT (SELECT id FROM runs WHERE task_id=records.id ORDER BY created_at DESC,id DESC LIMIT 1) FROM records WHERE kind='tasks') ORDER BY created_at DESC,id DESC", [])?.into())).await,
        ("GET", ["runs"]) => {
            let (limit, offset) = (input.number("limit", 40, 1, 100)?, input.number("offset", 0, 0, i64::MAX)?);
            let status = input.query.get("status").cloned();
            let task = input.query.get("taskId").cloned();
            s.store.read(move |db| Ok(db.runs(status.as_deref(), task.as_deref(), limit, offset, false)?.into())).await
        }
        ("GET", ["runs", id]) => s.store.run(id).await,
        ("GET", ["runs", id, "events"]) => {
            s.store.run(id).await?;
            let (after, limit) = (input.number("after", 0, 0, i64::MAX)?, input.number("limit", 100, 1, 500)?);
            let id = (*id).to_owned();
            s.store.read(move |db| Ok(db.events(&id, after, limit)?.into())).await
        }
        ("POST", ["runs", id, "retry"]) => {
            let run = s.store.run(id).await?;
            s.enqueue(text(&run, "taskId"), "retry", None).await
        }
        ("GET", ["codex", "models"]) => s.models.list(s).await,
        ("GET", ["chats"]) => Ok(s.chat_list().await?.into()),
        ("POST", ["chats"]) => s.chat_create(input.body.clone()).await,
        ("GET", ["chats", id]) => {
            let mut detail = s.chat_detail(id).await?;
            let private = detail["questions"].as_array().into_iter().flatten().filter(|q| q["fields"].as_array().is_some_and(|fields| fields.iter().any(|f| f["secret"] == true))).map(|q| q["id"].clone()).collect::<Vec<_>>();
            detail["messages"].as_array_mut().unwrap().retain(|m| m["status"] != "delivered");
            for message in detail["messages"].as_array_mut().unwrap() {
                if private.contains(&message["questionId"]) {
                    message["text"] = "Private answer".into();
                    message.as_object_mut().unwrap().remove("answers");
                }
            }
            detail["error"] = s.store.kv(&format!("chat-error:{id}")).await?.unwrap_or(Value::Null);
            Ok(detail)
        }
        ("POST", ["chats", id, "messages"]) => s.chat_send(id, input.body.clone()).await,
        ("PUT", ["chats", id, "messages", message]) => s.chat_edit(id, message, Some(input.body.clone())).await,
        ("DELETE", ["chats", id, "messages", message]) => s.chat_edit(id, message, None).await,
        ("POST", ["chats", id, "questions", question, "answer"]) => s.question_answer(id, question, input.body.clone()).await,
        ("GET", ["notifications"]) => s.notifications.configuration(s).await,
        ("POST", ["notifications", "subscriptions"]) => s.notifications.subscribe(s, input.body.clone()).await,
        ("GET", ["notifications", "subscriptions", id]) => {
            hash(id)?;
            Ok(json!({
            "registered":s.store.kv(&format!("push-device:{id}")).await?.is_some()}
            ))
        }
        ("DELETE", ["notifications", "subscriptions", id]) => {
            hash(id)?;
            s.notifications.unsubscribe(s, id).await
        }
        ("GET", ["skills"]) => {
            let mut items = s.skills.list("global", None).await?;
            for project in s.store.list("projects").await? {
                items.extend(s.skills.list(text(&project, "id"), Some(std::path::Path::new(text(&project, "path")))).await?);
            }
            Ok(items.into())
        }
        (method, ["skills", scope, name]) => {
            let project = skill_project(s, scope).await?;
            match method {
                "PUT" => {
                    let result = s.skills.save(name, input.string("content", 100000)?, project.as_deref()).await?;
                    s.store
                        .audit(
                            "skill.saved",
                            json!({
                            "scope":scope,"name":name}
                            ),
                        )
                        .await?;
                    Ok(result)
                }
                "DELETE" => {
                    let key = format!("{scope}/{name}");
                    if s.store.list("tasks").await?.iter().any(|t| t["skills"].as_array().is_some_and(|skills| skills.iter().any(|s| s == &key))) {
                        return Err(Error::new(409, "This skill is selected by a task. Update that task first."));
                    }
                    s.skills.remove(name, project.as_deref()).await?;
                    s.store
                        .audit(
                            "skill.deleted",
                            json!({
                            "scope":scope,"name":name}
                            ),
                        )
                        .await?;
                    Ok(json!({
                    "deleted":true}
                    ))
                }
                _ => Err(Error::new(404, "Not found")),
            }
        }
        ("GET", ["skills", scope, name, "files"]) => Ok(s.skills.files(name, skill_project(s, scope).await?.as_deref()).await?.into()),
        (method @ ("GET" | "PUT"), ["skills", scope, name, "file"]) => {
            let file = if method == "GET" { input.query.get("path").map(String::as_str).ok_or_else(|| Error::bad("Choose a file path."))? } else { input.string("path", 4096)? };
            let content = if method == "PUT" { Some(input.string("content", 100000)?) } else { None };
            s.skills.file(name, file, content, skill_project(s, scope).await?.as_deref()).await
        }
        ("GET", ["connections"]) => s.connections.status(s, input.query.get("refresh").is_some_and(|s| s == "true")).await,
        ("GET", ["codex", "accounts"]) => {
            s.accounts.initialize(s).await?;
            Ok(s.accounts.list(s).await?.into())
        }
        ("POST", ["codex", "accounts", "refresh"]) => {
            s.accounts.poll(s, false).await?;
            Ok(s.accounts.list(s).await?.into())
        }
        ("GET", ["codex", "accounts", "login"]) => Ok(s.account_login.lock().await.as_ref().map(|l| l.view()).unwrap_or(Value::Null)),
        ("POST", ["codex", "accounts", "login"]) => {
            let id = input.body["id"].as_str();
            if let Some(id) = id {
                uuid(id)?;
            }
            s.account_login(input.string("name", 100)?, id).await
        }
        ("DELETE", ["codex", "accounts", "login"]) => {
            s.cancel_account_login().await;
            Ok(json!({
            "cancelled":true}
            ))
        }
        ("PUT", ["codex", "accounts", id]) => {
            uuid(id)?;
            s.accounts.update(s, id, input.body.clone()).await
        }
        ("DELETE", ["codex", "accounts", id]) => {
            uuid(id)?;
            s.accounts.remove(s, id).await?;
            Ok(json!({
            "deleted":true}
            ))
        }
        ("GET", ["connections", "login"]) => {
            if s.legacy_codex_login.load(std::sync::atomic::Ordering::Relaxed) {
                Ok(s.account_login.lock().await.as_ref().map(|l| l.view()).unwrap_or(Value::Null))
            } else {
                Ok(s.connections.flow().await)
            }
        }
        ("POST", ["connections", "login"]) => {
            let provider = input.string("provider", 20)?;
            if !["codex", "github"].contains(&provider) {
                return Err(Error::bad("Unknown connection provider."));
            }
            s.legacy_codex_login.store(provider == "codex", std::sync::atomic::Ordering::Relaxed);
            if provider == "codex" { s.account_login("Codex account", None).await } else { s.connections.start(s, provider).await }
        }
        ("DELETE", ["connections", "login"]) => {
            s.connections.cancel().await;
            s.cancel_account_login().await;
            Ok(json!({
            "cancelled":true}
            ))
        }
        ("GET", ["agents", id, "github-token"]) => {
            s.get("agents", id).await?;
            Ok(json!({
            "configured":s.store.kv(&format!("agent-github:{id}")).await?.is_some()}
            ))
        }
        ("PUT", ["agents", id, "github-token"]) => {
            s.get("agents", id).await?;
            let token = input.string("token", 500)?.trim();
            let key = format!("agent-github:{id}");
            if token.is_empty() {
                s.store.delete(&key).await?;
            } else {
                s.store.set(&key, token.into(), None).await?;
            }
            s.store
                .audit(
                    "agent.connection.updated",
                    json!({
                    "agentId":id,"provider":"github"}
                    ),
                )
                .await?;
            Ok(json!({
            "configured":!token.is_empty()}
            ))
        }
        ("GET", ["settings"]) => Ok(json!({
        "publicUrl":s.config.public_url,"workspaceRoots":s.config.workspace_roots,"home":s.config.home,"concurrency":s.config.concurrency,"mcpUrl":format!("{}/mcp",s.config.public_url),"version":env!("CARGO_PKG_VERSION"),"commit":std::env::var("APP_COMMIT").unwrap_or_else(|_|"development".into()),"protocol":"2026-07-28"}
        )),
        ("GET", ["audit"]) => s.store.read(|db| Ok(db.json_rows("SELECT json_object('id',id,'created_at',created_at,'action',action,'detail',detail) FROM audit ORDER BY id DESC LIMIT 100", [])?.into())).await,
        ("GET", ["tokens"]) => Ok(s
            .store
            .keys("grant:")
            .await?
            .into_iter()
            .map(|(_, mut value)| {
                value["id"] = value["family"].clone();
                value
            })
            .collect::<Vec<_>>()
            .into()),
        ("POST", ["tokens"]) => {
            let label = input.string("label", 100)?;
            let scopes = input.body["scopes"].as_array().ok_or_else(|| Error::bad("Choose token scopes."))?.iter().map(|v| v.as_str().ok_or_else(|| Error::bad("Invalid scope."))).collect::<Result<Vec<_>>>()?;
            s.auth.personal(label, scopes).await
        }
        ("DELETE", ["tokens", id]) => {
            s.auth.revoke(id).await?;
            Ok(json!({
            "revoked":true}
            ))
        }
        ("POST", ["oauth", "preview"]) => s.auth.authorization(&input.body).await,
        ("POST", ["oauth", "consent"]) => Ok(json!({
        "redirect":s.auth.consent(input.body["parameters"].clone(),input.boolean("approved")?).await?}
        )),
        _ => Err(Error::new(404, "Not found")),
    }
}
async fn save(s: &Service, kind: &str, input: Value, id: Option<&str>) -> Result<Value> {
    match kind {
        "agents" => s.agent(input, id).await,
        "projects" => s.project(input, id).await,
        _ => s.task(input, id).await,
    }
}
async fn skill_project(s: &Service, scope: &str) -> Result<Option<PathBuf>> {
    if scope == "global" {
        return Ok(None);
    }
    Ok(Some(PathBuf::from(text(
        &s.get("projects", scope).await?,
        "path",
    ))))
}
fn hash(value: &str) -> Result<()> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return Err(Error::bad("Invalid identifier."));
    }
    Ok(())
}
