use crate::{
    config::{id, now},
    error::{Error, Result, required},
    notifications,
    service::{Service, task_projects},
    store::{Db, merge},
    validation::{parse, text},
};
use serde_json::{Value, json};
use std::collections::HashSet;
fn chat(db: &Db<'_>, id: &str) -> Result<Value> {
    required(db.get("chats", id)?, "Chat not found")
}
fn questions(db: &Db<'_>, chat: &str) -> Result<Vec<Value>> {
    let mut questions = db
        .keys(&format!("chat-question:{chat}:"))?
        .into_iter()
        .map(|(_, value)| value)
        .collect::<Vec<_>>();
    questions.sort_by_key(|q| q["createdAt"].as_i64());
    Ok(questions)
}
fn save_question(db: &Db<'_>, question: &Value) -> Result<Value> {
    db.set(
        &format!(
            "chat-question:{}:{}",
            text(question, "chatId"),
            text(question, "id")
        ),
        question,
        None,
    )?;
    Ok(question.clone())
}
fn view(db: &Db<'_>, mut chat: Value) -> Result<Value> {
    chat["pendingQuestions"] = questions(db, text(&chat, "id"))?
        .iter()
        .filter(|q| q["status"] == "pending")
        .count()
        .into();
    chat["agentName"] = db
        .get("agents", text(&chat, "agentId"))?
        .map(|a| a["name"].clone())
        .unwrap_or_else(|| "Deleted agent".into());
    chat["projectName"] = if chat["projectId"].is_null() {
        Value::Null
    } else {
        db.get("projects", text(&chat, "projectId"))?
            .map(|p| p["name"].clone())
            .unwrap_or_else(|| "Deleted project".into())
    };
    chat["status"] = db
        .run(text(&chat, "runId"))?
        .map(|r| r["status"].clone())
        .unwrap_or_else(|| "idle".into());
    Ok(chat)
}
fn validate_steer(db: &Db<'_>, chat: &Value, message: &Value) -> Result<()> {
    if message["mode"] != "steer" {
        return Ok(());
    }
    if let Some(run) = db.run(text(chat, "runId"))?
        && ["queued", "running"].contains(&text(&run, "status"))
        && ((!text(message, "model").is_empty()
            && message["model"] != run["snapshot"]["agent"]["model"])
            || (!text(message, "reasoning").is_empty()
                && message["reasoning"] != run["snapshot"]["agent"]["reasoning"]))
    {
        return Err(Error::new(
            409,
            "Queue this message to change model or reasoning on the next turn.",
        ));
    }
    Ok(())
}
fn send(
    db: &Db<'_>,
    chat_id: &str,
    mut values: Value,
    answer: Option<(Value, Value)>,
) -> Result<Value> {
    let mut chat = chat(db, chat_id)?;
    crate::attachments::message(db, chat_id, &mut values)?;
    let messages = db.messages(chat_id)?;
    if let Some(existing) = messages.iter().find(|m| m["id"] == values["id"]) {
        if existing["text"] != values["text"]
            || existing["model"] != values["model"]
            || text(existing, "reasoning") != text(&values, "reasoning")
            || !crate::attachments::same(existing, &values)
            || answer
                .as_ref()
                .is_some_and(|(q, _)| existing["questionId"] != q["id"])
        {
            return Err(Error::new(
                409,
                "This message identifier has already been used.",
            ));
        }
        return Ok(existing.clone());
    }
    if messages
        .iter()
        .filter(|m| m["status"] != "delivered")
        .count()
        >= 20
    {
        return Err(Error::new(
            409,
            "The queue is full. Wait for a reply or remove a queued message.",
        ));
    }
    if db.0.query_row(
        "SELECT EXISTS(SELECT 1 FROM chat_messages WHERE id=?)",
        [text(&values, "id")],
        |r| r.get::<_, bool>(0),
    )? {
        return Err(Error::new(
            409,
            "This message identifier has already been used.",
        ));
    }
    let agent = required(
        db.get("agents", text(&chat, "agentId"))?,
        "This agent is no longer available.",
    )?;
    task_projects(&agent, &chat, &db.list("projects")?)?;
    if db
        .run(text(&chat, "runId"))?
        .is_some_and(|r| !r["workspaceCleanedAt"].is_null())
    {
        return Err(Error::new(
            409,
            "This workspace has been cleaned up. Start a new chat.",
        ));
    }
    validate_steer(db, &chat, &values)?;
    if messages.is_empty() {
        let title = if text(&values, "text").is_empty() {
            text(&values["attachments"][0], "name")
        } else {
            text(&values, "text")
        };
        chat["title"] = title
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ")
            .chars()
            .take(90)
            .collect::<String>()
            .into();
    }
    chat["updatedAt"] = now().into();
    db.put("chats", &chat)?;
    if let Some((mut question, answers)) = answer {
        question["status"] = "answering".into();
        question["messageId"] = values["id"].clone();
        save_question(db, &question)?;
        values["questionId"] = question["id"].clone();
        values["answers"] = answers;
    }
    merge(
        &mut values,
        &json!({
        "chatId":chat_id,"status":"queued","createdAt":now()}
        ),
    );
    db.put_message(&values)
}
impl Service {
    pub async fn chat_list(&self) -> Result<Vec<Value>> {
        self.store
            .read(|db| {
                db.list("chats")?
                    .into_iter()
                    .map(|chat| view(db, chat))
                    .collect()
            })
            .await
    }
    pub async fn chat_detail(&self, id: &str) -> Result<Value> {
        let id = id.to_owned();
        self.store
            .read(move |db| {
                let mut result = view(db, chat(db, &id)?)?;
                result["questions"] = questions(db, &id)?.into();
                result["messages"] = db.messages(&id)?.into();
                result["run"] = db.run(text(&result, "runId"))?.unwrap_or(Value::Null);
                Ok(result)
            })
            .await
    }
    pub async fn chat_create(&self, input: Value) -> Result<Value> {
        let mut value = parse("chat", input)?;
        self.store
            .transaction(move |db| {
                let agent = required(db.get("agents", text(&value, "agentId"))?, "Agent not found")?;
                task_projects(&agent, &value, &db.list("projects")?)?;
                merge(
                    &mut value,
                    &json!({
                    "id":id(),"title":"New chat","runId":null,"paused":false,"createdAt":now(),"updatedAt":now()}
                    ),
                );
                db.put("chats", &value)
            })
            .await
    }
    pub async fn chat_send(&self, id: &str, input: Value) -> Result<Value> {
        let values = parse("message", input)?;
        let id = id.to_owned();
        self.store
            .transaction(move |db| send(db, &id, values, None))
            .await
    }
    pub async fn chat_edit(&self, id: &str, message: &str, input: Option<Value>) -> Result<Value> {
        let (id, message) = (id.to_owned(), message.to_owned());
        let values = input
            .map(|mut input| {
                input["id"] = message.clone().into();
                parse("message", input)
            })
            .transpose()?;
        self.store
            .transaction(move |db| {
                let chat = chat(db, &id)?;
                let mut current = required(
                    db.messages(&id)?.into_iter().find(|m| m["id"] == message),
                    "Message not found",
                )?;
                if current["status"] != "queued" {
                    return Err(Error::new(409, "This message is already being sent."));
                }
                let Some(mut values) = values else {
                    if let Some(mut question) = questions(db, &id)?
                        .into_iter()
                        .find(|q| q["id"] == current["questionId"])
                    {
                        question["status"] = "pending".into();
                        question.as_object_mut().unwrap().remove("messageId");
                        save_question(db, &question)?;
                    }
                    db.0.execute(
                        "DELETE FROM chat_messages WHERE chat_id=? AND id=?",
                        rusqlite::params![id, message],
                    )?;
                    return Ok(json!({
                    "deleted":true}
                    ));
                };
                if current["questionId"].is_string() {
                    return Err(Error::new(409, "A submitted answer cannot be edited."));
                }
                crate::attachments::message(db, &id, &mut values)?;
                validate_steer(db, &chat, &values)?;
                merge(&mut current, &values);
                db.put_message(&current)
            })
            .await
    }
    pub async fn chat_pause(&self, id: &str, paused: bool) -> Result<Value> {
        let id = id.to_owned();
        self.store
            .write(move |db| {
                let mut chat = chat(db, &id)?;
                merge(
                    &mut chat,
                    &json!({
                    "paused":paused,"updatedAt":now()}
                    ),
                );
                db.put("chats", &chat)
            })
            .await
    }
    pub async fn chat_acknowledge(&self, run_id: &str, message_id: &str) -> Result<()> {
        let (run_id, message_id) = (run_id.to_owned(), message_id.to_owned());
        self.store
            .transaction(move |db| {
                let Some(mut chat) = db.list("chats")?.into_iter().find(|c| c["runId"] == run_id)
                else {
                    return Ok(());
                };
                let Some(mut message) = db
                    .messages(text(&chat, "id"))?
                    .into_iter()
                    .find(|m| m["id"] == message_id)
                else {
                    return Ok(());
                };
                if message["status"] == "delivered" {
                    return Ok(());
                }
                message["status"] = "delivered".into();
                db.put_message(&message)?;
                let mut private = false;
                if let Some(mut question) = questions(db, text(&chat, "id"))?
                    .into_iter()
                    .find(|q| q["id"] == message["questionId"])
                {
                    private = question["fields"]
                        .as_array()
                        .is_some_and(|fields| fields.iter().any(|f| f["secret"] == true));
                    merge(
                        &mut question,
                        &json!({
                        "blocking":false,"status":"answered"}
                        ),
                    );
                    save_question(db, &question)?;
                }
                let text = if private {
                    "Answered a private question."
                } else {
                    text(&message, "text")
                };
                db.event(
                    &run_id,
                    "chat.user",
                    text,
                    Some(&json!({
                    "messageId":message_id,"text":text,"attachments":message["attachments"],"createdAt":message["createdAt"]}
                    )),
                )?;
                chat["updatedAt"] = now().into();
                db.put("chats", &chat)?;
                Ok(())
            })
            .await
    }
    pub async fn question_receive(&self, run_id: &str, input: Value) -> Result<()> {
        let id = text(&input, "id");
        if id.len() != 64
            || !id
                .bytes()
                .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
            || !input["blocking"].is_boolean()
        {
            return Ok(());
        }
        let Ok(fields) = parse("questions", input["fields"].clone()) else {
            return Ok(());
        };
        let mut question = json!({
        "id":id,"blocking":input["blocking"],"fields":fields,"runId":run_id,"status":"pending","createdAt":now()}
        );
        self.store
            .transaction(move |db| {
                let Some(chat) = db
                    .list("chats")?
                    .into_iter()
                    .find(|c| c["runId"] == question["runId"])
                else {
                    return Ok(());
                };
                question["chatId"] = chat["id"].clone();
                if let Some(mut existing) = questions(db, text(&chat, "id"))?
                    .into_iter()
                    .find(|q| q["id"] == question["id"])
                {
                    existing["blocking"] =
                        (existing["status"] == "pending" && question["blocking"] == true).into();
                    save_question(db, &existing)?;
                    return Ok(());
                }
                save_question(db, &question)?;
                notifications::enqueue(db, &question)
            })
            .await
    }
    pub async fn question_release(&self, run_id: &str, id: Option<&str>) -> Result<()> {
        let (run_id, id) = (run_id.to_owned(), id.map(str::to_owned));
        self.store
            .transaction(move |db| {
                let Some(chat) = db.list("chats")?.into_iter().find(|c| c["runId"] == run_id)
                else {
                    return Ok(());
                };
                for mut question in questions(db, text(&chat, "id"))? {
                    if id.as_ref().is_none_or(|id| question["id"] == *id)
                        && question["blocking"] == true
                    {
                        question["blocking"] = false.into();
                        save_question(db, &question)?;
                    }
                }
                Ok(())
            })
            .await
    }
    pub async fn question_answer(&self, chat_id: &str, id: &str, input: Value) -> Result<Value> {
        let values = parse("answer", input)?;
        let (chat_id, id) = (chat_id.to_owned(), id.to_owned());
        self.store
            .transaction(move |db| {
                chat(db, &chat_id)?;
                let question = required(questions(db, &chat_id)?.into_iter().find(|q| q["id"] == id), "Question not found")?;
                let previous = db.messages(&chat_id)?.into_iter().find(|m| m["id"] == values["id"]);
                if question["messageId"] == values["id"] && previous.is_some_and(|m| m["answers"] == values["answers"]) {
                    return Ok(question);
                }
                if question["status"] != "pending" {
                    return Err(Error::new(409, "This question has already been answered."));
                }
                let fields = question["fields"].as_array().unwrap();
                let answers = values["answers"].as_object().unwrap();
                if answers.len() != fields.len() || fields.iter().any(|f| !answers.contains_key(text(f, "id"))) {
                    return Err(Error::bad("Answer each question before sending."));
                }
                let parts = fields.iter().map(|f| format!("{}\n{}", text(f, "title"), answers[text(f, "id")].as_array().unwrap().iter().filter_map(Value::as_str).collect::<Vec<_>>().join("\n"))).collect::<Vec<_>>();
                let message = parse(
                    "message",
                    json!({
                    "id":values["id"],"mode":"steer","text":format!("My answers to your questions:\n\n{}",parts.join("\n\n"))}
                    ),
                )?;
                send(db, &chat_id, message, Some((question, values["answers"].clone())))?;
                required(questions(db, &chat_id)?.into_iter().find(|q| q["id"] == id), "Question not found")
            })
            .await
    }
    pub async fn chat_tick(&self, active: &HashSet<String>) -> Result<()> {
        for chat in self.store.list("chats").await? {
            let id = text(&chat, "id").to_owned();
            let run = if chat["runId"].is_string() {
                Some(self.store.run(text(&chat, "runId")).await?)
            } else {
                None
            };
            if let Some(run) = &run
                && ["queued", "running"].contains(&text(run, "status"))
            {
                let run_id = text(run, "id").to_owned();
                let run = run.clone();
                let directory = self
                    .config
                    .data_dir
                    .join("runs")
                    .join(text(&chat, "runId"))
                    .join("chat-input");
                let chat = chat.clone();
                let steering = self
                    .store
                    .transaction(move |db| {
                        let mut steering = Vec::new();
                        for mut message in db.messages(text(&chat, "id"))? {
                            if (chat["paused"] != true || message["questionId"].is_string())
                                && message["mode"] == "steer"
                                && message["status"] != "delivered"
                                && message["id"] != run["chatExecution"]["messageId"]
                            {
                                message["status"] = "sending".into();
                                db.put_message(&message)?;
                                steering.push(message);
                            }
                        }
                        Ok(steering)
                    })
                    .await?;
                for message in &steering {
                    self.prepare_chat_files(&run_id, &message["attachments"])
                        .await?;
                }
                crate::skills::private_dir(&directory).await?;
                crate::skills::atomic_write(
                    &directory.join("messages.json"),
                    &serde_json::to_vec(&steering)?,
                )
                .await?;
                continue;
            }
            if chat["paused"] == true
                || run
                    .as_ref()
                    .is_some_and(|r| active.contains(text(r, "id")) || r["recoveryPending"] == true)
            {
                continue;
            }
            if run.as_ref().is_some_and(|r| r["status"] != "succeeded") {
                self.chat_pause(&id, true).await?;
                continue;
            }
            let message = self
                .store
                .transaction({
                    let id = id.clone();
                    move |db| {
                        let mut messages = db.messages(&id)?;
                        for message in &mut messages {
                            if message["status"] == "sending" {
                                message["status"] = "queued".into();
                                db.put_message(message)?;
                            }
                        }
                        Ok(messages.into_iter().find(|m| m["status"] == "queued"))
                    }
                })
                .await?;
            let Some(message) = message else {
                continue;
            };
            if let Err(error) = self.chat_prepare(chat, run, message).await {
                self.chat_pause(&id, true).await?;
                self.store
                    .set(
                        &format!("chat-error:{id}"),
                        if error.status < 500 {
                            error.message.into()
                        } else {
                            "Unable to prepare this conversation.".into()
                        },
                        None,
                    )
                    .await?;
            }
        }
        Ok(())
    }
    async fn chat_prepare(&self, chat: Value, run: Option<Value>, message: Value) -> Result<()> {
        let prompt = if text(&message, "text").is_empty() {
            "Review the attached files."
        } else {
            text(&message, "text")
        };
        let mut task = parse(
            "task",
            json!({
            "name":chat["title"],"prompt":prompt,"agentId":chat["agentId"],"projectId":chat["projectId"],"worktree":true}
            ),
        )?;
        merge(
            &mut task,
            &json!({
            "id":chat["id"],"createdAt":chat["createdAt"],"nextRun":null}
            ),
        );
        let mut snapshot = self.snapshot(task, "chat").await?;
        if !text(&message, "model").is_empty() {
            snapshot["snapshot"]["agent"]["model"] = message["model"].clone();
            snapshot["snapshot"]["agent"]["reasoning"] = text(&message, "reasoning").into();
        }
        if !text(&message, "reasoning").is_empty() {
            snapshot["snapshot"]["agent"]["reasoning"] = message["reasoning"].clone();
        }
        self.store
            .transaction(move |db| {
                let mut current_chat = self::chat(db, text(&chat, "id"))?;
                let current = db.messages(text(&chat, "id"))?.into_iter().find(|m| m["id"] == message["id"]);
                if current_chat["paused"] == true || current.as_ref().is_none_or(|m| m["status"] != "queued" || m["text"] != message["text"] || m["model"] != message["model"] || text(m,"reasoning") != text(&message,"reasoning") || !crate::attachments::same(m,&message)) {
                    return Ok(());
                }
                let execution = json!({
                "messageId":message["id"],"text":message["text"],"attachments":message["attachments"],"recovery":false}
                );
                if let Some(run) = run {
                    if snapshot["snapshot"]["agent"]["access"] != run["snapshot"]["agent"]["access"] {
                        return Err(Error::new(409, "Agent access changed. Start a new chat with the updated permissions."));
                    }
                    let key = format!("run-checkpoint:{}", text(&run, "id"));
                    let mut checkpoint = required(db.kv(&key)?, "Run checkpoint not found")?;
                    checkpoint["completed"] = false.into();
                    checkpoint["remainingMs"] = (snapshot["snapshot"]["agent"]["timeoutMinutes"].as_i64().unwrap_or(60) * 60000).into();
                    checkpoint.as_object_mut().unwrap().remove("lastMessage");
                    checkpoint.as_object_mut().unwrap().remove("settled");
                    db.set(&key, &checkpoint, None)?;
                    db.patch_run(
                        text(&run, "id"),
                        &json!({
                        "snapshot":snapshot["snapshot"],"status":"queued","summary":"","finishedAt":null,"cancelRequestedAt":null,"recoveryPending":true,"chatExecution":execution}
                        ),
                    )?;
                } else {
                    snapshot["chatExecution"] = execution;
                    db.add_run(&snapshot, None)?;
                    current_chat["runId"] = snapshot["id"].clone();
                    db.put("chats", &current_chat)?;
                }
                let mut message = message;
                message["status"] = "sending".into();
                db.put_message(&message)?;
                Ok(())
            })
            .await
    }
}
