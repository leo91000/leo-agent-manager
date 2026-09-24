//! Only the manager rotates the shared Claude login. Guests receive access-only
//! snapshots over a run-scoped socket, and never return credential files.
use crate::{
    claude,
    config::now,
    error::{Error, Result},
    process::read_bounded,
    service::Service,
    skills::{atomic_write, private_dir},
    validation::text,
};
use serde_json::{Value, json};
use std::{
    path::{Path, PathBuf},
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::{UnixListener, UnixStream},
};
use tokio_util::sync::CancellationToken;

const TOKEN_URL: &str = "https://platform.claude.com/v1/oauth/token";
// Official Claude Code 2.1.280 default; persisted per-login clientId takes precedence.
const CLIENT_ID: &str = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
const LIMIT: usize = 128_000;
pub const SOCKET: &str = "leo-auth.sock";
fn unavailable() -> Error {
    Error::new(
        503,
        "Claude authentication is unavailable. Check the connection in Connections.",
    )
}
async fn read(home: &Path) -> Result<Value> {
    let file = tokio::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(home.join(".credentials.json"))
        .await
        .map_err(|_| unavailable())?;
    serde_json::from_slice(&read_bounded(file, LIMIT).await?).map_err(|_| unavailable())
}
fn snapshot(value: &Value) -> Result<Value> {
    let oauth = &value["claudeAiOauth"];
    if text(oauth, "accessToken").is_empty()
        || oauth["expiresAt"].as_i64().unwrap_or(0) <= now() + 30_000
    {
        return Err(unavailable());
    }
    let mut safe = json!({});
    for key in [
        "accessToken",
        "expiresAt",
        "scopes",
        "subscriptionType",
        "rateLimitTier",
    ] {
        if let Some(value) = oauth.get(key) {
            safe[key] = value.clone();
        }
    }
    Ok(json!({"claudeAiOauth":safe}))
}
// Caller holds the account gate, including through persistence after rotation.
async fn access_at(home: &Path, endpoint: &str) -> Result<Value> {
    let mut value = read(home).await?;
    let oauth = &value["claudeAiOauth"];
    if oauth["expiresAt"].as_i64().unwrap_or(0) <= now() + 300_000 {
        if text(oauth, "refreshToken").is_empty() {
            return snapshot(&value);
        }
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(30))
            .build()
            .map_err(|_| unavailable())?;
        let body = json!({"grant_type":"refresh_token","refresh_token":oauth["refreshToken"],"client_id":oauth["clientId"].as_str().unwrap_or(CLIENT_ID),"scope":oauth["scopes"].as_array().into_iter().flatten().filter_map(Value::as_str).collect::<Vec<_>>().join(" ")});
        let mut response = client
            .post(endpoint)
            .json(&body)
            .send()
            .await
            .map_err(|_| unavailable())?;
        if !response.status().is_success() {
            return Err(unavailable());
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| unavailable())? {
            if bytes.len() + chunk.len() > LIMIT {
                return Err(unavailable());
            }
            bytes.extend_from_slice(&chunk);
        }
        let rotated: Value = serde_json::from_slice(&bytes).map_err(|_| unavailable())?;
        let seconds = rotated["expires_in"]
            .as_i64()
            .filter(|n| *n > 60 && *n <= 366 * 86400)
            .ok_or_else(unavailable)?;
        if text(&rotated, "access_token").is_empty() {
            return Err(unavailable());
        }
        let oauth = &mut value["claudeAiOauth"];
        oauth["accessToken"] = rotated["access_token"].clone();
        oauth["expiresAt"] = (now() + seconds * 1000).into();
        if let Some(token) = rotated["refresh_token"].as_str().filter(|s| !s.is_empty()) {
            oauth["refreshToken"] = token.into();
        }
        if let Some(scope) = rotated["scope"].as_str() {
            oauth["scopes"] = json!(scope.split_whitespace().collect::<Vec<_>>());
        }
        if let Some(seconds) = rotated["refresh_token_expires_in"]
            .as_i64()
            .filter(|n| *n > 0 && *n <= 366 * 86400)
        {
            oauth["refreshTokenExpiresAt"] = (now() + seconds * 1000).into();
        }
        atomic_write(
            &home.join(".credentials.json"),
            &serde_json::to_vec(&value)?,
        )
        .await?;
        tokio::fs::File::open(home).await?.sync_all().await?;
    }
    snapshot(&value)
}
/// Metadata subprocesses also get access-only storage. Caller holds the account gate.
pub async fn metadata_home(s: &Service) -> Result<tempfile::TempDir> {
    let home = claude::home(&s.config);
    if home.join("sync-required").exists() {
        return Err(unavailable());
    }
    let snapshot = access_at(&home, TOKEN_URL).await?;
    let directory = tempfile::tempdir()?;
    atomic_write(
        &directory.path().join(".credentials.json"),
        &serde_json::to_vec(&snapshot)?,
    )
    .await?;
    Ok(directory)
}

