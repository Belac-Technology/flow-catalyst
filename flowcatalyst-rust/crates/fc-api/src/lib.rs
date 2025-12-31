//! FlowCatalyst API
//!
//! HTTP API endpoints for:
//! - Message publishing
//! - Health and monitoring
//! - Kubernetes probes (liveness/readiness)
//! - Warning management
//! - Pool statistics

use axum::{
    routing::{get, post, put},
    extract::{Path, Query, State},
    response::{Html, IntoResponse, Response},
    http::{header, StatusCode},
    Json, Router,
};
use utoipa::{OpenApi, ToSchema};
use utoipa_swagger_ui::SwaggerUi;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::collections::HashMap;
use fc_queue::QueuePublisher;
use fc_common::{
    Message, MediationType, HealthStatus, HealthReport, PoolStats, PoolConfig,
    Warning, WarningSeverity, WarningCategory,
};
use fc_router::{QueueManager, WarningService, HealthService, QueueMetrics, InFlightMessageInfo};
use uuid::Uuid;
use chrono::Utc;
use tracing::{debug, info, warn, error};

pub mod model;
use model::{PublishMessageRequest, PublishMessageResponse, PoolStatusResponse};

/// Application state shared across handlers
#[derive(Clone)]
pub struct AppState {
    pub publisher: Arc<dyn QueuePublisher>,
    pub queue_manager: Arc<QueueManager>,
    pub warning_service: Arc<WarningService>,
    pub health_service: Arc<HealthService>,
}

/// Simple health response for basic health check
#[derive(Serialize, ToSchema)]
pub struct SimpleHealthResponse {
    /// Health status: UP, DEGRADED
    pub status: String,
    /// Application version
    pub version: String,
}

/// Kubernetes probe response
#[derive(Serialize, ToSchema)]
pub struct ProbeResponse {
    /// Probe status: LIVE, READY, NOT_READY
    pub status: String,
}

/// Detailed monitoring response
#[derive(Serialize, ToSchema)]
pub struct MonitoringResponse {
    /// Overall status: HEALTHY, WARNING, DEGRADED
    pub status: String,
    /// Application version
    pub version: String,
    /// Detailed health report
    pub health_report: HealthReport,
    /// Pool statistics
    pub pool_stats: Vec<PoolStats>,
    /// Number of active (unacknowledged) warnings
    pub active_warnings: u32,
    /// Number of critical warnings
    pub critical_warnings: u32,
}

/// Query params for warnings endpoint
#[derive(Deserialize, Default, ToSchema)]
pub struct WarningsQuery {
    /// Filter by severity: INFO, WARN, ERROR, CRITICAL
    pub severity: Option<String>,
    /// Filter by category: ROUTING, PROCESSING, CONFIGURATION, etc.
    pub category: Option<String>,
    /// Filter by acknowledged status
    pub acknowledged: Option<bool>,
}

/// Request to update pool configuration
#[derive(Debug, Deserialize, ToSchema)]
pub struct PoolConfigUpdateRequest {
    /// New concurrency limit
    pub concurrency: Option<u32>,
    /// New rate limit (messages per minute)
    pub rate_limit_per_minute: Option<u32>,
}

/// Request to reload router configuration
#[derive(Debug, Deserialize, ToSchema)]
pub struct ConfigReloadRequest {
    /// List of pool configurations
    pub processing_pools: Vec<PoolConfigRequest>,
}

/// Pool configuration in reload request
#[derive(Debug, Clone, Deserialize, ToSchema)]
pub struct PoolConfigRequest {
    /// Pool code/identifier
    pub code: String,
    /// Worker concurrency
    pub concurrency: u32,
    /// Optional rate limit (messages per minute)
    pub rate_limit_per_minute: Option<u32>,
}

/// Response after config reload
#[derive(Debug, Serialize, ToSchema)]
pub struct ConfigReloadResponse {
    /// Whether the reload was successful
    pub success: bool,
    /// Number of pools updated
    pub pools_updated: usize,
    /// Number of new pools created
    pub pools_created: usize,
    /// Number of pools removed (draining)
    pub pools_removed: usize,
    /// Total active pools after reload
    pub total_active_pools: usize,
    /// Total pools currently draining
    pub total_draining_pools: usize,
}

/// Response for queue metrics endpoint
#[derive(Serialize, ToSchema)]
pub struct QueueMetricsResponse {
    /// Queue identifier
    pub queue_identifier: String,
    /// Number of messages waiting in the queue
    pub pending_messages: u64,
    /// Number of messages currently being processed
    pub in_flight_messages: u64,
}

