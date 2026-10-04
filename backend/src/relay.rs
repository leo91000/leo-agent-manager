//! The installation owns the outbound connection and dispatches through its real HTTP router.
use crate::{
    auth::{InstallationIdentity, InstallationRole},
    error::{Error, Result},
    skills::private_dir,
};
use axum::{
    Router,
    body::{Body, to_bytes},
    extract::ConnectInfo,
    http::{HeaderName, HeaderValue, Request},
};
use futures_util::{SinkExt, StreamExt};
use leo_relay_protocol::{
    ApiRequest, ApiResponse, Frame, MAX_BODY, MAX_FRAME, MAX_IN_FLIGHT, PROTOCOL_VERSION,
    REQUEST_TIMEOUT, Role,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    net::SocketAddr,
    os::unix::fs::PermissionsExt,
    path::{Path, PathBuf},
    time::Duration,
};
use tokio::{io::AsyncWriteExt, task::JoinSet};
use tokio_tungstenite::tungstenite::{
    Message, client::IntoClientRequest, protocol::WebSocketConfig,
};
use tokio_util::sync::CancellationToken;
use tower::ServiceExt;

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Identity {
    origin: String,
    installation_id: String,
    token: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Claimed {
    installation_id: String,
    token: String,
}

fn origin(value: &str) -> Result<url::Url> {
    let url = url::Url::parse(value).map_err(|_| Error::bad("Invalid official origin."))?;
    let loopback = url.host_str().is_some_and(|host| {
        host == "localhost"
            || host
                .parse::<std::net::IpAddr>()
                .is_ok_and(|ip| ip.is_loopback())
    });
    if !(url.scheme() == "https" || url.scheme() == "http" && loopback)
        || url.path() != "/"
        || url.query().is_some()
        || url.fragment().is_some()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err(Error::bad(
            "Use an HTTPS official origin (HTTP only on loopback).",
        ));
    }
    Ok(url)
}

/// The claim code is supplied in memory, never logged or included in a URL.
pub async fn claim(official: &str, directory: &Path, code: &str, name: &str) -> Result<()> {
    let official = origin(official)?;
    private_dir(directory).await?;
    let path = directory.join("identity.json");
    let mut file = tokio::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&path)
        .await
        .map_err(|_| Error::conflict("Installation identity exists or is not writable."))?;

    let result = async {
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(15))
            .build()
            .map_err(Error::internal)?;
        let response = client
            .post(official.join("api/relay/claim").map_err(Error::internal)?)
            .json(&serde_json::json!({
                "code": code,
                "name": name,
                "protocol": PROTOCOL_VERSION,
            }))
            .send()
            .await
            .map_err(|_| Error::unavailable("Cannot reach the official service."))?;
        if !response.status().is_success() {
            return Err(Error::bad(
                "Installation claim refused; obtain a new claim code.",
            ));
        }

        let claimed: Claimed = response
            .json()
            .await
            .map_err(|_| Error::bad("Invalid claim response."))?;
        uuid::Uuid::parse_str(&claimed.installation_id)
            .map_err(|_| Error::bad("Invalid installation identity."))?;
        let identity = Identity {
            origin: official.origin().ascii_serialization(),
            installation_id: claimed.installation_id,
            token: claimed.token,
        };
        file.write_all(&serde_json::to_vec(&identity)?).await?;
        file.sync_all().await?;
        Ok(())
    }
    .await;

    if result.is_err() {
        let _ = tokio::fs::remove_file(path).await;
    }

    result
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DeviceStarted {
    device_code: String,
    user_code: String,
}

