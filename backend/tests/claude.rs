use leo_agent_manager::{claude, claude_process, config::Config, http::Input, service::Service};
use serde_json::{Value, json};
use tempfile::TempDir;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;
fn config(root: &TempDir) -> Config {
    serde_json::from_value(json!({"dataDir":root.path().join("data"),"home":root.path().join("home"),"workspaceRoots":[root.path()],"publicUrl":"http://localhost:4310","host":"127.0.0.1","port":0,"setupToken":"test","codexBin":"/nonexistent-codex","claudeBin":std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../tests/fixtures/claude.mjs"),"ghBin":"gh","concurrency":1,"logger":false,"workerEnabled":false,"runnerUrl":""})).unwrap()
}
async fn route(
    s: &std::sync::Arc<Service>,
    method: &str,
    path: &str,
    body: Value,
) -> leo_agent_manager::error::Result<Value> {
    claude::routes(
        s,
        &Input {
            method: method.into(),
            path: format!("/api/claude/{path}"),
            query: Default::default(),
            headers: Default::default(),
            body,
        },
    )
    .await
}
async fn wait_login(s: &std::sync::Arc<Service>, state: &str) -> Value {
    tokio::time::timeout(std::time::Duration::from_secs(8), async {
        loop {
            let v = route(s, "GET", "connection", Value::Null).await.unwrap();
            if v["login"]["state"] == state {
                return v;
            }
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        }
    })
    .await
    .unwrap()
}
#[tokio::test]
async fn official_login_cancellation_failure_retry_identity_and_logout() {
    let root = TempDir::new().unwrap();
    let s = Service::new(config(&root)).await.unwrap();
    assert_eq!(
        route(&s, "GET", "connection", Value::Null).await.unwrap()["connected"],
        false
    );
    let login = route(&s, "POST", "login", json!({})).await.unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(8), async {
        loop {
            let connection = route(&s, "GET", "connection", Value::Null).await.unwrap();
            if connection["login"]["url"].is_string() {
                assert_eq!(
                    connection["login"]["url"],
                    "https://claude.com/oauth/authorize?fixture=1"
                );
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        }
    })
    .await
    .unwrap();
    assert!(route(&s, "POST", "login", json!({})).await.is_err());
    assert!(
        route(
            &s,
            "POST",
            "login/code",
            json!({"id":"stale","code":"fixture-code"})
        )
        .await
        .is_err()
    );
    route(&s, "DELETE", "login", json!({})).await.unwrap();
    wait_login(&s, "cancelled").await;
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    let failed = route(&s, "POST", "login", json!({})).await.unwrap();
    assert_ne!(login["id"], failed["id"]);
    route(
        &s,
        "POST",
        "login/code",
        json!({"id":failed["id"],"code":"wrong"}),
    )
    .await
    .unwrap();
    let failed = wait_login(&s, "failed").await;
    assert!(!failed.to_string().contains("never-return"));
    let login = route(&s, "POST", "login", json!({})).await.unwrap();
    route(
        &s,
        "POST",
        "login/code",
        json!({"id":login["id"],"code":"fixture-code"}),
    )
    .await
    .unwrap();
    let view = wait_login(&s, "completed").await;
    assert_eq!(view["connected"], true);
    assert!(!view.to_string().contains("never-return"));
    assert_eq!(view["email"], "claude-fixture@example.test");
    let catalog = route(&s, "GET", "models", Value::Null).await.unwrap();
    assert_eq!(catalog["stale"], false);
    assert_eq!(catalog["models"][0]["model"], "sonnet");
    assert!(
        catalog["models"][0]["supportedReasoningEfforts"]
            .as_array()
            .unwrap()
            .iter()
            .any(|effort| effort["reasoningEffort"] == "high")
    );
    route(&s, "DELETE", "connection", json!({})).await.unwrap();
    assert_eq!(
        route(&s, "GET", "connection", Value::Null).await.unwrap()["connected"],
        false
    );
}
fn plan(root: &TempDir, prompt: &str) -> Value {
    json!({"provider":"claude","execution":{"messageId":"original","text":prompt,"attachments":[]},"instructions":"Follow the task scope","inputDirectory":root.path().join("inbox"),"output":root.path().join("result.md"),"cwd":root.path(),"model":"sonnet","reasoning":"high","sandbox":"yolo","writableRoots":[root.path()],"claudeMcps":{"mcpServers":{}},"claudeDeniedTools":[]})
}
fn setup(root: &TempDir) -> Config {
    let c = config(root);
    std::fs::create_dir_all(c.home.join(".claude")).unwrap();
    std::fs::write(c.home.join(".claude/.credentials.json"), "yes").unwrap();
    std::fs::create_dir(root.path().join("inbox")).unwrap();
    c
}
#[tokio::test]
async fn streaming_tools_receipts_resume_and_no_replay_after_completion() {
    let root = TempDir::new().unwrap();
    let c = setup(&root);
    let p = plan(&root, "Inspect the workspace");
    let (tx, mut rx) = mpsc::channel(64);
    claude_process::run(&c, p.clone(), tx, CancellationToken::new())
        .await
        .unwrap();
    let mut events = Vec::new();
    while let Some(e) = rx.recv().await {
        events.push(e);
    }
    assert!(events.iter().any(|e| e["type"] == "thread.started"));
    assert!(events.iter().any(|e| e["type"] == "item.updated"));
    assert!(
        events
            .iter()
            .any(|e| e["item"]["aggregated_output"] == "fixture")
    );
    assert!(
        events
            .iter()
            .any(|e| e["type"] == "chat.delivered" && e["messageId"] == "original")
    );
    let (tx, mut rx) = mpsc::channel(64);
    claude_process::run(&c, p.clone(), tx, CancellationToken::new())
        .await
        .unwrap();
    while rx.recv().await.is_some() {}
    assert_eq!(
        std::fs::read_to_string(c.home.join(".claude/invocations.jsonl"))
            .unwrap()
            .lines()
            .count(),
        1
    );
    let mut next = p;
    next["execution"]["messageId"] = "next".into();
    next["sessionId"] = "70f5e7a1-8d65-4f5f-a545-af6ee8c0e1ab".into();
    let (tx, mut rx) = mpsc::channel(64);
    claude_process::run(&c, next, tx, CancellationToken::new())
        .await
        .unwrap();
    while rx.recv().await.is_some() {}
    let calls = std::fs::read_to_string(c.home.join(".claude/invocations.jsonl")).unwrap();
    assert!(calls.contains("--resume"));
    assert_eq!(calls.lines().count(), 2);
}
#[tokio::test]
async fn reasoning_before_text_keeps_one_message_per_block() {
    let root = TempDir::new().unwrap();
    let c = setup(&root);
    let (tx, mut rx) = mpsc::channel(64);
    claude_process::run(
        &c,
        plan(&root, "Inspect fixture:thinking"),
        tx,
        CancellationToken::new(),
    )
    .await
    .unwrap();
    let mut events = Vec::new();
    while let Some(e) = rx.recv().await {
        events.push(e);
    }
    let ids = |kind: &str| {
        events
            .iter()
            .filter(|e| e["item"]["type"] == kind)
            .map(|e| e["item"]["id"].as_str().unwrap().to_owned())
            .collect::<std::collections::BTreeSet<_>>()
    };
    // Streamed deltas and the per-block assistant events must describe the same items.
    let texts = ids("agent_message");
    let reasoning = ids("reasoning");
    assert_eq!(texts.len(), 1, "{events:#?}");
    assert_eq!(reasoning.len(), 1, "{events:#?}");
    assert!(texts.is_disjoint(&reasoning));
    assert!(events.iter().any(|e| e["type"] == "item.completed"
        && e["item"]["type"] == "agent_message"
        && e["item"]["text"] == "Claude fixture completed"));
}
#[tokio::test]
async fn question_answers_use_control_protocol() {
    let root = TempDir::new().unwrap();
    let c = setup(&root);
    let p = plan(&root, "fixture:question");
    let (tx, mut rx) = mpsc::channel(64);
    let task =
        tokio::spawn(async move { claude_process::run(&c, p, tx, CancellationToken::new()).await });
    tokio::time::timeout(std::time::Duration::from_secs(8),async{while let Some(e)=rx.recv().await{
        if e["type"]=="chat.question" {assert_eq!(e["question"]["id"].as_str().unwrap().len(),64);std::fs::write(root.path().join("inbox/messages.json"),json!([{"id":"answer","questionId":e["question"]["id"],"answers":{"0":["Small change"]}}]).to_string()).unwrap();}
    }}).await.unwrap();
    task.await.unwrap().unwrap();
    let response: Value = serde_json::from_slice(
        &std::fs::read(root.path().join("home/.claude/question-response.json")).unwrap(),
    )
    .unwrap();
    assert_eq!(
        response["response"]["response"]["updatedInput"]["answers"]["Which approach?"],
        "Small change"
    );
}
#[tokio::test]
async fn cancellation_and_provider_errors_do_not_complete_the_turn() {
    for prompt in ["fixture:hang", "fixture:fail", "fixture:background"] {
        let root = TempDir::new().unwrap();
        let c = setup(&root);
        let p = plan(&root, prompt);
        let cancel = CancellationToken::new();
        let stop = cancel.clone();
        let (tx, mut rx) = mpsc::channel(64);
        let task = tokio::spawn(async move { claude_process::run(&c, p, tx, stop).await });
        let timer = cancel.clone();
        tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            timer.cancel();
        });
        while let Some(e) = rx.recv().await {
            assert_ne!(e["type"], "turn.completed");
        }
        assert!(task.await.unwrap().is_err());
        assert!(!root.path().join("result.md").exists());
    }
}
#[test]
fn provider_defaults_and_sandbox_arguments_preserve_boundaries() {
    assert_eq!(claude::provider(&json!({})), "codex");
    assert!(claude::validate_agent(&json!({"provider":"claude","reasoning":"ultra"})).is_err());
    let root = TempDir::new().unwrap();
    let mut p = plan(&root, "test");
    p["sandbox"] = "workspace-write".into();
    let a = claude_process::args(&p);
    assert!(!a.contains(&"--dangerously-skip-permissions".into()));
    assert!(a.iter().any(|s| s.contains("failIfUnavailable")));
    assert!(a.contains(&"--strict-mcp-config".into()));
}

#[tokio::test]
async fn steering_waits_for_both_responses_and_acknowledges_each_message() {
    let root = TempDir::new().unwrap();
    let c = setup(&root);
    let p = plan(&root, "fixture:slow");
    let (tx, mut rx) = mpsc::channel(64);
    let task =
        tokio::spawn(async move { claude_process::run(&c, p, tx, CancellationToken::new()).await });
    let mut delivered = Vec::new();
    let mut responses = 0;
    tokio::time::timeout(std::time::Duration::from_secs(15),async {
        while let Some(e)=rx.recv().await {
            if e["type"]=="chat.delivered" {
                delivered.push(e["messageId"].clone());
                if e["messageId"]=="original" {
                    std::fs::write(root.path().join("inbox/messages.json"),json!([{"id":"steering","text":"fixture:slow additional instruction","attachments":[]}]).to_string()).unwrap();
                }
            }
            if e["type"]=="item.completed" && e["item"]["type"]=="agent_message" {responses+=1;}
            if e["type"]=="turn.completed" {assert_eq!(responses,2);}
        }
    }).await.unwrap();
    task.await.unwrap().unwrap();
    assert_eq!(delivered, vec![json!("original"), json!("steering")]);
}

#[tokio::test]
async fn restored_background_results_cannot_complete_an_undelivered_prompt_or_receipt() {
    let root = TempDir::new().unwrap();
    let c = setup(&root);
    std::fs::write(
        root.path().join("result.claude-receipt.json"),
        json!({"messageId":"original","delivered":[],"text":""}).to_string(),
    )
    .unwrap();
    let events = run_events(&c, plan(&root, "fixture:startup-result")).await;
    assert!(
        events
            .iter()
            .any(|e| e["type"] == "chat.delivered" && e["messageId"] == "original")
    );
    assert_eq!(
        std::fs::read_to_string(root.path().join("result.md")).unwrap(),
        "Actual requested response"
    );
}

async fn run_events(c: &Config, p: Value) -> Vec<Value> {
    let (tx, mut rx) = mpsc::channel(128);
    tokio::time::timeout(
        std::time::Duration::from_secs(8),
        claude_process::run(c, p, tx, CancellationToken::new()),
    )
    .await
    .unwrap()
    .unwrap();
    let mut events = Vec::new();
    while let Some(e) = rx.recv().await {
        events.push(e);
    }
    assert_eq!(
        events
            .iter()
            .filter(|e| e["type"] == "turn.completed")
            .count(),
        1
    );
    events
}

#[tokio::test]
async fn background_work_and_its_followup_finish_before_the_run_completes() {
    let root = TempDir::new().unwrap();
    let c = setup(&root);
    run_events(&c, plan(&root, "fixture:background")).await;
    assert_eq!(
        std::fs::read_to_string(root.path().join("result.md")).unwrap(),
        "Build checked and task finished"
    );
    let receipt: Value = serde_json::from_slice(
        &std::fs::read(root.path().join("result.claude-receipt.json")).unwrap(),
    )
    .unwrap();
    assert_eq!(receipt["text"], "Build checked and task finished");
}

#[tokio::test]
async fn ambient_watchers_do_not_keep_the_run_alive() {
    for prompt in [
        "fixture:background-ambient",
        "fixture:background-ambient-flip",
    ] {
        let root = TempDir::new().unwrap();
        let c = setup(&root);
        run_events(&c, plan(&root, prompt)).await;
    }
}

#[tokio::test]
async fn correlated_result_acknowledges_a_prompt_without_a_user_replay() {
    let root = TempDir::new().unwrap();
    let c = setup(&root);
    let events = run_events(&c, plan(&root, "fixture:result-ack")).await;
    assert_eq!(
        events
            .iter()
            .filter(|e| e["type"] == "chat.delivered" && e["messageId"] == "original")
            .count(),
        1
    );
}

#[tokio::test]
async fn merged_prompts_need_only_one_correlated_result() {
    let root = TempDir::new().unwrap();
    let c = setup(&root);
    std::fs::write(
        root.path().join("inbox/messages.json"),
        json!([{"id":"steering","text":"fixture:batch second prompt","attachments":[]}])
            .to_string(),
    )
    .unwrap();
    let events = run_events(&c, plan(&root, "fixture:batch first prompt")).await;
    assert_eq!(
        events
            .iter()
            .filter(|e| e["type"] == "chat.delivered")
            .count(),
        2
    );
    assert_eq!(
        std::fs::read_to_string(root.path().join("result.md")).unwrap(),
        "Both prompts processed"
    );
}

#[tokio::test]
async fn usage_is_sanitized_cached_and_preserved_on_failure_without_changing_connection() {
    let root = TempDir::new().unwrap();
    let c = config(&root);
    let home = claude::home(&c);
    std::fs::create_dir_all(&home).unwrap();
    std::fs::write(home.join(".credentials.json"), "fixture").unwrap();
    let s = Service::new(c).await.unwrap();
    let first = route(&s, "GET", "connection", Value::Null).await.unwrap();
    assert_eq!(first["connected"], true);
    assert_eq!(first["usage"]["windows"][0]["usedPercent"], 25.0);
    assert_eq!(first["usage"]["windows"][0]["resetsAt"], 1893499200i64);
    assert_eq!(first["usage"]["windows"][2]["label"], "Weekly · Sonnet");
    assert_eq!(first["usage"]["stale"], false);
    assert!(!first.to_string().contains("never-return"));
    assert!(
        !home.join("user-messages.jsonl").exists(),
        "Quota reads must not submit a prompt"
    );
    let (a, b) = tokio::join!(
        route(&s, "GET", "connection", Value::Null),
        route(&s, "GET", "connection", Value::Null)
    );
    assert_eq!(a.unwrap()["usage"], first["usage"]);
    assert_eq!(b.unwrap()["usage"], first["usage"]);
    assert_eq!(
        std::fs::read_to_string(home.join("usage-requests.jsonl"))
            .unwrap()
            .lines()
            .count(),
        1
    );
    assert!(
        std::fs::read_to_string(home.join("usage-requests.jsonl"))
            .unwrap()
            .contains("\"skip_behaviors\":true")
    );

    // A failed refresh keeps dated values, backs off, and does not disconnect.
    let mut old = first["usage"].clone();
    old["attemptedAt"] = 0.into();
    s.store.set("claude-usage", old, None).await.unwrap();
    std::fs::write(home.join("fixture-usage.json"), "{\"fixtureError\":true}").unwrap();
    let failed = route(&s, "GET", "connection", Value::Null).await.unwrap();
    assert_eq!(failed["connected"], true);
    assert_eq!(failed["usage"]["stale"], true);
    assert_eq!(failed["usage"]["windows"], first["usage"]["windows"]);
    assert_eq!(failed["usage"]["checkedAt"], first["usage"]["checkedAt"]);
    assert!(failed["usage"]["error"].is_string());
    assert!(!failed.to_string().contains("never-return"));
    route(&s, "GET", "connection", Value::Null).await.unwrap();
    assert_eq!(
        std::fs::read_to_string(home.join("usage-requests.jsonl"))
            .unwrap()
            .lines()
            .count(),
        2
    );

    // Credential synchronization must block new CLI queries even when overdue.
    let mut old = first["usage"].clone();
    old["attemptedAt"] = 0.into();
    s.store.set("claude-usage", old, None).await.unwrap();
    s.store.write(|db| db.add_run(&json!({"id":"busy-claude","taskId":"fixture","status":"running","createdAt":1,"snapshot":{"agent":{"provider":"claude"}}}), None)).await.unwrap();
    let busy = route(&s, "GET", "connection", Value::Null).await.unwrap();
    assert_eq!(busy["busy"], true);
    assert_eq!(busy["usage"]["stale"], true);
    assert_eq!(busy["usage"]["windows"], first["usage"]["windows"]);
    assert_eq!(
        std::fs::read_to_string(home.join("usage-requests.jsonl"))
            .unwrap()
            .lines()
            .count(),
        2
    );
    s.store
        .patch_run("busy-claude", json!({"status":"succeeded"}))
        .await
        .unwrap();
    std::fs::write(home.join("sync-required"), "run").unwrap();
    let blocked = route(&s, "GET", "connection", Value::Null).await.unwrap();
    assert_eq!(blocked["usage"]["stale"], true);
    assert_eq!(
        std::fs::read_to_string(home.join("usage-requests.jsonl"))
            .unwrap()
            .lines()
            .count(),
        2
    );
    std::fs::remove_file(home.join("sync-required")).unwrap();
    std::fs::remove_file(home.join("fixture-usage.json")).unwrap();
    let recovered = route(&s, "GET", "connection", Value::Null).await.unwrap();
    assert_eq!(recovered["usage"]["stale"], false);
    assert!(recovered["usage"]["error"].is_null());

    route(&s, "DELETE", "connection", Value::Null)
        .await
        .unwrap();
    assert!(s.store.kv("claude-usage").await.unwrap().is_none());
    assert!(route(&s, "GET", "connection", Value::Null).await.unwrap()["usage"].is_null());
}

#[tokio::test]
async fn usage_handles_partial_invalid_and_unavailable_windows_and_reconnect() {
    let root = TempDir::new().unwrap();
    let c = config(&root);
    let home = claude::home(&c);
    std::fs::create_dir_all(&home).unwrap();
    std::fs::write(home.join(".credentials.json"), "fixture").unwrap();
    let s = Service::new(c).await.unwrap();
    let payload = json!({"rate_limits_available":true,"rate_limits":{
        "five_hour":{"utilization":0,"resets_at":null},
        "seven_day":{"utilization":105,"resets_at":"invalid"},
        "seven_day_opus":{"utilization":null},
        "seven_day_sonnet":{"utilization":-1},
        "model_scoped":[{"display_name":"Fable","utilization":42,"resets_at":"2030-01-07T12:00:00Z","secret":"never-return"}],
        "unknown_secret":"never-return"
    }});
    std::fs::write(home.join("fixture-usage.json"), payload.to_string()).unwrap();
    let view = route(&s, "GET", "connection", Value::Null).await.unwrap();
    let windows = view["usage"]["windows"].as_array().unwrap();
    assert_eq!(windows.len(), 3);
    assert_eq!(windows[0]["usedPercent"], 0.0);
    assert_eq!(windows[1]["usedPercent"], 105.0);
    assert!(windows[1]["resetsAt"].is_null());
    assert_eq!(windows[2]["label"], "Weekly · Fable");
    assert!(!view.to_string().contains("never-return"));

    for payload in [
        json!({"rate_limits_available":false,"rate_limits":null}),
        json!({"rate_limits_available":true,"rate_limits":{"five_hour":{"utilization":"25"}}}),
    ] {
        s.store.delete("claude-usage").await.unwrap();
        std::fs::write(home.join("fixture-usage.json"), payload.to_string()).unwrap();
        let view = route(&s, "GET", "connection", Value::Null).await.unwrap();
        assert_eq!(view["connected"], true);
        assert_eq!(view["usage"]["windows"], json!([]));
        assert!(view["usage"]["checkedAt"].is_null());
        assert_eq!(view["usage"]["stale"], true);
    }
    route(&s, "POST", "login", json!({})).await.unwrap();
    assert!(s.store.kv("claude-usage").await.unwrap().is_none());
    let pending = route(&s, "GET", "connection", Value::Null).await.unwrap();
    assert_eq!(pending["usage"]["windows"], json!([]));
    route(&s, "DELETE", "login", json!({})).await.unwrap();
    wait_login(&s, "cancelled").await;
}
