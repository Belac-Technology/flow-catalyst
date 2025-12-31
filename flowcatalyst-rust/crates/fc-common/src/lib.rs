use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use std::time::Instant;
use utoipa::ToSchema;

// ============================================================================
// Core Message Types
// ============================================================================

/// The core message structure that flows through the system
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    pub id: String,
    pub pool_code: String,
    pub auth_token: Option<String>,
    /// Signing secret for HMAC-SHA256 webhook signatures
    pub signing_secret: Option<String>,
    pub mediation_type: MediationType,
    pub mediation_target: String,
    pub message_group_id: Option<String>,
    pub payload: serde_json::Value,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum MediationType {
    HTTP,
}

/// A message that has been received from a queue with tracking metadata
#[derive(Debug, Clone)]
pub struct QueuedMessage {
    pub message: Message,
    pub receipt_handle: String,
    pub broker_message_id: Option<String>,  // SQS/broker message ID for deduplication
    pub queue_identifier: String,
}

/// A message bundled with its callback for batch processing
#[derive(Debug)]
pub struct BatchMessage {
    pub message: Message,
    pub receipt_handle: String,
    pub broker_message_id: Option<String>,
    pub queue_identifier: String,
    pub batch_id: Option<String>,
    pub ack_tx: tokio::sync::oneshot::Sender<AckNack>,
}

/// ACK/NACK response sent back to the queue consumer
#[derive(Debug, Clone)]
pub enum AckNack {
    Ack,
    Nack { delay_seconds: Option<u32> },
    ExtendVisibility { seconds: u32 },
}

// ============================================================================
// In-Flight Message Tracking
// ============================================================================

/// Tracks a message currently being processed
#[derive(Debug, Clone)]
pub struct InFlightMessage {
    pub message_id: String,
    pub broker_message_id: Option<String>,
    pub pool_code: String,
    pub queue_identifier: String,
    pub started_at: Instant,
    pub message_group_id: Option<String>,
    pub batch_id: Option<String>,
    /// Current receipt handle - may be updated on SQS redelivery
    pub receipt_handle: String,
}

impl InFlightMessage {
    pub fn new(
        message: &Message,
        broker_message_id: Option<String>,
        queue_identifier: String,
        batch_id: Option<String>,
        receipt_handle: String,
    ) -> Self {
        Self {
            message_id: message.id.clone(),
            broker_message_id,
            pool_code: message.pool_code.clone(),
            queue_identifier,
            started_at: Instant::now(),
            message_group_id: message.message_group_id.clone(),
            batch_id,
            receipt_handle,
        }
    }

    pub fn elapsed_seconds(&self) -> u64 {
        self.started_at.elapsed().as_secs()
    }

    /// Update receipt handle when message is redelivered
    pub fn update_receipt_handle(&mut self, new_handle: String) {
        self.receipt_handle = new_handle;
    }
}

// ============================================================================
// Configuration Types
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct PoolConfig {
    pub code: String,
    pub concurrency: u32,
    pub rate_limit_per_minute: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueueConfig {
    pub name: String,
    pub uri: String,
    pub connections: u32,
    pub visibility_timeout: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RouterConfig {
    pub processing_pools: Vec<PoolConfig>,
    pub queues: Vec<QueueConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StandbyConfig {
    pub enabled: bool,
    pub redis_url: String,
    pub lock_key: String,
    pub instance_id: String,
    pub lock_ttl_seconds: u64,
    pub refresh_interval_seconds: u64,
}

impl Default for StandbyConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            redis_url: "redis://127.0.0.1:6379".to_string(),
            lock_key: "flowcatalyst:leader".to_string(),
            instance_id: uuid::Uuid::new_v4().to_string(),
            lock_ttl_seconds: 30,
            refresh_interval_seconds: 10,
        }
    }
}

// ============================================================================
// Mediation Types
// ============================================================================

/// Result of a mediation attempt
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediationResult {
    /// Successfully delivered and acknowledged
    Success,
    /// Configuration error (4xx) - ACK to prevent infinite retries
    ErrorConfig,
    /// Transient error (5xx, timeout) - NACK for retry
    ErrorProcess,
    /// Connection error - NACK for retry
    ErrorConnection,
}

/// Outcome of mediation including result and optional delay
#[derive(Debug, Clone)]
pub struct MediationOutcome {
    pub result: MediationResult,
    pub delay_seconds: Option<u32>,
    pub status_code: Option<u16>,
    pub error_message: Option<String>,
}

impl MediationOutcome {
    pub fn success() -> Self {
        Self {
            result: MediationResult::Success,
            delay_seconds: None,
            status_code: Some(200),
            error_message: None,
        }
    }

