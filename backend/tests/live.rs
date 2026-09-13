use leo_agent_manager::{
    config::{Config, MAIN_AGENT_ID},
    service::Service,
    validation::text,
};
use serde_json::{Value, json};
use std::{
    sync::Arc,
    time::{Duration, Instant},
};
use tempfile::TempDir;

struct Fixture {
    _root: TempDir,
    service: Arc<Service>,
    url: String,
    token: String,
    run: String,
    server: tokio::task::JoinHandle<()>,
}
impl Fixture {
    async fn new() -> Self {
        let root = TempDir::new().unwrap();
        std::fs::create_dir_all(root.path().join("home/.codex")).unwrap();
        std::fs::write(root.path().join("home/.codex/leo-managed-auth"), "1").unwrap();
        let config = Config {
            data_dir: root.path().join("data"),
            home: root.path().join("home"),
            workspace_roots: vec![root.path().to_owned()],
            public_url: "http://localhost:4310".into(),
            host: "127.0.0.1".into(),
            port: 0,
            setup_token: "test".into(),
            codex_bin: "codex".into(),
            gh_bin: "gh".into(),
            concurrency: 1,
            logger: false,
            worker_enabled: false,
            runner_url: String::new(),
        };
        let service = Service::new(config).await.unwrap();
        let token = text(&service.auth.session().await.unwrap(), "value").to_owned();
        let task = service
            .task(
                json!({"name":"Live test","prompt":"Test","agentId":MAIN_AGENT_ID}),
                None,
            )
            .await
            .unwrap();
        let run = service
            .enqueue(text(&task, "id"), "manual", None)
            .await
            .unwrap();
        let (url, server) = serve(service.clone()).await;
        Self {
            _root: root,
            service,
            url,
            token,
            run: text(&run, "id").into(),
            server,
        }
    }
    async fn open(&self, after: i64) -> Stream {
        self.open_path(
            &format!("/api/runs/{}/stream?after={after}", self.run),
            None,
        )
        .await
    }
    async fn open_path(&self, path: &str, last: Option<i64>) -> Stream {
        let mut request = reqwest::Client::new()
            .get(format!("{}{path}", self.url))
            .header("cookie", format!("leo_session={}", self.token))
            .header("accept-encoding", "gzip, br");
        if let Some(last) = last {
            request = request.header("Last-Event-ID", last);
        }
        let response = request.send().await.unwrap();
        assert_eq!(response.status(), 200);
        assert!(
            response.headers()["content-type"]
                .to_str()
                .unwrap()
                .starts_with("text/event-stream")
        );
        assert!(!response.headers().contains_key("content-encoding"));
        Stream {
            response,
            pending: String::new(),
        }
    }
    async fn append(&self, count: usize) {
        let run = self.run.clone();
        self.service
            .store
            .transaction(move |db| {
                for n in 0..count {
                    db.event(&run, "output", &format!("event-{n}"), None)?;
                }
                Ok(())
            })
            .await
            .unwrap();
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        self.service.shutdown.cancel();
        self.server.abort();
    }
}
async fn serve(service: Arc<Service>) -> (String, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let router = leo_agent_manager::http::router(service).await.unwrap();
    let server = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    (url, server)
}
struct Stream {
    response: reqwest::Response,
    pending: String,
}
impl Stream {
    async fn batch(&mut self) -> (i64, Value) {
        tokio::time::timeout(Duration::from_secs(4), async {
            loop {
                if let Some(end) = self.pending.find("\n\n") {
                    let frame: String = self.pending.drain(..end + 2).collect();
                    if !frame.lines().any(|l| l == "event: batch") {
                        continue;
                    }
                    let id = frame
                        .lines()
                        .find_map(|l| l.strip_prefix("id: "))
                        .unwrap()
                        .parse()
                        .unwrap();
                    let data = frame
                        .lines()
                        .find_map(|l| l.strip_prefix("data: "))
                        .unwrap();
                    return (id, serde_json::from_str(data).unwrap());
                }
                let chunk = self
                    .response
                    .chunk()
                    .await
                    .unwrap()
                    .expect("unexpected EOF");
                self.pending.push_str(std::str::from_utf8(&chunk).unwrap());
            }
        })
        .await
        .expect("stream stalled")
    }
    async fn through(&mut self, end: i64) -> Vec<i64> {
        let mut ids = Vec::new();
        loop {
            let (id, batch) = self.batch().await;
            ids.extend(
                batch["events"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|e| e["id"].as_i64().unwrap()),
            );
            if id >= end {
                return ids;
            }
        }
    }
}
async fn ids(f: &Fixture) -> Vec<i64> {
    let run = f.run.clone();
    f.service
        .store
        .read(move |db| {
            Ok(db
                .events(&run, 0, 10000)?
                .iter()
                .map(|e| e["id"].as_i64().unwrap())
                .collect())
        })
        .await
        .unwrap()
}

