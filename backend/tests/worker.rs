use leo_agent_manager::{
    config::{Config, id},
    service::Service,
    validation::text,
};
use serde_json::{Value, json};
use std::{sync::Arc, time::Duration};
use tempfile::TempDir;
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    process::{Child, Command},
};
struct Fixture {
    root: TempDir,
    service: Arc<Service>,
    process: Option<Child>,
}
impl Fixture {
    async fn new() -> Self {
        let root = TempDir::new().unwrap();
        std::fs::create_dir(root.path().join("home")).unwrap();
        let config = Config {
            data_dir: root.path().join("data"),
            home: root.path().join("home"),
            workspace_roots: vec![root.path().to_owned()],
            public_url: "http://localhost:4310".into(),
            host: "127.0.0.1".into(),
            port: 0,
            setup_token: String::new(),
            codex_bin: std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../tests/fixtures/codex.mjs")
                .to_string_lossy()
                .into_owned(),
            gh_bin: "gh".into(),
            concurrency: 1,
            logger: false,
            worker_enabled: true,
            runner_url: String::new(),
        };
        let service = Service::new(config).await.unwrap();
        let mut fixture = Self {
            root,
            service,
            process: None,
        };
        fixture.start().await;
        fixture
    }
    async fn start(&mut self) {
        self.start_backend(false).await;
    }
    async fn start_backend(&mut self, legacy: bool) {
        let file = self.root.path().join("config.json");
        tokio::fs::write(&file, serde_json::to_vec(&self.service.config).unwrap())
            .await
            .unwrap();
        let mut command = if legacy {
            let mut command = Command::new("node");
            command.args(["--import", "tsx"]);
            command.arg(
                std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                    .join("../tests/fixtures/legacy-worker.mjs"),
            );
            command
        } else {
            let mut command = Command::new(env!("CARGO_BIN_EXE_leo"));
            command.arg("serve");
            command
        };
        if self.root.path().join("usage.json").exists() {
            command.env("LEO_FIXTURE_USAGE", self.root.path().join("usage.json"));
        }
        let mut child = command
            .env("LEO_CONFIG", file)
            .env_remove("LEO_TOOLKIT_DIR")
            .env("NODE_ENV", "test")
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::inherit())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let mut lines = BufReader::new(child.stdout.take().unwrap()).lines();
        let line = tokio::time::timeout(Duration::from_secs(10), lines.next_line())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        assert!(line.starts_with("Listening on"), "{line}");
        self.process = Some(child);
    }
    async fn stop(&mut self, abrupt: bool) {
        if let Some(mut process) = self.process.take() {
            if abrupt {
                process.kill().await.unwrap();
            } else {
                unsafe {
                    libc::kill(process.id().unwrap() as i32, libc::SIGTERM);
                }
                tokio::time::timeout(Duration::from_secs(15), process.wait())
                    .await
                    .unwrap()
                    .unwrap();
            }
        }
    }
    async fn enqueue(&self, prompt: &str) -> Value {
        let task = self.service.task(json!({"name":"Fixture run","prompt":prompt,"worktree":false,"agentId":leo_agent_manager::config::MAIN_AGENT_ID}), None).await.unwrap();
        self.service
            .enqueue(text(&task, "id"), "manual", None)
            .await
            .unwrap()
    }
    async fn until(&self, id: &str, condition: impl Fn(&Value) -> bool) -> Value {
        tokio::time::timeout(Duration::from_secs(20), async {
            loop {
                let run = self.service.store.run(id).await.unwrap();
                if condition(&run) {
                    return run;
                }
                tokio::time::sleep(Duration::from_millis(30)).await;
            }
        })
        .await
        .unwrap()
    }
}

