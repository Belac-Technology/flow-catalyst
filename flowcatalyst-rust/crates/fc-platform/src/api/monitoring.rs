//! Monitoring API
//!
//! REST endpoints for platform monitoring and observability.

use axum::{
    extract::State,
    routing::get,
    Json, Router,
};
use serde::Serialize;
use std::sync::Arc;
use std::collections::HashMap;
use tokio::sync::RwLock;
use utoipa::ToSchema;

use crate::api::common::ApiResult;
use crate::api::middleware::Authenticated;
use crate::repository::DispatchJobRepository;
use crate::domain::DispatchStatus;

/// Standby status response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct StandbyStatus {
    /// Whether this instance is the leader
    pub is_leader: bool,
    /// Instance ID
    pub instance_id: String,
    /// Current role (LEADER or STANDBY)
    pub role: String,
    /// Leader instance ID (if known)
    pub leader_id: Option<String>,
    /// Last heartbeat time
    pub last_heartbeat: Option<String>,
    /// Cluster members
    pub cluster_members: Vec<ClusterMember>,
}

/// Cluster member info
#[derive(Debug, Clone, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ClusterMember {
    pub instance_id: String,
    pub role: String,
    pub last_seen: String,
    pub healthy: bool,
}

/// Dashboard metrics response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct DashboardMetrics {
    /// Total events received
    pub total_events: u64,
    /// Events in last hour
    pub events_last_hour: u64,
    /// Total dispatch jobs
    pub total_jobs: u64,
    /// Jobs by status
    pub jobs_by_status: HashMap<String, u64>,
    /// Active subscriptions
    pub active_subscriptions: u64,
    /// Active dispatch pools
    pub active_pools: u64,
    /// System health
    pub health: SystemHealth,
}

/// System health info
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct SystemHealth {
    pub status: String,
    pub uptime_seconds: u64,
    pub memory_used_mb: u64,
    pub cpu_usage_percent: f32,
}

/// Circuit breaker state
#[derive(Debug, Clone, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CircuitBreakerState {
    /// Target identifier
    pub target: String,
    /// Current state (CLOSED, OPEN, HALF_OPEN)
    pub state: String,
    /// Failure count
    pub failure_count: u32,
    /// Success count since last failure
    pub success_count: u32,
    /// Last failure time
    pub last_failure: Option<String>,
    /// Time until reset (if open)
    pub reset_at: Option<String>,
}

/// Circuit breakers response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CircuitBreakersResponse {
    pub breakers: Vec<CircuitBreakerState>,
    pub total_open: usize,
    pub total_half_open: usize,
    pub total_closed: usize,
}

/// In-flight message info
#[derive(Debug, Clone, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct InFlightMessage {
    pub job_id: String,
    pub event_id: Option<String>,
    pub target_url: String,
    pub started_at: String,
    pub elapsed_ms: u64,
    pub attempt: u32,
    pub pool_id: Option<String>,
    pub message_group: Option<String>,
}

/// In-flight messages response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct InFlightMessagesResponse {
    pub messages: Vec<InFlightMessage>,
    pub total_in_flight: usize,
    pub by_pool: HashMap<String, usize>,
    pub by_message_group: HashMap<String, usize>,
}

/// Leader election state (shared across handlers)
#[derive(Clone)]
pub struct LeaderState {
    pub is_leader: Arc<RwLock<bool>>,
    pub instance_id: String,
    pub leader_id: Arc<RwLock<Option<String>>>,
    pub cluster_members: Arc<RwLock<Vec<ClusterMember>>>,
}

impl LeaderState {
    pub fn new(instance_id: String) -> Self {
        Self {
            is_leader: Arc::new(RwLock::new(false)),
            instance_id,
            leader_id: Arc::new(RwLock::new(None)),
            cluster_members: Arc::new(RwLock::new(vec![])),
        }
    }

    pub async fn set_leader(&self, is_leader: bool) {
        let mut guard = self.is_leader.write().await;
        *guard = is_leader;
        if is_leader {
            let mut leader = self.leader_id.write().await;
            *leader = Some(self.instance_id.clone());
        }
    }
}

/// Circuit breaker registry
#[derive(Clone, Default)]
pub struct CircuitBreakerRegistry {
    pub breakers: Arc<RwLock<HashMap<String, CircuitBreakerState>>>,
}