async fn read_identity(directory: &Path) -> Result<Option<Identity>> {
    let path = directory.join("identity.json");
    let metadata = match tokio::fs::symlink_metadata(&path).await {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    if !metadata.is_file() || metadata.permissions().mode() & 0o077 != 0 {
        return Err(Error::bad(
            "Installation identity must be a private regular file.",
        ));
    }
    let identity = serde_json::from_str(&crate::skills::small_file(&path).await?)?;
    Ok(Some(identity))
}

/// Approve through the official app before atomically replacing a private identity.
/// `display` receives only the browser URL and temporary human code, never a token.
pub async fn device_claim(
    official: Option<&str>,
    directory: &Path,
    name: &str,
    stop: CancellationToken,
    display: impl FnOnce(&str, &str),
) -> Result<()> {
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::OpenOptionsExt;

    private_dir(directory).await?;
    let lock = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(directory.join("claim.lock"))?;
    if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return Err(Error::conflict("Another leo claim is already running."));
    }
    let previous = read_identity(directory).await?;
    let official = origin(
        official
            .or_else(|| previous.as_ref().map(|identity| identity.origin.as_str()))
            .ok_or_else(|| Error::bad("Set LEO_OFFICIAL_ORIGIN to claim this installation."))?,
    )?;
    if previous
        .as_ref()
        .is_some_and(|identity| identity.origin != official.origin().ascii_serialization())
    {
        return Err(Error::bad(
            "Official origin differs from the private identity. Detach and back up that identity before changing origins.",
        ));
    }
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(Error::internal)?;
    let start = client
        .post(
            official
                .join("api/relay/device-claim/start")
                .map_err(Error::internal)?,
        )
        .json(&serde_json::json!({
            "name": name,
            "protocol": PROTOCOL_VERSION,
            "identity": previous,
        }))
        .send()
        .await
        .map_err(|_| Error::unavailable("Cannot reach the official service."))?;
    if start.status() == reqwest::StatusCode::CONFLICT {
        return Err(Error::conflict(
            "Detach the installation in the official app before running leo claim.",
        ));
    }
    if !start.status().is_success() {
        return Err(Error::bad(
            "Installation claim refused. Check the official origin and private identity.",
        ));
    }
    let started: DeviceStarted = start
        .json()
        .await
        .map_err(|_| Error::bad("Invalid device claim response."))?;
    let browser = official.join("claim").map_err(Error::internal)?;
    if started.user_code.len() != 14
        || !started
            .user_code
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() || byte == b'-')
    {
        return Err(Error::bad("Invalid device claim code."));
    }
    display(browser.as_str(), &started.user_code);
    let polling = async {
        loop {
            let response = client
                .post(
                    official
                        .join("api/relay/device-claim/poll")
                        .map_err(Error::internal)?,
                )
                .json(&serde_json::json!({ "deviceCode": started.device_code }))
                .send()
                .await
                .map_err(|_| {
                    Error::unavailable("Cannot reach the official service; run leo claim again.")
                })?;
            if response.status() == reqwest::StatusCode::ACCEPTED {
                tokio::time::sleep(Duration::from_secs(2)).await;
                continue;
            }
            if response.status() != reqwest::StatusCode::OK {
                return Err(Error::bad(
                    "Device claim expired or refused; run leo claim again.",
                ));
            }
            let claimed: Claimed = response
                .json()
                .await
                .map_err(|_| Error::bad("Invalid claim response."))?;
            return Ok(claimed);
        }
    };
    let claimed = tokio::select! {
        () = stop.cancelled() => Err(Error::bad("Claim cancelled; run leo claim again.")),
        result = tokio::time::timeout(Duration::from_secs(600), polling) => {
            result.map_err(|_| Error::bad("Device claim expired; run leo claim again."))?
        }
    }?;
    uuid::Uuid::parse_str(&claimed.installation_id)
        .map_err(|_| Error::bad("Invalid installation identity."))?;
    if claimed.token.len() != 64 || !claimed.token.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(Error::bad("Invalid installation credential."));
    }
    let identity = Identity {
        origin: official.origin().ascii_serialization(),
        installation_id: claimed.installation_id,
        token: claimed.token,
    };
    crate::skills::atomic_write(
        &directory.join("identity.json"),
        &serde_json::to_vec(&identity)?,
    )
    .await?;
    Ok(())
}

