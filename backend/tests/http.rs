use axum::{
    body::{Body, to_bytes},
    http::Request,
};
use leo_agent_manager::{config::Config, http::router, service::Service};
use serde_json::{Value, json};
use tempfile::TempDir;
use tower::ServiceExt;
async fn app() -> (TempDir, axum::Router) {
    let root = TempDir::new().unwrap();
    let config = Config {
        data_dir: root.path().join("data"),
        home: root.path().join("home"),
        workspace_roots: vec![root.path().to_owned()],
        public_url: "http://localhost:4310".into(),
        host: "127.0.0.1".into(),
        port: 0,
        setup_token: "test-setup".into(),
        codex_bin: "codex".into(),
        gh_bin: "gh".into(),
        concurrency: 1,
        logger: false,
        worker_enabled: false,
        runner_url: String::new(),
    };
    let app = router(Service::new(config).await.unwrap()).await.unwrap();
    (root, app)
}
fn request(method: &str, path: &str, body: Value) -> axum::http::request::Builder {
    let _ = body;
    Request::builder()
        .method(method)
        .uri(path)
        .header("host", "localhost:4310")
        .header("content-type", "application/json")
}
#[tokio::test]
async fn http_authentication_csrf_host_origin_and_cookie_contracts() {
    let (_root, app) = app().await;
    let response = app
        .clone()
        .oneshot(
            request("GET", "/api/projects", Value::Null)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), 401);
    assert_eq!(response.headers()["cache-control"], "no-store");
    assert_eq!(response.headers()["x-frame-options"], "DENY");
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/session")
                .header("host", "attacker.example")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), 403);
    let response = app
        .clone()
        .oneshot(
            request("POST", "/api/setup", Value::Null)
                .header("origin", "https://attacker.example")
                .body(Body::from(
                    json!({"setupToken":"test-setup","password":"password-long-enough"})
                        .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), 403);
    let response = app
        .clone()
        .oneshot(
            request("POST", "/api/setup", Value::Null)
                .body(Body::from(
                    json!({"setupToken":"test-setup","password":"password-long-enough"})
                        .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    let cookie = response.headers()["set-cookie"]
        .to_str()
        .unwrap()
        .to_owned();
    assert!(cookie.contains("HttpOnly"));
    assert!(cookie.contains("SameSite=Lax"));
    let cookie = cookie.split(';').next().unwrap();
    let body: Value =
        serde_json::from_slice(&to_bytes(response.into_body(), 10000).await.unwrap()).unwrap();
    let csrf = body["csrf"].as_str().unwrap();
    let response = app
        .clone()
        .oneshot(
            request("POST", "/api/chats", Value::Null)
                .header("cookie", cookie)
                .body(Body::from("{}"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), 403);
    let response = app
        .clone()
        .oneshot(
            request("POST", "/api/chats", Value::Null)
                .header("cookie", cookie)
                .header("x-csrf-token", csrf)
                .body(Body::from("{}"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    let response = app
        .clone()
        .oneshot(
            request("GET", "/api/chats", Value::Null)
                .header("cookie", cookie)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    let chats: Value =
        serde_json::from_slice(&to_bytes(response.into_body(), 10000).await.unwrap()).unwrap();
    assert_eq!(chats.as_array().unwrap().len(), 1);
    let response = app
        .clone()
        .oneshot(
            request("POST", "/api/logout", Value::Null)
                .header("cookie", cookie)
                .header("x-csrf-token", csrf)
                .body(Body::from("{}"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    let response = app
        .oneshot(
            request("GET", "/api/chats", Value::Null)
                .header("cookie", cookie)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), 401);
}
#[tokio::test]
async fn login_limits_ignore_forged_forwarded_ips() {
    let (_root, app) = app().await;
    for n in 0..11 {
        let response = app
            .clone()
            .oneshot(
                request("POST", "/api/login", Value::Null)
                    .header("x-forwarded-for", format!("192.0.2.{n}"))
                    .body(Body::from("{\"password\":\"test\"}"))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), if n < 10 { 401 } else { 429 });
    }
}
