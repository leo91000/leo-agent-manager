//! Run-scoped access to one serialized, manager-owned refresh-token lifecycle.
use crate::{
    accounts::Lease,
    error::{Error, Result},
    rpc::{Incoming, Session},
    service::Service,
    skills::private_dir,
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

pub const SOCKET: &str = "leo-auth.sock";

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

pub async fn serve(s: &Service, lease: &Lease) -> Result<Broker> {
    use std::os::unix::fs::PermissionsExt;
    private_dir(&lease.home).await?;
    let path = lease.home.join(SOCKET);
    if path.exists() {
        tokio::fs::remove_file(&path).await?;
    }
    let listener = UnixListener::bind(&path)?;
    tokio::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).await?;
    let stop = CancellationToken::new();
    let stopping = stop.clone();
    let service = s.clone();
    let lease = lease.clone();
    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = stopping.cancelled() => break,
                _ = service.shutdown.cancelled() => break,
                accepted = listener.accept() => {
                    let Ok((stream, _)) = accepted else { break };
                    // One outstanding request per run. The account lock also
                    // coalesces refresh requests from different runs.
                    // Do not cancel a refresh halfway through token rotation.
                    let _ = respond(&service, &lease, stream).await;
                }
            }
        }
    });
    Ok(Broker { stop, path })
}

async fn line(stream: &mut BufReader<UnixStream>) -> Result<Value> {
    let mut bytes = Vec::new();
    loop {
        let buffer = stream.fill_buf().await?;
        if buffer.is_empty() {
            return Err(Error::bad("Account connection closed."));
        }
        let end = buffer.iter().position(|b| *b == b'\n');
        let length = end.map_or(buffer.len(), |n| n + 1);
        if bytes.len() + length > 32768 {
            return Err(Error::bad("Invalid account request."));
        }
        bytes.extend_from_slice(&buffer[..length]);
        stream.consume(length);
        if end.is_some() {
            return serde_json::from_slice(&bytes)
                .map_err(|_| Error::bad("Invalid account request."));
        }
    }
}

async fn respond(s: &Service, lease: &Lease, stream: UnixStream) -> Result<()> {
    let mut stream = BufReader::new(stream);
    let request = tokio::time::timeout(Duration::from_secs(2), line(&mut stream))
        .await
        .map_err(|_| Error::bad("Account request timed out."))??;
    let result = s.accounts.access_tokens(s, lease, &request).await;
    // Neither provider errors nor credentials enter events or logs.
    let response = result.unwrap_or_else(|_| json!({"error":"Account authentication is unavailable. Reconnect the account after its runs finish."}));
    let mut bytes = serde_json::to_vec(&response)?;
    bytes.push(b'\n');
    stream.get_mut().write_all(&bytes).await?;
    Ok(())
}

pub struct Client {
    path: PathBuf,
    previous: String,
    account: String,
}
impl Client {
    pub fn new(home: &Path) -> Option<Self> {
        let path = std::env::var_os("LEO_AUTH_SOCKET")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(SOCKET));
        path.exists().then_some(Self {
            path,
            previous: String::new(),
            account: String::new(),
        })
    }
    pub async fn tokens(&mut self, refresh: bool) -> Result<Value> {
        let result = tokio::time::timeout(Duration::from_secs(9), async {
            let mut stream = UnixStream::connect(&self.path).await?;
            let mut bytes =
                serde_json::to_vec(&json!({"refresh":refresh,"previous":self.previous}))?;
            bytes.push(b'\n');
            stream.write_all(&bytes).await?;
            line(&mut BufReader::new(stream)).await
        })
        .await
        .map_err(|_| Error::new(503, "Account authentication timed out."))??;
        if text(&result, "accessToken").is_empty()
            || text(&result, "chatgptAccountId").is_empty()
            || (!self.account.is_empty() && result["chatgptAccountId"] != self.account)
        {
            return Err(Error::new(503, "Account authentication is unavailable."));
        }
        self.previous = crate::auth::hex_digest(text(&result, "accessToken"));
        self.account = text(&result, "chatgptAccountId").into();
        Ok(result)
    }
    pub async fn login(&mut self, session: &mut Session) -> Result<()> {
        let mut tokens = self.tokens(false).await?;
        tokens["type"] = "chatgptAuthTokens".into();
        let result = session.request("account/login/start", tokens).await?;
        if result["type"] != "chatgptAuthTokens" {
            return Err(Error::new(
                503,
                "Update Codex to support shared account authentication.",
            ));
        }
        Ok(())
    }
    pub async fn refresh(&mut self, rpc: &crate::rpc::Rpc, incoming: &Incoming) -> Result<()> {
        let id = incoming
            .id
            .clone()
            .ok_or_else(|| Error::bad("Invalid account refresh request."))?;
        if incoming.params["previousAccountId"].is_string()
            && incoming.params["previousAccountId"] != self.account
        {
            return rpc.reject(id).await;
        }
        match self.tokens(true).await {
            Ok(tokens) => rpc.reply(id, tokens).await,
            Err(_) => rpc.reject(id).await,
        }
    }
}