impl From<QueueMetrics> for QueueMetricsResponse {
    fn from(m: QueueMetrics) -> Self {
        QueueMetricsResponse {
            queue_identifier: m.queue_identifier,
            pending_messages: m.pending_messages,
            in_flight_messages: m.in_flight_messages,
        }
    }
}

/// OpenAPI documentation
#[derive(OpenApi)]
#[openapi(
    info(
        title = "FlowCatalyst Message Router API",
        version = "0.1.0",
        description = "HTTP API for message routing, health monitoring, and pool management"
    ),
    paths(
        health_handler,
        liveness_probe,
        readiness_probe,
        metrics_handler,
        monitoring_handler,
        pool_stats_handler,
        queue_metrics_handler,
        update_pool_config,
        reload_config,
        list_warnings,
        acknowledge_warning,
        acknowledge_all_warnings,
        get_critical_warnings,
        dashboard_health_handler,
        dashboard_queue_stats_handler,
        dashboard_pool_stats_handler,
        dashboard_warnings_handler,
        dashboard_circuit_breakers_handler,
        dashboard_in_flight_messages_handler,
        publish_message,
    ),
    components(schemas(
        SimpleHealthResponse,
        ProbeResponse,
        MonitoringResponse,
        WarningsQuery,
        PoolConfigUpdateRequest,
        ConfigReloadRequest,
        PoolConfigRequest,
        ConfigReloadResponse,
        QueueMetricsResponse,
        PublishMessageRequest,
        PublishMessageResponse,
        PoolStatusResponse,
        DashboardHealthResponse,
        DashboardHealthDetails,
        DashboardQueueStats,
        DashboardPoolStats,
        DashboardWarning,
        DashboardCircuitBreakerStats,
        InFlightMessagesQuery,
    )),
    tags(
        (name = "health", description = "Health check endpoints"),
        (name = "monitoring", description = "Monitoring and metrics endpoints"),
        (name = "warnings", description = "Warning management endpoints"),
        (name = "messages", description = "Message publishing endpoints"),
    )
)]
pub struct ApiDoc;

/// Create the full router with all endpoints
pub fn create_router(
    publisher: Arc<dyn QueuePublisher>,
    queue_manager: Arc<QueueManager>,
    warning_service: Arc<WarningService>,
    health_service: Arc<HealthService>,
) -> Router {
    let state = AppState {
        publisher,
        queue_manager,
        warning_service,
        health_service,
    };

    Router::new()
        // Swagger UI
        .merge(SwaggerUi::new("/swagger-ui").url("/api-doc/openapi.json", ApiDoc::openapi()))
        // Basic health
        .route("/health", get(health_handler))
        .route("/q/health", get(health_handler))
        // Kubernetes probes
        .route("/health/live", get(liveness_probe))
        .route("/health/ready", get(readiness_probe))
        .route("/q/health/live", get(liveness_probe))
        .route("/q/health/ready", get(readiness_probe))
        // Prometheus metrics
        .route("/metrics", get(metrics_handler))
        .route("/q/metrics", get(metrics_handler))
        // Detailed monitoring
        .route("/monitoring", get(monitoring_handler))
        .route("/monitoring/health", get(dashboard_health_handler))
        .route("/monitoring/pools", get(pool_stats_handler))
        .route("/monitoring/pools/:pool_code", put(update_pool_config))
        .route("/monitoring/queues", get(queue_metrics_handler))
        // Dashboard-compatible endpoints
        .route("/monitoring/queue-stats", get(dashboard_queue_stats_handler))
        .route("/monitoring/pool-stats", get(dashboard_pool_stats_handler))
        .route("/monitoring/warnings", get(dashboard_warnings_handler))
        .route("/monitoring/circuit-breakers", get(dashboard_circuit_breakers_handler))
        .route("/monitoring/in-flight-messages", get(dashboard_in_flight_messages_handler))
        .route("/monitoring/dashboard", get(dashboard_html_handler))
        // Configuration management
        .route("/config/reload", post(reload_config))
        // Warnings management
        .route("/warnings", get(list_warnings))
        .route("/warnings/:id/acknowledge", post(acknowledge_warning))
        .route("/warnings/acknowledge-all", post(acknowledge_all_warnings))
        .route("/warnings/critical", get(get_critical_warnings))
        // Message publishing
        .route("/messages", post(publish_message))
        .with_state(state)
}

/// Simple state for simple router
#[derive(Clone)]
pub struct SimpleState {
    pub publisher: Arc<dyn QueuePublisher>,
}

/// Create a simple router with just message publishing
pub fn create_simple_router(publisher: Arc<dyn QueuePublisher>) -> Router {
    let state = SimpleState { publisher };

    Router::new()
        .route("/health", get(simple_health_handler))
        .route("/messages", post(simple_publish_message))
        .with_state(state)
}

