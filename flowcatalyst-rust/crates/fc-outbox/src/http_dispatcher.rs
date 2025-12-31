//! HTTP Dispatcher for FlowCatalyst API
//!
//! Sends outbox items to the FlowCatalyst REST API endpoints.
//! Matches the Java FlowCatalystApiClient behavior.

use std::sync::Arc;
use std::time::Duration;
use async_trait::async_trait;
use fc_common::Message;
use serde::{Deserialize, Serialize};
use tracing::{debug, error, warn};

use crate::message_group_processor::{
    DispatchResult, MessageDispatcher, BatchMessageDispatcher,
    BatchDispatchResult, BatchItemResult,
};

/// HTTP dispatcher configuration
#[derive(Debug, Clone)]
pub struct HttpDispatcherConfig {
    /// FlowCatalyst API base URL
    pub api_base_url: String,
    /// Optional Bearer token for authentication
    pub api_token: Option<String>,
    /// Connect timeout
    pub connect_timeout: Duration,
    /// Request timeout
    pub request_timeout: Duration,
}

impl Default for HttpDispatcherConfig {
    fn default() -> Self {
        Self {
            api_base_url: "http://localhost:8080".to_string(),
            api_token: None,
            connect_timeout: Duration::from_secs(10),
            request_timeout: Duration::from_secs(30),
        }
    }
}

/// Batch request payload (matches Java structure)
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BatchRequest {
    pub items: Vec<BatchItem>,
}

/// Single item in a batch request
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BatchItem {
    pub id: String,
    pub pool_code: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub auth_token: Option<String>,
    pub mediation_type: String,
    pub mediation_target: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message_group_id: Option<String>,
    pub payload: serde_json::Value,
}

impl From<&Message> for BatchItem {
    fn from(msg: &Message) -> Self {
        Self {
            id: msg.id.clone(),
            pool_code: msg.pool_code.clone(),
            auth_token: msg.auth_token.clone(),
            mediation_type: format!("{:?}", msg.mediation_type),
            mediation_target: msg.mediation_target.clone(),
            message_group_id: msg.message_group_id.clone(),
            payload: msg.payload.clone(),
        }
    }
}

/// Batch response from the API
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BatchResponse {
    pub results: Vec<ItemResult>,
}

/// Result for a single item
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ItemResult {
    pub id: String,
    pub status: ItemStatus,
    #[serde(default)]
    pub error: Option<String>,
}

/// Item status from API response
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ItemStatus {
    Success,
    BadRequest,
    InternalError,
    Unauthorized,
    Forbidden,
    GatewayError,
}

impl ItemStatus {
    pub fn is_retryable(&self) -> bool {
        matches!(
            self,
            ItemStatus::InternalError | ItemStatus::Unauthorized | ItemStatus::GatewayError
        )
    }

    pub fn is_terminal(&self) -> bool {
        matches!(
            self,
            ItemStatus::Success | ItemStatus::BadRequest | ItemStatus::Forbidden
        )
    }
}

/// HTTP dispatcher that sends items to FlowCatalyst API
pub struct HttpDispatcher {
    config: HttpDispatcherConfig,
    client: reqwest::Client,
}

impl HttpDispatcher {
    pub fn new(config: HttpDispatcherConfig) -> anyhow::Result<Self> {
        let client = reqwest::Client::builder()
            .connect_timeout(config.connect_timeout)
            .timeout(config.request_timeout)
            .build()?;

        Ok(Self { config, client })
    }

    /// Send a batch of messages to the API
    pub async fn send_batch(&self, messages: &[Message]) -> Vec<ItemResult> {
        if messages.is_empty() {
            return Vec::new();
        }

        let batch_request = BatchRequest {
            items: messages.iter().map(BatchItem::from).collect(),
        };

        let url = format!("{}/api/dispatch/jobs/batch", self.config.api_base_url);
        debug!("Sending batch of {} items to {}", messages.len(), url);

        let mut request = self.client.post(&url).json(&batch_request);

        if let Some(ref token) = self.config.api_token {
            request = request.header("Authorization", format!("Bearer {}", token));
        }

        match request.send().await {
            Ok(response) => {
                let status = response.status();
                if status.is_success() {
                    match response.json::<BatchResponse>().await {
                        Ok(batch_response) => batch_response.results,
                        Err(e) => {
                            error!("Failed to parse batch response: {}", e);
                            // Return internal error for all items
                            messages
                                .iter()
                                .map(|m| ItemResult {
                                    id: m.id.clone(),
                                    status: ItemStatus::InternalError,
                                    error: Some(format!("Parse error: {}", e)),
                                })
                                .collect()
                        }
                    }
                } else {
                    let error_status = match status.as_u16() {
                        400 => ItemStatus::BadRequest,
                        401 => ItemStatus::Unauthorized,
                        403 => ItemStatus::Forbidden,
                        500 => ItemStatus::InternalError,
                        502 | 503 | 504 => ItemStatus::GatewayError,
                        _ => ItemStatus::InternalError,
                    };

                    let error_body = response.text().await.unwrap_or_default();
                    warn!("Batch request failed with status {}: {}", status, error_body);

                    messages
                        .iter()
                        .map(|m| ItemResult {
                            id: m.id.clone(),
                            status: error_status,
                            error: Some(format!("HTTP {}: {}", status, error_body)),
                        })
                        .collect()
                }
            }
            Err(e) => {
                error!("HTTP request failed: {}", e);
                let error_msg = e.to_string();
                messages
                    .iter()
                    .map(|m| ItemResult {
                        id: m.id.clone(),
                        status: ItemStatus::GatewayError,
                        error: Some(error_msg.clone()),
                    })
                    .collect()
            }
        }
    }
}

