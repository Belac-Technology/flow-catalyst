//! API Endpoint Tests
//!
//! Tests for:
//! - Health endpoints (basic, liveness, readiness)
//! - Monitoring endpoints
//! - Warning management
//! - Pool configuration updates
//! - Message publishing

use std::sync::Arc;
use axum::{
    body::Body,
    http::{Request, StatusCode, Method},
};
use tower::ServiceExt;
use http_body_util::BodyExt;
use async_trait::async_trait;

use fc_common::{Message, MediationType, MediationOutcome, PoolConfig, RouterConfig};
use fc_queue::QueuePublisher;
use fc_router::{
    QueueManager, WarningService, HealthService, Mediator,
    WarningServiceConfig, HealthServiceConfig,
};

use fc_api::create_router;

/// Mock publisher for testing
struct MockPublisher {
    published: parking_lot::Mutex<Vec<Message>>,
}

impl MockPublisher {
    fn new() -> Self {
        Self {
            published: parking_lot::Mutex::new(Vec::new()),
        }
    }

    fn published_count(&self) -> usize {
        self.published.lock().len()
    }
}

#[async_trait]
impl QueuePublisher for MockPublisher {
    fn identifier(&self) -> &str {
        "mock-publisher"
    }

    async fn publish(&self, message: Message) -> fc_queue::Result<String> {
        let id = message.id.clone();
        self.published.lock().push(message);
        Ok(id)
    }

    async fn publish_batch(&self, messages: Vec<Message>) -> fc_queue::Result<Vec<String>> {
        let mut ids = Vec::new();
        for msg in messages {
            ids.push(msg.id.clone());
            self.published.lock().push(msg);
        }
        Ok(ids)
    }
}

/// Mock mediator for testing
struct MockMediator;

#[async_trait]
impl Mediator for MockMediator {
    async fn mediate(&self, _message: &Message) -> MediationOutcome {
        MediationOutcome::success()
    }
}

async fn create_test_app() -> (axum::Router, Arc<MockPublisher>, Arc<QueueManager>) {
    let publisher = Arc::new(MockPublisher::new());
    let mediator = Arc::new(MockMediator);
    let queue_manager = Arc::new(QueueManager::new(mediator));
    let warning_service = Arc::new(WarningService::new(WarningServiceConfig::default()));
    let health_service = Arc::new(HealthService::new(
        HealthServiceConfig::default(),
        warning_service.clone(),
    ));

    // Apply a default config
    let config = RouterConfig {
        processing_pools: vec![PoolConfig {
            code: "DEFAULT".to_string(),
            concurrency: 10,
            rate_limit_per_minute: None,
        }],
        queues: vec![],
    };
    queue_manager.apply_config(config).await.unwrap();

    let app = create_router(
        publisher.clone(),
        queue_manager.clone(),
        warning_service,
        health_service,
    );

    (app, publisher, queue_manager)
}

async fn get_body_string(body: Body) -> String {
    let bytes = body.collect().await.unwrap().to_bytes();
    String::from_utf8(bytes.to_vec()).unwrap()
}

// ============================================================================
// Health Endpoint Tests
// ============================================================================

#[tokio::test]
async fn test_health_endpoint() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/health")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = get_body_string(response.into_body()).await;
    let json: serde_json::Value = serde_json::from_str(&body).unwrap();

    assert_eq!(json["status"], "UP");
    assert!(json["version"].is_string());
}

#[tokio::test]
async fn test_liveness_probe() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/health/live")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = get_body_string(response.into_body()).await;
    let json: serde_json::Value = serde_json::from_str(&body).unwrap();

    assert_eq!(json["status"], "LIVE");
}

#[tokio::test]
async fn test_readiness_probe() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/health/ready")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = get_body_string(response.into_body()).await;
    let json: serde_json::Value = serde_json::from_str(&body).unwrap();

    assert_eq!(json["status"], "READY");
}

// ============================================================================
// Monitoring Endpoint Tests
// ============================================================================

#[tokio::test]
async fn test_monitoring_endpoint() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/monitoring")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = get_body_string(response.into_body()).await;
    let json: serde_json::Value = serde_json::from_str(&body).unwrap();

    assert_eq!(json["status"], "HEALTHY");
    assert!(json["version"].is_string());
    assert!(json["pool_stats"].is_array());
    assert!(json["active_warnings"].is_number());
    assert!(json["critical_warnings"].is_number());
}

#[tokio::test]
async fn test_health_report_endpoint() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/monitoring/health")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = get_body_string(response.into_body()).await;
    let json: serde_json::Value = serde_json::from_str(&body).unwrap();

    assert!(json["status"].is_string());
}

