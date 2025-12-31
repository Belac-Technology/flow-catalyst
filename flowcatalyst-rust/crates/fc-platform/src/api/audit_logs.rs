//! Audit Logs Admin API
//!
//! REST endpoints for viewing audit logs.

use axum::{
    routing::get,
    extract::{State, Path, Query},
    Json, Router,
};
use utoipa::{ToSchema, IntoParams};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use chrono::{DateTime, Utc};

use crate::domain::{AuditLog, AuditAction};
use crate::repository::AuditLogRepository;
use crate::error::PlatformError;
use crate::api::common::PaginationParams;
use crate::api::middleware::Authenticated;

/// Audit log response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct AuditLogResponse {
    pub id: String,
    pub action: String,
    pub entity_type: String,
    pub entity_id: Option<String>,
    pub description: String,
    pub principal_id: Option<String>,
    pub principal_email: Option<String>,
    pub client_id: Option<String>,
    pub ip_address: Option<String>,
    pub request_id: Option<String>,
    pub created_at: String,
}

impl From<AuditLog> for AuditLogResponse {
    fn from(log: AuditLog) -> Self {
        Self {
            id: log.id,
            action: format!("{:?}", log.action).to_uppercase(),
            entity_type: log.entity_type,
            entity_id: log.entity_id,
            description: log.description,
            principal_id: log.principal_id,
            principal_email: log.principal_email,
            client_id: log.client_id,
            ip_address: log.ip_address,
            request_id: log.request_id,
            created_at: log.created_at.to_rfc3339(),
        }
    }
}

/// Query parameters for audit logs
#[derive(Debug, Default, Deserialize, IntoParams)]
#[serde(rename_all = "camelCase")]
#[into_params(parameter_in = Query)]
pub struct AuditLogsQuery {
    #[serde(flatten)]
    pub pagination: PaginationParams,

    /// Filter by entity type
    pub entity_type: Option<String>,

    /// Filter by entity ID
    pub entity_id: Option<String>,

    /// Filter by action
    pub action: Option<String>,

    /// Filter by principal ID
    pub principal_id: Option<String>,

    /// Filter by client ID
    pub client_id: Option<String>,

    /// Filter from date (ISO 8601)
    pub from_date: Option<String>,

    /// Filter to date (ISO 8601)
    pub to_date: Option<String>,
}

/// Audit logs service state
#[derive(Clone)]
pub struct AuditLogsState {
    pub audit_log_repo: Arc<AuditLogRepository>,
}

fn parse_action(s: &str) -> Option<AuditAction> {
    match s.to_uppercase().as_str() {
        "CREATE" => Some(AuditAction::Create),
        "UPDATE" => Some(AuditAction::Update),
        "DELETE" => Some(AuditAction::Delete),
        "ARCHIVE" => Some(AuditAction::Archive),
        "LOGIN" => Some(AuditAction::Login),
        "LOGOUT" => Some(AuditAction::Logout),
        "TOKEN_ISSUED" => Some(AuditAction::TokenIssued),
        "TOKEN_REVOKED" => Some(AuditAction::TokenRevoked),
        "PERMISSION_GRANTED" => Some(AuditAction::PermissionGranted),
        "PERMISSION_REVOKED" => Some(AuditAction::PermissionRevoked),
        "ROLE_ASSIGNED" => Some(AuditAction::RoleAssigned),
        "ROLE_UNASSIGNED" => Some(AuditAction::RoleUnassigned),
        "CLIENT_ACCESS_GRANTED" => Some(AuditAction::ClientAccessGranted),
        "CLIENT_ACCESS_REVOKED" => Some(AuditAction::ClientAccessRevoked),
        "SUBSCRIPTION_PAUSED" => Some(AuditAction::SubscriptionPaused),
        "SUBSCRIPTION_RESUMED" => Some(AuditAction::SubscriptionResumed),
        "POOL_PAUSED" => Some(AuditAction::PoolPaused),
        "POOL_RESUMED" => Some(AuditAction::PoolResumed),
        "CONFIG_CHANGED" => Some(AuditAction::ConfigChanged),
        _ => None,
    }
}

fn parse_datetime(s: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(s).ok().map(|dt| dt.with_timezone(&Utc))
}

/// Get audit log by ID
#[utoipa::path(
    get,
    path = "/{id}",
    tag = "audit-logs",
    params(
        ("id" = String, Path, description = "Audit log ID")
    ),
    responses(
        (status = 200, description = "Audit log found", body = AuditLogResponse),
        (status = 404, description = "Audit log not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_audit_log(
    State(state): State<AuditLogsState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<AuditLogResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let log = state.audit_log_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("AuditLog", &id))?;

    Ok(Json(log.into()))
}

