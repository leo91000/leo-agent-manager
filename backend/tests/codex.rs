use leo_agent_manager::{
    accounts::{blocked, recovered, remaining},
    config::Config,
    connections::DeviceLogin,
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

async fn device_fixture(mode: &str) -> (TempDir, DeviceLogin) {
    let root = TempDir::new().unwrap();
    let config = config(&root);
    let home = config.home.join(".codex");
    std::fs::create_dir_all(&home).unwrap();
    std::fs::write(
        home.join("fixture-login.json"),
        json!({"mode":mode}).to_string(),
    )
    .unwrap();
    let login = DeviceLogin::start(&config, "codex", &config.home).unwrap();
    (root, login)
}

async fn wait_login(login: &DeviceLogin) {
    tokio::time::timeout(std::time::Duration::from_secs(5), login.wait())
        .await
        .unwrap();
}

#[tokio::test]
async fn structured_login_displays_the_code_and_cancels_the_matching_attempt() {
    let (root, login) = device_fixture("hold").await;
    let mut updates = login.flow.subscribe();
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        while updates.borrow()["phase"] != "authorizing" {
            updates.changed().await.unwrap();
        }
    })
    .await
    .unwrap();
    assert_eq!(login.view()["code"], "ABCD-12345");
    assert_eq!(login.view()["url"], "https://auth.openai.com/codex/device");
    assert_eq!(login.view()["state"], "pending");
    assert!(login.view()["expiresAt"].as_i64().unwrap() > leo_agent_manager::config::now());
    login.cancel().await;
    assert_eq!(login.view()["state"], "failed");
    assert_eq!(login.view()["code"], "");
    assert_eq!(
        std::fs::read_to_string(root.path().join("home/.codex/fixture-login-cancelled")).unwrap(),
        "fixture-device-login"
    );
    assert!(!root.path().join("home/.codex/auth.json").exists());
}

#[tokio::test]
async fn structured_login_handles_completion_before_the_start_reply() {
    let (root, login) = device_fixture("immediate").await;
    wait_login(&login).await;
    assert_eq!(login.view()["state"], "complete");
    assert_eq!(login.view()["code"], "");
    assert!(root.path().join("home/.codex/auth.json").exists());
}

#[tokio::test]
async fn structured_login_reports_errors_without_exposing_provider_details() {
    for mode in ["failure", "unsupported", "disconnect"] {
        let (_root, login) = device_fixture(mode).await;
        wait_login(&login).await;
        let flow = login.view();
        assert_eq!(flow["state"], "failed", "{mode}");
        assert_eq!(flow["code"], "");
        assert!(!flow["error"].as_str().unwrap().is_empty());
        assert!(!flow.to_string().contains("synthetic secret"));
        if mode == "unsupported" {
            assert!(flow["error"].as_str().unwrap().contains("Update Codex"));
        }
    }
}

#[tokio::test]
async fn account_sign_in_verifies_identity_captures_credentials_and_cleans_up() {
    let root = TempDir::new().unwrap();
    let service = Service::new(config(&root)).await.unwrap();
    let flow = service.account_login("Personal", None).await.unwrap();
    let id = flow["accountId"].as_str().unwrap();
    let login = service.account_login.lock().await.clone().unwrap();
    let mut complete = login.complete.clone();
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        while !*complete.borrow() {
            complete.changed().await.unwrap();
        }
    })
    .await
    .unwrap();
    assert_eq!(login.view()["state"], "complete");
    assert_eq!(
        service.accounts.get(&service, id).await.unwrap()["state"],
        "ready"
    );
    assert!(
        service
            .vault
            .get(&format!("codex-account:{id}"))
            .await
            .unwrap()
            .is_some()
    );
    assert!(!login.home.exists());
    assert!(service.accounts.connecting.lock().await.is_none());
}
#[tokio::test]
async fn models_are_paginated_cached_and_keep_last_known_options_when_codex_is_down() {
    let root = TempDir::new().unwrap();
    let mut configuration = config(&root);
    let executable = root.path().join("codex");
    std::os::unix::fs::symlink(&configuration.codex_bin, &executable).unwrap();
    configuration.codex_bin = executable.to_string_lossy().into_owned();
    let service = Service::new(configuration).await.unwrap();
    let catalog = service.models.list(&service).await.unwrap();
    assert_eq!(catalog["models"].as_array().unwrap().len(), 3);
    assert_eq!(catalog["stale"], false);
    assert!(
        catalog["models"]
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m["hidden"] == true)
    );
    assert!(
        catalog["models"]
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m["supportedReasoningEfforts"][1]["reasoningEffort"] == "ultra")
    );
    std::fs::remove_file(executable).unwrap();
    assert_eq!(service.models.list(&service).await.unwrap(), catalog);
    let mut cache = service.store.kv("codex-models:").await.unwrap().unwrap();
    cache["checkedAt"] = 1.into();
    cache["attemptedAt"] = 1.into();
    service
        .store
        .set("codex-models:", cache, None)
        .await
        .unwrap();
    let stale = service.models.list(&service).await.unwrap();
    assert_eq!(stale["models"], catalog["models"]);
    assert_eq!(stale["stale"], true);
    assert!(!stale["error"].as_str().unwrap().is_empty());
}