// ============================================================================
// Health Endpoints
// ============================================================================

/// Health check endpoint
#[utoipa::path(
    get,
    path = "/health",
    tag = "health",
    responses(
        (status = 200, description = "Health status", body = SimpleHealthResponse)
    )
)]
async fn health_handler(State(state): State<AppState>) -> Json<SimpleHealthResponse> {
    let pool_stats = state.queue_manager.get_pool_stats();
    let report = state.health_service.get_health_report(&pool_stats);

    let status = match report.status {
        HealthStatus::Healthy => "UP",
        HealthStatus::Warning => "UP",
        HealthStatus::Degraded => "DEGRADED",
    };

    Json(SimpleHealthResponse {
        status: status.to_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
    })
}

/// Simple health handler (no state dependency)
async fn simple_health_handler() -> Json<SimpleHealthResponse> {
    Json(SimpleHealthResponse {
        status: "UP".to_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
    })
}

/// Kubernetes liveness probe - returns 200 if the application is running
#[utoipa::path(
    get,
    path = "/health/live",
    tag = "health",
    responses(
        (status = 200, description = "Application is live", body = ProbeResponse)
    )
)]
async fn liveness_probe() -> Json<ProbeResponse> {
    Json(ProbeResponse { status: "LIVE".to_string() })
}

/// Kubernetes readiness probe - returns 200 if ready to accept traffic
#[utoipa::path(
    get,
    path = "/health/ready",
    tag = "health",
    responses(
        (status = 200, description = "Application is ready", body = ProbeResponse),
        (status = 503, description = "Application is not ready", body = ProbeResponse)
    )
)]
async fn readiness_probe(State(state): State<AppState>) -> Response {
    let pool_stats = state.queue_manager.get_pool_stats();
    let report = state.health_service.get_health_report(&pool_stats);

    match report.status {
        HealthStatus::Healthy | HealthStatus::Warning => {
            (StatusCode::OK, Json(ProbeResponse { status: "READY".to_string() })).into_response()
        }
        HealthStatus::Degraded => {
            (StatusCode::SERVICE_UNAVAILABLE, Json(ProbeResponse { status: "NOT_READY".to_string() })).into_response()
        }
    }
}

/// Prometheus metrics endpoint
#[utoipa::path(
    get,
    path = "/metrics",
    tag = "monitoring",
    responses(
        (status = 200, description = "Prometheus metrics", content_type = "text/plain")
    )
)]
async fn metrics_handler() -> Response {
    let output = "# HELP fc_requests_total Total number of requests
# TYPE fc_requests_total counter
fc_requests_total 0
# HELP fc_active_pools Number of active processing pools
# TYPE fc_active_pools gauge
fc_active_pools 1
";
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "text/plain; charset=utf-8")],
        output,
    ).into_response()
}

// ============================================================================
// Monitoring Endpoints
// ============================================================================

/// Detailed monitoring information
#[utoipa::path(
    get,
    path = "/monitoring",
    tag = "monitoring",
    responses(
        (status = 200, description = "Monitoring data", body = MonitoringResponse)
    )
)]
async fn monitoring_handler(State(state): State<AppState>) -> Json<MonitoringResponse> {
    let pool_stats = state.queue_manager.get_pool_stats();
    let health_report = state.health_service.get_health_report(&pool_stats);
    let active_warnings = state.warning_service.unacknowledged_count() as u32;
    let critical_warnings = state.warning_service.critical_count() as u32;

    let status = match health_report.status {
        HealthStatus::Healthy => "HEALTHY",
        HealthStatus::Warning => "WARNING",
        HealthStatus::Degraded => "DEGRADED",
    };

    Json(MonitoringResponse {
        status: status.to_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
        health_report,
        pool_stats,
        active_warnings,
        critical_warnings,
    })
}

/// Pool statistics
#[utoipa::path(
    get,
    path = "/monitoring/pools",
    tag = "monitoring",
    responses(
        (status = 200, description = "Pool statistics", body = Vec<PoolStats>)
    )
)]
async fn pool_stats_handler(State(state): State<AppState>) -> Json<Vec<PoolStats>> {
    Json(state.queue_manager.get_pool_stats())
}

/// Queue metrics
#[utoipa::path(
    get,
    path = "/monitoring/queues",
    tag = "monitoring",
    responses(
        (status = 200, description = "Queue metrics", body = Vec<QueueMetricsResponse>)
    )
)]
async fn queue_metrics_handler(State(state): State<AppState>) -> Json<Vec<QueueMetricsResponse>> {
    let metrics = state.queue_manager.get_queue_metrics().await;
    Json(metrics.into_iter().map(QueueMetricsResponse::from).collect())
}

