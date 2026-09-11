use leo_agent_manager::{
    accounts::{blocked, recovered, remaining},
    config::Config,
    rpc::Session,
    service::Service,
};
use serde_json::json;
use tempfile::TempDir;
fn config(root: &TempDir) -> Config {
    Config {
        data_dir: root.path().join("data"),
        home: root.path().join("home"),
        workspace_roots: vec![root.path().to_owned()],
        public_url: "http://localhost:4310".into(),
        host: "127.0.0.1".into(),
        port: 0,
        setup_token: "fixture".into(),
        codex_bin: std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../tests/fixtures/codex.mjs")
            .to_string_lossy()
            .into_owned(),
        gh_bin: "gh".into(),
        concurrency: 1,
        logger: false,
        worker_enabled: false,
        runner_url: String::new(),
    }
}
#[tokio::test]
async fn native_rpc_runs_a_turn_and_resumes_its_persisted_thread() {
    let root = TempDir::new().unwrap();
    let config = config(&root);
    let home = root.path().join("codex");
    std::fs::create_dir_all(&home).unwrap();
    let mut session = Session::codex(&config, &home, &[], Some(root.path()))
        .await
        .unwrap();
    let started = session
        .request("thread/start", json!({"cwd":root.path()}))
        .await
        .unwrap();
    assert_eq!(started["thread"]["id"], "fixture-chat");
    session.request("turn/start", json!({"threadId":"fixture-chat","input":[{"type":"text","text":"Check the workspace"}],"clientUserMessageId":"message-1"})).await.unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        loop {
            let incoming = session.incoming.recv().await.unwrap();
            if incoming.method == "turn/completed" {
                break;
            }
        }
    })
    .await
    .unwrap();
    session.close().await;
    let mut resumed = Session::codex(&config, &home, &[], Some(root.path()))
        .await
        .unwrap();
    resumed
        .request("thread/resume", json!({"threadId":"fixture-chat"}))
        .await
        .unwrap();
    let turns = resumed
        .request(
            "thread/turns/list",
            json!({"threadId":"fixture-chat","limit":100}),
        )
        .await
        .unwrap();
    assert_eq!(turns["data"][0]["status"], "completed");
    assert_eq!(turns["data"][0]["items"][0]["clientId"], "message-1");
    resumed.close().await;
}
#[tokio::test]
async fn device_login_verifies_identity_then_leases_private_credentials() {
    let root = TempDir::new().unwrap();
    let service = Service::new(config(&root)).await.unwrap();
    let flow = service.account_login("Test account", None).await.unwrap();
    let id = flow["accountId"].as_str().unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(10), async {
        loop {
            let login = service.account_login.lock().await.clone().unwrap();
            if !login.busy() {
                assert_eq!(login.view()["state"], "complete", "{:?}", login.view());
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    let accounts = service.accounts.list(&service).await.unwrap();
    assert_eq!(accounts.len(), 1);
    assert_eq!(accounts[0]["remainingPercent"], 60.0);
    assert!(accounts[0].get("identity").is_none());
    let lease = service
        .accounts
        .acquire(&service, "11111111-1111-4111-8111-111111111111", "")
        .await
        .unwrap()
        .unwrap();
    assert_eq!(lease.account_id, id);
    assert!(lease.home.join("auth.json").exists());
    assert!(service.accounts.remove(&service, id).await.is_err());
    assert!(
        service
            .accounts
            .acquire(&service, "22222222-2222-4222-8222-222222222222", "")
            .await
            .is_err()
    );
    service.accounts.release(&service, &lease).await.unwrap();
    assert!(!lease.home.join("auth.json").exists());
    service.accounts.remove(&service, id).await.unwrap();
    assert!(service.accounts.list(&service).await.unwrap().is_empty());
}
#[test]
fn usage_requires_observed_capacity_and_respects_model_limits() {
    let before = json!({"ordinaryUsageAllowed":true,"rateLimits":{"primary":{"usedPercent":100,"resetsAt":1},"secondary":{"usedPercent":40}},"rateLimitsByLimitId":{"model":{"limitId":"special","normalModelSlug":"model-x","primary":{"usedPercent":95}}}});
    assert_eq!(remaining(&before, ""), Some(0.));
    let mut after = before.clone();
    after["rateLimits"]["primary"]["resetsAt"] = 9999999999_i64.into();
    assert!(!recovered(&before, &after, ""));
    after["rateLimits"]["primary"]["usedPercent"] = 0.into();
    assert!(recovered(&before, &after, ""));
    assert_eq!(remaining(&after, ""), Some(60.));
    assert_eq!(remaining(&after, "model-x"), Some(5.));
    after["rateLimitsByLimitId"]["model"]["spendControlReached"] = true.into();
    assert!(blocked(&after, "model-x"));
    assert!(!blocked(&after, ""));
}
