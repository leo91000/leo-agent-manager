//! A run's MCP channel: run-scoped MCP served during an attempt on a private
//! socket in the run home (`microvm::mcp::SOCKET`). The VM controller relays
//! its guest's loopback origin there. The manager serves it for its local
//! runner; a remote node's connector forwards it over the node's session.
use crate::{
    error::{Error, Result},
    nodes::executor::Session,
    service::Service,
    skills::private_dir,
};
use axum::{
    Router,
    body::{Body, to_bytes},
    extract::{Request, State},
    response::{IntoResponse, Response},
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    collections::BTreeMap,
    os::unix::fs::PermissionsExt,
    path::{Path, PathBuf},
    sync::Arc,
};
use tokio::net::UnixListener;
use tokio_util::sync::CancellationToken;

/// Request headers an MCP client needs; the run token travels in `authorization`.
const REQUEST_HEADERS: [&str; 6] = [
    "authorization",
    "content-type",
    "accept",
    "mcp-protocol-version",
    "mcp-method",
    "mcp-session-id",
];

const RESPONSE_HEADERS: [&str; 3] = ["content-type", "mcp-protocol-version", "cache-control"];
/// Above the manager's own MCP body limit, which still applies once forwarded.
const MAX_REQUEST_BYTES: usize = 1_000_000;

/// One MCP request forwarded by a remote node to the manager.
#[derive(Serialize, Deserialize)]
struct Forwarded {
    method: String,
    path: String,
    headers: BTreeMap<String, String>,
    body: String,
}

/// Serves the run's channel until dropped.
pub struct Channel {
    stop: CancellationToken,
    path: PathBuf,
}

impl Drop for Channel {
    fn drop(&mut self) {
        self.stop.cancel();
        let _ = std::fs::remove_file(&self.path);
    }
}

async fn bind(path: &Path) -> Result<UnixListener> {
    if let Some(home) = path.parent() {
        private_dir(home).await?;
    }
    match tokio::fs::remove_file(path).await {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }

    let listener = UnixListener::bind(path)?;
    tokio::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600)).await?;
    Ok(listener)
}

/// The manager side: answers run-scoped MCP for `run` in `home`.
pub async fn serve(s: &Arc<Service>, home: &Path, run: &str) -> Result<Channel> {
    let path = home.join(crate::microvm::mcp::SOCKET);
    let listener = bind(&path).await?;

    let stop = CancellationToken::new();
    let app = Router::new()
        .fallback(answer)
        .with_state((s.clone(), run.to_owned()));
    let stopping = stop.clone();
    let shutdown = s.shutdown.clone();
    tokio::spawn(async move {
        let stopped = async move {
            tokio::select! {
                () = stopping.cancelled() => {},
                () = shutdown.cancelled() => {},
            }
        };
        if let Err(error) = axum::serve(listener, app)
            .with_graceful_shutdown(stopped)
            .await
        {
            tracing::warn!(%error, "Run MCP channel stopped");
        }
    });
    Ok(Channel { stop, path })
}

async fn answer(State((s, run)): State<(Arc<Service>, String)>, request: Request) -> Response {
    crate::mcp_server::run_scoped(&s, &run, request)
        .await
        .into_response()
}

/// The manager side of a remote node's channel. The caller has checked that the
/// node owns the attempt executing `run`.
pub(crate) async fn relayed(s: &Arc<Service>, run: &str, forwarded: &Value) -> Result<Response> {
    let invalid = || Error::bad("Invalid MCP request.");
    let forwarded = Forwarded::deserialize(forwarded).map_err(|_| invalid())?;

    let mut request = Request::builder()
        .method(forwarded.method.as_str())
        .uri(forwarded.path.as_str());
    for (name, value) in &forwarded.headers {
        if REQUEST_HEADERS.contains(&name.as_str()) {
            request = request.header(name, value);
        }
    }
    let request = request
        .body(Body::from(forwarded.body))
        .map_err(|_| invalid())?;

    crate::mcp_server::run_scoped(s, run, request).await
}

/// The node side: forwards the attempt's MCP requests to the manager over the
/// node's authenticated session until `stop`. No other network path is opened.
pub async fn forward(path: PathBuf, session: Session, stop: CancellationToken) -> Result<()> {
    let listener = bind(&path).await?;
    let app = Router::new().fallback(send).with_state(Arc::new(session));

    let result = axum::serve(listener, app)
        .with_graceful_shutdown(stop.cancelled_owned())
        .await;
    let _ = tokio::fs::remove_file(path).await;
    result.map_err(Error::from)
}

async fn send(State(session): State<Arc<Session>>, request: Request) -> Response {
    send_request(&session, request).await.into_response()
}