// ============================================================================
// Configuration Management
// ============================================================================

/// Reload configuration (hot reload)
#[utoipa::path(
    post,
    path = "/config/reload",
    tag = "monitoring",
    request_body = ConfigReloadRequest,
    responses(
        (status = 200, description = "Configuration reloaded", body = ConfigReloadResponse),
        (status = 503, description = "Service unavailable", body = ConfigReloadResponse),
        (status = 500, description = "Internal error", body = ConfigReloadResponse)
    )
)]
async fn reload_config(
    State(state): State<AppState>,
    Json(req): Json<ConfigReloadRequest>,
) -> Response {
    use fc_common::RouterConfig;

    let router_config = RouterConfig {
        processing_pools: req.processing_pools
            .into_iter()
            .map(|p| PoolConfig {
                code: p.code,
                concurrency: p.concurrency,
                rate_limit_per_minute: p.rate_limit_per_minute,
            })
            .collect(),
        queues: vec![],
    };

    let pools_before = state.queue_manager.pool_codes().len();

    match state.queue_manager.reload_config(router_config).await {
        Ok(true) => {
            let pools_after = state.queue_manager.pool_codes().len();
            let pool_stats = state.queue_manager.get_pool_stats();
            let pools_created = pools_after.saturating_sub(pools_before);
            let pools_removed = pools_before.saturating_sub(pools_after);

            info!(
                pools_before = pools_before,
                pools_after = pools_after,
                pools_created = pools_created,
                pools_removed = pools_removed,
                "Configuration reloaded via API"
            );

            (StatusCode::OK, Json(ConfigReloadResponse {
                success: true,
                pools_updated: 0,
                pools_created,
                pools_removed,
                total_active_pools: pool_stats.len(),
                total_draining_pools: 0,
            })).into_response()
        }
        Ok(false) => {
            warn!("Configuration reload was skipped (shutdown in progress)");
            (StatusCode::SERVICE_UNAVAILABLE, Json(ConfigReloadResponse {
                success: false,
                pools_updated: 0,
                pools_created: 0,
                pools_removed: 0,
                total_active_pools: 0,
                total_draining_pools: 0,
            })).into_response()
        }
        Err(e) => {
            error!(error = %e, "Failed to reload configuration");
            (StatusCode::INTERNAL_SERVER_ERROR, Json(ConfigReloadResponse {
                success: false,
                pools_updated: 0,
                pools_created: 0,
                pools_removed: 0,
                total_active_pools: 0,
                total_draining_pools: 0,
            })).into_response()
        }
    }
}

/// Update pool configuration
#[utoipa::path(
    put,
    path = "/monitoring/pools/{pool_code}",
    tag = "monitoring",
    params(
        ("pool_code" = String, Path, description = "Pool code to update")
    ),
    request_body = PoolConfigUpdateRequest,
    responses(
        (status = 200, description = "Pool updated"),
        (status = 500, description = "Internal error")
    )
)]
async fn update_pool_config(
    State(state): State<AppState>,
    Path(pool_code): Path<String>,
    Json(req): Json<PoolConfigUpdateRequest>,
) -> Response {
    let existing_stats: Option<PoolStats> = state.queue_manager
        .get_pool_stats()
        .into_iter()
        .find(|s| s.pool_code == pool_code);

    let new_config = match existing_stats {
        Some(stats) => PoolConfig {
            code: pool_code.clone(),
            concurrency: req.concurrency.unwrap_or(stats.concurrency),
            rate_limit_per_minute: if req.rate_limit_per_minute.is_some() {
                req.rate_limit_per_minute
            } else {
                stats.rate_limit_per_minute
            },
        },
        None => PoolConfig {
            code: pool_code.clone(),
            concurrency: req.concurrency.unwrap_or(10),
            rate_limit_per_minute: req.rate_limit_per_minute,
        },
    };

    match state.queue_manager.update_pool_config(&pool_code, new_config.clone()).await {
        Ok(_) => {
            info!(pool_code = %pool_code, "Pool configuration updated via API");
            (StatusCode::OK, Json(serde_json::json!({
                "success": true,
                "pool_code": pool_code,
                "new_config": {
                    "concurrency": new_config.concurrency,
                    "rate_limit_per_minute": new_config.rate_limit_per_minute,
                }
            }))).into_response()
        }
        Err(e) => {
            error!(pool_code = %pool_code, error = %e, "Failed to update pool configuration");
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({
                "success": false,
                "error": e.to_string(),
            }))).into_response()
        }
    }
}

// ============================================================================
// Warning Endpoints
// ============================================================================