/// List audit logs with filters
#[utoipa::path(
    get,
    path = "",
    tag = "audit-logs",
    params(AuditLogsQuery),
    responses(
        (status = 200, description = "List of audit logs", body = Vec<AuditLogResponse>)
    ),
    security(("bearer_auth" = []))
)]
pub async fn list_audit_logs(
    State(state): State<AuditLogsState>,
    auth: Authenticated,
    Query(query): Query<AuditLogsQuery>,
) -> Result<Json<Vec<AuditLogResponse>>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let action = query.action.as_deref().and_then(parse_action);
    let from_date = query.from_date.as_deref().and_then(parse_datetime);
    let to_date = query.to_date.as_deref().and_then(parse_datetime);

    let skip = query.pagination.offset() as u64;
    let limit = query.pagination.limit as i64;

    let logs = state.audit_log_repo.search(
        query.entity_type.as_deref(),
        query.entity_id.as_deref(),
        action,
        query.principal_id.as_deref(),
        query.client_id.as_deref(),
        from_date,
        to_date,
        skip,
        limit,
    ).await?;

    let response: Vec<AuditLogResponse> = logs.into_iter()
        .map(|l| l.into())
        .collect();

    Ok(Json(response))
}

/// Get audit logs for a specific entity
#[utoipa::path(
    get,
    path = "/entity/{entity_type}/{entity_id}",
    tag = "audit-logs",
    params(
        ("entity_type" = String, Path, description = "Entity type"),
        ("entity_id" = String, Path, description = "Entity ID")
    ),
    responses(
        (status = 200, description = "Audit logs for entity", body = Vec<AuditLogResponse>)
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_entity_audit_logs(
    State(state): State<AuditLogsState>,
    auth: Authenticated,
    Path((entity_type, entity_id)): Path<(String, String)>,
    Query(query): Query<PaginationParams>,
) -> Result<Json<Vec<AuditLogResponse>>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let limit = query.limit as i64;

    let logs = state.audit_log_repo.find_by_entity(&entity_type, &entity_id, limit).await?;

    let response: Vec<AuditLogResponse> = logs.into_iter()
        .map(|l| l.into())
        .collect();

    Ok(Json(response))
}

/// Get audit logs for a principal
#[utoipa::path(
    get,
    path = "/principal/{principal_id}",
    tag = "audit-logs",
    params(
        ("principal_id" = String, Path, description = "Principal ID")
    ),
    responses(
        (status = 200, description = "Audit logs for principal", body = Vec<AuditLogResponse>)
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_principal_audit_logs(
    State(state): State<AuditLogsState>,
    auth: Authenticated,
    Path(principal_id): Path<String>,
    Query(query): Query<PaginationParams>,
) -> Result<Json<Vec<AuditLogResponse>>, PlatformError> {
    // Allow principals to view their own audit logs
    if !auth.0.is_anchor() && auth.0.principal_id != principal_id {
        return Err(PlatformError::forbidden("Cannot view other principal's audit logs"));
    }

    let limit = query.limit as i64;

    let logs = state.audit_log_repo.find_by_principal(&principal_id, limit).await?;

    let response: Vec<AuditLogResponse> = logs.into_iter()
        .map(|l| l.into())
        .collect();

    Ok(Json(response))
}

/// Get recent audit logs
#[utoipa::path(
    get,
    path = "/recent",
    tag = "audit-logs",
    responses(
        (status = 200, description = "Recent audit logs", body = Vec<AuditLogResponse>)
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_recent_audit_logs(
    State(state): State<AuditLogsState>,
    auth: Authenticated,
    Query(query): Query<PaginationParams>,
) -> Result<Json<Vec<AuditLogResponse>>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let limit = query.limit.max(100) as i64;

    let logs = state.audit_log_repo.find_recent(limit).await?;

    let response: Vec<AuditLogResponse> = logs.into_iter()
        .map(|l| l.into())
        .collect();

    Ok(Json(response))
}

/// Create audit logs router
pub fn audit_logs_router(state: AuditLogsState) -> Router {
    Router::new()
        .route("/", get(list_audit_logs))
        .route("/recent", get(get_recent_audit_logs))
        .route("/:id", get(get_audit_log))
        .route("/entity/:entity_type/:entity_id", get(get_entity_audit_logs))
        .route("/principal/:principal_id", get(get_principal_audit_logs))
        .with_state(state)
}