async fn access(s: &Service) -> Result<Value> {
    let _gate = s.claude.gate.lock().await;
    let home = claude::home(&s.config);
    if home.join("sync-required").exists() {
        return Err(unavailable());
    }
    access_at(&home, TOKEN_URL).await
}

pub struct Broker {
    stop: CancellationToken,
    path: PathBuf,
}
impl Drop for Broker {
    fn drop(&mut self) {
        self.stop.cancel();
        let _ = std::fs::remove_file(&self.path);
    }
}
pub async fn serve(s: &Service, home: &Path) -> Result<Broker> {
    use std::os::unix::fs::PermissionsExt;
    private_dir(home).await?;
    let path = home.join(SOCKET);
    if path.exists() {
        tokio::fs::remove_file(&path).await?;
    }
    let listener = UnixListener::bind(&path)?;
    tokio::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).await?;
    let stop = CancellationToken::new();
    let stopping = stop.clone();
    let service = s.clone();
    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = stopping.cancelled() => break,
                _ = service.shutdown.cancelled() => break,
                accepted = listener.accept() => {
                    let Ok((mut stream, _)) = accepted else { break };
                    // Never cancel a rotation between the provider response and durable storage.
                    let response = access(&service).await.unwrap_or(json!({"error":"Claude authentication unavailable"}));
                    let _ = tokio::time::timeout(Duration::from_secs(3), stream.write_all(format!("{response}\n").as_bytes())).await;
                }
            }
        }
    });
    Ok(Broker { stop, path })
}