/// List warnings with optional filters
#[utoipa::path(
    get,
    path = "/warnings",
    tag = "warnings",
    params(
        ("severity" = Option<String>, Query, description = "Filter by severity"),
        ("category" = Option<String>, Query, description = "Filter by category"),
        ("acknowledged" = Option<bool>, Query, description = "Filter by acknowledged status")
    ),
    responses(
        (status = 200, description = "List of warnings", body = Vec<Warning>)
    )
)]
async fn list_warnings(
    State(state): State<AppState>,
    Query(query): Query<WarningsQuery>,
) -> Json<Vec<Warning>> {
    let mut warnings = if let Some(false) = query.acknowledged {
        state.warning_service.get_unacknowledged_warnings()
    } else {
        state.warning_service.get_all_warnings()
    };

    // Filter by severity if specified
    if let Some(ref sev_str) = query.severity {
        let severity = match sev_str.to_uppercase().as_str() {
            "INFO" => Some(WarningSeverity::Info),
            "WARN" | "WARNING" => Some(WarningSeverity::Warn),
            "ERROR" => Some(WarningSeverity::Error),
            "CRITICAL" => Some(WarningSeverity::Critical),
            _ => None,
        };
        if let Some(sev) = severity {
            warnings.retain(|w| w.severity == sev);
        }
    }

    // Filter by category if specified
    if let Some(ref cat_str) = query.category {
        let category = match cat_str.to_uppercase().as_str() {
            "ROUTING" => Some(WarningCategory::Routing),
            "PROCESSING" => Some(WarningCategory::Processing),
            "CONFIGURATION" => Some(WarningCategory::Configuration),
            "GROUPTHREADRESTART" => Some(WarningCategory::GroupThreadRestart),
            "RATELIMITING" => Some(WarningCategory::RateLimiting),
            "QUEUECONNECTIVITY" => Some(WarningCategory::QueueConnectivity),
            "POOLCAPACITY" => Some(WarningCategory::PoolCapacity),
            "CONSUMERHEALTH" => Some(WarningCategory::ConsumerHealth),
            "RESOURCE" => Some(WarningCategory::Resource),
            _ => None,
        };
        if let Some(cat) = category {
            warnings.retain(|w| w.category == cat);
        }
    }

    // Sort by created_at descending (newest first)
    warnings.sort_by(|a, b| b.created_at.cmp(&a.created_at));

    Json(warnings)
}

/// Acknowledge a warning
#[utoipa::path(
    post,
    path = "/warnings/{id}/acknowledge",
    tag = "warnings",
    params(
        ("id" = String, Path, description = "Warning ID to acknowledge")
    ),
    responses(
        (status = 200, description = "Warning acknowledged"),
        (status = 404, description = "Warning not found")
    )
)]
async fn acknowledge_warning(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Response {
    if state.warning_service.acknowledge_warning(&id) {
        debug!(id = %id, "Warning acknowledged");
        (StatusCode::OK, Json(serde_json::json!({ "acknowledged": true }))).into_response()
    } else {
        (StatusCode::NOT_FOUND, Json(serde_json::json!({ "error": "Warning not found" }))).into_response()
    }
}

/// Acknowledge all warnings
#[utoipa::path(
    post,
    path = "/warnings/acknowledge-all",
    tag = "warnings",
    responses(
        (status = 200, description = "All warnings acknowledged")
    )
)]
async fn acknowledge_all_warnings(State(state): State<AppState>) -> Json<serde_json::Value> {
    let count = state.warning_service.acknowledge_matching(|_| true);
    debug!(count = count, "Acknowledged all warnings");
    Json(serde_json::json!({ "acknowledged": count }))
}

/// Get critical warnings
#[utoipa::path(
    get,
    path = "/warnings/critical",
    tag = "warnings",
    responses(
        (status = 200, description = "Critical warnings", body = Vec<Warning>)
    )
)]
async fn get_critical_warnings(State(state): State<AppState>) -> Json<Vec<Warning>> {
    Json(state.warning_service.get_critical_warnings())
}

// ============================================================================
// Dashboard-Compatible Endpoints
// ============================================================================

/// Dashboard health response matching Java format
#[derive(Serialize, ToSchema)]
struct DashboardHealthResponse {
    status: String,
    #[serde(rename = "uptimeMillis")]
    uptime_millis: u64,
    details: Option<DashboardHealthDetails>,
}