#[async_trait]
impl MessageDispatcher for HttpDispatcher {
    async fn dispatch(&self, message: &Message) -> DispatchResult {
        let results = self.send_batch(&[message.clone()]).await;

        match results.first() {
            Some(result) => {
                if result.status == ItemStatus::Success {
                    DispatchResult::Success
                } else {
                    DispatchResult::Failure {
                        error: result.error.clone().unwrap_or_else(|| "Unknown error".to_string()),
                        retryable: result.status.is_retryable(),
                    }
                }
            }
            None => DispatchResult::Failure {
                error: "No result returned".to_string(),
                retryable: true,
            },
        }
    }
}

#[async_trait]
impl BatchMessageDispatcher for HttpDispatcher {
    async fn dispatch_batch(&self, messages: &[Message]) -> BatchDispatchResult {
        let api_results = self.send_batch(messages).await;

        let results = api_results.into_iter().map(|item_result| {
            let result = if item_result.status == ItemStatus::Success {
                DispatchResult::Success
            } else {
                DispatchResult::Failure {
                    error: item_result.error.clone().unwrap_or_else(|| "Unknown error".to_string()),
                    retryable: item_result.status.is_retryable(),
                }
            };
            BatchItemResult {
                message_id: item_result.id,
                result,
            }
        }).collect();

        BatchDispatchResult { results }
    }
}

/// Batch dispatcher for efficient bulk sending
pub struct BatchHttpDispatcher {
    dispatcher: Arc<HttpDispatcher>,
}

impl BatchHttpDispatcher {
    pub fn new(dispatcher: Arc<HttpDispatcher>) -> Self {
        Self { dispatcher }
    }

    /// Dispatch a batch of messages and return results
    pub async fn dispatch_batch(&self, messages: &[Message]) -> Vec<ItemResult> {
        self.dispatcher.send_batch(messages).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use fc_common::MediationType;
    use chrono::Utc;

    fn create_test_message(id: &str) -> Message {
        Message {
            id: id.to_string(),
            pool_code: "DEFAULT".to_string(),
            auth_token: None,
            signing_secret: None,
            mediation_type: MediationType::HTTP,
            mediation_target: "http://target.example.com/webhook".to_string(),
            message_group_id: Some("group-1".to_string()),
            payload: serde_json::json!({"key": "value"}),
            created_at: Utc::now(),
        }
    }

    #[test]
    fn test_batch_item_from_message() {
        let msg = create_test_message("test-1");
        let item = BatchItem::from(&msg);

        assert_eq!(item.id, "test-1");
        assert_eq!(item.pool_code, "DEFAULT");
        assert_eq!(item.message_group_id, Some("group-1".to_string()));
    }

    #[test]
    fn test_item_status_retryable() {
        assert!(ItemStatus::InternalError.is_retryable());
        assert!(ItemStatus::Unauthorized.is_retryable());
        assert!(ItemStatus::GatewayError.is_retryable());
        assert!(!ItemStatus::Success.is_retryable());
        assert!(!ItemStatus::BadRequest.is_retryable());
        assert!(!ItemStatus::Forbidden.is_retryable());
    }

    #[test]
    fn test_item_status_terminal() {
        assert!(ItemStatus::Success.is_terminal());
        assert!(ItemStatus::BadRequest.is_terminal());
        assert!(ItemStatus::Forbidden.is_terminal());
        assert!(!ItemStatus::InternalError.is_terminal());
        assert!(!ItemStatus::Unauthorized.is_terminal());
        assert!(!ItemStatus::GatewayError.is_terminal());
    }
}
