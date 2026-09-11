use crate::error::{Error, Result};
use serde::{Deserialize, Serialize};
use std::{env, path::PathBuf};
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub data_dir: PathBuf,
    pub home: PathBuf,
    pub workspace_roots: Vec<PathBuf>,
    pub public_url: String,
    pub host: String,
    pub port: u16,
    pub setup_token: String,
    pub codex_bin: String,
    pub gh_bin: String,
    pub concurrency: usize,
    pub logger: bool,
    pub worker_enabled: bool,
    pub runner_url: String,
}
impl Config {
    pub fn load() -> Result<Self> {
        let cwd = env::current_dir()?;
        let get = |key, default: &str| env::var(key).unwrap_or_else(|_| default.to_owned());
        let mut value = serde_json::json!({
                    "dataDir":get("DATA_DIR",".data"), "home":get("AGENT_HOME",&get("HOME","/home/node")),
                    "workspaceRoots":get("WORKSPACE_ROOTS",&cwd.to_string_lossy()).split(':').collect::<Vec<_>>(),
                    "publicUrl":get("PUBLIC_URL","http://localhost:4310"),"host":get("HOST","127.0.0.1"),
                    "port":get("PORT","4310").parse::<u16>().map_err(|_|Error::bad("PORT must be a valid port number."))?,
                    "setupToken":get("SETUP_TOKEN",""),"codexBin":get("CODEX_BIN","codex"),"ghBin":get("GH_BIN","gh"),
                    "concurrency":get("CONCURRENCY","1").parse::<usize>().map_err(|_|Error::bad("Invalid concurrency"))?,
                    "logger":get("NODE_ENV","")!="test", "workerEnabled":get("WORKER_ENABLED","true")!="false", "runnerUrl":get("RUNNER_URL","")
                }
        );
        if let Ok(file) = env::var("LEO_CONFIG") {
            let overrides: serde_json::Value = serde_json::from_slice(&std::fs::read(file)?)?;
            value.as_object_mut().unwrap().extend(
                overrides
                    .as_object()
                    .ok_or_else(|| Error::bad("Invalid configuration"))?
                    .clone(),
            );
        }
        let mut config: Self = serde_json::from_value(value)?;
        if !(1..=4).contains(&config.concurrency) {
            return Err(Error::bad(
                "CONCURRENCY must be an integer between 1 and 4.",
            ));
        }
        let url =
            url::Url::parse(&config.public_url).map_err(|_| Error::bad("Invalid PUBLIC_URL"))?;
        if !["http", "https"].contains(&url.scheme())
            || url.path() != "/"
            || url.query().is_some()
            || url.fragment().is_some()
            || !url.username().is_empty()
            || url.password().is_some()
        {
            return Err(Error::bad(
                "PUBLIC_URL must be an HTTP(S) origin without a path or credentials.",
            ));
        }
        config.public_url = url.origin().ascii_serialization();
        config.data_dir = std::path::absolute(&config.data_dir)?;
        for root in &mut config.workspace_roots {
            *root = std::path::absolute(&*root)?;
        }
        Ok(config)
    }
}
pub fn now() -> i64 {
    chrono::Utc::now().timestamp_millis()
}
pub fn id() -> String {
    uuid::Uuid::new_v4().to_string()
}
pub const MAIN_AGENT_ID: &str = "00000000-0000-4000-8000-000000000001";
