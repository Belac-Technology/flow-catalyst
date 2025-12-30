//! API Endpoint Tests
//!
//! Tests for:
//! - Health endpoints (basic, liveness, readiness)
//! - Monitoring endpoints
//! - Warning management
//! - Pool configuration updates
//! - Message publishing

use std::sync::Arc;
use salvo::prelude::*;
use salvo::test::*;
use async_trait::async_trait;

use fc_common::{Message, MediationOutcome, PoolConfig, RouterConfig};
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

async fn create_test_app() -> (Service, Arc<MockPublisher>, Arc<QueueManager>) {
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

    let router = create_router(
        publisher.clone(),
        queue_manager.clone(),
        warning_service,
        health_service,
    );

    let service = Service::new(router);

    (service, publisher, queue_manager)
}

// Helper to get status from response
fn status(res: &Response) -> StatusCode {
    res.status_code.unwrap_or(StatusCode::OK)
}

// Helper to extract JSON body
async fn take_json(res: &mut Response) -> serde_json::Value {
    use salvo::http::body::ResBody;

    let body = res.take_body();
    let bytes = match body {
        ResBody::Once(bytes) => bytes,
        ResBody::Chunks(chunks) => {
            let mut all_bytes = bytes::BytesMut::new();
            for chunk in chunks {
                all_bytes.extend_from_slice(&chunk);
            }
            all_bytes.freeze()
        }
        _ => bytes::Bytes::new(),
    };
    serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null)
}

// ============================================================================
// Health Endpoint Tests
// ============================================================================

#[tokio::test]
async fn test_health_endpoint() {
    let (service, _, _) = create_test_app().await;

    let mut response = TestClient::get("http://localhost/health")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);

    let json = take_json(&mut response).await;

    assert_eq!(json["status"], "UP");
    assert!(json["version"].is_string());
}

#[tokio::test]
async fn test_liveness_probe() {
    let (service, _, _) = create_test_app().await;

    let mut response = TestClient::get("http://localhost/health/live")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);

    let json = take_json(&mut response).await;

    assert_eq!(json["status"], "LIVE");
}

#[tokio::test]
async fn test_readiness_probe() {
    let (service, _, _) = create_test_app().await;

    let mut response = TestClient::get("http://localhost/health/ready")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);

    let json = take_json(&mut response).await;

    assert_eq!(json["status"], "READY");
}

// ============================================================================
// Monitoring Endpoint Tests
// ============================================================================

#[tokio::test]
async fn test_monitoring_endpoint() {
    let (service, _, _) = create_test_app().await;

    let mut response = TestClient::get("http://localhost/monitoring")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);

    let json = take_json(&mut response).await;

    assert_eq!(json["status"], "HEALTHY");
    assert!(json["version"].is_string());
    assert!(json["pool_stats"].is_array());
    assert!(json["active_warnings"].is_number());
    assert!(json["critical_warnings"].is_number());
}

#[tokio::test]
async fn test_health_report_endpoint() {
    let (service, _, _) = create_test_app().await;

    let mut response = TestClient::get("http://localhost/monitoring/health")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);

    let json = take_json(&mut response).await;

    assert!(json["status"].is_string());
}

#[tokio::test]
async fn test_pool_stats_endpoint() {
    let (service, _, _) = create_test_app().await;

    let mut response = TestClient::get("http://localhost/monitoring/pools")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);

    let json = take_json(&mut response).await;

    assert!(json.is_array());
    let pools = json.as_array().unwrap();
    assert!(!pools.is_empty());
    assert_eq!(pools[0]["pool_code"], "DEFAULT");
}

#[tokio::test]
async fn test_update_pool_config() {
    let (service, _, queue_manager) = create_test_app().await;

    let mut response = TestClient::put("http://localhost/monitoring/pools/DEFAULT")
        .add_header("Content-Type", "application/json", true)
        .body(r#"{"concurrency": 20, "rate_limit_per_minute": 1000}"#)
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);

    let json = take_json(&mut response).await;

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
    let (service, _, _) = create_test_app().await;

    let mut response = TestClient::get("http://localhost/warnings")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);

    let json = take_json(&mut response).await;

    assert!(json.is_array());
    assert!(json.as_array().unwrap().is_empty());
}