#[tokio::test]
async fn model_catalog_respects_enabled_accounts_and_routes_to_an_account_with_the_model() {
    let root = TempDir::new().unwrap();
    let service = Service::new(config(&root)).await.unwrap();
    service.accounts.initialize(&service).await.unwrap();
    let mut ids = Vec::new();
    for name in ["fast-only", "all-models"] {
        let account = service.accounts.new_account(&service, name).await.unwrap();
        let id = account["id"].as_str().unwrap().to_owned();
        service
            .vault
            .set(
                &format!("codex-account:{id}"),
                &json!({"tokens":{"access_token":"synthetic","account_id":name}}),
            )
            .await
            .unwrap();
        service.accounts.refresh(&service, &id).await.unwrap();
        ids.push(id);
    }
    let catalog = service.models.list(&service).await.unwrap();
    assert_eq!(catalog["models"].as_array().unwrap().len(), 3);
    for id in &ids {
        assert!(
            !service
                .config
                .data_dir
                .join("codex-model-discovery")
                .join(id)
                .exists()
        );
    }
    let lease = service
        .accounts
        .acquire(
            &service,
            "11111111-1111-4111-8111-111111111111",
            "fixture-deep",
        )
        .await
        .unwrap()
        .unwrap();
    assert_eq!(lease.account_id, ids[1]);
    service.accounts.release(&service, &lease).await.unwrap();
    service
        .accounts
        .update(
            &service,
            &ids[1],
            json!({"name":"all-models","enabled":false}),
        )
        .await
        .unwrap();
    let catalog = service.models.list(&service).await.unwrap();
    assert!(
        !catalog["models"]
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m["model"] == "fixture-deep")
    );
    service.accounts.remove(&service, &ids[0]).await.unwrap();
    assert!(
        service.models.list(&service).await.unwrap()["models"]
            .as_array()
            .unwrap()
            .is_empty()
    );
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
async fn queued_reasoning_is_idempotent_editable_and_part_of_the_run_snapshot() {
    use leo_agent_manager::config::id;
    let root = TempDir::new().unwrap();
    std::fs::create_dir(root.path().join("home")).unwrap();
    let service = Service::new(config(&root)).await.unwrap();
    let chat = service.chat_create(json!({})).await.unwrap();
    let chat_id = chat["id"].as_str().unwrap();
    let message_id = id();
    let mut message =
        json!({"id":message_id,"text":"Review this","model":"fixture-deep","reasoning":"ultra"});
    let first = service.chat_send(chat_id, message.clone()).await.unwrap();
    assert_eq!(
        service.chat_send(chat_id, message.clone()).await.unwrap(),
        first
    );
    message["reasoning"] = "medium".into();
    assert_eq!(
        service
            .chat_send(chat_id, message.clone())
            .await
            .unwrap_err()
            .status,
        409
    );
    service
        .chat_edit(chat_id, &message_id, Some(message))
        .await
        .unwrap();
    service.chat_tick(&Default::default()).await.unwrap();
    let detail = service.chat_detail(chat_id).await.unwrap();
    assert!(
        detail["run"].is_object(),
        "{:?}",
        service
            .store
            .kv(&format!("chat-error:{chat_id}"))
            .await
            .unwrap()
    );
    assert_eq!(detail["run"]["snapshot"]["agent"]["reasoning"], "medium");
    assert_eq!(detail["run"]["snapshot"]["agent"]["model"], "fixture-deep");
    let steer = json!({"id":id(),"text":"More detail","mode":"steer","reasoning":"ultra"});
    assert_eq!(
        service.chat_send(chat_id, steer).await.unwrap_err().status,
        409
    );
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
