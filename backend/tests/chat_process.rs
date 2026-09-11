use leo_agent_manager::{chat_process, config::Config, skills::atomic_write};
use serde_json::{Value, json};
use tempfile::TempDir;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;
fn config(root: &TempDir) -> Config {
    Config {
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
        worker_enabled: false,
        runner_url: String::new(),
    }
}
fn plan(root: &TempDir, text: &str) -> Value {
    json!({"execution":{"messageId":"original-message","text":text,"recovery":false},"instructions":"Test instructions","inputDirectory":root.path().join("inbox"),"output":root.path().join("result.md"),"cwd":root.path(),"model":"","reasoning":"medium","sandbox":"yolo","writableRoots":[],"args":[]})
}
#[tokio::test]
async fn native_in_flight_question_accepts_answer_and_preserves_receipt() {
    let root = TempDir::new().unwrap();
    std::fs::create_dir(root.path().join("inbox")).unwrap();
    std::fs::create_dir(root.path().join("codex")).unwrap();
    let config = config(&root);
    let plan = plan(&root, "fixture:question");
    let (tx, mut rx) = mpsc::channel(64);
    let home = root.path().join("codex");
    let task = tokio::spawn(async move {
        chat_process::run(&config, &home, plan, tx, CancellationToken::new()).await
    });
    let mut answer_receipts = 0;
    let mut completed = false;
    let mut question_id = String::new();
    tokio::time::timeout(std::time::Duration::from_secs(10), async {
        while let Some(event) = rx.recv().await {
            match event["type"].as_str().unwrap_or("") {
                "chat.question" => {
                    question_id = event["question"]["id"].as_str().unwrap().to_owned();
                    assert_eq!(event["question"]["blocking"], false);
                    atomic_write(&root.path().join("inbox/messages.json"), json!([{"id":"answer-message","questionId":question_id,"answers":{"direction":["Gradual rollout"]},"text":"My answer"}]).to_string().as_bytes()).await.unwrap();
                }
                "chat.delivered" if event["messageId"] == "answer-message" => answer_receipts += 1,
                "turn.completed" => completed = true,
                _ => {}
            }
        }
    })
    .await
    .unwrap();
    task.await.unwrap().unwrap();
    assert_eq!(answer_receipts, 1);
    assert!(completed);
    assert_eq!(question_id.len(), 64);
    assert!(root.path().join("result.md").exists());
}
#[tokio::test]
async fn replay_of_completed_turn_does_not_submit_the_instruction_again() {
    let root = TempDir::new().unwrap();
    std::fs::create_dir(root.path().join("inbox")).unwrap();
    std::fs::create_dir(root.path().join("codex")).unwrap();
    let config = config(&root);
    let home = root.path().join("codex");
    let mut plan = plan(&root, "One instruction");
    for resume in [false, true] {
        let (tx, mut rx) = mpsc::channel(64);
        let output = tokio::spawn(async move { while rx.recv().await.is_some() {} });
        if resume {
            plan["sessionId"] = "fixture-chat".into();
            plan["execution"]["recovery"] = true.into();
        }
        chat_process::run(&config, &home, plan.clone(), tx, CancellationToken::new())
            .await
            .unwrap();
        output.await.unwrap();
    }
    let thread: Value =
        serde_json::from_slice(&std::fs::read(home.join("fixture-conversation.json")).unwrap())
            .unwrap();
    assert_eq!(thread["turns"].as_array().unwrap().len(), 1);
}