    pub fn error_config(status_code: u16, message: String) -> Self {
        Self {
            result: MediationResult::ErrorConfig,
            delay_seconds: None,
            status_code: Some(status_code),
            error_message: Some(message),
        }
    }

    pub fn error_process(delay_seconds: Option<u32>, message: String) -> Self {
        Self {
            result: MediationResult::ErrorProcess,
            delay_seconds,
            status_code: None,
            error_message: Some(message),
        }
    }

    pub fn error_connection(message: String) -> Self {
        Self {
            result: MediationResult::ErrorConnection,
            delay_seconds: Some(5),
            status_code: None,
            error_message: Some(message),
        }
    }
}

// ============================================================================
// Outbox Types
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum OutboxStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OutboxItem {
    pub id: String,
    pub item_type: String,
    pub pool_code: Option<String>,
    pub mediation_target: Option<String>,
    pub message_group: Option<String>,
    pub payload: serde_json::Value,
    pub status: OutboxStatus,
    pub retry_count: u32,
    pub created_at: DateTime<Utc>,
}

// ============================================================================
// Warning System Types
// ============================================================================

/// Warning categories for the message router
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, ToSchema)]
pub enum WarningCategory {
    /// Message routing issues
    Routing,
    /// Message processing failures
    Processing,
    /// Configuration errors
    Configuration,
    /// Message group thread restart
    GroupThreadRestart,
    /// Rate limiting triggered
    RateLimiting,
    /// Queue connectivity issues
    QueueConnectivity,
    /// Pool capacity issues
    PoolCapacity,
    /// Consumer health issues
    ConsumerHealth,
    /// Memory/resource issues
    Resource,
}

/// Warning severity levels
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize, ToSchema)]
pub enum WarningSeverity {
    /// Informational warning
    Info,
    /// Warning that may need attention
    Warn,
    /// Error requiring attention
    Error,
    /// Critical error requiring immediate attention
    Critical,
}

/// A system warning
#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct Warning {
    pub id: String,
    pub category: WarningCategory,
    pub severity: WarningSeverity,
    pub message: String,
    pub source: String,
    pub created_at: DateTime<Utc>,
    pub acknowledged: bool,
    pub acknowledged_at: Option<DateTime<Utc>>,
}

impl Warning {
    pub fn new(
        category: WarningCategory,
        severity: WarningSeverity,
        message: String,
        source: String,
    ) -> Self {
        Self {
            id: uuid::Uuid::new_v4().to_string(),
            category,
            severity,
            message,
            source,
            created_at: Utc::now(),
            acknowledged: false,
            acknowledged_at: None,
        }
    }

    pub fn age_minutes(&self) -> i64 {
        (Utc::now() - self.created_at).num_minutes()
    }
}

/// Overall system health status
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, ToSchema)]
pub enum HealthStatus {
    /// All systems operational
    Healthy,
    /// Some issues detected but operational
    Warning,
    /// Significant issues affecting operations
    Degraded,
}

/// Detailed health report
#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct HealthReport {
    pub status: HealthStatus,
    pub pools_healthy: u32,
    pub pools_unhealthy: u32,
    pub consumers_healthy: u32,
    pub consumers_unhealthy: u32,
    pub active_warnings: u32,
    pub critical_warnings: u32,
    pub issues: Vec<String>,
}

// ============================================================================
// Health & Metrics Types
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct PoolStats {
    pub pool_code: String,
    pub concurrency: u32,
    pub active_workers: u32,
    pub queue_size: u32,
    pub queue_capacity: u32,
    pub message_group_count: u32,
    pub rate_limit_per_minute: Option<u32>,
    pub is_rate_limited: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConsumerHealth {
    pub queue_identifier: String,
    pub is_healthy: bool,
    pub last_poll_time_ms: Option<i64>,
    pub time_since_last_poll_ms: Option<i64>,
    pub is_running: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InfrastructureHealth {
    pub healthy: bool,
    pub message: String,
    pub issues: Vec<String>,
}

// ============================================================================
// Error Types
// ============================================================================

#[derive(Debug, thiserror::Error)]
pub enum FlowCatalystError {
    #[error("Queue error: {0}")]
    Queue(String),

    #[error("Pool error: {0}")]
    Pool(String),

    #[error("Mediation error: {0}")]
    Mediation(String),

    #[error("Configuration error: {0}")]
    Config(String),

    #[error("Redis error: {0}")]
    Redis(String),

    #[error("Database error: {0}")]
    Database(String),

    #[error("Serialization error: {0}")]
    Serialization(String),

    #[error("Shutdown in progress")]
    ShutdownInProgress,
}

pub type Result<T> = std::result::Result<T, FlowCatalystError>;
