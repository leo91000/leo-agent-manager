use crate::{
    config::Config,
    error::{Error, Result},
};
use std::{collections::HashMap, path::Path, process::Stdio, time::Duration};
use tokio::{
    io::{AsyncRead, AsyncReadExt},
    process::Command,
};
pub type Environment = HashMap<String, String>;
pub fn codex_environment(config: &Config, home: &Path) -> Environment {
    let mut env = std::env::vars().collect::<Environment>();
    env.insert("HOME".into(), config.home.to_string_lossy().into_owned());
    env.insert("CODEX_HOME".into(), home.to_string_lossy().into_owned());
    for key in ["CODEX_API_KEY", "OPENAI_API_KEY", "CODEX_THREAD_ID"] {
        env.remove(key);
    }
    env
}
pub fn command(binary: &str, args: &[String], env: &Environment, cwd: Option<&Path>) -> Command {
    let mut command = Command::new(binary);
    command
        .args(args)
        .env_clear()
        .envs(env)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    if let Some(cwd) = cwd {
        command.current_dir(cwd);
    }
    command
}
pub struct Output {
    pub success: bool,
    pub stdout: String,
    pub stderr: String,
}
pub async fn bounded_output(
    mut command: Command,
    timeout: Duration,
    limit: usize,
) -> Result<Output> {
    let mut child = command
        .spawn()
        .map_err(|_| Error::new(503, "Unable to start the requested executable."))?;
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let operation = async {
        let (stdout, stderr, status) = tokio::try_join!(
            read_bounded(stdout, limit),
            read_bounded(stderr, limit),
            async { child.wait().await.map_err(Error::from) }
        )?;
        Ok(Output {
            success: status.success(),
            stdout: String::from_utf8_lossy(&stdout).into_owned(),
            stderr: String::from_utf8_lossy(&stderr).into_owned(),
        })
    };
    match tokio::time::timeout(timeout, operation).await {
        Ok(Ok(output)) => Ok(output),
        result => {
            let _ = child.kill().await;
            match result {
                Ok(Err(error)) => Err(error),
                _ => Err(Error::new(504, "The executable timed out.")),
            }
        }
    }
}
pub async fn read_bounded(reader: impl AsyncRead + Unpin, limit: usize) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    reader
        .take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .await?;
    if bytes.len() > limit {
        return Err(Error::new(
            502,
            "Executable output exceeded the supported limit.",
        ));
    }
    Ok(bytes)
}
