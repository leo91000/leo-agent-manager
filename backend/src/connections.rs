use crate::{
    config::{Config, now},
    error::{Error, Result},
    process::{bounded_output, codex_environment, command},
    service::Service,
    validation::text,
};
use serde_json::{Value, json};
use std::{
    path::Path,
    sync::{Arc, LazyLock},
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::{Mutex, watch},
};
use tokio_util::sync::CancellationToken;
static CODE: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new(r"\b[A-Z0-9]{4}-[A-Z0-9]{4,5}\b").unwrap());
static URL: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(r"https://(?:auth\.openai\.com|github\.com)/[\w/-]+").unwrap()
});
#[derive(Clone)]
pub struct DeviceLogin {
    pub flow: watch::Sender<Value>,
    pub stop: CancellationToken,
    finished: watch::Receiver<bool>,
}
impl DeviceLogin {
    pub fn start(config: &Config, provider: &str, home: &Path) -> Result<Self> {
        let mut config = config.clone();
        config.home = home.to_owned();
        if provider == "codex" {
            let (flow, _) = watch::channel(
                json!({"provider":"codex","state":"pending","phase":"starting","url":"","code":""}),
            );
            let (done, finished) = watch::channel(false);
            let stop = CancellationToken::new();
            let login = Self {
                flow: flow.clone(),
                stop: stop.clone(),
                finished,
            };
            let home = home.to_owned();
            tokio::spawn(async move {
                let result = crate::codex_login::run(&config, &home, &flow, &stop).await;
                flow.send_modify(|value| {
                    value["code"] = "".into();
                    value["url"] = "".into();
                    value["expiresAt"] = Value::Null;
                    value["phase"] = "verifying".into();
                    value["state"] = if result.is_ok() { "complete" } else { "failed" }.into();
                    if let Err(error) = result {
                        value["error"] = error.message.into();
                    }
                });
                let _ = done.send(true);
            });
            return Ok(login);
        }
        let args = [
            "auth",
            "login",
            "--hostname",
            "github.com",
            "--git-protocol",
            "https",
            "--web",
        ];
        let mut command = command(
            &config.gh_bin,
            &args.iter().map(|s| (*s).to_owned()).collect::<Vec<_>>(),
            &codex_environment(&config, &home.join(".codex")),
            None,
        );
        command.stdin(std::process::Stdio::piped());
        let mut child = command
            .spawn()
            .map_err(|_| Error::new(503, "Unable to start sign-in. Check the CLI installation."))?;
        let mut stdout = child.stdout.take().unwrap();
        let mut stderr = child.stderr.take().unwrap();
        let mut stdin = child.stdin.take().unwrap();
        let (flow, _) = watch::channel(json!({
        "provider":provider,"state":"pending","url":"","code":""}
        ));
        let (done, finished) = watch::channel(false);
        let stop = CancellationToken::new();
        let login = Self {
            flow: flow.clone(),
            stop: stop.clone(),
            finished,
        };
        tokio::spawn(async move {
            let _ = stdin.write_all(b"\n").await;
            drop(stdin);
            let mut buffer = Vec::new();
            let mut out = [0; 4096];
            let mut err = [0; 4096];
            let mut stdout_open = true;
            let mut stderr_open = true;
            let deadline = tokio::time::sleep(Duration::from_secs(15 * 60));
            tokio::pin!(deadline);
            let success = loop {
                tokio::select! {
                                    _=stop.cancelled()=>break false,
                                    _=&mut deadline=>break false,
                                    result=child.wait()=>break result.is_ok_and(|s|s.success()),
                                    result=stdout.read(&mut out),if stdout_open=>match result {
                Ok(0)|Err(_)=>stdout_open=false,Ok(n)=>receive(&flow,&mut buffer,&out[..n])}
                ,
                                    result=stderr.read(&mut err),if stderr_open=>match result {
                Ok(0)|Err(_)=>stderr_open=false,Ok(n)=>receive(&flow,&mut buffer,&err[..n])}
                ,
                                }
            };
            if !success {
                if let Some(pid) = child.id() {
                    unsafe {
                        libc::kill(pid as i32, libc::SIGTERM);
                    }
                }
                if tokio::time::timeout(Duration::from_secs(2), child.wait())
                    .await
                    .is_err()
                {
                    let _ = child.kill().await;
                }
            }
            flow.send_modify(|value| {
                value["state"] = if success { "complete" } else { "failed" }.into();
                if !success {
                    value["error"] = "Sign-in did not complete. Retry or use the documented container login command.".into();
                }
            });
            let _ = done.send(true);
        });
        Ok(login)
    }
    pub fn running(&self) -> bool {
        !*self.finished.borrow()
    }
    pub async fn wait(&self) {
        let mut finished = self.finished.clone();
        while !*finished.borrow() {
            if finished.changed().await.is_err() {
                break;
            }
        }
    }
    pub async fn cancel(&self) {
        self.stop.cancel();
        self.wait().await;
    }
    pub fn view(&self) -> Value {
        self.flow.borrow().clone()
    }
}
fn receive(flow: &watch::Sender<Value>, buffer: &mut Vec<u8>, bytes: &[u8]) {
    buffer.extend_from_slice(bytes);
    if buffer.len() > 16000 {
        buffer.drain(..buffer.len() - 16000);
    }
    let output = String::from_utf8_lossy(buffer);
    flow.send_modify(|value| {
        if let Some(code) = CODE.find(&output) {
            value["code"] = code.as_str().into();
        }
        if let Some(url) = URL.find(&output) {
            value["url"] = url.as_str().into();
        }
    });
}
#[derive(Default)]
pub struct Connections {
    cache: Mutex<Option<(i64, Value)>>,
    pub login: Mutex<Option<DeviceLogin>>,
}
impl Connections {
    pub async fn status(&self, s: &Service, force: bool) -> Result<Value> {
        let mut cache = self.cache.lock().await;
        if !force
            && let Some((at, value)) = &*cache
            && now() - at < 30000
        {
            return Ok(value.clone());
        }
        let (codex, github) = tokio::join!(check(&s.config, "codex"), check(&s.config, "github"));
        let value = json!([codex, github]);
        *cache = Some((now(), value.clone()));
        Ok(value)
    }
    pub async fn start(&self, s: &Service, provider: &str) -> Result<Value> {
        if !["codex", "github"].contains(&provider) {
            return Err(Error::bad("Unknown connection provider."));
        }
        let mut login = self.login.lock().await;
        if login.as_ref().is_some_and(DeviceLogin::running) {
            return Err(Error::new(409, "A sign-in is already in progress."));
        }
        let flow = DeviceLogin::start(&s.config, provider, &s.config.home)?;
        let result = flow.view();
        *login = Some(flow);
        *self.cache.lock().await = None;
        Ok(result)
    }
    pub async fn flow(&self) -> Value {
        self.login
            .lock()
            .await
            .as_ref()
            .map(DeviceLogin::view)
            .unwrap_or(Value::Null)
    }
    pub async fn cancel(&self) {
        if let Some(login) = self.login.lock().await.take() {
            login.cancel().await;
        }
        *self.cache.lock().await = None;
    }
}
async fn check(config: &Config, provider: &str) -> Value {
    let binary = if provider == "codex" {
        &config.codex_bin
    } else {
        &config.gh_bin
    };
    let env = codex_environment(config, &config.home.join(".codex"));
    let version = bounded_output(
        command(binary, &["--version".into()], &env, None),
        Duration::from_secs(5),
        10000,
    )
    .await;
    let Ok(version) = version else {
        return json!({
        "provider":provider,"installed":false,"connected":false,"account":"CLI not installed","version":""}
        );
    };
    if !version.success {
        return json!({
        "provider":provider,"installed":false,"connected":false,"account":"CLI not installed","version":""}
        );
    }
    let args = if provider == "codex" {
        vec!["login", "status"]
    } else {
        vec!["api", "user", "--jq", ".login"]
    };
    let login = bounded_output(
        command(
            binary,
            &args.iter().map(|s| (*s).into()).collect::<Vec<_>>(),
            &env,
            None,
        ),
        Duration::from_secs(10),
        10000,
    )
    .await
    .ok()
    .filter(|o| o.success);
    let auth = login
        .as_ref()
        .map(|o| {
            if provider == "codex" {
                format!("{}{}", o.stdout, o.stderr)
            } else {
                o.stdout.trim().to_owned()
            }
        })
        .unwrap_or_default();
    let connected = if provider == "codex" {
        auth.contains("ChatGPT")
    } else {
        login.is_some()
    };
    json!({
    "provider":provider,"installed":true,"version":version.stdout.lines().next().unwrap_or(""),"connected":connected,"account":if !connected{
    "Not signed in"}
    else if provider=="codex"{
    "ChatGPT subscription"}
    else{
    &auth}
    }
    )
}
#[derive(Clone)]
pub struct AccountLogin {
    pub account_id: String,
    pub device: DeviceLogin,
    pub home: std::path::PathBuf,
    pub complete: watch::Receiver<bool>,
}
impl AccountLogin {
    pub fn busy(&self) -> bool {
        !*self.complete.borrow()
    }
    pub fn view(&self) -> Value {
        let mut value = self.device.view();
        // Authentication is only complete after credentials are captured, the
        // account identity is verified, and the temporary home is removed.
        if self.busy() && value["state"] == "complete" {
            value["state"] = "pending".into();
            value["phase"] = "verifying".into();
        }
        value["accountId"] = self.account_id.clone().into();
        value
    }
}
impl Service {
    pub async fn account_login(self: &Arc<Self>, name: &str, id: Option<&str>) -> Result<Value> {
        self.accounts.initialize(self).await?;
        let _selection = self.accounts.selection.lock().await;
        let mut login = self.account_login.lock().await;
        if login.as_ref().is_some_and(AccountLogin::busy) {
            return Err(Error::new(409, "A Codex sign-in is already in progress."));
        }
        let account = if let Some(id) = id {
            let id_owned = id.to_owned();
            let recovering =
                self.store
                    .read(move |db| {
                        Ok(db.active()?.iter().any(|r| {
                            r["recoveryPending"] == true && r["codexAccountId"] == id_owned
                        }))
                    })
                    .await?;
            if recovering || self.accounts.leases.lock().await.contains_key(id) {
                return Err(Error::new(
                    409,
                    "Wait for this account’s run to finish before reconnecting it.",
                ));
            }
            self.accounts.get(self, id).await?
        } else {
            if self.store.list("codexAccounts").await?.len() >= 10 {
                return Err(Error::bad(
                    "A maximum of ten Codex accounts can be connected.",
                ));
            }
            self.accounts.new_account(self, name).await?
        };
        let id = text(&account, "id").to_owned();
        let _guard = self.accounts.lock(&id).await;
        let home = self.config.data_dir.join("codex-login").join(&id);
        crate::skills::private_dir(&home.join(".codex")).await?;
        crate::skills::atomic_write(
            &home.join(".codex/config.toml"),
            b"cli_auth_credentials_store = \"file\"\nforced_login_method = \"chatgpt\"\n",
        )
        .await?;
        let device = DeviceLogin::start(&self.config, "codex", &home)?;
        *self.accounts.connecting.lock().await = Some(id.clone());
        let (done, complete) = watch::channel(false);
        let flow = AccountLogin {
            account_id: id.clone(),
            device: device.clone(),
            home: home.clone(),
            complete,
        };
        let result = flow.view();
        *login = Some(flow);
        let s = self.clone();
        tokio::spawn(async move {
            device.wait().await;
            let operation = async {
                if device.view()["state"] != "complete" {
                    return Ok(());
                }
                device.flow.send_modify(|f| f["state"] = "pending".into());
                let previous = s.vault.get(&format!("codex-account:{id}")).await?;
                s.accounts.capture(&s, &id, &home.join(".codex")).await?;
                // Keep selection fenced until identity verification and credential rollback finish.
                let _selection = s.accounts.selection.lock().await;
                *s.accounts.connecting.lock().await = None;
                s.accounts.refresh(&s, &id).await?;
                *s.accounts.connecting.lock().await = Some(id.clone());
                let account = s.accounts.get(&s, &id).await?;
                if account["state"] != "ready" {
                    if let Some(previous) = previous {
                        s.vault
                            .set(&format!("codex-account:{id}"), &previous)
                            .await?;
                    } else {
                        s.vault.delete(&format!("codex-account:{id}")).await?;
                    }
                    return Err(Error::bad(text(&account, "error")));
                }
                device.flow.send_modify(|f| f["state"] = "complete".into());
                Ok(())
            }
            .await;
            if let Err(error) = operation {
                device.flow.send_modify(|f| {
                    f["state"] = "failed".into();
                    f["error"] = if error.status < 500 {
                        error.message.into()
                    } else {
                        "Unable to complete sign-in. Reconnect and try again.".into()
                    };
                });
            }
            let _ = tokio::fs::remove_dir_all(&home).await;
            *s.accounts.connecting.lock().await = None;
            let _ = done.send(true);
        });
        Ok(result)
    }
    pub async fn cancel_account_login(&self) {
        let mut current = self.account_login.lock().await;
        if let Some(mut login) = current.take() {
            login.device.cancel().await;
            while !*login.complete.borrow() {
                if login.complete.changed().await.is_err() {
                    break;
                }
            }
        }
    }
}