#[derive(Serialize, ToSchema)]
struct DashboardHealthDetails {
    #[serde(rename = "totalQueues")]
    total_queues: u32,
    #[serde(rename = "healthyQueues")]
    healthy_queues: u32,
    #[serde(rename = "totalPools")]
    total_pools: u32,
    #[serde(rename = "healthyPools")]
    healthy_pools: u32,
    #[serde(rename = "activeWarnings")]
    active_warnings: u32,
    #[serde(rename = "criticalWarnings")]
    critical_warnings: u32,
    #[serde(rename = "circuitBreakersOpen")]
    circuit_breakers_open: u32,
    #[serde(rename = "degradationReason")]
    degradation_reason: Option<String>,
}

static START_TIME: std::sync::OnceLock<std::time::Instant> = std::sync::OnceLock::new();

fn get_uptime_millis() -> u64 {
    START_TIME.get_or_init(std::time::Instant::now).elapsed().as_millis() as u64
}

/// Health endpoint for dashboard
#[utoipa::path(
    get,
    path = "/monitoring/health",
    tag = "monitoring",
    responses(
        (status = 200, description = "Dashboard health", body = DashboardHealthResponse)
    )
)]
async fn dashboard_health_handler(State(state): State<AppState>) -> Json<DashboardHealthResponse> {
    let pool_stats = state.queue_manager.get_pool_stats();
    let health_report = state.health_service.get_health_report(&pool_stats);

    let status = match health_report.status {
        HealthStatus::Healthy => "HEALTHY",
        HealthStatus::Warning => "WARNING",
        HealthStatus::Degraded => "DEGRADED",
    };

    let degradation_reason = if !health_report.issues.is_empty() {
        Some(health_report.issues.join("; "))
    } else {
        None
    };

    Json(DashboardHealthResponse {
        status: status.to_string(),
        uptime_millis: get_uptime_millis(),
        details: Some(DashboardHealthDetails {
            total_queues: (health_report.consumers_healthy + health_report.consumers_unhealthy) as u32,
            healthy_queues: health_report.consumers_healthy,
            total_pools: (health_report.pools_healthy + health_report.pools_unhealthy) as u32,
            healthy_pools: health_report.pools_healthy,
            active_warnings: health_report.active_warnings,
            critical_warnings: health_report.critical_warnings,
            circuit_breakers_open: 0,
            degradation_reason,
        }),
    })
}

/// Queue stats for dashboard
#[derive(Serialize, ToSchema)]
struct DashboardQueueStats {
    name: String,
    #[serde(rename = "totalMessages")]
    total_messages: u64,
    #[serde(rename = "totalConsumed")]
    total_consumed: u64,
    #[serde(rename = "totalFailed")]
    total_failed: u64,
    #[serde(rename = "successRate")]
    success_rate: f64,
    #[serde(rename = "successRate5min")]
    success_rate_5min: f64,
    #[serde(rename = "successRate30min")]
    success_rate_30min: f64,
    #[serde(rename = "totalMessages5min")]
    total_messages_5min: u64,
    #[serde(rename = "totalConsumed5min")]
    total_consumed_5min: u64,
    #[serde(rename = "totalFailed5min")]
    total_failed_5min: u64,
    #[serde(rename = "totalMessages30min")]
    total_messages_30min: u64,
    #[serde(rename = "totalConsumed30min")]
    total_consumed_30min: u64,
    #[serde(rename = "totalFailed30min")]
    total_failed_30min: u64,
    #[serde(rename = "pendingMessages")]
    pending_messages: u64,
    #[serde(rename = "messagesNotVisible")]
    messages_not_visible: u64,
    throughput: f64,
}

/// Queue stats endpoint for dashboard
#[utoipa::path(
    get,
    path = "/monitoring/queue-stats",
    tag = "monitoring",
    responses(
        (status = 200, description = "Queue stats for dashboard")
    )
)]
async fn dashboard_queue_stats_handler(State(state): State<AppState>) -> Json<HashMap<String, DashboardQueueStats>> {
    let metrics = state.queue_manager.get_queue_metrics().await;
    let mut result = HashMap::new();

    for m in metrics {
        let total = m.pending_messages + m.in_flight_messages;
        let consumed = total.saturating_sub(m.pending_messages);

        let stats = DashboardQueueStats {
            name: m.queue_identifier.clone(),
            total_messages: total,
            total_consumed: consumed,
            total_failed: 0,
            success_rate: if total > 0 { consumed as f64 / total as f64 } else { 1.0 },
            success_rate_5min: 1.0,
            success_rate_30min: 1.0,
            total_messages_5min: 0,
            total_consumed_5min: 0,
            total_failed_5min: 0,
            total_messages_30min: 0,
            total_consumed_30min: 0,
            total_failed_30min: 0,
            pending_messages: m.pending_messages,
            messages_not_visible: m.in_flight_messages,
            throughput: 0.0,
        };
        result.insert(m.queue_identifier, stats);
    }

    Json(result)
}

