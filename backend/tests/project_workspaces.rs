use leo_agent_manager::{
    config::{Config, MAIN_AGENT_ID, id},
    execution, project_workspaces,
    service::Service,
    validation::text,
};
use serde_json::{Value, json};
use std::{path::Path, sync::Arc};
use tempfile::TempDir;

async fn fixture() -> (TempDir, Arc<Service>, Vec<Value>) {
    let root = TempDir::new().unwrap();
    let home = root.path().join("home");
    std::fs::create_dir_all(home.join(".codex")).unwrap();
    std::fs::write(home.join(".codex/leo-managed-auth"), "1").unwrap();
    let s = Service::new(Config {
        data_dir: root.path().join("data"),
        home,
        workspace_roots: vec![root.path().to_owned()],
        public_url: "http://localhost:4310".into(),
        host: "127.0.0.1".into(),
        port: 0,
        setup_token: "fixture".into(),
        codex_bin: "codex".into(),
        gh_bin: "gh".into(),
        concurrency: 2,
        logger: false,
        worker_enabled: false,
        runner_url: "http://runner:4311".into(),
    })
    .await
    .unwrap();
    let mut projects = Vec::new();
    for name in ["First", "Second"] {
        let path = root.path().join(name);
        std::fs::create_dir(&path).unwrap();
        std::fs::write(path.join("hello"), name).unwrap();
        projects.push(
            s.project(json!({"name":name,"path":path}), None)
                .await
                .unwrap(),
        );
    }
    (root, s, projects)
}

async fn run(s: &Service, project: Value, agent: &str) -> Value {
    let task = s
        .task(
            json!({"name":"Probe","agentId":agent,"projectId":project,"prompt":"Hello"}),
            None,
        )
        .await
        .unwrap();
    s.enqueue(text(&task, "id"), "manual", None).await.unwrap()
}

#[tokio::test]
async fn empty_start_does_not_touch_unselected_repositories_and_selected_start_loads_only_one() {
    let (_root, s, projects) = fixture().await;
    let mut empty = run(&s, Value::Null, MAIN_AGENT_ID).await;
    // An unavailable, unneeded repository must not prevent a simple chat starting.
    std::fs::remove_dir_all(text(&projects[1], "path")).unwrap();
    let prepared = execution::prepare(&empty, &s.config, None, None, None)
        .await
        .unwrap();
    assert_eq!(prepared["workspaces"], json!([]));
    assert_eq!(
        std::fs::read_dir(text(&prepared, "cwd")).unwrap().count(),
        0
    );
    empty["isolated"] = true.into();
    let prompt = leo_agent_manager::run_output::prompt(&empty, true);
    assert!(prompt.contains("open_project"));
    assert!(
        !prompt.contains(text(&projects[0], "path")),
        "host paths must not be advertised as guest workspaces"
    );
    std::fs::create_dir(text(&projects[1], "path")).unwrap();
    let selected = run(&s, projects[0]["id"].clone(), MAIN_AGENT_ID).await;
    let prepared = execution::prepare(&selected, &s.config, None, None, None)
        .await
        .unwrap();
    assert_eq!(prepared["workspaces"].as_array().unwrap().len(), 1);
    assert_eq!(prepared["workspaces"][0]["projectId"], projects[0]["id"]);
    assert_eq!(project_workspaces::catalog(&selected).len(), 2);
    let plan = leo_agent_manager::run_output::chat_plan(
        &selected,
        &prepared,
        Path::new("/tmp"),
        &json!({"args":[]}),
        None,
    );
    assert!(
        plan["writableRoots"]
            .as_array()
            .unwrap()
            .contains(&prepared["projectRoot"])
    );
}