async fn send_request(session: &Session, request: Request) -> Result<Response> {
    let (parts, body) = request.into_parts();
    let body = to_bytes(body, MAX_REQUEST_BYTES)
        .await
        .map_err(|_| Error::too_large("Request body is too large."))?;
    let headers = REQUEST_HEADERS
        .iter()
        .filter_map(|name| {
            let value = parts.headers.get(*name)?.to_str().ok()?;
            Some(((*name).to_owned(), value.to_owned()))
        })
        .collect();
    let forwarded = Forwarded {
        method: parts.method.as_str().to_owned(),
        path: parts.uri.path().to_owned(),
        headers,
        body: String::from_utf8(body.to_vec()).map_err(|_| Error::bad("Invalid MCP request."))?,
    };

    let url = session
        .master
        .join(&format!("internal/node-workspace/{}/mcp", session.attempt))
        .map_err(Error::internal)?;
    let response = session
        .client
        .post(url)
        .bearer_auth(&session.token)
        .json(&forwarded)
        .send()
        .await
        .map_err(|_| Error::unavailable("Workspace connection interrupted."))?;

    // The manager's answer is the MCP response, rejections included. Run-scoped
    // MCP is stateless and answers with complete JSON: no stream or session.
    let mut answer = Response::builder().status(response.status().as_u16());
    for name in RESPONSE_HEADERS {
        if let Some(value) = response.headers().get(name) {
            answer = answer.header(name, value.as_bytes());
        }
    }
    let bytes = response
        .bytes()
        .await
        .map_err(|_| Error::unavailable("Workspace connection interrupted."))?;
    answer.body(Body::from(bytes)).map_err(Error::internal)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{Json, routing::post};
    use serde_json::json;
    use std::{
        net::SocketAddr,
        sync::atomic::{AtomicUsize, Ordering},
        time::Duration,
    };
    use tokio::sync::{Notify, mpsc};

    /// A manager whose first forwarded MCP call stays in flight until released,
    /// as a long tool call does.
    struct Master {
        calls: AtomicUsize,
        received: mpsc::UnboundedSender<()>,
        release: Notify,
    }

    async fn answer_forwarded(State(master): State<Arc<Master>>) -> Json<Value> {
        let first = master.calls.fetch_add(1, Ordering::SeqCst) == 0;
        let _ = master.received.send(());
        if first {
            master.release.notified().await;
        }
        Json(json!({ "jsonrpc": "2.0", "id": 1, "result": {} }))
    }

    async fn master() -> (SocketAddr, Arc<Master>, mpsc::UnboundedReceiver<()>) {
        let (received, calls) = mpsc::unbounded_channel();
        let master = Arc::new(Master {
            calls: AtomicUsize::new(0),
            received,
            release: Notify::new(),
        });
        let app = Router::new()
            .route(
                "/internal/node-workspace/{attempt}/mcp",
                post(answer_forwarded),
            )
            .with_state(master.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await });
        (address, master, calls)
    }

    fn session(master: SocketAddr) -> Session {
        Session {
            client: reqwest::Client::new(),
            master: format!("http://{master}").parse().unwrap(),
            token: "node-token".into(),
            attempt: "attempt".into(),
        }
    }

    /// An agent's MCP call through the run socket, on a fresh connection.
    async fn call(socket: &Path) -> reqwest::Result<reqwest::Response> {
        let client = reqwest::Client::builder()
            .unix_socket(socket)
            .timeout(Duration::from_secs(10))
            .build()?;
        let body = json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/list" });
        client
            .post("http://127.0.0.1:5202/mcp-workspace")
            .json(&body)
            .send()
            .await
    }

    /// The first call of a channel being bound: the master holds it in flight.
    async fn in_flight(socket: PathBuf) -> reqwest::Result<reqwest::Response> {
        for _ in 0..100 {
            if tokio::net::UnixStream::connect(&socket).await.is_ok() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        call(&socket).await
    }

    async fn answered(socket: &Path) {
        for _ in 0..100 {
            if let Ok(response) = call(socket).await
                && response.status().is_success()
            {
                return;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        panic!("{} never answered", socket.display());
    }

    #[tokio::test]
    async fn stopping_a_channel_ends_its_open_connections() {
        let (address, _master, mut received) = master().await;
        let directory = tempfile::tempdir().unwrap();
        let socket = directory
            .path()
            .join("home")
            .join(crate::microvm::mcp::SOCKET);
        let stop = CancellationToken::new();
        let channel = tokio::spawn(forward(socket.clone(), session(address), stop.clone()));
        let pending = tokio::spawn(in_flight(socket.clone()));
        received.recv().await.unwrap();

        stop.cancel();
        let ended = tokio::time::timeout(Duration::from_secs(2), channel).await;

        assert!(ended.is_ok(), "an open connection kept the stopped channel");
        ended.unwrap().unwrap().unwrap();
        let interrupted = tokio::time::timeout(Duration::from_secs(2), pending)
            .await
            .unwrap()
            .unwrap();
        assert!(interrupted.is_err(), "{interrupted:?}");
        assert!(!socket.exists());
    }

    #[tokio::test]
    async fn a_stopped_attempt_never_removes_the_socket_of_its_resume() {
        let (address, master, mut received) = master().await;
        let directory = tempfile::tempdir().unwrap();
        let socket = directory
            .path()
            .join("home")
            .join(crate::microvm::mcp::SOCKET);
        let first_stop = CancellationToken::new();
        let first = tokio::spawn(forward(
            socket.clone(),
            session(address),
            first_stop.clone(),
        ));
        let pending = tokio::spawn(in_flight(socket.clone()));
        received.recv().await.unwrap();

        // The attempt is cancelled with that call in flight, and resumed in
        // the same run home.
        first_stop.cancel();
        let resume_stop = CancellationToken::new();
        let resume = tokio::spawn(forward(
            socket.clone(),
            session(address),
            resume_stop.clone(),
        ));
        answered(&socket).await;
        master.release.notify_one();
        let _ = pending.await;
        tokio::time::timeout(Duration::from_secs(5), first)
            .await
            .unwrap()
            .unwrap()
            .unwrap();

        assert!(
            socket.exists(),
            "the stopped attempt removed its resume's socket"
        );
        let response = call(&socket).await.unwrap();
        assert!(response.status().is_success(), "{}", response.status());
        resume_stop.cancel();
        resume.await.unwrap().unwrap();
        assert!(!socket.exists());
    }
}