/// Pool stats for dashboard
#[derive(Serialize, ToSchema)]
struct DashboardPoolStats {
    #[serde(rename = "poolCode")]
    pool_code: String,
    #[serde(rename = "totalProcessed")]
    total_processed: u64,
    #[serde(rename = "totalSucceeded")]
    total_succeeded: u64,
    #[serde(rename = "totalFailed")]
    total_failed: u64,
    #[serde(rename = "totalRateLimited")]
    total_rate_limited: u64,
    #[serde(rename = "successRate")]
    success_rate: f64,
    #[serde(rename = "successRate5min")]
    success_rate_5min: f64,
    #[serde(rename = "successRate30min")]
    success_rate_30min: f64,
    #[serde(rename = "totalProcessed5min")]
    total_processed_5min: u64,
    #[serde(rename = "totalSucceeded5min")]
    total_succeeded_5min: u64,
    #[serde(rename = "totalProcessed30min")]
    total_processed_30min: u64,
    #[serde(rename = "totalSucceeded30min")]
    total_succeeded_30min: u64,
    #[serde(rename = "activeWorkers")]
    active_workers: u32,
    #[serde(rename = "availablePermits")]
    available_permits: u32,
    #[serde(rename = "maxConcurrency")]
    max_concurrency: u32,
    #[serde(rename = "queueSize")]
    queue_size: u32,
    #[serde(rename = "maxQueueCapacity")]
    max_queue_capacity: u32,
    #[serde(rename = "averageProcessingTimeMs")]
    average_processing_time_ms: f64,
}

/// Pool stats endpoint for dashboard
#[utoipa::path(
    get,
    path = "/monitoring/pool-stats",
    tag = "monitoring",
    responses(
        (status = 200, description = "Pool stats for dashboard")
    )
)]
async fn dashboard_pool_stats_handler(State(state): State<AppState>) -> Json<HashMap<String, DashboardPoolStats>> {
    let pool_stats = state.queue_manager.get_pool_stats();
    let mut result = HashMap::new();

    for s in pool_stats {
        let stats = DashboardPoolStats {
            pool_code: s.pool_code.clone(),
            total_processed: 0,
            total_succeeded: 0,
            total_failed: 0,
            total_rate_limited: 0,
            success_rate: 1.0,
            success_rate_5min: 1.0,
            success_rate_30min: 1.0,
            total_processed_5min: 0,
            total_succeeded_5min: 0,
            total_processed_30min: 0,
            total_succeeded_30min: 0,
            active_workers: s.active_workers,
            available_permits: s.concurrency.saturating_sub(s.active_workers),
            max_concurrency: s.concurrency,
            queue_size: s.queue_size,
            max_queue_capacity: s.queue_capacity,
            average_processing_time_ms: 0.0,
        };
        result.insert(s.pool_code, stats);
    }

    Json(result)
}

/// Warning format for dashboard
#[derive(Serialize, ToSchema)]
struct DashboardWarning {
    id: String,
    timestamp: String,
    severity: String,
    category: String,
    source: String,
    message: String,
    acknowledged: bool,
}

/// Warnings endpoint for dashboard
#[utoipa::path(
    get,
    path = "/monitoring/warnings",
    tag = "monitoring",
    responses(
        (status = 200, description = "Warnings for dashboard", body = Vec<DashboardWarning>)
    )
)]
async fn dashboard_warnings_handler(State(state): State<AppState>) -> Json<Vec<DashboardWarning>> {
    let warnings = state.warning_service.get_all_warnings();

    let result: Vec<DashboardWarning> = warnings
        .into_iter()
        .map(|w| DashboardWarning {
            id: w.id,
            timestamp: w.created_at.to_rfc3339(),
            severity: format!("{:?}", w.severity).to_uppercase(),
            category: format!("{:?}", w.category).to_uppercase(),
            source: w.source,
            message: w.message,
            acknowledged: w.acknowledged,
        })
        .collect();

    Json(result)
}

/// Circuit breaker stats for dashboard
#[derive(Serialize, ToSchema)]
struct DashboardCircuitBreakerStats {
    name: String,
    state: String,
    #[serde(rename = "successfulCalls")]
    successful_calls: u64,
    #[serde(rename = "failedCalls")]
    failed_calls: u64,
    #[serde(rename = "rejectedCalls")]
    rejected_calls: u64,
    #[serde(rename = "failureRate")]
    failure_rate: f64,
    #[serde(rename = "bufferedCalls")]
    buffered_calls: u32,
    #[serde(rename = "bufferSize")]
    buffer_size: u32,
}