#[tokio::test]
async fn scoped_access_rejects_other_projects_expired_grants_and_changed_permissions() {
    let (_root, s, projects) = fixture().await;
    let agent = s.agent(json!({"name":"Restricted","access":{"projects":[projects[0]["id"]],"skills":[],"mcps":[],"github":false,"sandbox":"read-only"}}),None).await.unwrap();
    let run = run(&s, Value::Null, text(&agent, "id")).await;
    s.store
        .patch_run(text(&run, "id"), json!({"status":"running"}))
        .await
        .unwrap();
    let config = s.mcps.run_configuration(&s, &run).await.unwrap();
    assert!(config["args"].to_string().contains("leo_workspace"));
    let token = text(&config["env"], "LEO_MCP_RUN_TOKEN");
    assert_eq!(
        s.projects
            .open(&s, token, text(&projects[1], "id"))
            .await
            .unwrap_err()
            .status,
        403
    );
    let mut changed = agent.clone();
    changed["access"]["projects"] = json!([]);
    s.store.put("agents", changed).await.unwrap();
    assert_eq!(
        project_workspaces::authorize(&s, token)
            .await
            .unwrap_err()
            .status,
        403
    );
    s.mcps.revoke_run(&s, text(&run, "id")).await.unwrap();
    assert_eq!(
        project_workspaces::authorize(&s, token)
            .await
            .unwrap_err()
            .status,
        401
    );
}

#[tokio::test]
async fn open_imports_once_reuses_seed_and_restores_loaded_catalog_without_checkpoint_races() {
    let (_root, mut service, projects) = fixture().await;
    let calls = Arc::new(tokio::sync::Mutex::new(Vec::<Value>::new()));
    let requests = calls.clone();
    let router = axum::Router::new().fallback(axum::routing::post(
        move |axum::Json(value): axum::Json<Value>| {
            let requests = requests.clone();
            async move {
                let mut requests = requests.lock().await;
                requests.push(value);
                axum::Json(json!({"ok":true,"reused":requests.len()>1}))
            }
        },
    ));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    Arc::get_mut(&mut service).unwrap().config.runner_url =
        format!("http://{}", listener.local_addr().unwrap());
    let server = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    let s = &service;
    let run = run(s, Value::Null, MAIN_AGENT_ID).await;
    let prepared = execution::prepare(&run, &s.config, None, None, None)
        .await
        .unwrap();
    s.store
        .patch_run(
            text(&run, "id"),
            json!({"status":"running","workspaces":[]}),
        )
        .await
        .unwrap();
    s.store
        .set(
            &format!("run-checkpoint:{}", text(&run, "id")),
            json!({"runnerId":id(),"prepared":prepared}),
            None,
        )
        .await
        .unwrap();
    let config = s.mcps.run_configuration(s, &run).await.unwrap();
    let token = text(&config["env"], "LEO_MCP_RUN_TOKEN");
    let project_id = text(&projects[0], "id");
    let (a, b) = tokio::join!(
        s.projects.open(s, token, project_id),
        s.projects.open(s, token, project_id)
    );
    assert!(a.is_ok() && b.is_ok());
    let a = a.unwrap();
    assert_eq!(a["path"], b.unwrap()["path"]);
    std::fs::write(
        Path::new(text(&projects[0], "path")).join("hello"),
        "changed upstream",
    )
    .unwrap();
    let again = s.projects.open(s, token, project_id).await.unwrap();
    assert_eq!(again["reused"], true);
    assert_eq!(
        std::fs::read_to_string(Path::new(text(&again, "path")).join("hello")).unwrap(),
        "First"
    );
    let saved = s.store.run(text(&run, "id")).await.unwrap();
    assert_eq!(saved["workspaces"].as_array().unwrap().len(), 1);
    let restored = execution::restore(&saved, prepared, &s.config, None)
        .await
        .unwrap();
    assert_eq!(restored["workspaces"], saved["workspaces"]);
    let mut legacy = restored.clone();
    legacy.as_object_mut().unwrap().remove("projectRoot");
    let upgraded = execution::restore(&saved, legacy, &s.config, None)
        .await
        .unwrap();
    assert_eq!(upgraded["projectRoot"], restored["projectRoot"]);
    assert_eq!(upgraded["workspaces"], restored["workspaces"]);
    assert_eq!(calls.lock().await.len(), 3);
    server.abort();
}

