use crate::{
    auth::hex_digest,
    config::{id, now},
    error::{Error, Result, required},
    rpc::Session,
    service::Service,
    skills::{atomic_write, private_dir},
    store::merge,
    validation::text,
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::Arc,
};
use tokio::sync::{Mutex, OnceCell};
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Lease {
    pub account_id: String,
    pub run_id: String,
    pub home: PathBuf,
    pub model: String,
}
#[derive(Default)]
pub struct Accounts {
    pub leases: Mutex<HashMap<String, Lease>>,
    locks: Mutex<HashMap<String, Arc<Mutex<()>>>>,
    initialized: OnceCell<()>,
    attempted: Mutex<HashMap<String, i64>>,
    polling: Mutex<()>,
    pub selection: Mutex<()>,
    pub connecting: Mutex<Option<String>>,
}
fn buckets<'a>(limits: &'a Value, model: &str) -> HashMap<String, &'a Value> {
    if limits.is_null() {
        return HashMap::new();
    }
    let mut result = HashMap::from([("$main".into(), &limits["rateLimits"])]);
    if !model.is_empty()
        && let Some(buckets) = limits["rateLimitsByLimitId"].as_object()
    {
        for (key, bucket) in buckets {
            if bucket["normalModelSlug"] == model || bucket["limitId"] == model {
                result.insert(key.clone(), bucket);
            }
        }
    }
    result
}
pub fn remaining(limits: &Value, model: &str) -> Option<f64> {
    buckets(limits, model)
        .values()
        .flat_map(|b| [&b["primary"], &b["secondary"]])
        .filter_map(|w| w["usedPercent"].as_f64())
        .filter(|n| n.is_finite())
        .map(|n| (100. - n).clamp(0., 100.))
        .reduce(f64::min)
}
pub fn blocked(limits: &Value, model: &str) -> bool {
    limits["ordinaryUsageAllowed"] == false
        || buckets(limits, model).values().any(|b| {
            !text(b, "rateLimitReachedType").is_empty() || b["spendControlReached"] == true
        })
}
pub fn recovered(before: &Value, after: &Value, model: &str) -> bool {
    if blocked(after, model) || remaining(after, model).unwrap_or(0.) <= 0. {
        return false;
    }
    if before["ordinaryUsageAllowed"] == false && after["ordinaryUsageAllowed"] == true {
        return true;
    }
    let previous = buckets(before, model);
    buckets(after, model).iter().any(|(key, b)| {
        previous.get(key).is_some_and(|old| {
            ["primary", "secondary"].iter().any(|w| {
                match (old[w]["usedPercent"].as_f64(), b[w]["usedPercent"].as_f64()) {
                    (Some(old), Some(current)) => current < old,
                    _ => false,
                }
            })
        })
    })
}
fn subject(token: &str) -> String {
    token
        .split('.')
        .nth(1)
        .and_then(|s| URL_SAFE_NO_PAD.decode(s).ok())
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .and_then(|v| v["sub"].as_str().map(str::to_owned))
        .unwrap_or_default()
}
fn auth_input(value: Value) -> Result<Value> {
    if text(&value["tokens"], "access_token").is_empty() {
        return Err(Error::bad(
            "Connect a ChatGPT subscription account. API keys are not supported here.",
        ));
    }
    Ok(value)
}
fn limits_input(value: Value) -> Result<Value> {
    if !value["rateLimits"].is_object() {
        return Err(Error::new(502, "Codex returned invalid usage data."));
    }
    let mut all = vec![&value["rateLimits"]];
    if let Some(b) = value["rateLimitsByLimitId"].as_object() {
        all.extend(b.values());
    }
    for bucket in all {
        for key in ["primary", "secondary"] {
            let window = &bucket[key];
            if window.is_null() {
                continue;
            }
            if window["usedPercent"]
                .as_f64()
                .is_none_or(|n| !n.is_finite() || n < 0.)
            {
                return Err(Error::new(502, "Codex returned invalid usage data."));
            }
        }
    }
    Ok(value)
}
async fn remove_file(path: &Path) -> Result<()> {
    match tokio::fs::remove_file(path).await {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.into()),
    }
}
async fn remove_directory(path: &Path) -> Result<()> {
    match tokio::fs::remove_dir_all(path).await {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.into()),
    }
}
impl Accounts {
    pub async fn lock(&self, id: &str) -> tokio::sync::OwnedMutexGuard<()> {
        self.locks
            .lock()
            .await
            .entry(id.into())
            .or_default()
            .clone()
            .lock_owned()
            .await
    }
    pub async fn get(&self, s: &Service, id: &str) -> Result<Value> {
        required(
            s.store.get("codexAccounts", id).await?,
            "Codex account not found.",
        )
    }
    pub async fn view(&self, mut account: Value) -> Value {
        account["remainingPercent"] = remaining(&account["limits"], "")
            .map(Value::from)
            .unwrap_or(Value::Null);
        account["stale"] = account["checkedAt"]
            .as_i64()
            .is_none_or(|at| now() - at > 90000)
            .into();
        account["activeRunId"] = self
            .leases
            .lock()
            .await
            .get(text(&account, "id"))
            .map(|l| l.run_id.clone().into())
            .unwrap_or(Value::Null);
        account.as_object_mut().unwrap().remove("identity");
        account
    }
    pub async fn list(&self, s: &Service) -> Result<Vec<Value>> {
        let mut accounts = s.store.list("codexAccounts").await?;
        accounts.sort_by(|a, b| {
            a["createdAt"]
                .as_i64()
                .cmp(&b["createdAt"].as_i64())
                .then(text(a, "name").cmp(text(b, "name")))
        });
        let mut result = Vec::new();
        for account in accounts {
            result.push(self.view(account).await);
        }
        Ok(result)
    }
    pub async fn new_account(&self, s: &Service, name: &str) -> Result<Value> {
        let name = name.trim();
        if name.is_empty() || name.chars().count() > 100 {
            return Err(Error::bad("Choose a name of 1–100 characters."));
        }
        let account = json!({
        "id":id(),"name":name,"enabled":true,"email":null,"plan":null,"identity":null,"createdAt":now(),"checkedAt":null,"state":"pending","error":"","limits":null,"lastUsedAt":null,"exhausted":null}
        );
        s.store
            .transaction(move |db| {
                db.put("codexAccounts", &account)?;
                db.set("codex-accounts-enabled", &json!(true), None)?;
                Ok(account)
            })
            .await
    }
    pub async fn capture(&self, s: &Service, id: &str, home: &Path) -> Result<()> {
        let bytes = tokio::fs::read(home.join("auth.json"))
            .await
            .map_err(|error| {
                if error.kind() == std::io::ErrorKind::NotFound {
                    Error::new(404, "No saved account credentials in this workspace.")
                } else {
                    Error::from(error)
                }
            })?;
        let auth = auth_input(serde_json::from_slice(&bytes)?)?;
        if let Some(previous) = s.vault.get(&format!("codex-account:{id}")).await?
            && ((!text(&previous["tokens"], "account_id").is_empty()
                && auth["tokens"]["account_id"] != previous["tokens"]["account_id"])
                || (!subject(text(&previous["tokens"], "id_token")).is_empty()
                    && subject(text(&previous["tokens"], "id_token"))
                        != subject(text(&auth["tokens"], "id_token"))))
        {
            return Err(Error::new(
                409,
                "Sign-in belongs to a different account. Add it as a new account instead.",
            ));
        }
        s.vault.set(&format!("codex-account:{id}"), &auth).await?;
        use std::os::unix::fs::PermissionsExt;
        tokio::fs::set_permissions(
            home.join("auth.json"),
            std::fs::Permissions::from_mode(0o600),
        )
        .await?;
        Ok(())
    }
    pub async fn materialize(&self, s: &Service, id: &str, home: &Path) -> Result<()> {
        let auth = required(
            s.vault.get(&format!("codex-account:{id}")).await?,
            "Reconnect this Codex account before using it.",
        )?;
        private_dir(home).await?;
        atomic_write(&home.join("auth.json"), &serde_json::to_vec(&auth)?).await?;
        atomic_write(
            &home.join("config.toml"),
            b"cli_auth_credentials_store = \"file\"\nforced_login_method = \"chatgpt\"\n",
        )
        .await
    }
    async fn recovering(&self, s: &Service, id: &str) -> Result<bool> {
        let id = id.to_owned();
        s.store
            .read(move |db| {
                Ok(db
                    .active()?
                    .iter()
                    .any(|r| r["recoveryPending"] == true && r["codexAccountId"] == id))
            })
            .await
    }
    pub async fn recover_run(&self, s: &Service, run: &Value) -> Result<()> {
        let id = text(run, "codexAccountId");
        let _guard = if id.is_empty() {
            None
        } else {
            Some(self.lock(id).await)
        };
        for relative in ["codex", "home/.codex"] {
            let home = s
                .config
                .data_dir
                .join("runs")
                .join(text(run, "id"))
                .join(relative);
            if !id.is_empty() && s.store.get("codexAccounts", id).await?.is_some() {
                let _ = self.capture(s, id, &home).await;
            }
            remove_file(&home.join("auth.json")).await?;
        }
        self.leases
            .lock()
            .await
            .retain(|_, lease| lease.run_id != text(run, "id"));
        Ok(())
    }
    pub async fn initialize(&self, s: &Service) -> Result<()> {
        self.initialized
            .get_or_try_init(|| async {
                for account in s.store.list("codexAccounts").await? {
                    for relative in ["codex-monitor", "codex-login"] {
                        let directory = s.config.data_dir.join(relative).join(text(&account, "id"));
                        let home = if relative == "codex-login" {
                            directory.join(".codex")
                        } else {
                            directory.clone()
                        };
                        let _ = self.capture(s, text(&account, "id"), &home).await;
                        remove_directory(&directory).await?;
                    }
                }
                if let Ok(mut entries) = tokio::fs::read_dir(s.config.data_dir.join("runs")).await {
                    while let Some(entry) = entries.next_entry().await? {
                        let id = entry.file_name().to_string_lossy().into_owned();
                        if uuid::Uuid::parse_str(&id).is_err() {
                            continue;
                        }
                        if let Ok(run) = s.store.run(&id).await
                            && run["recoveryPending"] != true
                        {
                            self.recover_run(s, &run).await?;
                        }
                    }
                }
                if s.store.kv("codex-accounts-enabled").await?.is_some() {
                    return Ok(());
                }
                let home = s.config.home.join(".codex");
                let Ok(bytes) = tokio::fs::read(home.join("auth.json")).await else {
                    return Ok(());
                };
                if serde_json::from_slice::<Value>(&bytes)
                    .ok()
                    .and_then(|v| auth_input(v).ok())
                    .is_none()
                {
                    return Ok(());
                }
                let account = self.new_account(s, "Primary account").await?;
                self.capture(s, text(&account, "id"), &home).await?;
                s.store
                    .audit(
                        "codex.account.imported",
                        json!({
                        "id":account["id"]}
                        ),
                    )
                    .await
            })
            .await
            .map(|_| ())
    }
    pub async fn refresh(&self, s: &Service, id: &str) -> Result<()> {
        let _guard = self.lock(id).await;
        if s.store.get("codexAccounts", id).await?.is_none()
            || self.connecting.lock().await.as_deref() == Some(id)
            || self.recovering(s, id).await?
        {
            return Ok(());
        }
        self.attempted.lock().await.insert(id.into(), now());
        let lease = self.leases.lock().await.get(id).cloned();
        let home = lease
            .as_ref()
            .map(|l| l.home.clone())
            .unwrap_or_else(|| s.config.data_dir.join("codex-monitor").join(id));
        let operation = async {
            if lease.is_none() {
                self.materialize(s, id, &home).await?;
            }
            let mut session = Session::codex(&s.config, &home, &[], None).await?;
            let result = self
                .read_usage(
                    s,
                    id,
                    &home,
                    lease.as_ref().map(|l| l.model.as_str()),
                    &mut session,
                )
                .await;
            session.close().await;
            result?;
            self.capture(s, id, &home).await
        }
        .await;
        if let Err(error) = operation {
            let mut account = self.get(s, id).await?;
            merge(
                &mut account,
                &json!({
                "state":"error","error":if error.status<500{
                error.message}
                else{
                "Unable to read this Codex account. Reconnect it and try again.".into()}
                }
                ),
            );
            s.store.put("codexAccounts", account).await?;
        }
        if lease.is_none() {
            let _ = self.capture(s, id, &home).await;
            remove_directory(&home).await?;
        }
        Ok(())
    }
    async fn read_usage(
        &self,
        s: &Service,
        id: &str,
        home: &Path,
        model: Option<&str>,
        rpc: &mut Session,
    ) -> Result<()> {
        let identity = rpc
            .request(
                "account/read",
                json!({
                "refreshToken":false}
                ),
            )
            .await?;
        if identity["account"]["type"] != "chatgpt" {
            return Err(Error::bad(
                "Connect a ChatGPT subscription account. API keys are not supported here.",
            ));
        }
        let auth = auth_input(serde_json::from_slice(
            &tokio::fs::read(home.join("auth.json")).await?,
        )?)?;
        let limits = read_limits(rpc, &auth).await?;
        let mut account = self.get(s, id).await?;
        let subject = subject(text(&auth["tokens"], "id_token"));
        let subject = if subject.is_empty() {
            text(&identity["account"], "email")
        } else {
            &subject
        };
        if subject.is_empty() {
            return Err(Error::bad(
                "Codex did not return an account identity. Reconnect this account.",
            ));
        }
        let account_id = text(&auth["tokens"], "account_id");
        let account_id = if account_id.is_empty() {
            text(&limits, "accountId")
        } else {
            account_id
        };
        let fingerprint = hex_digest(&format!("{account_id}:{subject}"));
        if account["identity"].is_string() && account["identity"] != fingerprint {
            return Err(Error::new(
                409,
                "Sign-in belongs to a different account. Add it as a new account instead.",
            ));
        }
        // Identity uniqueness is checked and saved together, even when two logins finish together.
        merge(
            &mut account,
            &json!({
            "identity":fingerprint,"email":identity["account"]["email"],"plan":identity["account"]["planType"],"state":"ready","checkedAt":now(),"error":"","limits":limits}
            ),
        );
        if !account["exhausted"].is_null()
            && recovered(
                &account["exhausted"]["limits"],
                &limits,
                text(&account["exhausted"], "model"),
            )
        {
            account["exhausted"] = Value::Null;
        }
        s.store
            .transaction({
                let account = account.clone();
                move |db| {
                    if db.list("codexAccounts")?.iter().any(|a| a["id"] != account["id"] && a["identity"] == account["identity"]) {
                        return Err(Error::new(409, "This account is already connected. Reconnect the existing account instead."));
                    }
                    db.put("codexAccounts", &account)
                }
            })
            .await?;
        let model = model.unwrap_or_else(|| text(&account["exhausted"], "model"));
        let (limits, reset_error, confirmed) = self.reset(s, &account, model, rpc, &auth).await?;
        let mut account = self.get(s, id).await?;
        if !account["exhausted"].is_null() {
            let model = text(&account["exhausted"], "model");
            if (confirmed
                && !blocked(&limits, model)
                && remaining(&limits, model).unwrap_or(0.) > 0.)
                || recovered(&account["exhausted"]["limits"], &limits, model)
            {
                account["exhausted"] = Value::Null;
            } else {
                account["exhausted"]["limits"] = limits.clone();
            }
        }
        merge(
            &mut account,
            &json!({
            "limits":limits,"resetError":reset_error}
            ),
        );
        s.store.put("codexAccounts", account).await?;
        Ok(())
    }
    async fn reset(
        &self,
        s: &Service,
        account: &Value,
        model: &str,
        rpc: &mut Session,
        auth: &Value,
    ) -> Result<(Value, String, bool)> {
        let mut limits = account["limits"].clone();
        let key = format!("codex-reset:{}", text(account, "id"));
        let mut attempt = s.store.kv(&key).await?;
        let restored = |limits: &Value, model: &str| {
            !blocked(limits, model) && remaining(limits, model).unwrap_or(0.) > 2.
        };
        if let Some(a) = &attempt {
            if account["exhausted"].is_null() && restored(&limits, text(a, "model")) {
                s.store.delete(&key).await?;
                return Ok((limits, String::new(), a["confirmed"] == true));
            }
            if a["confirmed"] == true {
                if !restored(&limits, text(a, "model")) {
                    return Ok((
                        limits,
                        "Banked reset redeemed; waiting for refreshed capacity.".into(),
                        false,
                    ));
                }
                s.store.delete(&key).await?;
                return Ok((limits, String::new(), true));
            }
        }
        if account["enabled"] != true
            || (account["exhausted"].is_null() && remaining(&limits, model).is_none_or(|n| n > 2.))
        {
            return Ok((limits, String::new(), false));
        }
        if attempt.is_none()
            && limits["rateLimitResetCredits"]["availableCount"]
                .as_u64()
                .unwrap_or(0)
                == 0
        {
            return Ok((limits, String::new(), false));
        }
        if attempt.is_none() {
            let credit = limits["rateLimitResetCredits"]["credits"]
                .as_array()
                .into_iter()
                .flatten()
                .filter(|c| {
                    c["status"] == "available"
                        && c["resetType"] == "codexRateLimits"
                        && c["expiresAt"].as_i64().is_none_or(|t| t * 1000 > now())
                })
                .min_by_key(|c| c["expiresAt"].as_i64().unwrap_or(i64::MAX));
            let mut value = json!({
            "params":{
            "idempotencyKey":id()}
            ,"model":model,"confirmed":false}
            );
            if let Some(credit) = credit {
                value["params"]["creditId"] = credit["id"].clone();
            }
            s.store.set(&key, value.clone(), None).await?;
            attempt = Some(value);
        }
        let mut attempt = attempt.unwrap();
        let result = async {
            let result = rpc
                .request(
                    "account/rateLimitResetCredit/consume",
                    attempt["params"].clone(),
                )
                .await?;
            match text(&result, "outcome") {
                "nothingToReset" | "noCredit" => {
                    s.store.delete(&key).await?;
                    Ok((
                        limits.clone(),
                        if result["outcome"] == "nothingToReset" {
                            "Banked reset is not eligible yet; checking again automatically."
                        } else {
                            "No banked reset available; waiting for capacity or another account."
                        }
                        .into(),
                        false,
                    ))
                }
                "reset" | "alreadyRedeemed" => {
                    attempt["confirmed"] = true.into();
                    s.store.set(&key, attempt.clone(), None).await?;
                    s.store
                        .audit(
                            "codex.account.reset",
                            json!({
                            "id":account["id"],"outcome":result["outcome"]}
                            ),
                        )
                        .await?;
                    limits = read_limits(rpc, auth).await?;
                    let confirmed = restored(&limits, text(&attempt, "model"));
                    if confirmed {
                        s.store.delete(&key).await?;
                    }
                    Ok((
                        limits.clone(),
                        if confirmed {
                            String::new()
                        } else {
                            "Banked reset redeemed; waiting for refreshed capacity.".into()
                        },
                        confirmed,
                    ))
                }
                _ => Err(Error::new(502, "Invalid reset result")),
            }
        }
        .await;
        Ok(result.unwrap_or_else(|_: Error| (limits, "Unable to confirm banked reset. Retrying automatically without spending another reset.".into(), false)))
    }
    pub async fn poll(&self, s: &Service, only_due: bool) -> Result<()> {
        let Ok(_guard) = self.polling.try_lock() else {
            return Ok(());
        };
        self.initialize(s).await?;
        let mut ids = Vec::new();
        for account in s.store.list("codexAccounts").await? {
            if account["state"] == "pending"
                && s.vault
                    .get(&format!("codex-account:{}", text(&account, "id")))
                    .await?
                    .is_none()
            {
                continue;
            }
            let id = text(&account, "id");
            let model = self
                .leases
                .lock()
                .await
                .get(id)
                .map(|l| l.model.clone())
                .unwrap_or_else(|| text(&account["exhausted"], "model").into());
            let pending = s.store.kv(&format!("codex-reset:{id}")).await?.is_some();
            let attention = !account["exhausted"].is_null()
                || pending
                || remaining(&account["limits"], &model).is_some_and(|n| n <= 10.);
            let interval = if account["enabled"] == true
                && attention
                && (pending
                    || account["limits"]["rateLimitResetCredits"]["availableCount"]
                        .as_u64()
                        .unwrap_or(0)
                        > 0)
            {
                15000
            } else {
                60000
            };
            if !only_due
                || now() - self.attempted.lock().await.get(id).copied().unwrap_or(0) >= interval
            {
                ids.push(id.to_owned());
            }
        }
        for batch in ids.chunks(2) {
            futures_util::future::try_join_all(batch.iter().map(|id| self.refresh(s, id))).await?;
        }
        Ok(())
    }
    pub async fn acquire(&self, s: &Service, run_id: &str, model: &str) -> Result<Option<Lease>> {
        self.initialize(s).await?;
        if s.store.kv("codex-accounts-enabled").await?.is_none() {
            return Ok(None);
        }
        self.poll(s, true).await?;
        let _selection = self.selection.lock().await;
        let leases = self.leases.lock().await;
        let connecting = self.connecting.lock().await.clone();
        let mut candidates = Vec::new();
        for account in s.store.list("codexAccounts").await? {
            let id = text(&account, "id");
            if account["enabled"] == true
                && account["exhausted"].is_null()
                && account["state"] == "ready"
                && account["checkedAt"]
                    .as_i64()
                    .is_some_and(|at| now() - at <= 90000)
                && !self.recovering(s, id).await?
                && !leases.contains_key(id)
                && connecting.as_deref() != Some(id)
                && !blocked(&account["limits"], model)
                && remaining(&account["limits"], model).unwrap_or(0.) > 0.
            {
                candidates.push(account);
            }
        }
        drop(leases);
        candidates.sort_by(|a, b| {
            remaining(&b["limits"], model)
                .unwrap()
                .total_cmp(&remaining(&a["limits"], model).unwrap())
                .then(
                    a["lastUsedAt"]
                        .as_i64()
                        .unwrap_or(0)
                        .cmp(&b["lastUsedAt"].as_i64().unwrap_or(0)),
                )
                .then(text(a, "id").cmp(text(b, "id")))
        });
        let Some(mut account) = candidates.into_iter().next() else {
            return Err(Error::new(
                409,
                "Waiting for a Codex account with available usage. Usage is checked every minute.",
            ));
        };
        let lease = Lease {
            account_id: text(&account, "id").into(),
            run_id: run_id.into(),
            model: model.into(),
            home: s.config.data_dir.join("runs").join(run_id).join("codex"),
        };
        let _guard = self.lock(&lease.account_id).await;
        self.materialize(s, &lease.account_id, &lease.home).await?;
        self.leases
            .lock()
            .await
            .insert(lease.account_id.clone(), lease.clone());
        account["lastUsedAt"] = now().into();
        s.store.put("codexAccounts", account).await?;
        Ok(Some(lease))
    }
    pub async fn release(&self, s: &Service, lease: &Lease) -> Result<()> {
        let _guard = self.lock(&lease.account_id).await;
        let result = self.capture(s, &lease.account_id, &lease.home).await;
        let removed = remove_file(&lease.home.join("auth.json")).await;
        self.leases.lock().await.remove(&lease.account_id);
        result?;
        removed
    }
    pub async fn relocate(&self, s: &Service, lease: &mut Lease, home: &Path) -> Result<()> {
        let _guard = self.lock(&lease.account_id).await;
        self.capture(s, &lease.account_id, &lease.home).await?;
        if lease.home == home {
            return Ok(());
        }
        self.materialize(s, &lease.account_id, home).await?;
        remove_file(&lease.home.join("auth.json")).await?;
        lease.home = home.to_owned();
        self.leases
            .lock()
            .await
            .insert(lease.account_id.clone(), lease.clone());
        Ok(())
    }
    pub async fn exhausted(&self, s: &Service, id: &str, model: &str) -> Result<()> {
        let (id, model) = (id.to_owned(), model.to_owned());
        s.store
            .transaction(move |db| {
                let mut a = required(db.get("codexAccounts", &id)?, "Codex account not found.")?;
                a["exhausted"] = json!({
                "at":now(),"model":model,"limits":a["limits"]}
                );
                db.put("codexAccounts", &a)?;
                db.audit(
                    "codex.account.exhausted",
                    &json!({
                    "id":id}
                    ),
                )
            })
            .await
    }
    pub async fn update(&self, s: &Service, id: &str, input: Value) -> Result<Value> {
        let _guard = self.lock(id).await;
        if self.connecting.lock().await.as_deref() == Some(id) {
            return Err(Error::new(
                409,
                "Finish or cancel sign-in before editing this account.",
            ));
        }
        let name = text(&input, "name").trim();
        if name.is_empty()
            || name.chars().count() > 100
            || input.get("enabled").is_some_and(|v| !v.is_boolean())
        {
            return Err(Error::bad("Choose a valid account name and enabled state."));
        }
        let mut a = self.get(s, id).await?;
        merge(
            &mut a,
            &json!({
            "name":name,"enabled":input.get("enabled").cloned().unwrap_or(json!(true))}
            ),
        );
        Ok(self.view(s.store.put("codexAccounts", a).await?).await)
    }
    pub async fn remove(&self, s: &Service, id: &str) -> Result<()> {
        let _selection = self.selection.lock().await;
        let _guard = self.lock(id).await;
        self.get(s, id).await?;
        if self.recovering(s, id).await?
            || self.leases.lock().await.contains_key(id)
            || self.connecting.lock().await.as_deref() == Some(id)
        {
            return Err(Error::new(
                409,
                "Wait for this account’s run or sign-in to finish before removing it.",
            ));
        }
        let id = id.to_owned();
        s.store
            .transaction(move |db| {
                db.delete(&format!("mcp-secret:codex-account:{id}"))?;
                db.delete(&format!("codex-reset:{id}"))?;
                db.remove("codexAccounts", &id)?;
                db.audit(
                    "codex.account.removed",
                    &json!({
                    "id":id}
                    ),
                )
            })
            .await
    }
    pub async fn redactions(&self, s: &Service, id: &str) -> Result<Vec<String>> {
        let auth = s
            .vault
            .get(&format!("codex-account:{id}"))
            .await?
            .unwrap_or(Value::Null);
        Ok(["access_token", "refresh_token", "id_token"]
            .iter()
            .filter_map(|key| {
                auth["tokens"][key]
                    .as_str()
                    .filter(|v| !v.is_empty())
                    .map(str::to_owned)
            })
            .collect())
    }
}
async fn read_limits(rpc: &mut Session, auth: &Value) -> Result<Value> {
    let limits = limits_input(rpc.request("account/rateLimits/read", json!({})).await?)?;
    if !text(&limits, "accountId").is_empty()
        && !text(&auth["tokens"], "account_id").is_empty()
        && limits["accountId"] != auth["tokens"]["account_id"]
    {
        return Err(Error::new(
            409,
            "Codex returned usage for a different account. Reconnect this account.",
        ));
    }
    Ok(limits)
}