/// Circuit breakers endpoint for dashboard
#[utoipa::path(
    get,
    path = "/monitoring/circuit-breakers",
    tag = "monitoring",
    responses(
        (status = 200, description = "Circuit breakers for dashboard")
    )
)]
async fn dashboard_circuit_breakers_handler() -> Json<HashMap<String, DashboardCircuitBreakerStats>> {
    Json(HashMap::new())
}

/// Query params for in-flight messages
#[derive(Deserialize, Default, ToSchema)]
struct InFlightMessagesQuery {
    limit: Option<usize>,
    #[serde(rename = "messageId")]
    message_id: Option<String>,
}

/// In-flight messages endpoint for dashboard
#[utoipa::path(
    get,
    path = "/monitoring/in-flight-messages",
    tag = "monitoring",
    params(
        ("limit" = Option<usize>, Query, description = "Maximum number of messages to return"),
        ("messageId" = Option<String>, Query, description = "Filter by message ID")
    ),
    responses(
        (status = 200, description = "In-flight messages", body = Vec<InFlightMessageInfo>)
    )
)]
async fn dashboard_in_flight_messages_handler(
    State(state): State<AppState>,
    Query(query): Query<InFlightMessagesQuery>,
) -> Json<Vec<InFlightMessageInfo>> {
    let limit = query.limit.unwrap_or(100);
    let messages = state.queue_manager.get_in_flight_messages(limit, query.message_id.as_deref());
    Json(messages)
}

/// Serve dashboard HTML
async fn dashboard_html_handler() -> impl IntoResponse {
    const DASHBOARD_HTML: &str = include_str!("../resources/dashboard.html");
    Html(DASHBOARD_HTML)
}

// ============================================================================
// Message Publishing
// ============================================================================

/// Publish a message
#[utoipa::path(
    post,
    path = "/messages",
    tag = "messages",
    request_body = PublishMessageRequest,
    responses(
        (status = 200, description = "Message published", body = PublishMessageResponse),
        (status = 500, description = "Failed to publish")
    )
)]
async fn publish_message(
    State(state): State<AppState>,
    Json(req): Json<PublishMessageRequest>,
) -> Response {
    let message_id = Uuid::new_v4().to_string();

    let message = Message {
        id: message_id.clone(),
        pool_code: req.pool_code.unwrap_or_else(|| "DEFAULT".to_string()),
        auth_token: None,
        signing_secret: None,
        mediation_type: MediationType::HTTP,
        mediation_target: req.mediation_target.unwrap_or_else(|| "http://localhost:8080/echo".to_string()),
        message_group_id: req.message_group_id,
        payload: req.payload,
        created_at: Utc::now(),
    };

    match state.publisher.publish(message).await {
        Ok(_) => {
            (StatusCode::OK, Json(PublishMessageResponse {
                message_id,
                status: "ACCEPTED".to_string(),
            })).into_response()
        }
        Err(_) => {
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "error": "Failed to publish message" }))).into_response()
        }
    }
}

/// Simple publish message (for simple router)
async fn simple_publish_message(
    State(state): State<SimpleState>,
    Json(req): Json<PublishMessageRequest>,
) -> Response {
    let message_id = Uuid::new_v4().to_string();

    let message = Message {
        id: message_id.clone(),
        pool_code: req.pool_code.unwrap_or_else(|| "DEFAULT".to_string()),
        auth_token: None,
        signing_secret: None,
        mediation_type: MediationType::HTTP,
        mediation_target: req.mediation_target.unwrap_or_else(|| "http://localhost:8080/echo".to_string()),
        message_group_id: req.message_group_id,
        payload: req.payload,
        created_at: Utc::now(),
    };

    match state.publisher.publish(message).await {
        Ok(_) => {
            (StatusCode::OK, Json(PublishMessageResponse {
                message_id,
                status: "ACCEPTED".to_string(),
            })).into_response()
        }
        Err(_) => {
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "error": "Failed to publish message" }))).into_response()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_severity_parsing() {
        let cases = [
            ("INFO", Some(WarningSeverity::Info)),
            ("WARN", Some(WarningSeverity::Warn)),
            ("WARNING", Some(WarningSeverity::Warn)),
            ("ERROR", Some(WarningSeverity::Error)),
            ("CRITICAL", Some(WarningSeverity::Critical)),
            ("UNKNOWN", None),
        ];

        for (input, expected) in cases {
            let result = match input.to_uppercase().as_str() {
                "INFO" => Some(WarningSeverity::Info),
                "WARN" | "WARNING" => Some(WarningSeverity::Warn),
                "ERROR" => Some(WarningSeverity::Error),
                "CRITICAL" => Some(WarningSeverity::Critical),
                _ => None,
            };
            assert_eq!(result, expected);
        }
    }
}