pub struct Client {
    socket: PathBuf,
    home: PathBuf,
    expires: i64,
    previous: Vec<u8>,
}
impl Client {
    pub fn new(home: &Path) -> Self {
        Self {
            socket: std::env::var_os("LEO_AUTH_SOCKET")
                .map(PathBuf::from)
                .unwrap_or_else(|| home.join(SOCKET)),
            home: home.into(),
            expires: 0,
            previous: Vec::new(),
        }
    }
    pub async fn sync(&mut self) -> Result<()> {
        let result = tokio::time::timeout(Duration::from_secs(40), async {
            let stream = UnixStream::connect(&self.socket)
                .await
                .map_err(|_| unavailable())?;
            let mut reader = BufReader::new(stream);
            let mut bytes = Vec::new();
            loop {
                let buf = reader.fill_buf().await?;
                if buf.is_empty() {
                    return Err(unavailable());
                }
                let end = buf.iter().position(|b| *b == b'\n');
                let len = end.map_or(buf.len(), |i| i + 1);
                if bytes.len() + len > LIMIT {
                    return Err(unavailable());
                }
                bytes.extend_from_slice(&buf[..len]);
                reader.consume(len);
                if end.is_some() {
                    break;
                }
            }
            let value: Value = serde_json::from_slice(&bytes).map_err(|_| unavailable())?;
            let value = snapshot(&value)?; // Explicit allowlist also prevents accidental refresh-token distribution.
            let bytes = serde_json::to_vec(&value)?;
            if bytes != self.previous
                || tokio::fs::read(self.home.join(".credentials.json"))
                    .await
                    .ok()
                    .as_deref()
                    != Some(bytes.as_slice())
            {
                atomic_write(&self.home.join(".credentials.json"), &bytes).await?;
                self.previous = bytes;
            }
            self.expires = value["claudeAiOauth"]["expiresAt"].as_i64().unwrap_or(0);
            Ok(())
        })
        .await
        .unwrap_or_else(|_| Err(unavailable()));
        // Transient manager restarts must not interrupt a still-authenticated conversation.
        if result.is_err() && self.expires > now() + 30_000 {
            return Ok(());
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    };
    fn credentials() -> Value {
        json!({"claudeAiOauth":{"accessToken":"old-access","refreshToken":"private-refresh","expiresAt":now()-1000,"scopes":["user:profile","user:inference"],"subscriptionType":"max","clientId":"login-client"},"otherSecret":"must-stay-on-host"})
    }
    #[tokio::test]
    async fn concurrent_requests_rotate_once_and_keep_refresh_state_on_host() {
        let root = tempfile::TempDir::new().unwrap();
        atomic_write(
            &root.path().join(".credentials.json"),
            &serde_json::to_vec(&credentials()).unwrap(),
        )
        .await
        .unwrap();
        let requests = Arc::new(AtomicUsize::new(0));
        let count = requests.clone();
        let router=axum::Router::new().route("/token",axum::routing::post(move |axum::Json(v):axum::Json<Value>| {let count=count.clone();async move {
            assert_eq!(v["grant_type"],"refresh_token");assert_eq!(v["refresh_token"],"private-refresh");assert_eq!(v["client_id"],"login-client");
            count.fetch_add(1,Ordering::SeqCst);
            tokio::time::sleep(Duration::from_millis(50)).await;
            axum::Json(json!({"access_token":"new-access","refresh_token":"new-private-refresh","expires_in":3600,"scope":"user:profile user:inference"}))
        }}));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!("http://{}/token", listener.local_addr().unwrap());
        let server = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        let gate = Arc::new(tokio::sync::Mutex::new(()));
        let mut jobs = Vec::new();
        for _ in 0..8 {
            let gate = gate.clone();
            let home = root.path().to_owned();
            let endpoint = endpoint.clone();
            jobs.push(tokio::spawn(async move {
                let _gate = gate.lock().await;
                access_at(&home, &endpoint).await.unwrap()
            }));
        }
        for job in jobs {
            let value = job.await.unwrap();
            assert_eq!(value["claudeAiOauth"]["accessToken"], "new-access");
            assert!(!value.to_string().contains("refresh"));
            assert!(!value.to_string().contains("must-stay-on-host"));
        }
        assert_eq!(requests.load(Ordering::SeqCst), 1);
        assert_eq!(
            read(root.path()).await.unwrap()["claudeAiOauth"]["refreshToken"],
            "new-private-refresh"
        );
        server.abort();
    }
    #[tokio::test]
    async fn failed_refresh_is_private_and_does_not_destroy_credentials() {
        let root = tempfile::TempDir::new().unwrap();
        let original = credentials();
        atomic_write(
            &root.path().join(".credentials.json"),
            &serde_json::to_vec(&original).unwrap(),
        )
        .await
        .unwrap();
        let router = axum::Router::new().route(
            "/token",
            axum::routing::post(|| async {
                (
                    axum::http::StatusCode::UNAUTHORIZED,
                    "private-refresh should never appear in errors",
                )
            }),
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!("http://{}/token", listener.local_addr().unwrap());
        let server = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        let error = access_at(root.path(), &endpoint).await.unwrap_err();
        assert!(!error.message.contains("private-refresh"));
        assert_eq!(read(root.path()).await.unwrap(), original);
        server.abort();
    }
    #[tokio::test]
    async fn client_replaces_credentials_without_refresh_tokens_and_survives_transient_outage() {
        let root = tempfile::TempDir::new().unwrap();
        let socket = root.path().join(SOCKET);
        let listener = UnixListener::bind(&socket).unwrap();
        let mut value = credentials();
        value["claudeAiOauth"]["expiresAt"] = (now() + 3600000).into();
        let serve = tokio::spawn(async move {
            for token in ["first", "second"] {
                let (mut stream, _) = listener.accept().await.unwrap();
                value["claudeAiOauth"]["accessToken"] = token.into();
                stream
                    .write_all(format!("{value}\n").as_bytes())
                    .await
                    .unwrap();
            }
        });
        let mut client = Client {
            socket,
            home: root.path().into(),
            expires: 0,
            previous: Vec::new(),
        };
        client.sync().await.unwrap();
        assert_eq!(
            read(root.path()).await.unwrap()["claudeAiOauth"]["accessToken"],
            "first"
        );
        client.sync().await.unwrap();
        serve.await.unwrap();
        let stored = read(root.path()).await.unwrap();
        assert_eq!(stored["claudeAiOauth"]["accessToken"], "second");
        assert!(!stored.to_string().contains("refresh"));
        client.sync().await.unwrap();
        client.expires = 0;
        assert!(client.sync().await.is_err());
    }
}