#[tokio::test]
async fn delivered_message_retains_its_original_submission_timestamp() {
    let (_root, s, _) = fixture().await;
    let chat = s
        .chat_create(json!({"agentId":MAIN_AGENT_ID}))
        .await
        .unwrap();
    let run = run(&s, Value::Null, MAIN_AGENT_ID).await;
    let message = s
        .chat_send(text(&chat, "id"), json!({"id":id(),"text":"Hello"}))
        .await
        .unwrap();
    let mut chat = chat;
    chat["runId"] = run["id"].clone();
    s.store.put("chats", chat).await.unwrap();
    s.chat_acknowledge(text(&run, "id"), text(&message, "id"))
        .await
        .unwrap();
    let run_id = text(&run, "id").to_owned();
    let events = s
        .store
        .read(move |db| db.events(&run_id, 0, 100))
        .await
        .unwrap();
    let event = events.iter().find(|e| e["type"] == "chat.user").unwrap();
    assert_eq!(event["payload"]["createdAt"], message["createdAt"]);
}

#[tokio::test]
async fn builtin_mcp_endpoint_exposes_scoped_workspace_tools_and_checks_the_run_grant() {
    let (_root, mut s, _) = fixture().await;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    Arc::get_mut(&mut s).unwrap().config.public_url = url.clone();
    let router = leo_agent_manager::http::router(s.clone()).await.unwrap();
    let server = tokio::spawn(async move {
        axum::serve(
            listener,
            router.into_make_service_with_connect_info::<std::net::SocketAddr>(),
        )
        .await
        .unwrap();
    });
    let run = run(&s, Value::Null, MAIN_AGENT_ID).await;
    s.store
        .patch_run(text(&run, "id"), json!({"status":"running"}))
        .await
        .unwrap();
    let config = s.mcps.run_configuration(&s, &run).await.unwrap();
    let token = text(&config["env"], "LEO_MCP_RUN_TOKEN");
    let client = reqwest::Client::new();
    let endpoint = format!("{url}/mcp-workspace");
    for (method, params) in [
        ("initialize", json!({"protocolVersion":"2025-11-25"})),
        ("tools/list", json!({})),
    ] {
        let response = client
            .post(&endpoint)
            .bearer_auth(token)
            .json(&json!({"jsonrpc":"2.0","id":1,"method":method,"params":params}))
            .send()
            .await
            .unwrap();
        assert!(response.status().is_success());
        assert_eq!(response.headers()["cache-control"], "no-store");
        let value: Value = response.json().await.unwrap();
        assert!(value["error"].is_null(), "{value}");
        if method == "tools/list" {
            assert_eq!(value["result"]["tools"].as_array().unwrap().len(), 2);
            assert_eq!(value["result"]["tools"][1]["name"], "publish_artifact");
            assert_eq!(value["result"]["tools"][0]["name"], "open_project");
        }
    }
    s.mcps.revoke_run(&s, text(&run, "id")).await.unwrap();
    let response = client
        .post(endpoint)
        .bearer_auth(token)
        .json(&json!({"jsonrpc":"2.0","id":2,"method":"tools/list"}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 401);
    server.abort();
}

#[tokio::test]
async fn seed_cleanup_does_not_follow_repository_symlinks_outside_its_private_copy() {
    let (root, s, projects) = fixture().await;
    let run = run(&s, Value::Null, MAIN_AGENT_ID).await;
    let prepared = execution::prepare(&run, &s.config, None, None, None)
        .await
        .unwrap();
    let outside = root.path().join("outside");
    std::fs::create_dir_all(outside.join("skills")).unwrap();
    std::fs::write(outside.join("skills/keep"), "host file").unwrap();
    let source = Path::new(text(&projects[0], "path"));
    std::fs::remove_dir_all(source.join(".agents")).unwrap();
    std::os::unix::fs::symlink(&outside, source.join(".agents")).unwrap();
    std::os::unix::fs::symlink(&outside, source.join(".codex")).unwrap();
    let entry = execution::project_seed(
        &run,
        &projects[0],
        &s.config,
        Path::new(text(&prepared, "projectRoot")),
    )
    .await
    .unwrap();
    assert!(outside.join("skills/keep").exists());
    assert!(!Path::new(text(&entry, "path")).join(".agents").exists());
    assert!(!Path::new(text(&entry, "path")).join(".codex").exists());
}