#[tokio::test]
async fn migration_resumes_a_checkpoint_written_by_the_node_backend() {
    let mut fixture = Fixture::new().await;
    fixture.stop(false).await;
    fixture.start_backend(true).await;
    let run = fixture.enqueue("fixture:restart").await;
    let id = text(&run, "id");
    let running = fixture
        .until(id, |r| r["sessionId"] == "fixture-session")
        .await;
    fixture.stop(true).await;
    fixture.start().await;
    let completed = fixture
        .until(id, |r| ["succeeded", "failed"].contains(&text(r, "status")))
        .await;
    assert_eq!(completed["status"], "succeeded", "{completed}");
    assert_eq!(completed["workspace"], running["workspace"]);
    assert_eq!(completed["sessionId"], running["sessionId"]);
    assert_eq!(completed["resumeCount"], 1);
    fixture.stop(false).await;
}

#[tokio::test]
async fn usage_exhaustion_switches_accounts_and_preserves_the_conversation() {
    use leo_agent_manager::config::now;
    let mut fixture = Fixture::new().await;
    fixture.stop(false).await;
    let s = &fixture.service;
    s.store
        .set("codex-accounts-enabled", json!(true), None)
        .await
        .unwrap();
    let mut accounts = Vec::new();
    let mut usage = json!({});
    for (name, used) in [("More capacity", 10), ("Backup", 30)] {
        let id = id();
        let limits = json!({"ordinaryUsageAllowed":true,"rateLimits":{"limitId":"codex","primary":{"usedPercent":used,"windowDurationMins":300,"resetsAt":now()/1000+7200}}});
        s.store.put("codexAccounts", json!({"id":id,"name":name,"enabled":true,"email":format!("{id}@example.test"),"plan":"plus","identity":null,"createdAt":now(),"checkedAt":now(),"state":"ready","error":"","limits":limits,"lastUsedAt":null,"exhausted":null})).await.unwrap();
        s.vault.set(&format!("codex-account:{id}"), &json!({"tokens":{"account_id":id,"access_token":"synthetic-access","refresh_token":"synthetic-refresh"}})).await.unwrap();
        usage[&id] = limits;
        accounts.push(id);
    }
    tokio::fs::write(fixture.root.path().join("usage.json"), usage.to_string())
        .await
        .unwrap();
    fixture.start().await;
    let run = fixture.enqueue("fixture:exhaust").await;
    let id = text(&run, "id");
    let completed = fixture
        .until(id, |r| ["succeeded", "failed"].contains(&text(r, "status")))
        .await;
    assert_eq!(completed["status"], "succeeded", "{completed}");
    assert_eq!(completed["codexAccountId"], accounts[1]);
    assert_eq!(completed["sessionId"], "fixture-session");
    fixture.stop(false).await;
}
#[tokio::test]
async fn native_worker_records_artifacts_and_completes_task() {
    let mut fixture = Fixture::new().await;
    let run = fixture.enqueue("Inspect the fixture").await;
    let id = text(&run, "id");
    let complete = fixture
        .until(id, |r| {
            r["status"] == "succeeded" || r["status"] == "failed"
        })
        .await;
    assert_eq!(complete["status"], "succeeded", "{complete}");
    assert_eq!(complete["sessionId"], "fixture-session");
    let id = id.to_owned();
    let events = fixture
        .service
        .store
        .read(move |db| db.events(&id, 0, 500))
        .await
        .unwrap();
    assert!(events.iter().any(|e| e["payload"].is_object()));
    fixture.stop(false).await;
}
#[tokio::test]
async fn abrupt_restart_fences_previous_process_and_resumes_workspace() {
    let mut fixture = Fixture::new().await;
    let run = fixture.enqueue("fixture:restart").await;
    let id = text(&run, "id");
    let running = fixture
        .until(id, |r| r["sessionId"] == "fixture-session")
        .await;
    fixture.stop(true).await;
    fixture.start().await;
    let completed = fixture
        .until(id, |r| {
            r["status"] == "succeeded" || r["status"] == "failed"
        })
        .await;
    assert_eq!(completed["status"], "succeeded", "{completed}");
    assert_eq!(completed["workspace"], running["workspace"]);
    assert_eq!(completed["sessionId"], running["sessionId"]);
    assert_eq!(completed["resumeCount"], 1);
    assert!(text(&completed, "summary").contains("saved conversation"));
    fixture.stop(false).await;
}
#[tokio::test]
async fn native_chat_turns_reuse_the_same_run_and_conversation() {
    let mut fixture = Fixture::new().await;
    let chat = fixture.service.chat_create(json!({})).await.unwrap();
    let chat_id = text(&chat, "id");
    fixture
        .service
        .chat_send(chat_id, json!({"id":id(),"text":"First message"}))
        .await
        .unwrap();
    let run_id = tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            let chat = fixture.service.chat_detail(chat_id).await.unwrap();
            if let Some(id) = chat["runId"].as_str() {
                break id.to_owned();
            }
            tokio::time::sleep(Duration::from_millis(30)).await;
        }
    })
    .await
    .unwrap();
    let first = fixture
        .until(&run_id, |r| {
            ["succeeded", "failed"].contains(&text(r, "status"))
        })
        .await;
    assert_eq!(first["status"], "succeeded", "{first}");
    fixture
        .service
        .chat_send(chat_id, json!({"id":id(),"text":"Second message"}))
        .await
        .unwrap();
    let second = fixture
        .until(&run_id, |r| {
            r["status"] == "succeeded" && text(r, "summary").contains("Second message")
        })
        .await;
    assert_eq!(second["sessionId"], "fixture-chat");
    assert_eq!(
        fixture.service.chat_detail(chat_id).await.unwrap()["runId"],
        run_id
    );
    fixture.stop(false).await;
}