impl CircuitBreakerRegistry {
    pub fn new() -> Self {
        Self {
            breakers: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn get_all(&self) -> Vec<CircuitBreakerState> {
        let guard = self.breakers.read().await;
        guard.values().cloned().collect()
    }

    pub async fn update(&self, target: &str, state: CircuitBreakerState) {
        let mut guard = self.breakers.write().await;
        guard.insert(target.to_string(), state);
    }
}

/// In-flight message tracker
#[derive(Clone, Default)]
pub struct InFlightTracker {
    pub messages: Arc<RwLock<HashMap<String, InFlightMessage>>>,
}

impl InFlightTracker {
    pub fn new() -> Self {
        Self {
            messages: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn add(&self, job_id: &str, msg: InFlightMessage) {
        let mut guard = self.messages.write().await;
        guard.insert(job_id.to_string(), msg);
    }

    pub async fn remove(&self, job_id: &str) {
        let mut guard = self.messages.write().await;
        guard.remove(job_id);
    }

    pub async fn get_all(&self) -> Vec<InFlightMessage> {
        let guard = self.messages.read().await;
        guard.values().cloned().collect()
    }
}

/// Monitoring service state
#[derive(Clone)]
pub struct MonitoringState {
    pub leader_state: LeaderState,
    pub circuit_breakers: CircuitBreakerRegistry,
    pub in_flight: InFlightTracker,
    pub dispatch_job_repo: Arc<DispatchJobRepository>,
    pub start_time: std::time::Instant,
}

/// Get standby status
pub async fn get_standby_status(
    State(state): State<MonitoringState>,
    Authenticated(auth): Authenticated,
) -> ApiResult<StandbyStatus> {
    crate::service::checks::require_anchor(&auth)?;

    let is_leader = *state.leader_state.is_leader.read().await;
    let leader_id = state.leader_state.leader_id.read().await.clone();
    let cluster_members = state.leader_state.cluster_members.read().await.clone();

    Ok(Json(StandbyStatus {
        is_leader,
        instance_id: state.leader_state.instance_id.clone(),
        role: if is_leader { "LEADER".to_string() } else { "STANDBY".to_string() },
        leader_id,
        last_heartbeat: Some(chrono::Utc::now().to_rfc3339()),
        cluster_members,
    }))
}

/// Get dashboard metrics
pub async fn get_dashboard(
    State(state): State<MonitoringState>,
    Authenticated(auth): Authenticated,
) -> ApiResult<DashboardMetrics> {
    crate::service::checks::require_anchor(&auth)?;

    // Get job counts by status
    let pending = state.dispatch_job_repo.count_by_status(DispatchStatus::Pending).await.unwrap_or(0);
    let queued = state.dispatch_job_repo.count_by_status(DispatchStatus::Queued).await.unwrap_or(0);
    let in_progress = state.dispatch_job_repo.count_by_status(DispatchStatus::InProgress).await.unwrap_or(0);
    let completed = state.dispatch_job_repo.count_by_status(DispatchStatus::Completed).await.unwrap_or(0);
    let failed = state.dispatch_job_repo.count_by_status(DispatchStatus::Failed).await.unwrap_or(0);

    let mut jobs_by_status = HashMap::new();
    jobs_by_status.insert("PENDING".to_string(), pending);
    jobs_by_status.insert("QUEUED".to_string(), queued);
    jobs_by_status.insert("IN_PROGRESS".to_string(), in_progress);
    jobs_by_status.insert("COMPLETED".to_string(), completed);
    jobs_by_status.insert("FAILED".to_string(), failed);

    let total_jobs = pending + queued + in_progress + completed + failed;

    Ok(Json(DashboardMetrics {
        total_events: 0, // Would need event repo
        events_last_hour: 0,
        total_jobs,
        jobs_by_status,
        active_subscriptions: 0, // Would need subscription repo
        active_pools: 0, // Would need pool repo
        health: SystemHealth {
            status: "UP".to_string(),
            uptime_seconds: state.start_time.elapsed().as_secs(),
            memory_used_mb: 0, // Could use sysinfo crate
            cpu_usage_percent: 0.0,
        },
    }))
}

/// Get circuit breaker states
pub async fn get_circuit_breakers(
    State(state): State<MonitoringState>,
    Authenticated(auth): Authenticated,
) -> ApiResult<CircuitBreakersResponse> {
    crate::service::checks::require_anchor(&auth)?;

    let breakers = state.circuit_breakers.get_all().await;

    let total_open = breakers.iter().filter(|b| b.state == "OPEN").count();
    let total_half_open = breakers.iter().filter(|b| b.state == "HALF_OPEN").count();
    let total_closed = breakers.iter().filter(|b| b.state == "CLOSED").count();

    Ok(Json(CircuitBreakersResponse {
        breakers,
        total_open,
        total_half_open,
        total_closed,
    }))
}

/// Get in-flight messages
pub async fn get_in_flight_messages(
    State(state): State<MonitoringState>,
    Authenticated(auth): Authenticated,
) -> ApiResult<InFlightMessagesResponse> {
    crate::service::checks::require_anchor(&auth)?;

    let messages = state.in_flight.get_all().await;
    let total_in_flight = messages.len();

    // Group by pool
    let mut by_pool: HashMap<String, usize> = HashMap::new();
    for msg in &messages {
        if let Some(ref pool_id) = msg.pool_id {
            *by_pool.entry(pool_id.clone()).or_insert(0) += 1;
        }
    }

    // Group by message group
    let mut by_message_group: HashMap<String, usize> = HashMap::new();
    for msg in &messages {
        if let Some(ref group) = msg.message_group {
            *by_message_group.entry(group.clone()).or_insert(0) += 1;
        }
    }

    Ok(Json(InFlightMessagesResponse {
        messages,
        total_in_flight,
        by_pool,
        by_message_group,
    }))
}

/// Create monitoring router
pub fn monitoring_router(state: MonitoringState) -> Router {
    Router::new()
        .route("/standby-status", get(get_standby_status))
        .route("/dashboard", get(get_dashboard))
        .route("/circuit-breakers", get(get_circuit_breakers))
        .route("/in-flight-messages", get(get_in_flight_messages))
        .with_state(state)
}