#[tokio::test]
async fn multiple_clients_refresh_disconnect_and_catch_up_without_gaps() {
    let f = Fixture::new().await;
    f.append(237).await;
    let expected = ids(&f).await;
    let end = *expected.last().unwrap();
    let (mut a, mut b) = tokio::join!(f.open(0), f.open(0));
    let (a_ids, b_ids) = tokio::join!(a.through(end), b.through(end));
    assert_eq!(a_ids, expected);
    assert_eq!(b_ids, expected);
    drop(b);
    let started = Instant::now();
    f.append(23).await;
    let all = ids(&f).await;
    let new: Vec<_> = all.iter().copied().filter(|id| *id > end).collect();
    assert_eq!(a.through(*all.last().unwrap()).await, new);
    assert!(
        started.elapsed() < Duration::from_secs(1),
        "live delivery fell back to polling"
    );
    // Header takes precedence over a stale URL cursor (native EventSource resume).
    let mut resumed = f
        .open_path(&format!("/api/runs/{}/stream?after=0", f.run), Some(end))
        .await;
    assert_eq!(resumed.through(*all.last().unwrap()).await, new);
    let mut refreshed = f.open(0).await;
    assert_eq!(refreshed.through(*all.last().unwrap()).await, all);
    let mut ahead = f.open(i64::MAX).await;
    let (_, batch) = ahead.batch().await;
    assert_eq!(batch["reset"], true);
    assert_eq!(batch["events"][0]["id"], all[0]);
}

#[tokio::test]
async fn writes_during_replay_and_slow_readers_do_not_block_or_lose_events() {
    let f = Fixture::new().await;
    f.append(300).await;
    let mut reader = f.open(0).await;
    let (_, first) = reader.batch().await;
    assert_eq!(first["more"], true);
    let slow = f.open(0).await;
    let run = f.run.clone();
    let start = Instant::now();
    f.service
        .store
        .transaction(move |db| {
            for _ in 0..500 {
                db.event(&run, "output", &"x".repeat(16000), None)?;
            }
            Ok(())
        })
        .await
        .unwrap();
    assert!(start.elapsed() < Duration::from_secs(5));
    let all = ids(&f).await;
    let mut received: Vec<_> = first["events"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v["id"].as_i64().unwrap())
        .collect();
    received.extend(reader.through(*all.last().unwrap()).await);
    assert_eq!(received, all);
    drop(slow);
    // Rolled-back events are never observable.
    let run = f.run.clone();
    let result = f
        .service
        .store
        .transaction(move |db| {
            db.event(&run, "output", "must not escape", None)?;
            Err::<(), _>(leo_agent_manager::error::Error::bad("rollback"))
        })
        .await;
    assert!(result.is_err());
    assert_eq!(ids(&f).await, all);
}

#[tokio::test]
async fn server_restart_replays_committed_history_and_continues_live() {
    let mut f = Fixture::new().await;
    f.append(3).await;
    let mut stream = f.open(0).await;
    let cursor = *ids(&f).await.last().unwrap();
    stream.through(cursor).await;
    f.service.shutdown.cancel();
    f.server.abort();
    drop(stream);
    f.service = Service::new(f.service.config.clone()).await.unwrap();
    let (url, server) = serve(f.service.clone()).await;
    f.url = url;
    f.server = server;
    f.append(5).await;
    let all = ids(&f).await;
    let mut resumed = f.open(cursor).await;
    assert_eq!(
        resumed.through(*all.last().unwrap()).await,
        all.into_iter()
            .filter(|id| *id > cursor)
            .collect::<Vec<_>>()
    );
    f.append(1).await;
    assert_eq!(
        resumed.batch().await.1["events"].as_array().unwrap().len(),
        1
    );
}