/// Reconnect until shutdown; failed in-flight writes are never automatically replayed.
pub async fn connect(directory: PathBuf, router: Router, stop: CancellationToken) -> Result<()> {
    let identity = read_identity(&directory)
        .await?
        .ok_or_else(|| Error::bad("Run leo claim before starting the relay."))?;
    let official = origin(&identity.origin)?;
    let mut delay = Duration::from_millis(250);
    loop {
        let started = tokio::time::Instant::now();
        tokio::select! {
            () = stop.cancelled() => return Ok(()),
            result = connected(&identity, &official, router.clone()) => {
                if let Err(error) = result {
                    if error.status == 401 {
                        tracing::warn!("Installation identity revoked; run leo claim, then restart the manager");
                        return Ok(());
                    }
                    tracing::warn!("Installation relay disconnected; retrying");
                }
            }
        }
        if started.elapsed() > Duration::from_secs(30) {
            delay = Duration::from_millis(250);
        }
        tokio::select! {
            () = stop.cancelled() => return Ok(()),
            () = tokio::time::sleep(delay) => {}
        }
        delay = (delay * 2).min(Duration::from_secs(15));
    }
}

async fn connected(identity: &Identity, official: &url::Url, router: Router) -> Result<()> {
    let mut url = official
        .join(&format!("api/relay/{}/connect", identity.installation_id))
        .map_err(Error::internal)?;
    let scheme = if official.scheme() == "https" {
        "wss"
    } else {
        "ws"
    };
    url.set_scheme(scheme)
        .map_err(|()| Error::bad("Invalid relay origin."))?;
    let mut request = url
        .as_str()
        .into_client_request()
        .map_err(Error::internal)?;
    request.headers_mut().insert(
        "authorization",
        HeaderValue::from_str(&format!("Bearer {}", identity.token))
            .map_err(|_| Error::bad("Invalid installation identity."))?,
    );
    let config = WebSocketConfig::default()
        .max_message_size(Some(MAX_FRAME))
        .max_frame_size(Some(MAX_FRAME));
    let (mut socket, _) = tokio::time::timeout(
        Duration::from_secs(15),
        tokio_tungstenite::connect_async_with_config(request, Some(config), false),
    )
    .await
    .map_err(|_| Error::unavailable("Relay connection timed out."))?
    .map_err(|error| {
        if matches!(&error, tokio_tungstenite::tungstenite::Error::Http(response) if response.status().as_u16() == 401) {
            Error::unauthorized("Installation identity revoked.")
        } else {
            Error::unavailable("Relay connection refused.")
        }
    })?;

    socket
        .send(Message::Text(
            serde_json::to_string(&Frame::Hello {
                versions: vec![PROTOCOL_VERSION],
            })?
            .into(),
        ))
        .await
        .map_err(Error::internal)?;

    let welcome = tokio::time::timeout(Duration::from_secs(5), socket.next())
        .await
        .map_err(|_| Error::unavailable("Relay negotiation timed out."))?;
    let Some(Ok(Message::Text(welcome))) = welcome else {
        return Err(Error::unavailable("Relay negotiation failed."));
    };
    if !matches!(
        serde_json::from_str::<Frame>(&welcome)?,
        Frame::Welcome {
            version: PROTOCOL_VERSION
        }
    ) {
        return Err(Error::bad("Incompatible relay protocol."));
    }

    let mut requests = JoinSet::new();
    let mut request_ids = HashMap::new();
    loop {
        tokio::select! {
            result = requests.join_next_with_id(), if !requests.is_empty() => {
                let completed = result
                    .ok_or_else(|| Error::unavailable("Relay request stopped."))?;
                let (task_id, result) = match completed {
                    Ok((task_id, result)) => (task_id, result),
                    Err(error) => (
                        error.id(),
                        Err(Error::bad_gateway("Installation handler failed.")),
                    ),
                };
                let request_id = request_ids
                    .remove(&task_id)
                    .ok_or_else(|| Error::bad_gateway("Unknown relay request."))?;
                let response = match result {
                    Ok(response) => response,
                    Err(error) => request_failure(request_id, error.status, &error.message),
                };

                let frame = serde_json::to_string(&Frame::Response(response))?;
                socket
                    .send(Message::Text(frame.into()))
                    .await
                    .map_err(Error::internal)?;
            }
            message = tokio::time::timeout(Duration::from_secs(45), socket.next()) => {
                let message = message.map_err(|_| Error::unavailable("Relay heartbeat lost."))?;
                match message {
                    Some(Ok(Message::Text(message))) => {
                        let Frame::Request(request) = serde_json::from_str::<Frame>(&message)? else {
                            return Err(Error::bad("Unexpected relay frame."));
                        };
                        if requests.len() >= MAX_IN_FLIGHT {
                            let response = request_failure(request.id, 503, "Installation busy.");
                            let frame = serde_json::to_string(&Frame::Response(response))?;
                            socket
                                .send(Message::Text(frame.into()))
                                .await
                                .map_err(Error::internal)?;
                            continue;
                        }

                        let request_id = request.id.clone();
                        let router = router.clone();
                        let task = requests.spawn(async move {
                            tokio::time::timeout(REQUEST_TIMEOUT, dispatch(router, request))
                                .await
                                .unwrap_or_else(|_| {
                                    Err(Error::gateway_timeout("Installation request timed out."))
                                })
                        });
                        request_ids.insert(task.id(), request_id);
                    }
                    Some(Ok(Message::Ping(bytes))) => {
                        socket.send(Message::Pong(bytes)).await.map_err(Error::internal)?;
                    }
                    Some(Ok(Message::Pong(_))) => {}
                    _ => return Err(Error::unavailable("Relay connection closed.")),
                }
            }
        }
    }
}

