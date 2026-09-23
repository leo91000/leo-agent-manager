//! Claude Code owns sign-in and credential storage. No OAuth implementation or token API.
use crate::{
    config::{Config, id, now},
    error::{Error, Result},
    http::Input,
    process::{Environment, bounded_output, command},
    service::Service,
    skills::private_dir,
    validation::text,
};
use serde_json::{Value, json};
use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::{Mutex, mpsc},
};
use tokio_util::sync::CancellationToken;

#[derive(Default)]
pub struct Claude {
    pub gate: Mutex<()>,
    login: Mutex<Option<Login>>,
}
struct Login {
    view: Value,
    cancel: CancellationToken,
    input: mpsc::Sender<String>,
}
pub fn provider(agent: &Value) -> &str {
    if agent["provider"] == "claude" {
        "claude"
    } else {
        "codex"
    }
}
pub fn is_claude(run: &Value) -> bool {
    provider(&run["snapshot"]["agent"]) == "claude"
}
pub fn home(config: &Config) -> PathBuf {
    config.data_dir.join("claude")
}
pub fn environment(config: &Config, directory: &Path) -> Environment {
    let mut env = std::env::vars().collect::<Environment>();
    for key in [
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_BASE_URL",
        "ANTHROPIC_AUTH_TOKEN",
        "CLAUDE_CODE_OAUTH_TOKEN",
        "CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR",
        "CLAUDE_CODE_USE_BEDROCK",
        "CLAUDE_CODE_USE_VERTEX",
        "CLAUDE_CODE_USE_FOUNDRY",
        "CLAUDECODE",
        "CLAUDE_CODE_SIMPLE",
        "CLAUDE_CODE_SAFE_MODE",
    ] {
        env.remove(key);
    }
    env.insert("HOME".into(), config.home.to_string_lossy().into_owned());
    env.insert(
        "CLAUDE_CONFIG_DIR".into(),
        directory.to_string_lossy().into_owned(),
    );
    env.insert("DISABLE_AUTOUPDATER".into(), "1".into());
    env.insert("BROWSER".into(), "true".into());
    env
}
pub fn validate_agent(agent: &Value) -> Result<()> {
    if provider(agent) == "claude" {
        if !["", "low", "medium", "high", "xhigh", "max"].contains(&text(agent, "reasoning")) {
            return Err(Error::bad("Choose a supported Claude effort level."));
        }
        if text(agent, "model").starts_with("gpt-") {
            return Err(Error::bad("Choose a Claude model for this agent."));
        }
    }
    Ok(())
}
pub fn models() -> Value {
    let rows = [("sonnet", "Sonnet"), ("opus", "Opus"), ("haiku", "Haiku")].iter().map(|(model, name)| json!({"model":model,"displayName":name,"description":"Claude Code alias · connect to load account capabilities","hidden":false,"isDefault":false,"defaultReasoningEffort":"","supportedReasoningEfforts":[]})).collect::<Vec<_>>();
    json!({"models":rows,"checkedAt":null,"stale":true,"error":"Connect Claude Code to load available models and effort levels."})
}
async fn discover_models(s: &Service) -> Result<Value> {
    use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};
    let directory = home(&s.config);
    private_dir(&directory).await?;
    let args = [
        "-p",
        "--input-format",
        "stream-json",
        "--output-format",
        "stream-json",
        "--verbose",
        "--no-session-persistence",
        "--setting-sources",
        "",
        "--strict-mcp-config",
        "--mcp-config",
        "{\"mcpServers\":{}}",
    ]
    .map(str::to_owned);
    let mut cmd = command(
        &s.config.claude_bin,
        &args,
        &environment(&s.config, &directory),
        Some(&directory),
    );
    cmd.stdin(std::process::Stdio::piped());
    let mut child = cmd
        .spawn()
        .map_err(|_| Error::new(503, "Claude Code is not installed."))?;
    let mut stdin = child.stdin.take().unwrap();
    let mut stdout = BufReader::new(child.stdout.take().unwrap().take(2_000_000));
    let mut stderr = child.stderr.take().unwrap();
    let drain = tokio::spawn(async move {
        let _ = tokio::io::copy(&mut stderr, &mut tokio::io::sink()).await;
    });
    let result=tokio::time::timeout(Duration::from_secs(20),async {
        stdin.write_all(b"{\"type\":\"control_request\",\"request_id\":\"models\",\"request\":{\"subtype\":\"initialize\"}}\n").await?;
        let mut line=String::new();
        loop {
            line.clear();if stdout.read_line(&mut line).await?==0 {return Err(Error::new(502,"Claude model discovery stopped."));}
            let value:Value=serde_json::from_str(&line)?;
            if value["type"]!="control_response" || value["response"]["request_id"]!="models" {continue;}
            let rows=value["response"]["response"]["models"].as_array().ok_or_else(||Error::new(502,"Update Claude Code to load its model catalog."))?;
            let models=rows.iter().filter(|row|!text(row,"value").is_empty()).map(|row|json!({"model":row["value"],"displayName":row["displayName"],"description":row["description"],"hidden":false,"isDefault":row["value"]=="default","defaultReasoningEffort":"","supportedReasoningEfforts":row["supportedEffortLevels"].as_array().into_iter().flatten().filter_map(Value::as_str).map(|effort|json!({"reasoningEffort":effort,"description":""})).collect::<Vec<_>>()})).collect::<Vec<_>>();
            if models.is_empty(){return Err(Error::new(502,"Claude Code returned no available models."));}
            return Ok(json!({"models":models,"checkedAt":now(),"stale":false,"error":""}));
        }
    }).await.unwrap_or_else(|_|Err(Error::new(504,"Claude model discovery timed out.")));
    let _ = child.kill().await;
    let _ = child.wait().await;
    drain.abort();
    result
}
async fn model_catalog(s: &Service) -> Result<Value> {
    let _guard = s.claude.gate.lock().await;
    let mut cached = s.store.kv("claude-models").await?.unwrap_or_else(models);
    if active(s).await?
        || s.claude
            .login
            .lock()
            .await
            .as_ref()
            .is_some_and(|l| l.view["state"] == "pending")
        || cached["checkedAt"]
            .as_i64()
            .is_some_and(|at| now() - at < 300_000)
    {
        return Ok(cached);
    }
    match discover_models(s).await {
        Ok(catalog) => {
            s.store.set("claude-models", catalog.clone(), None).await?;
            Ok(catalog)
        }
        Err(e) => {
            cached["stale"] = true.into();
            cached["error"] = e.message.into();
            Ok(cached)
        }
    }
}
async fn status(s: &Service) -> Result<Value> {
    let directory = home(&s.config);
    private_dir(&directory).await?;
    let output = bounded_output(
        command(
            &s.config.claude_bin,
            &["auth".into(), "status".into()],
            &environment(&s.config, &directory),
            Some(&directory),
        ),
        Duration::from_secs(15),
        32000,
    )
    .await?;
    let value: Value = serde_json::from_str(&output.stdout).map_err(|_| {
        Error::new(
            502,
            "Update Claude Code: authentication status was not valid JSON.",
        )
    })?;
    // Never return arbitrary CLI output: only documented, non-secret identity fields.
    Ok(
        json!({"connected":output.success && value["loggedIn"]==true,"email":value["email"].as_str(),"authMethod":value["authMethod"].as_str(),"subscriptionType":value["subscriptionType"].as_str(),"checkedAt":now()}),
    )
}
async fn active(s: &Service) -> Result<bool> {
    Ok(s.store
        .read(|db| db.active())
        .await?
        .iter()
        .any(|r| is_claude(r) && (r["status"] == "running" || r["recoveryPending"] == true)))
}
impl Claude {
    pub async fn available(&self, s: &Service, run_id: &str) -> Result<()> {
        if self
            .login
            .lock()
            .await
            .as_ref()
            .is_some_and(|l| l.view["state"] == "pending")
        {
            return Err(Error::new(
                409,
                "Waiting for Claude Code sign-in to finish.",
            ));
        }
        if active(s).await? {
            return Err(Error::new(
                409,
                "Waiting for the active Claude Code run. Claude sessions use one account at a time.",
            ));
        }
        if tokio::fs::read_to_string(home(&s.config).join("sync-required"))
            .await
            .is_ok_and(|owner| owner != run_id)
        {
            return Err(Error::new(
                409,
                "Reconnect Claude Code after an interrupted credential synchronization.",
            ));
        }
        let view = status(s).await?;
        if view["connected"] != true {
            return Err(Error::new(
                409,
                "Connect Claude Code in Connections before running this agent.",
            ));
        }
        Ok(())
    }
    pub async fn cancel(&self) {
        let mut login = self.login.lock().await;
        if let Some(login) = login.as_mut() {
            login.cancel.cancel();
            login.view["url"] = Value::Null;
        }
    }
    async fn view(&self, s: &Service) -> Result<Value> {
        let busy = active(s).await?;
        let mut account = if busy {
            s.store
                .kv("claude-status")
                .await?
                .unwrap_or(json!({"connected":true}))
        } else {
            match status(s).await {
                Ok(v) => {
                    s.store.set("claude-status", v.clone(), None).await?;
                    v
                }
                Err(e) => json!({"connected":false,"error":e.message}),
            }
        };
        account["busy"] = busy.into();
        if home(&s.config).join("sync-required").exists() && !busy {
            account["error"] =
                "Reconnect Claude Code after the interrupted run to refresh its sign-in.".into();
        }
        account["login"] = self
            .login
            .lock()
            .await
            .as_ref()
            .map(|l| l.view.clone())
            .unwrap_or(Value::Null);
        Ok(account)
    }
    async fn start(self: &Arc<Self>, s: &Arc<Service>) -> Result<Value> {
        let _gate = self.gate.lock().await;
        if active(s).await? {
            return Err(Error::new(
                409,
                "Finish the active Claude run before reconnecting.",
            ));
        }
        let mut guard = self.login.lock().await;
        if guard.as_ref().is_some_and(|l| l.view["state"] == "pending") {
            return Err(Error::new(
                409,
                "Finish or cancel the current Claude sign-in.",
            ));
        }
        let directory = home(&s.config);
        private_dir(&directory).await?;
        let mut cmd = command(
            &s.config.claude_bin,
            &["auth".into(), "login".into(), "--claudeai".into()],
            &environment(&s.config, &directory),
            Some(&directory),
        );
        cmd.stdin(std::process::Stdio::piped());
        let mut child = cmd.spawn().map_err(|_| {
            Error::new(
                503,
                "Claude Code is not installed. Install the supported CLI on the server.",
            )
        })?;
        let mut stdin = child.stdin.take().unwrap();
        let mut stdout = child.stdout.take().unwrap();
        let mut stderr = child.stderr.take().unwrap();
        let (tx, mut rx) = mpsc::channel::<String>(1);
        let cancel = CancellationToken::new();
        let attempt = id();
        let view = json!({"id":attempt,"state":"pending","url":null,"expiresAt":now()+900_000,"error":null});
        *guard = Some(Login {
            view: view.clone(),
            cancel: cancel.clone(),
            input: tx,
        });
        drop(guard);
        let this = self.clone();
        let service = s.clone();
        tokio::spawn(async move {
            let mut output = String::new();
            let mut a = [0u8; 4096];
            let mut b = [0u8; 4096];
            let mut stdout_open = true;
            let mut stderr_open = true;
            let deadline = tokio::time::sleep(Duration::from_secs(900));
            tokio::pin!(deadline);
            let result:Result<bool>=async {loop {tokio::select! {
                _=cancel.cancelled()=>return Ok(false),
                _=service.shutdown.cancelled()=>return Ok(false),
                _=&mut deadline=>return Err(Error::new(408,"Sign-in expired. Start again to get a new link.")),
                code=rx.recv()=>if let Some(code)=code {stdin.write_all(code.as_bytes()).await?;stdin.write_all(b"\n").await?;},
                status=child.wait()=>return Ok(status?.success()),
                n=stdout.read(&mut a),if stdout_open=>{let n=n?;stdout_open=n>0;output.push_str(&String::from_utf8_lossy(&a[..n]));},
                n=stderr.read(&mut b),if stderr_open=>{let n=n?;stderr_open=n>0;output.push_str(&String::from_utf8_lossy(&b[..n]));}
            }
            if output.len()>64_000 {return Err(Error::new(502,"Claude Code sign-in returned too much output."));}
            if let Some(url)=login_url(&output) {let mut login=this.login.lock().await;if let Some(l)=login.as_mut().filter(|l|l.view["id"]==attempt) {l.view["url"]=url.into();}}
            }}.await;
            let _ = child.kill().await;
            let _ = child.wait().await;
            let connected = matches!(result, Ok(true))
                && status(&service).await.is_ok_and(|v| v["connected"] == true);
            if connected {
                let _ = tokio::fs::remove_file(home(&service.config).join("sync-required")).await;
                let _ = service.store.delete("claude-models").await;
            }
            let mut login = this.login.lock().await;
            if let Some(l) = login.as_mut().filter(|l| l.view["id"] == attempt) {
                l.view["url"] = Value::Null;
                l.view["state"] = if connected {
                    "completed"
                } else if cancel.is_cancelled() || service.shutdown.is_cancelled() {
                    "cancelled"
                } else {
                    "failed"
                }
                .into();
                if l.view["state"] == "failed" {
                    l.view["error"] = result
                        .err()
                        .map(|e| e.message)
                        .unwrap_or_else(|| {
                            "Claude Code could not finish sign-in. Try again.".into()
                        })
                        .into();
                }
            }
        });
        Ok(view)
    }
}
fn login_url(output: &str) -> Option<String> {
    output
        .split_whitespace()
        .filter_map(|s| url::Url::parse(s).ok())
        .find(|u| {
            u.scheme() == "https"
                && matches!(
                    u.host_str(),
                    Some(
                        "claude.ai"
                            | "claude.com"
                            | "platform.claude.com"
                            | "console.anthropic.com"
                    )
                )
                && u.username().is_empty()
                && u.password().is_none()
        })
        .map(|u| u.to_string())
}
pub async fn routes(s: &Arc<Service>, input: &Input) -> Result<Value> {
    match (input.method.as_str(), input.path.as_str()) {
        ("GET", "/api/claude/models") => model_catalog(s).await,
        ("GET", "/api/claude/connection") => s.claude.view(s).await,
        ("POST", "/api/claude/login") => s.claude.start(s).await,
        ("POST", "/api/claude/login/code") => {
            let code = input.string("code", 4096)?.trim();
            if code.is_empty() || code.chars().any(char::is_whitespace) {
                return Err(Error::bad(
                    "Paste the authorization code shown by Anthropic.",
                ));
            }
            let login = s.claude.login.lock().await;
            let l = login
                .as_ref()
                .filter(|l| l.view["state"] == "pending" && l.view["id"] == input.body["id"])
                .ok_or_else(|| Error::new(409, "This sign-in is no longer active."))?;
            l.input
                .try_send(code.into())
                .map_err(|_| Error::new(409, "A code is already being checked."))?;
            Ok(json!({"submitted":true}))
        }
        ("DELETE", "/api/claude/login") => {
            s.claude.cancel().await;
            Ok(json!({"cancelled":true}))
        }
        ("DELETE", "/api/claude/connection") => {
            let _gate = s.claude.gate.lock().await;
            if active(s).await? {
                return Err(Error::new(
                    409,
                    "Finish the active Claude run before disconnecting.",
                ));
            }
            if s.claude
                .login
                .lock()
                .await
                .as_ref()
                .is_some_and(|l| l.view["state"] == "pending")
            {
                return Err(Error::new(409, "Cancel sign-in before disconnecting."));
            }
            let directory = home(&s.config);
            let out = bounded_output(
                command(
                    &s.config.claude_bin,
                    &["auth".into(), "logout".into()],
                    &environment(&s.config, &directory),
                    Some(&directory),
                ),
                Duration::from_secs(15),
                32000,
            )
            .await?;
            if !out.success {
                return Err(Error::new(502, "Claude Code could not disconnect."));
            }
            s.store
                .set("claude-status", json!({"connected":false}), None)
                .await?;
            s.store.delete("claude-models").await?;
            Ok(json!({"disconnected":true}))
        }
        _ => Err(Error::new(404, "Unknown Claude operation.")),
    }
}

/// Move only the CLI's opaque authentication files into the private hosted home.
/// The account is serialized; the runner returns rotated state before another run starts.
pub async fn prepare_home(config: &Config, target: &Path) -> Result<()> {
    private_dir(target).await?;
    for name in [".credentials.json", ".claude.json"] {
        let path = home(config).join(name);
        let file = match tokio::fs::OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW)
            .open(path)
            .await
        {
            Ok(f) => f,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(e) => return Err(e.into()),
        };
        let bytes = crate::process::read_bounded(file, 128_000).await?;
        crate::skills::atomic_write(&target.join(name), &bytes).await?;
    }
    Ok(())
}