#[tokio::test]
async fn metadata_auth_revocation_and_invalid_requests() {
    let f = Fixture::new().await;
    let mut stream = f.open(0).await;
    stream.batch().await;
    f.service
        .store
        .patch_run(&f.run, json!({"status":"succeeded","result":"Done"}))
        .await
        .unwrap();
    let (_, batch) = stream.batch().await;
    assert_eq!(batch["state"]["run"]["result"], "Done");
    f.service.auth.logout(&f.token).await.unwrap();
    let ended = tokio::time::timeout(Duration::from_secs(2), stream.response.chunk())
        .await
        .unwrap();
    assert!(ended.unwrap().is_none());
    let client = reqwest::Client::new();
    assert_eq!(
        client
            .get(format!("{}/api/runs/{}/stream", f.url, f.run))
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    let token = text(&f.service.auth.session().await.unwrap(), "value").to_owned();
    for (path, code) in [
        (format!("/api/runs/{}/stream?after=-1", f.run), 400),
        ("/api/runs/not-an-id/stream".into(), 400),
        (
            format!("/api/runs/{}/stream", leo_agent_manager::config::id()),
            404,
        ),
    ] {
        assert_eq!(
            client
                .get(format!("{}{path}", f.url))
                .header("cookie", format!("leo_session={token}"))
                .send()
                .await
                .unwrap()
                .status(),
            code
        );
    }
}

#[tokio::test]
async fn chat_state_questions_and_artifacts_update_without_text_events() {
    let f = Fixture::new().await;
    let chat = f.service.chat_create(json!({})).await.unwrap();
    let chat_id = text(&chat, "id");
    let mut stream = f
        .open_path(&format!("/api/chats/{chat_id}/stream"), None)
        .await;
    let (_, first) = stream.batch().await;
    assert!(first["state"]["run"].is_null());
    let mut attached = chat.clone();
    attached["runId"] = f.run.clone().into();
    f.service.store.put("chats", attached).await.unwrap();
    let (_, batch) = stream.batch().await;
    assert_eq!(batch["state"]["run"]["id"], f.run);
    assert_eq!(batch["reset"], true);
    f.service.chat_pause(chat_id, true).await.unwrap();
    assert_eq!(stream.batch().await.1["state"]["chat"]["paused"], true);
    // Mutations of metadata have no event id of their own and still reach every client.
    f.service
        .store
        .set(
            &format!("chat-question:{chat_id}:question"),
            json!({"id":"question","status":"pending"}),
            None,
        )
        .await
        .unwrap();
    assert_eq!(
        stream.batch().await.1["state"]["chat"]["pendingQuestions"],
        1
    );
    f.service
        .store
        .set(
            &format!("artifact:{}:file", f.run),
            json!({"id":"file","previewStatus":"pending"}),
            None,
        )
        .await
        .unwrap();
    assert_eq!(
        stream.batch().await.1["state"]["artifacts"][0]["previewStatus"],
        "pending"
    );
    f.service
        .store
        .set(
            &format!("artifact:{}:file", f.run),
            json!({"id":"file","previewStatus":"ready"}),
            None,
        )
        .await
        .unwrap();
    assert_eq!(
        stream.batch().await.1["state"]["artifacts"][0]["previewStatus"],
        "ready"
    );
}

#[tokio::test]
async fn live_delivery_latency_under_repeated_writes() {
    let f = Fixture::new().await;
    let mut stream = f.open(0).await;
    let initial = *ids(&f).await.last().unwrap();
    stream.through(initial).await;
    let mut samples = Vec::new();
    for _ in 0..25 {
        let start = Instant::now();
        f.append(1).await;
        let (_, batch) = stream.batch().await;
        assert_eq!(batch["events"].as_array().unwrap().len(), 1);
        samples.push(start.elapsed().as_millis());
    }
    samples.sort_unstable();
    println!(
        "SQLite write → HTTP client (25 samples): p50={}ms p95={}ms max={}ms",
        samples[12], samples[23], samples[24]
    );
    assert!(
        samples[23] < 500,
        "delivery must not depend on the former 1.2s polling cadence"
    );
}

#[tokio::test]
async fn wire_deltas_reestablish_baselines_after_reconnect_and_keep_rest_compatible() {
    let f = Fixture::new().await;
    let mut stream = f.open(0).await;
    stream.batch().await;
    let publish = async |content: &str| {
        f.service
            .store
            .event(
                &f.run,
                "item.updated",
                content,
                Some(json!({"item":{"id":"message","type":"agent_message","text":content}})),
            )
            .await
            .unwrap();
    };
    publish("Bonjour 👋").await;
    let (cursor, first) = stream.batch().await;
    assert_eq!(first["events"][0]["payload"]["item"]["text"], "Bonjour 👋");
    publish("Bonjour 👋 café").await;
    let (_, second) = stream.batch().await;
    assert_eq!(second["events"][0]["payload"]["item"]["delta"], " café");
    assert!(second["events"][0]["payload"]["item"]["text"].is_null());
    let mut resumed = f.open(cursor).await;
    assert_eq!(
        resumed.batch().await.1["events"][0]["payload"]["item"]["text"],
        "Bonjour 👋 café"
    );
    publish("Corrected message").await;
    assert_eq!(
        stream.batch().await.1["events"][0]["payload"]["item"]["text"],
        "Corrected message"
    );
    let run = f.run.clone();
    let stored = f
        .service
        .store
        .read(move |db| db.events(&run, cursor, 10))
        .await
        .unwrap();
    assert_eq!(stored[0]["payload"]["item"]["text"], "Bonjour 👋 café");
}
