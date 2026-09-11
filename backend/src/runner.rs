//! Authenticated execution interface shared by chats, scheduled tasks and recovery.
use crate::{
    auth::safe_equal,
    config::now,
    error::{Error, Result},
    microvm::{host, wire},
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
use base64::{Engine, engine::general_purpose::STANDARD};
use bytes::Bytes;
use futures_util::StreamExt;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt, BufReader},
    sync::{Mutex, watch},
};
use tokio_util::sync::CancellationToken;

pub const CONTROLLER_INTERRUPTED: i32 = 75;

struct Attempt {
    stop: CancellationToken,
    done: watch::Receiver<bool>,
    slot: u8,
}
#[derive(Clone)]
struct Broker {
    data: PathBuf,
    state: PathBuf,
    image: PathBuf,
    active: Arc<Mutex<HashMap<String, Attempt>>>,
    stop: CancellationToken,
}

fn normalized(path: &Path) -> bool {
    path.is_absolute()
        && path.components().all(|p| {
            !matches!(
                p,
                std::path::Component::ParentDir | std::path::Component::CurDir
            )
        })
        && !path.to_string_lossy().contains("//")
}
fn validate(plan: &Value, id: &str, data: &Path) -> Result<()> {
    uuid(text(plan, "runId"))?;
    if plan["id"] != id
        || plan["expires"]
            .as_i64()
            .is_none_or(|n| n <= now() || n > now() + 13 * 3600000)
    {
        return Err(Error::bad("Invalid or expired execution plan."));
    }
    let run_root = data.join("runs").join(text(plan, "runId"));
    for import in plan["imports"]
        .as_array()
        .ok_or_else(|| Error::bad("Missing execution imports."))?
    {
        let source = Path::new(text(import, "source"));
        let target = Path::new(text(import, "target"));
        if !normalized(source)
            || !source.starts_with(&run_root)
            || !normalized(target)
            || !(target.starts_with(&run_root)
                || target == Path::new("/home/node")
                || target == Path::new("/run/leo-chat"))
        {
            return Err(Error::bad(
                "Execution import is outside its private workspace.",
            ));
        }
    }
    if !Path::new(text(plan, "cwd")).starts_with(&run_root) {
        return Err(Error::bad("Invalid guest workspace."));
    }
    if let Some(output) = plan["chat"]["output"].as_str()
        && Path::new(output) != run_root.join("output/result.md")
    {
        return Err(Error::bad("Invalid result destination."));
    }
    Ok(())
}
impl Broker {
    async fn start(&self, id: &str) -> Result<()> {
        let mut active = self.active.lock().await;
        if active.contains_key(id) {
            return Ok(());
        }
        if self.state.join(format!("{id}.stopped")).exists()
            || self.state.join(format!("{id}.exit")).exists()
        {
            return Err(Error::new(
                409,
                "This execution attempt has already stopped.",
            ));
        }
        let file = tokio::fs::File::open(self.data.join("runner-plans").join(format!("{id}.json")))
            .await?;
        let mut bytes = Vec::new();
        file.take(8_000_001).read_to_end(&mut bytes).await?;
        if bytes.len() > 8_000_000 {
            return Err(Error::bad("Execution plan exceeds limit."));
        }
        let plan: Value = serde_json::from_slice(&bytes)?;
        validate(&plan, id, &self.data)?;
        // Imports must resolve to regular directories in this run; a symlink is not a scope grant.
        for import in plan["imports"].as_array().unwrap() {
            let path = Path::new(text(import, "source"));
            if tokio::fs::canonicalize(path).await? != path {
                return Err(Error::bad("Execution import changed location."));
            }
        }
        let slot = (1..=4)
            .find(|slot| active.values().all(|a| a.slot != *slot))
            .ok_or_else(|| Error::new(503, "All VM slots are occupied."))?;
        let stop = self.stop.child_token();
        let (done, receiver) = watch::channel(false);
        atomic_write(&self.state.join(format!("{id}.active")), b"1").await?;
        active.insert(
            id.into(),
            Attempt {
                stop: stop.clone(),
                done: receiver,
                slot,
            },
        );
        let broker = self.clone();
        let id = id.to_owned();
        tokio::spawn(async move {
            let deadline =
                Duration::from_millis((plan["expires"].as_i64().unwrap() - now()).max(1) as u64);
            let expiry = stop.clone();
            let timer = tokio::spawn(async move {
                tokio::time::sleep(deadline).await;
                expiry.cancel();
            });
            let result =
                host::execute(plan, broker.state.clone(), broker.image.clone(), slot, stop).await;
            timer.abort();
            let code = match result {
                Ok(code) => code,
                Err(error) => {
                    let event = json!({"type":"output","stderr":true,"data":STANDARD.encode(format!("{}\n",error.message))});
                    if let Ok(mut log) = tokio::fs::OpenOptions::new()
                        .create(true)
                        .append(true)
                        .open(broker.state.join(format!("{id}.log")))
                        .await
                    {
                        let _ = wire::write(&mut log, &event).await;
                    }
                    1
                }
            };
            let _ = atomic_write(
                &broker.state.join(format!("{id}.exit")),
                code.to_string().as_bytes(),
            )
            .await;
            let _ = tokio::fs::remove_file(broker.state.join(format!("{id}.active"))).await;
            broker.active.lock().await.remove(&id);
            let _ = done.send(true);
        });
        Ok(())
    }
    async fn stop(&self, id: &str) -> Result<()> {
        atomic_write(&self.state.join(format!("{id}.stopped")), b"").await?;
        let attempt = {
            let active = self.active.lock().await;
            active.get(id).map(|a| {
                a.stop.cancel();
                a.done.clone()
            })
        };
        if let Some(mut done) = attempt {
            let _ = tokio::time::timeout(Duration::from_secs(15), done.wait_for(|done| *done))
                .await
                .map_err(|_| Error::new(503, "Waiting for the previous VM to stop."))?;
        }
        Ok(())
    }
}
async fn handler(State(broker): State<Broker>, request: Request) -> Result<Response> {
    if request.uri().path() == "/health" {
        return Ok(Json(json!({"status":"ok","backend":"firecracker","runtimeId":std::env::var("APP_RUNTIME_ID").unwrap_or_else(|_|"development".into()),"activeRuns":broker.active.lock().await.len()})).into_response());
    }
    let credential = tokio::fs::read_to_string(broker.data.join("runner-secret")).await?;
    let authorization = request
        .headers()
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if !safe_equal(authorization, &format!("Bearer {}", credential.trim())) {
        return Err(Error::new(401, "Invalid runner credential."));
    }
    let segments = request
        .uri()
        .path()
        .trim_start_matches('/')
        .split('/')
        .collect::<Vec<_>>();
    let id = segments
        .get(1)
        .filter(|_| segments.first() == Some(&"runs"))
        .ok_or_else(|| Error::new(404, "Not found"))?;
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
            let id = (*id).to_owned();
            let stream = futures_util::stream::try_unfold(
                (broker, id, 0_u64, Vec::new()),
                |(broker, id, offset, mut pending)| async move {
                    let mut offset = offset;
                    loop {
                        if let Ok(mut file) =
                            tokio::fs::File::open(broker.state.join(format!("{id}.log"))).await
                        {
                            use tokio::io::AsyncSeekExt;
                            file.seek(std::io::SeekFrom::Start(offset)).await?;
                            let mut chunk = vec![0; 65536];
                            let count = file.read(&mut chunk).await?;
                            pending.extend_from_slice(&chunk[..count]);
                            offset += count as u64;
                        }
                        if let Some(end) = pending.iter().rposition(|b| *b == b'\n') {
                            let rest = pending.split_off(end + 1);
                            return Ok::<_, std::io::Error>(Some((
                                Bytes::from(pending),
                                (broker, id, offset, rest),
                            )));
                        }
                        if broker.state.join(format!("{id}.exit")).exists()
                            || broker.stop.is_cancelled()
                        {
                            return Ok(None);
                        }
                        tokio::time::sleep(Duration::from_millis(100)).await;
                        if pending.is_empty() {
                            return Ok(Some((
                                Bytes::from_static(b"{\"type\":\"heartbeat\"}\n"),
                                (broker, id, offset, pending),
                            )));
                        }
                    }
                },
            );
            Ok((
                [("content-type", "application/x-ndjson")],
                Body::from_stream(stream),
            )
                .into_response())
        }
        ("POST", ["runs", id, "wait"]) => {
            let id = (*id).to_owned();
            let stream = futures_util::stream::unfold(
                (broker, id, false),
                |(broker, id, done)| async move {
                    if done {
                        return None;
                    }
                    if let Ok(code) =
                        tokio::fs::read_to_string(broker.state.join(format!("{id}.exit"))).await
                    {
                        return Some((
                            Ok::<_, std::io::Error>(Bytes::from(
                                json!({"StatusCode":code.parse::<i32>().unwrap_or(1)}).to_string(),
                            )),
                            (broker, id, true),
                        ));
                    }
                    tokio::time::sleep(Duration::from_secs(1)).await;
                    let stop = broker.stop.is_cancelled();
                    Some((Ok(Bytes::from_static(b" ")), (broker, id, stop)))
                },
            );
            Ok((
                [("content-type", "application/json")],
                Body::from_stream(stream),
            )
                .into_response())
        }
        _ => Err(Error::new(405, "Method not allowed.")),
    }
}

