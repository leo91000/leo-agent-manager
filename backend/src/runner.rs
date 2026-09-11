use crate::{
    auth::safe_equal,
    config::now,
    error::{Error, Result},
    skills::{atomic_write, private_dir},
    validation::{text, uuid},
};
use axum::{
    Json, Router,
    body::Body,
    extract::{Request, State},
    response::{IntoResponse, Response},
    routing::any,
};
use bytes::{Buf, Bytes, BytesMut};
use futures_util::{Stream, StreamExt};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;
#[derive(Clone)]
struct Broker {
    data: PathBuf,
    state: PathBuf,
    manager: String,
    http: reqwest::Client,
    locks: Arc<Mutex<HashMap<String, Arc<Mutex<()>>>>>,
    stop: CancellationToken,
}
fn normalized(path: &Path) -> bool {
    path.is_absolute()
        && path.components().collect::<PathBuf>().as_os_str() == path.as_os_str()
        && !path.components().any(|c| {
            matches!(
                c,
                std::path::Component::ParentDir | std::path::Component::CurDir
            )
        })
        && !path.to_string_lossy().contains(':')
        && !path.to_string_lossy().contains("//")
}
pub fn translate_mount(source: &str, mounts: &Value) -> Result<PathBuf> {
    let source = Path::new(source);
    if !normalized(source) {
        return Err(Error::bad("Runner mount is outside the manager volumes."));
    }
    let mount = mounts
        .as_array()
        .into_iter()
        .flatten()
        .filter(|m| source.starts_with(text(m, "Destination")))
        .max_by_key(|m| text(m, "Destination").len())
        .ok_or_else(|| Error::bad("Runner mount is outside the manager volumes."))?;
    Ok(Path::new(text(mount, "Source")).join(
        source
            .strip_prefix(text(mount, "Destination"))
            .map_err(|_| Error::bad("Invalid runner mount."))?,
    ))
}
impl Broker {
    async fn request(
        &self,
        method: reqwest::Method,
        endpoint: &str,
        body: Option<Value>,
    ) -> Result<reqwest::Response> {
        let mut request = self
            .http
            .request(method, format!("http://localhost{endpoint}"));
        if !endpoint.ends_with("/wait") && !endpoint.contains("/logs?") {
            request = request.timeout(Duration::from_secs(30));
        }
        if let Some(body) = body {
            request = request.json(&body);
        }
        let response = request
            .send()
            .await
            .map_err(|_| Error::new(503, "Container engine request failed."))?;
        if !response.status().is_success() {
            return Err(Error::new(
                response.status().as_u16(),
                format!(
                    "Runner container operation failed ({}).",
                    response.status().as_u16()
                ),
            ));
        }
        Ok(response)
    }
    async fn json(
        &self,
        method: reqwest::Method,
        endpoint: &str,
        body: Option<Value>,
    ) -> Result<Value> {
        let response = self.request(method, endpoint, body).await?;
        let bytes = response
            .bytes()
            .await
            .map_err(|_| Error::new(502, "Container engine response failed."))?;
        if bytes.is_empty() {
            return Ok(json!({}));
        }
        serde_json::from_slice(&bytes)
            .map_err(|_| Error::new(502, "Container engine returned invalid JSON."))
    }
    async fn lock(&self, id: &str) -> tokio::sync::OwnedMutexGuard<()> {
        let lock = {
            let mut locks = self.locks.lock().await;
            locks.retain(|_, lock| Arc::strong_count(lock) > 1);
            locks.entry(id.into()).or_default().clone()
        };
        lock.lock_owned().await
    }
    async fn start(&self, id: &str) -> Result<()> {
        let _guard = self.lock(id).await;
        if tokio::fs::try_exists(self.state.join(format!("{id}.stopped"))).await? {
            return Err(Error::new(
                409,
                "This execution attempt has already stopped.",
            ));
        }
        let file = self.data.join("runner-plans").join(format!("{id}.json"));
        let bytes = tokio::fs::read(&file).await?;
        if bytes.len() > 8_000_000 {
            return Err(Error::bad("Run plan is too large."));
        }
        let plan: Value = serde_json::from_slice(&bytes)?;
        if plan["id"] != id
            || plan["expires"]
                .as_i64()
                .is_none_or(|expires| expires <= now() || expires > now() + 13 * 3600000)
        {
            return Err(Error::bad("Run plan is invalid or expired."));
        }
        let host = self
            .json(
                reqwest::Method::GET,
                &format!("/containers/{}/json", self.manager),
                None,
            )
            .await?;
        let mounts = plan["mounts"]
            .as_array()
            .ok_or_else(|| Error::bad("Invalid runner mounts."))?;
        let mut binds = Vec::new();
        for mount in mounts {
            let target = Path::new(text(mount, "target"));
            if !normalized(target) {
                return Err(Error::bad("Invalid runner mount target."));
            }
            binds.push(format!(
                "{}:{}:{}",
                translate_mount(text(mount, "source"), &host["Mounts"])?.display(),
                target.display(),
                if mount["readOnly"] == true {
                    "ro"
                } else {
                    "rw"
                }
            ));
        }
        binds.push(format!(
            "{}:/run/leo-plan.json:ro",
            translate_mount(
                file.to_str()
                    .ok_or_else(|| Error::bad("Invalid runner path."))?,
                &host["Mounts"]
            )?
            .display()
        ));
        let mut security = vec!["no-new-privileges:true".to_owned()];
        if plan["sandbox"] != "yolo" {
            let seccomp: Value =
                serde_json::from_str(include_str!("../schemas/runner-seccomp.json"))?;
            security.push(format!("seccomp={seccomp}"));
            if let Ok(profile) = std::env::var("RUNNER_APPARMOR_PROFILE") {
                security.push(format!("apparmor={profile}"));
            }
        }
        self.json(
            reqwest::Method::POST,
            &format!("/containers/create?name=leo-run-{id}"),
            Some(json!({
            "Image":host["Image"],"User":"1000:1000","Entrypoint":["/usr/local/bin/leo","runner-entry"],"Cmd":[],"Env":["HOME=/home/node","CODEX_HOME=/home/node/.codex","NODE_ENV=production","PATH=/pnpm/bin:/pnpm:/usr/local/bin:/usr/bin:/bin"],"WorkingDir":"/app","Labels":{
            "leo.agent-run":"true","leo.expires":plan["expires"].to_string()}
            ,"Healthcheck":{
            "Test":["NONE"]}
            ,"HostConfig":{
            "Binds":binds,"ReadonlyRootfs":true,"CapDrop":["ALL"],"SecurityOpt":security,"Init":true,"PidsLimit":512,"Memory":4294967296_i64,"NanoCpus":2000000000,"NetworkMode":"bridge","Tmpfs":{
            "/tmp":"rw,nosuid,nodev,size=1g,mode=1777","/run":"rw,nosuid,nodev,size=16m"}
            ,"LogConfig":{
            "Type":"json-file","Config":{
            "max-size":"10m","max-file":"2"}
            }
            }
            }
            )),
        )
        .await?;
        // A concurrent stop writes its marker before it waits for this lock.
        if tokio::fs::try_exists(self.state.join(format!("{id}.stopped"))).await? {
            return self.remove(id).await;
        }
        self.json(
            reqwest::Method::POST,
            &format!("/containers/leo-run-{id}/start"),
            None,
        )
        .await?;
        Ok(())
    }
    async fn remove(&self, id: &str) -> Result<()> {
        match self
            .json(
                reqwest::Method::DELETE,
                &format!("/containers/leo-run-{id}?force=true&v=true"),
                None,
            )
            .await
        {
            Ok(_) => Ok(()),
            Err(error) if error.status == 404 => Ok(()),
            Err(error) => Err(error),
        }
    }
    async fn stop(&self, id: &str) -> Result<()> {
        private_dir(&self.state).await?;
        atomic_write(&self.state.join(format!("{id}.stopped")), b"").await?;
        let _guard = self.lock(id).await;
        self.remove(id).await
    }
}
async fn handler(State(broker): State<Broker>, request: Request) -> Result<Response> {
    if request.uri().path() == "/health" {
        return Ok("ok".into_response());
    }
    let authorization = request
        .headers()
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let credential = tokio::fs::read_to_string(broker.data.join("runner-secret")).await?;
    if !safe_equal(authorization, &format!("Bearer {}", credential.trim())) {
        return Err(Error::new(401, "Invalid runner credential."));
    }
    let segments = request
        .uri()
        .path()
        .trim_start_matches('/')
        .split('/')
        .collect::<Vec<_>>();
    let Some(id) = segments
        .get(1)
        .filter(|_| segments.first() == Some(&"runs"))
    else {
        return Err(Error::new(404, "Not found"));
    };
    uuid(id)?;
    match (request.method().as_str(), segments.as_slice()) {
        ("POST", ["runs", id]) => {
            broker.start(id).await?;
            Ok(Json(json!({})).into_response())
        }
        ("DELETE", ["runs", id]) => {
            broker.stop(id).await?;
            Ok(Json(json!({})).into_response())
        }
        ("GET", ["runs", id, "logs"]) => {
            let response = broker
                .request(
                    reqwest::Method::GET,
                    &format!("/containers/leo-run-{id}/logs?follow=1&stdout=1&stderr=1"),
                    None,
                )
                .await?;
            let stream = heartbeat(
                docker_frames(
                    response
                        .bytes_stream()
                        .map(|r| r.map_err(|_| std::io::Error::other("Container stream failed"))),
                ),
                Bytes::from_static(&[0; 8]),
                broker.stop.clone(),
            );
            Ok((
                [("content-type", "application/octet-stream")],
                Body::from_stream(stream),
            )
                .into_response())
        }
        ("POST", ["runs", id, "wait"]) => {
            let id = (*id).to_owned();
            let stop = broker.stop.clone();
            let stream = futures_util::stream::once(async move {
                broker
                    .json(
                        reqwest::Method::POST,
                        &format!("/containers/leo-run-{id}/wait"),
                        None,
                    )
                    .await
                    .map(|v| Bytes::from(v.to_string()))
                    .map_err(|_| std::io::Error::other("Container wait failed"))
            });
            Ok((
                [("content-type", "application/json")],
                Body::from_stream(heartbeat(stream, Bytes::from_static(b" "), stop)),
            )
                .into_response())
        }
        _ => Err(Error::new(405, "Method not allowed.")),
    }
}
// Docker chunks may end inside a header or payload. Only yield complete frames:
// injecting a keepalive into a partial frame would corrupt the agent's output.
fn docker_frames(
    stream: impl Stream<Item = std::io::Result<Bytes>> + Send + 'static,
) -> impl Stream<Item = std::io::Result<Bytes>> + Send {
    futures_util::stream::try_unfold(
        (Box::pin(stream), BytesMut::new()),
        |(mut stream, mut buffer)| async move {
            loop {
                if buffer.len() >= 8 {
                    let length = u32::from_be_bytes(buffer[4..8].try_into().unwrap()) as usize;
                    if length > 16 * 1024 * 1024 || buffer[0] > 2 || buffer[1..4] != [0; 3] {
                        return Err(std::io::Error::other("Invalid container frame"));
                    }
                    if buffer.len() >= length + 8 {
                        return Ok(Some((
                            buffer.split_to(length + 8).freeze(),
                            (stream, buffer),
                        )));
                    }
                }
                match stream.next().await {
                    Some(bytes) => buffer.extend_from_slice(&bytes?),
                    None if buffer.is_empty() => return Ok(None),
                    None => return Err(std::io::Error::other("Truncated container frame")),
                }
            }
        },
    )
}
fn heartbeat(
    stream: impl Stream<Item = std::result::Result<Bytes, std::io::Error>> + Send + 'static,
    heartbeat: Bytes,
    stop: CancellationToken,
) -> impl Stream<Item = std::result::Result<Bytes, std::io::Error>> + Send {
    let mut interval = tokio::time::interval(Duration::from_secs(15));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    futures_util::stream::unfold(
        (Box::pin(stream), interval, heartbeat, stop),
        |(mut stream, mut interval, heartbeat, stop)| async move {
            let next = tokio::select! {
            _=stop.cancelled()=>None,value=stream.next()=>value,_=interval.tick()=>Some(Ok(heartbeat.clone()))};
            next.map(|next| (next, (stream, interval, heartbeat, stop)))
        },
    )
}