fn request_failure(id: String, status: u16, message: &str) -> ApiResponse {
    ApiResponse {
        id,
        status,
        headers: vec![("content-type".into(), "application/json".into())],
        body: serde_json::to_vec(&serde_json::json!({ "error": message })).unwrap(),
    }
}

async fn dispatch(router: Router, input: ApiRequest) -> Result<ApiResponse> {
    if !leo_relay_protocol::api_path(&input.path) || input.body.len() > MAX_BODY {
        return Err(Error::bad("Invalid relayed API request."));
    }
    let role = match input.role {
        Role::Owner => InstallationRole::Owner,
        Role::Member => InstallationRole::Member,
    };
    let mut request = Request::builder()
        .method(input.method.as_str())
        .uri(&input.path)
        .header("host", "localhost")
        .body(Body::from(input.body))
        .map_err(|_| Error::bad_gateway("Invalid installation request."))?;
    for (name, value) in input.headers {
        if leo_relay_protocol::request_header(&name)
            && let (Ok(name), Ok(value)) =
                (HeaderName::try_from(name), HeaderValue::try_from(value))
        {
            request.headers_mut().append(name, value);
        }
    }

    request
        .extensions_mut()
        .insert(ConnectInfo("127.0.0.1:0".parse::<SocketAddr>().unwrap()));
    request
        .extensions_mut()
        .insert(InstallationIdentity::trusted(role, &input.account_id));

    let response = router
        .oneshot(request)
        .await
        .map_err(|_| Error::bad_gateway("Installation handler failed."))?;
    let status = response.status().as_u16();
    let headers = response
        .headers()
        .iter()
        .filter(|(name, _)| leo_relay_protocol::response_header(name.as_str()))
        .filter_map(|(name, value)| {
            value
                .to_str()
                .ok()
                .map(|value| (name.to_string(), value.to_owned()))
        })
        .collect();
    let body = to_bytes(response.into_body(), MAX_BODY)
        .await
        .map_err(|error| {
            let oversized = std::error::Error::source(&error)
                .is_some_and(<dyn std::error::Error>::is::<http_body_util::LengthLimitError>);
            if oversized {
                Error::too_large("Installation response is too large.")
            } else {
                Error::bad_gateway("Installation response failed.")
            }
        })?
        .to_vec();

    Ok(ApiResponse {
        id: input.id,
        status,
        headers,
        body,
    })
}