pub async fn serve(stop: CancellationToken) -> Result<()> {
    let data = PathBuf::from(std::env::var("DATA_DIR").unwrap_or_else(|_| "/data".into()));
    let state =
        PathBuf::from(std::env::var("RUNNER_STATE_DIR").unwrap_or_else(|_| "/runner-state".into()));
    private_dir(&state).await?;
    let controller = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(state.join("controller.lock"))?;
    if unsafe { libc::flock(controller.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return Err(Error::new(503, "Another VM controller owns this storage."));
    }
    let image = host::assets(&state).await?;
    // A controller container restart fences its entire PID namespace. Mark interrupted attempts
    // exited, but retain all guest disks so the manager can launch replacement attempts.
    let mut files = tokio::fs::read_dir(&state).await?;
    while let Some(file) = files.next_entry().await? {
        if file.path().extension().is_some_and(|s| s == "active") {
            atomic_write(&file.path().with_extension("exit"), b"143").await?;
            tokio::fs::remove_file(file.path()).await?;
        }
    }
    let broker = Broker {
        data,
        state,
        image,
        active: Default::default(),
        stop: stop.clone(),
    };
    let draining = broker.clone();
    let listener = tokio::net::TcpListener::bind("0.0.0.0:4311").await?;
    axum::serve(
        listener,
        Router::new().fallback(any(handler)).with_state(broker),
    )
    .with_graceful_shutdown(stop.cancelled_owned())
    .await?;
    let attempts = draining
        .active
        .lock()
        .await
        .keys()
        .cloned()
        .collect::<Vec<_>>();
    for id in attempts {
        draining.stop(&id).await?;
    }
    drop(controller);
    Ok(())
}
use std::os::fd::AsRawFd;
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
            .map_err(|_| Error::new(503, "VM controller could not start the run."))?;
        if !response.status().is_success() {
            return Err(Error::new(503, "VM controller could not start the run."));
        }
        let logs = http
            .get(format!("{url}/logs"))
            .bearer_auth(&token)
            .send()
            .await
            .map_err(|_| Error::new(503, "Could not read VM output."))?;
        if !logs.status().is_success() {
            return Err(Error::new(503, "Could not read VM output."));
        }
        let output = async {
            let stream = logs
                .bytes_stream()
                .map(|r| r.map_err(std::io::Error::other));
            let mut reader = BufReader::new(tokio_util::io::StreamReader::new(stream));
            let mut stdout = tokio::io::stdout();
            let mut stderr = tokio::io::stderr();
            while let Some(event) = wire::read(&mut reader).await.map_err(|error| {
                if error.status == 500 {
                    Error::new(503, "VM output connection was interrupted.")
                } else {
                    error
                }
            })? {
                if event["type"] == "heartbeat" {
                    continue;
                }
                let bytes = STANDARD
                    .decode(text(&event, "data"))
                    .map_err(|_| Error::bad("Invalid VM output."))?;
                if event["stderr"] == true {
                    stderr.write_all(&bytes).await?;
                } else {
                    stdout.write_all(&bytes).await?;
                }
            }
            Ok::<_, Error>(())
        };
        let wait = async {
            let response = http
                .post(format!("{url}/wait"))
                .bearer_auth(&token)
                .send()
                .await
                .map_err(|_| Error::new(503, "Could not wait for VM."))?;
            let value: Value = response
                .json()
                .await
                .map_err(|_| Error::new(503, "VM completion connection was interrupted."))?;
            value["StatusCode"]
                .as_i64()
                .filter(|n| (0..=255).contains(n))
                .map(|n| n as i32)
                .ok_or_else(|| Error::bad("Invalid VM exit status."))
        };
        let (_, code) = tokio::try_join!(output, wait)?;
        Ok(code)
    };
    let result = tokio::select! {_=stop.cancelled()=>Ok(143),result=operation=>result};
    let _ = http
        .delete(url)
        .bearer_auth(token)
        .timeout(Duration::from_secs(17))
        .send()
        .await;
    match result {
        Err(error) if error.status == 503 => {
            eprintln!("{}", error.message);
            Ok(CONTROLLER_INTERRUPTED)
        }
        Ok(143) if !stop.is_cancelled() => Ok(CONTROLLER_INTERRUPTED),
        result => result,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn imports_cannot_grant_host_or_other_run_access() {
        let id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        let mut plan = json!({"id":id,"runId":id,"expires":now()+60000,"cwd":format!("/data/runs/{id}/workspace"),"imports":[]});
        assert!(validate(&plan, id, Path::new("/data")).is_ok());
        for source in [
            "/data/private",
            "/home/node",
            "/data/runs/another/workspace",
            "/data/runs/../private",
        ] {
            plan["imports"] = json!([{"source":source,"target":"/home/node"}]);
            assert!(validate(&plan, id, Path::new("/data")).is_err());
        }
    }
}