#[tokio::test]
async fn test_pool_stats_endpoint() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/monitoring/pools")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = get_body_string(response.into_body()).await;
    let json: serde_json::Value = serde_json::from_str(&body).unwrap();

    assert!(json.is_array());
    let pools = json.as_array().unwrap();
    assert!(!pools.is_empty());
    assert_eq!(pools[0]["pool_code"], "DEFAULT");
}

#[tokio::test]
async fn test_update_pool_config() {
    let (app, _, queue_manager) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(Method::PUT)
                .uri("/monitoring/pools/DEFAULT")
                .header("Content-Type", "application/json")
                .body(Body::from(r#"{"concurrency": 20, "rate_limit_per_minute": 1000}"#))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = get_body_string(response.into_body()).await;
    let json: serde_json::Value = serde_json::from_str(&body).unwrap();

    assert_eq!(json["success"], true);

    // Verify the update took effect
    let stats = queue_manager.get_pool_stats();
    let default_pool = stats.iter().find(|s| s.pool_code == "DEFAULT").unwrap();
    assert_eq!(default_pool.concurrency, 20);
    assert_eq!(default_pool.rate_limit_per_minute, Some(1000));
}

// ============================================================================
// Warning Endpoint Tests
// ============================================================================

#[tokio::test]
async fn test_list_warnings_empty() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/warnings")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = get_body_string(response.into_body()).await;
    let json: serde_json::Value = serde_json::from_str(&body).unwrap();

    assert!(json.is_array());
    assert!(json.as_array().unwrap().is_empty());
}

#[tokio::test]
async fn test_critical_warnings_endpoint() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/warnings/critical")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = get_body_string(response.into_body()).await;
    let json: serde_json::Value = serde_json::from_str(&body).unwrap();

    assert!(json.is_array());
}

#[tokio::test]
async fn test_acknowledge_nonexistent_warning() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/warnings/nonexistent-id/acknowledge")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn test_acknowledge_all_warnings() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/warnings/acknowledge-all")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = get_body_string(response.into_body()).await;
    let json: serde_json::Value = serde_json::from_str(&body).unwrap();

    assert!(json["acknowledged"].is_number());
}

// ============================================================================
// Message Publishing Tests
// ============================================================================

#[tokio::test]
async fn test_publish_message() {
    let (app, publisher, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/messages")
                .header("Content-Type", "application/json")
                .body(Body::from(r#"{
                    "payload": {"test": true, "value": 42}
                }"#))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = get_body_string(response.into_body()).await;
    let json: serde_json::Value = serde_json::from_str(&body).unwrap();

    assert_eq!(json["status"], "ACCEPTED");
    assert!(json["message_id"].is_string());

    // Verify message was published
    assert_eq!(publisher.published_count(), 1);
}

#[tokio::test]
async fn test_publish_message_with_pool_code() {
    let (app, publisher, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/messages")
                .header("Content-Type", "application/json")
                .body(Body::from(r#"{
                    "pool_code": "HIGH_PRIORITY",
                    "payload": {"important": true}
                }"#))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(publisher.published_count(), 1);
}

#[tokio::test]
async fn test_publish_message_with_target() {
    let (app, publisher, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/messages")
                .header("Content-Type", "application/json")
                .body(Body::from(r#"{
                    "mediation_target": "http://example.com/webhook",
                    "payload": {"data": "value"}
                }"#))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(publisher.published_count(), 1);
}

#[tokio::test]
async fn test_publish_message_with_group_id() {
    let (app, publisher, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/messages")
                .header("Content-Type", "application/json")
                .body(Body::from(r#"{
                    "message_group_id": "order-123",
                    "payload": {"order_id": 123}
                }"#))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(publisher.published_count(), 1);
}

// ============================================================================
// Error Handling Tests
// ============================================================================

#[tokio::test]
async fn test_invalid_json_body() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/messages")
                .header("Content-Type", "application/json")
                .body(Body::from("not valid json"))
                .unwrap(),
        )
        .await
        .unwrap();

    // Should return a 4xx error
    assert!(response.status().is_client_error());
}

#[tokio::test]
async fn test_missing_required_field() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(Method::POST)
                .uri("/messages")
                .header("Content-Type", "application/json")
                .body(Body::from(r#"{}"#)) // Missing payload
                .unwrap(),
        )
        .await
        .unwrap();

    // Should return a 4xx error for missing required field
    assert!(response.status().is_client_error());
}

#[tokio::test]
async fn test_unknown_route() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/unknown/path")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

// ============================================================================
// Query Parameter Tests
// ============================================================================

#[tokio::test]
async fn test_warnings_with_severity_filter() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/warnings?severity=CRITICAL")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn test_warnings_with_category_filter() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/warnings?category=ROUTING")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn test_warnings_unacknowledged_only() {
    let (app, _, _) = create_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .uri("/warnings?acknowledged=false")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
}
