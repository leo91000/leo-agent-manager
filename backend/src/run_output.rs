use crate::{service::policy, validation::text};
use serde_json::{Value, json};
use std::{path::Path, sync::LazyLock};
static TOKEN: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(r"\b(?:gh[pousr]_\w{15,}|github_pat_\w{15,}|sk-[\w-]{12,})\b").unwrap()
});
static BEARER: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new(r"(?i)(Bearer\s+)[\w.~-]+").unwrap());
static CREDENTIAL: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(r#"(?i)("?(?:access_token|refresh_token|id_token|OPENAI_API_KEY|CODEX_API_KEY)"?\s*[:=]\s*"?)[^"\s,}]+"#).unwrap()
});
static EXHAUSTED: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(r"(?i)^(?:you['’]ve hit your usage limit|you have hit your usage limit|usage limit (?:has been )?(?:reached|exceeded))\b").unwrap()
});
pub fn redact(text: &str, secrets: &[String]) -> String {
    let text = TOKEN.replace_all(text, "[redacted]");
    let text = BEARER.replace_all(&text, "${1}[redacted]");
    let mut text = CREDENTIAL.replace_all(&text, "${1}[redacted]").into_owned();
    for secret in secrets {
        if !secret.is_empty() {
            text = text.replace(secret, "[redacted]");
        }
    }
    text
}
pub fn payload(value: &Value, secrets: &[String]) -> Value {
    match value {
        Value::String(s) => redact(s, secrets).into(),
        Value::Array(a) => a.iter().map(|v| payload(v, secrets)).collect(),
        Value::Object(o) => Value::Object(
            o.iter()
                .map(|(k, v)| {
                    (
                        k.clone(),
                        if [
                            "access_token",
                            "refresh_token",
                            "id_token",
                            "openai_api_key",
                            "codex_api_key",
                        ]
                        .contains(&k.to_lowercase().as_str())
                        {
                            "[redacted]".into()
                        } else {
                            payload(v, secrets)
                        },
                    )
                })
                .collect(),
        ),
        _ => value.clone(),
    }
}
pub fn exhausted(event: &Value) -> bool {
    if !["turn.failed", "error"].contains(&text(event, "type")) {
        return false;
    }
    let error = if event["type"] == "turn.failed" {
        &event["error"]
    } else {
        event
    };
    error["code"] == "usage_limit_reached"
        || error["codexErrorInfo"] == "usageLimitExceeded"
        || EXHAUSTED.is_match(text(error, "message"))
}
pub fn args(run: &Value, output: &str, session: Option<&str>) -> Vec<String> {
    let agent = &run["snapshot"]["agent"];
    let access = policy(agent);
    let mut args = if access["sandbox"] == "yolo" {
        vec!["--dangerously-bypass-approvals-and-sandbox".into()]
    } else {
        vec![
            "--sandbox".into(),
            text(&access, "sandbox").into(),
            "-a".into(),
            "never".into(),
        ]
    };
    args.extend(
        [
            "-c",
            "forced_login_method=\"chatgpt\"",
            "-c",
            "cli_auth_credentials_store=\"file\"",
        ]
        .map(str::to_owned),
    );
    let roots = || {
        let mut roots = vec![
            Path::new(output)
                .parent()
                .unwrap_or(Path::new("."))
                .to_string_lossy()
                .into_owned(),
        ];
        roots.extend(
            run["workspaces"]
                .as_array()
                .into_iter()
                .flatten()
                .map(|w| text(w, "path").to_owned()),
        );
        roots
            .into_iter()
            .flat_map(|r| vec!["--add-dir".into(), r])
            .collect::<Vec<_>>()
    };
    if session.is_some() && access["sandbox"] == "workspace-write" {
        args.extend(roots());
    }
    args.push("exec".into());
    if let Some(session) = session {
        args.extend(["resume".into(), session.into()]);
    }
    args.extend(["--json", "--skip-git-repo-check"].map(str::to_owned));
    if session.is_none() {
        args.extend(["--color", "never"].map(str::to_owned));
    }
    if !text(agent, "reasoning").is_empty() {
        args.extend([
            "-c".into(),
            format!("model_reasoning_effort={}", agent["reasoning"]),
        ]);
    }
    if !text(agent, "model").is_empty() {
        args.extend(["--model".into(), text(agent, "model").into()]);
    }
    if access["sandbox"] == "workspace-write" {
        args.extend(["-c", "sandbox_workspace_write.network_access=true"].map(str::to_owned));
        if session.is_none() {
            args.extend(roots());
        }
    }
    args.extend(["--output-last-message".into(), output.into(), "-".into()]);
    args
}
pub fn prompt(run: &Value, chat: bool) -> String {
    let deliverables = if run["isolated"] == true {
        "When the user requests files, screenshots, videos or documents, publish each finished deliverable with leo_workspace.publish_artifact. Use a stable key for revisions and a shared group for related screenshots. Store export files in the current run workspace or /tmp. Wait for successful publication and include the returned durable URL in your reply. Do not present VM-local paths as downloadable links."
    } else {
        ""
    };
    let interaction = if chat {
        "This is an interactive chat. Use native user-input questions when clarification is useful. Nonblocking questions let you continue independent work while the user considers the options; a suggested answer is never user approval. Follow the latest user instructions and do not treat a question as authorization to publish changes."
    } else {
        "This unattended task cannot answer clarification questions; report a concrete blocker if required information is missing."
    };
    let projects = crate::project_workspaces::catalog(run)
        .iter()
        .map(|project| {
            let path = run["workspaces"]
                .as_array()
                .into_iter()
                .flatten()
                .find(|w| w["projectId"] == project["id"])
                .map(|w| text(w, "path"))
                .unwrap_or("");
            if !path.is_empty() {
                format!("- {} ({}): {path}",text(project,"name"),text(project,"id"))
            } else if run["isolated"] == true {
                format!("- {} ({}): not loaded; call leo_workspace.open_project with this projectId when needed.",text(project,"name"),text(project,"id"))
            } else {
                format!("- {}: {}",text(project,"name"),text(project,"path"))
            }
        })
        .collect::<Vec<_>>()
        .join("\n");
    let skills = run["snapshot"]["skills"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|s| format!("\n{}\n{}", text(s, "path"), text(s, "content")))
        .collect::<Vec<_>>()
        .join("\n");
    format!(
        "{}\n\n{}\n\nAuthorized projects (open only those needed for the task; unopened repositories are not on disk):\n{}\n\nTooling: mise manages project runtimes and global tools. Prefer rg and fd for search. Respect mise.toml, .tool-versions, .nvmrc, .node-version, .python-version, rust-toolchain.toml, and package.json packageManager pins. Use mise exec -- <command> when project environment variables are needed; use uv for Python environments. Do not upgrade project pins unless the task requests it.\n\nSelected skills (use their supporting resources from the supplied paths):\n{skills}\n\nRun this task to completion within its stated scope. Preserve unrelated files. Do not expose credentials. {interaction}\n{deliverables} All task-authorized effects such as creating PRs or releasing must follow their checks. Use .agents/skills for skills. Summarize actual changes, validation, external links and remaining blockers at the end.",
        text(&run["snapshot"]["agent"], "instructions"),
        text(&run["snapshot"]["task"], "prompt"),
        if projects.is_empty() {
            "No projects assigned; use the task workspace."
        } else {
            &projects
        }
    )
}
pub fn chat_plan(
    run: &Value,
    prepared: &Value,
    directory: &Path,
    mcp: &Value,
    session: Option<&str>,
) -> Value {
    let mut context = run.clone();
    context["snapshot"]["task"]["prompt"] = "".into();
    if prepared["isolated"] == true {
        context["isolated"] = true.into();
        context["snapshot"]["skills"] = prepared["skills"].clone();
    }
    let mut roots = vec![
        Path::new(text(prepared, "output"))
            .parent()
            .unwrap()
            .to_string_lossy()
            .into_owned(),
    ];
    if let Some(root) = prepared["projectRoot"].as_str() {
        roots.push(root.to_owned());
    }
    roots.extend(
        prepared["workspaces"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|w| text(w, "path").into()),
    );
    let mut plan = json!({
    "execution":run["chatExecution"],"instructions":prompt(&context,true),"inputDirectory":if prepared["isolated"]==true{
    Path::new("/run/leo-chat").to_owned()}
    else{
    directory.join("chat-input")}
    ,"output":prepared["output"],"cwd":prepared["cwd"],"model":run["snapshot"]["agent"]["model"],"reasoning":run["snapshot"]["agent"]["reasoning"],"sandbox":policy(&run["snapshot"]["agent"])["sandbox"],"writableRoots":roots,"args":mcp["args"]}
    );
    if let Some(session) = session {
        plan["sessionId"] = session.into();
    }
    plan
}