#[tokio::test]
async fn test_critical_warnings_endpoint() {
    let (service, _, _) = create_test_app().await;

    let mut response = TestClient::get("http://localhost/warnings/critical")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);

    let json = take_json(&mut response).await;

    assert!(json.is_array());
}

#[tokio::test]
async fn test_acknowledge_nonexistent_warning() {
    let (service, _, _) = create_test_app().await;

    let response = TestClient::post("http://localhost/warnings/nonexistent-id/acknowledge")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn test_acknowledge_all_warnings() {
    let (service, _, _) = create_test_app().await;

    let mut response = TestClient::post("http://localhost/warnings/acknowledge-all")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);

    let json = take_json(&mut response).await;

    assert!(json["acknowledged"].is_number());
}

// ============================================================================
// Message Publishing Tests
// ============================================================================

#[tokio::test]
async fn test_publish_message() {
    let (service, publisher, _) = create_test_app().await;

    let mut response = TestClient::post("http://localhost/messages")
        .add_header("Content-Type", "application/json", true)
        .body(r#"{
            "payload": {"test": true, "value": 42}
        }"#)
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);

    let json = take_json(&mut response).await;

    assert_eq!(json["status"], "ACCEPTED");
    assert!(json["message_id"].is_string());

    // Verify message was published
    assert_eq!(publisher.published_count(), 1);
}

#[tokio::test]
async fn test_publish_message_with_pool_code() {
    let (service, publisher, _) = create_test_app().await;

    let response = TestClient::post("http://localhost/messages")
        .add_header("Content-Type", "application/json", true)
        .body(r#"{
            "pool_code": "HIGH_PRIORITY",
            "payload": {"important": true}
        }"#)
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);
    assert_eq!(publisher.published_count(), 1);
}

#[tokio::test]
async fn test_publish_message_with_target() {
    let (service, publisher, _) = create_test_app().await;

    let response = TestClient::post("http://localhost/messages")
        .add_header("Content-Type", "application/json", true)
        .body(r#"{
            "mediation_target": "http://example.com/webhook",
            "payload": {"data": "value"}
        }"#)
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);
    assert_eq!(publisher.published_count(), 1);
}

#[tokio::test]
async fn test_publish_message_with_group_id() {
    let (service, publisher, _) = create_test_app().await;

    let response = TestClient::post("http://localhost/messages")
        .add_header("Content-Type", "application/json", true)
        .body(r#"{
            "message_group_id": "order-123",
            "payload": {"order_id": 123}
        }"#)
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);
    assert_eq!(publisher.published_count(), 1);
}

// ============================================================================
// Error Handling Tests
// ============================================================================

#[tokio::test]
async fn test_invalid_json_body() {
    let (service, _, _) = create_test_app().await;

    let response = TestClient::post("http://localhost/messages")
        .add_header("Content-Type", "application/json", true)
        .body("not valid json")
        .send(&service)
        .await;

    // Should return a 4xx error
    assert!(status(&response).is_client_error());
}

#[tokio::test]
async fn test_missing_required_field() {
    let (service, _, _) = create_test_app().await;

    let response = TestClient::post("http://localhost/messages")
        .add_header("Content-Type", "application/json", true)
        .body(r#"{}"#) // Missing payload
        .send(&service)
        .await;

    // Should return a 4xx error for missing required field
    assert!(status(&response).is_client_error());
}

#[tokio::test]
async fn test_unknown_route() {
    let (service, _, _) = create_test_app().await;

    let response = TestClient::get("http://localhost/unknown/path")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::NOT_FOUND);
}

// ============================================================================
// Query Parameter Tests
// ============================================================================

#[tokio::test]
async fn test_warnings_with_severity_filter() {
    let (service, _, _) = create_test_app().await;

    let response = TestClient::get("http://localhost/warnings?severity=CRITICAL")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);
}

#[tokio::test]
async fn test_warnings_with_category_filter() {
    let (service, _, _) = create_test_app().await;

    let response = TestClient::get("http://localhost/warnings?category=ROUTING")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);
}

#[tokio::test]
async fn test_warnings_unacknowledged_only() {
    let (service, _, _) = create_test_app().await;

    let response = TestClient::get("http://localhost/warnings?acknowledged=false")
        .send(&service)
        .await;

    assert_eq!(status(&response), StatusCode::OK);
}