pub async fn serve(stop: CancellationToken) -> Result<()> {
    let data = PathBuf::from(std::env::var("DATA_DIR").unwrap_or_else(|_| "/data".into()));
    let broker = Broker {
        data,
        state: PathBuf::from(
            std::env::var("RUNNER_STATE_DIR").unwrap_or_else(|_| "/runner-state".into()),
        ),
        manager: std::env::var("RUNNER_MANAGER_CONTAINER").unwrap_or_else(|_| "leo-manager".into()),
        http: reqwest::Client::builder()
            .unix_socket("/var/run/docker.sock")
            .no_proxy()
            .build()
            .map_err(Error::internal)?,
        locks: Default::default(),
        stop: stop.clone(),
    };
    let cleanup = broker.clone();
    tokio::spawn(async move {
        let mut timer = tokio::time::interval(Duration::from_secs(30));
        loop {
            tokio::select! {
            _=cleanup.stop.cancelled()=>break,_=timer.tick()=>{
            let filters=url::form_urlencoded::byte_serialize(br#"{"label":["leo.agent-run=true"]}"#).collect::<String>();
            if let Ok(containers)=cleanup.json(reqwest::Method::GET,&format!("/containers/json?all=1&filters={filters}"),None).await{
            for container in containers.as_array().into_iter().flatten(){
            if text(&container["Labels"],"leo.expires").parse::<i64>().is_ok_and(|expires|expires+30000<now()){
            let _=cleanup.json(reqwest::Method::DELETE,&format!("/containers/{}?force=true&v=true",text(container,"Id")),None).await;
            }
            }
            }
            }
            }
        }
    });
    let listener = tokio::net::TcpListener::bind("0.0.0.0:4311").await?;
    let router = Router::new().fallback(any(handler)).with_state(broker);
    axum::serve(listener, router)
        .with_graceful_shutdown(stop.cancelled_owned())
        .await?;
    Ok(())
}
pub async fn client(id: &str, stop: CancellationToken) -> Result<i32> {
    uuid(id)?;
    let base = std::env::var("RUNNER_URL").map_err(|_| Error::bad("Missing runner URL."))?;
    let token =
        std::env::var("RUNNER_TOKEN").map_err(|_| Error::bad("Missing runner credential."))?;
    let url = format!("{base}/runs/{id}");
    let http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(Error::internal)?;
    let operation = async {
        let response = http
            .post(&url)
            .bearer_auth(&token)
            .timeout(Duration::from_secs(30))
            .send()
            .await
            .map_err(|_| Error::new(503, "Isolated runner could not start."))?;
        if !response.status().is_success() {
            return Err(Error::new(503, "Isolated runner could not start."));
        }
        let logs = http
            .get(format!("{url}/logs"))
            .bearer_auth(&token)
            .send()
            .await
            .map_err(|_| Error::new(503, "Could not read isolated runner output."))?;
        if !logs.status().is_success() {
            return Err(Error::new(503, "Could not read isolated runner output."));
        }
        let output = async {
            use tokio::io::AsyncWriteExt;
            let mut stream = logs.bytes_stream();
            let mut buffer = BytesMut::new();
            let mut stdout = tokio::io::stdout();
            let mut stderr = tokio::io::stderr();
            while let Some(bytes) = stream.next().await {
                buffer.extend_from_slice(
                    &bytes.map_err(|_| Error::new(502, "Container log stream failed."))?,
                );
                while let Some((error, data)) = frame(&mut buffer)? {
                    if error {
                        stderr.write_all(&data).await?;
                    } else {
                        stdout.write_all(&data).await?;
                    }
                }
            }
            if !buffer.is_empty() {
                return Err(Error::new(502, "Container log frame was truncated."));
            }
            Ok::<_, Error>(())
        };
        let wait = async {
            let response = http
                .post(format!("{url}/wait"))
                .bearer_auth(&token)
                .send()
                .await
                .map_err(|_| Error::new(503, "Could not wait for isolated runner."))?;
            if !response.status().is_success() {
                return Err(Error::new(503, "Could not wait for isolated runner."));
            }
            let result: Value = response
                .json()
                .await
                .map_err(|_| Error::new(502, "Invalid isolated runner exit status."))?;
            result["StatusCode"]
                .as_i64()
                .filter(|v| (0..=255).contains(v))
                .map(|v| v as i32)
                .ok_or_else(|| Error::new(502, "Isolated runner did not return an exit status."))
        };
        let (_, code) = tokio::try_join!(output, wait)?;
        Ok(code)
    };
    let result = tokio::select! {
    _=stop.cancelled()=>Ok(143),result=operation=>result};
    let _ = http
        .delete(url)
        .bearer_auth(token)
        .timeout(Duration::from_millis(2500))
        .send()
        .await;
    result
}
pub fn frame(buffer: &mut BytesMut) -> Result<Option<(bool, Bytes)>> {
    if buffer.len() < 8 {
        return Ok(None);
    }
    let length = u32::from_be_bytes(buffer[4..8].try_into().unwrap()) as usize;
    if length > 16 * 1024 * 1024 {
        return Err(Error::new(502, "Invalid container log frame."));
    }
    if buffer.len() < length + 8 {
        return Ok(None);
    }
    let stderr = buffer[0] == 2;
    buffer.advance(8);
    Ok(Some((stderr, buffer.split_to(length).freeze())))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn fragmented_log_headers_and_payloads_survive_keepalives() {
        let data = Bytes::from_static(b"\x01\0\0\0\0\0\0\x05hello\x02\0\0\0\0\0\0\x03err");
        // Every possible byte boundary, including boundaries inside the header.
        for split in 1..data.len() {
            let chunks =
                futures_util::stream::iter(vec![Ok(data.slice(..split)), Ok(data.slice(split..))]);
            let output = heartbeat(
                docker_frames(chunks),
                Bytes::from_static(&[0; 8]),
                CancellationToken::new(),
            );
            tokio::pin!(output);
            let mut frames = Vec::new();
            while let Some(frame) = output.next().await {
                let frame = frame.unwrap();
                if frame.as_ref() != [0; 8] {
                    frames.extend_from_slice(&frame);
                }
            }
            assert_eq!(frames, data);
        }
        let truncated = docker_frames(futures_util::stream::iter(vec![Ok(data.slice(..9))]));
        tokio::pin!(truncated);
        assert!(truncated.next().await.unwrap().is_err());
    }
    #[test]
    fn mount_translation_uses_the_longest_boundary_and_rejects_aliases() {
        let mounts = json!([{"Destination":"/data","Source":"/host/data"},{"Destination":"/data/nested","Source":"/host/specific"}]);
        assert_eq!(
            translate_mount("/data/nested/file", &mounts).unwrap(),
            Path::new("/host/specific/file")
        );
        for path in [
            "/data-other/file",
            "/data/../secret",
            "/data/./file",
            "/data//file",
            "/data/file:ro",
            "data/file",
        ] {
            assert!(translate_mount(path, &mounts).is_err(), "{path}");
        }
    }
}