#[tokio::test]
async fn chat_attachments_survive_worker_restart_and_reach_codex() {
    use axum::{body::Body, http::Request};
    let mut fixture = Fixture::new().await;
    let chat = fixture.service.chat_create(json!({})).await.unwrap();
    let chat_id = text(&chat, "id");
    let attachment_id = id();
    let request = Request::builder()
        .method("PUT")
        .uri("/?name=design.png")
        .body(Body::from(b"\x89PNG\r\n\x1a\nfixture".to_vec()))
        .unwrap();
    fixture
        .service
        .attachment_http(chat_id, &attachment_id, request)
        .await
        .unwrap();
    fixture
        .service
        .chat_send(
            chat_id,
            json!({"id":id(),"text":"fixture:chat-hang","attachmentIds":[attachment_id]}),
        )
        .await
        .unwrap();
    let run_id = tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            let chat = fixture.service.chat_detail(chat_id).await.unwrap();
            if let Some(id) = chat["runId"].as_str() {
                break id.to_owned();
            }
            tokio::time::sleep(Duration::from_millis(30)).await;
        }
    })
    .await
    .unwrap();
    fixture
        .until(&run_id, |r| r["sessionId"] == "fixture-chat")
        .await;
    // Wait for the original input receipt, so recovery resumes the accepted turn.
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if fixture.service.chat_detail(chat_id).await.unwrap()["messages"][0]["status"]
                == "delivered"
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(30)).await;
        }
    })
    .await
    .unwrap();
    fixture.stop(true).await;
    fixture.start().await;
    let completed = fixture
        .until(&run_id, |r| {
            ["succeeded", "failed"].contains(&text(r, "status"))
        })
        .await;
    assert_eq!(completed["status"], "succeeded", "{completed}");
    assert_eq!(completed["resumeCount"], 1);
    let path = fixture.service.config.data_dir.join("runs").join(&run_id);
    let thread: Value = serde_json::from_slice(
        &std::fs::read(path.join("codex/fixture-conversation.json")).unwrap(),
    )
    .unwrap();
    let turns = thread["turns"].as_array().unwrap();
    assert_eq!(turns.len(), 2);
    for turn in turns {
        let user = turn["items"]
            .as_array()
            .unwrap()
            .iter()
            .find(|i| i["type"] == "userMessage")
            .unwrap();
        let image = user["content"]
            .as_array()
            .unwrap()
            .iter()
            .find(|i| i["type"] == "localImage")
            .unwrap();
        assert_eq!(
            std::fs::read(text(image, "path")).unwrap(),
            b"\x89PNG\r\n\x1a\nfixture"
        );
    }
    fixture.stop(false).await;
}
