//! Dispatch Jobs BFF API
//!
//! REST endpoints for viewing dispatch job status.

use axum::{
    extract::{Path, Query, State},
    routing::get,
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use utoipa::ToSchema;

use crate::domain::{DispatchJob, DispatchJobRead, DispatchStatus};
use crate::repository::DispatchJobRepository;
use crate::error::PlatformError;
use crate::api::common::{ApiResult, PaginationParams};
use crate::api::middleware::Authenticated;

/// Dispatch job response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct DispatchJobResponse {
    pub id: String,
    pub external_id: Option<String>,
    pub kind: String,
    pub code: String,
    pub source: String,
    pub subject: Option<String>,
    pub target_url: String,
    pub event_id: Option<String>,
    pub correlation_id: Option<String>,
    pub client_id: Option<String>,
    pub subscription_id: Option<String>,
    pub dispatch_pool_id: Option<String>,
    pub message_group: Option<String>,
    pub mode: String,
    pub status: String,
    pub attempt_count: u32,
    pub max_retries: u32,
    pub last_error: Option<String>,
    pub created_at: String,
    pub queued_at: Option<String>,
    pub completed_at: Option<String>,
    pub duration_millis: Option<i64>,
}

impl From<DispatchJob> for DispatchJobResponse {
    fn from(job: DispatchJob) -> Self {
        Self {
            id: job.id,
            external_id: job.external_id,
            kind: format!("{:?}", job.kind).to_uppercase(),
            code: job.code,
            source: job.source,
            subject: job.subject,
            target_url: job.target_url,
            event_id: job.event_id,
            correlation_id: job.correlation_id,
            client_id: job.client_id,
            subscription_id: job.subscription_id,
            dispatch_pool_id: job.dispatch_pool_id,
            message_group: job.message_group,
            mode: format!("{:?}", job.mode).to_uppercase(),
            status: format!("{:?}", job.status).to_uppercase(),
            attempt_count: job.attempt_count,
            max_retries: job.max_retries,
            last_error: job.last_error,
            created_at: job.created_at.to_rfc3339(),
            queued_at: job.queued_at.map(|t| t.to_rfc3339()),
            completed_at: job.completed_at.map(|t| t.to_rfc3339()),
            duration_millis: job.duration_millis,
        }
    }
}

/// Dispatch job read projection response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct DispatchJobReadResponse {
    pub id: String,
    pub external_id: Option<String>,
    pub kind: String,
    pub code: String,
    pub source: String,
    pub target_url: String,
    pub event_id: Option<String>,
    pub correlation_id: Option<String>,
    pub client_id: Option<String>,
    pub client_name: Option<String>,
    pub subscription_id: Option<String>,
    pub subscription_name: Option<String>,
    pub status: String,
    pub attempt_count: u32,
    pub max_retries: u32,
    pub last_error: Option<String>,
    pub created_at: String,
    pub completed_at: Option<String>,
    pub duration_millis: Option<i64>,
}

impl From<DispatchJobRead> for DispatchJobReadResponse {
    fn from(job: DispatchJobRead) -> Self {
        Self {
            id: job.id,
            external_id: job.external_id,
            kind: format!("{:?}", job.kind).to_uppercase(),
            code: job.code,
            source: job.source,
            target_url: job.target_url,
            event_id: job.event_id,
            correlation_id: job.correlation_id,
            client_id: job.client_id,
            client_name: job.client_name,
            subscription_id: job.subscription_id,
            subscription_name: job.subscription_name,
            status: format!("{:?}", job.status).to_uppercase(),
            attempt_count: job.attempt_count,
            max_retries: job.max_retries,
            last_error: job.last_error,
            created_at: job.created_at.to_rfc3339(),
            completed_at: job.completed_at.map(|t| t.to_rfc3339()),
            duration_millis: job.duration_millis,
        }
    }
}

/// Query parameters for dispatch jobs list
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DispatchJobsQuery {
    #[serde(flatten)]
    pub pagination: PaginationParams,

    /// Filter by event ID
    pub event_id: Option<String>,

    /// Filter by correlation ID
    pub correlation_id: Option<String>,

    /// Filter by subscription ID
    pub subscription_id: Option<String>,

    /// Filter by client ID
    pub client_id: Option<String>,

    /// Filter by status
    pub status: Option<String>,
}

/// Dispatch jobs service state
#[derive(Clone)]
pub struct DispatchJobsState {
    pub dispatch_job_repo: Arc<DispatchJobRepository>,
}

/// Get dispatch job by ID
pub async fn get_dispatch_job(
    State(state): State<DispatchJobsState>,
    Authenticated(auth): Authenticated,
    Path(id): Path<String>,
) -> ApiResult<DispatchJobResponse> {
    crate::service::checks::can_read_dispatch_jobs(&auth)?;

    let job = state.dispatch_job_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("DispatchJob", &id))?;

    // Check client access
    if let Some(ref cid) = job.client_id {
        if !auth.can_access_client(cid) {
            return Err(PlatformError::forbidden("No access to this dispatch job"));
        }
    }

    Ok(Json(job.into()))
}

/// List dispatch jobs
pub async fn list_dispatch_jobs(
    State(state): State<DispatchJobsState>,
    Authenticated(auth): Authenticated,
    Query(query): Query<DispatchJobsQuery>,
) -> ApiResult<Vec<DispatchJobResponse>> {
    crate::service::checks::can_read_dispatch_jobs(&auth)?;

    let jobs = if let Some(ref event_id) = query.event_id {
        state.dispatch_job_repo.find_by_event_id(event_id).await?
    } else if let Some(ref corr_id) = query.correlation_id {
        state.dispatch_job_repo.find_by_correlation_id(corr_id).await?
    } else if let Some(ref sub_id) = query.subscription_id {
        state.dispatch_job_repo.find_by_subscription_id(sub_id, query.pagination.limit as i64).await?
    } else if let Some(ref client_id) = query.client_id {
        if !auth.can_access_client(client_id) {
            return Err(PlatformError::forbidden(format!("No access to client: {}", client_id)));
        }
        state.dispatch_job_repo.find_by_client(client_id, query.pagination.limit as i64).await?
    } else if let Some(ref status_str) = query.status {
        let status = match status_str.to_uppercase().as_str() {
            "PENDING" => DispatchStatus::Pending,
            "QUEUED" => DispatchStatus::Queued,
            "IN_PROGRESS" => DispatchStatus::InProgress,
            "COMPLETED" => DispatchStatus::Completed,
            "FAILED" => DispatchStatus::Failed,
            "EXPIRED" => DispatchStatus::Expired,
            _ => return Err(PlatformError::validation(format!("Invalid status: {}", status_str))),
        };
        state.dispatch_job_repo.find_by_status(status, query.pagination.limit as i64).await?
    } else {
        // Return empty for now - need proper listing
        vec![]
    };

    // Filter by client access
    let filtered: Vec<DispatchJobResponse> = jobs.into_iter()
        .filter(|j| {
            match &j.client_id {
                Some(cid) => auth.can_access_client(cid),
                None => auth.is_anchor(),
            }
        })
        .map(|j| j.into())
        .collect();

    Ok(Json(filtered))
}

/// Get dispatch jobs for an event
pub async fn get_jobs_for_event(
    State(state): State<DispatchJobsState>,
    Authenticated(auth): Authenticated,
    Path(event_id): Path<String>,
) -> ApiResult<Vec<DispatchJobResponse>> {
    crate::service::checks::can_read_dispatch_jobs(&auth)?;

    let jobs = state.dispatch_job_repo.find_by_event_id(&event_id).await?;

    // Filter by client access
    let filtered: Vec<DispatchJobResponse> = jobs.into_iter()
        .filter(|j| {
            match &j.client_id {
                Some(cid) => auth.can_access_client(cid),
                None => auth.is_anchor(),
            }
        })
        .map(|j| j.into())
        .collect();

    Ok(Json(filtered))
}

/// Create dispatch jobs router
pub fn dispatch_jobs_router(state: DispatchJobsState) -> Router {
    Router::new()
        .route("/", get(list_dispatch_jobs))
        .route("/:id", get(get_dispatch_job))
        .route("/by-event/:event_id", get(get_jobs_for_event))
        .with_state(state)
}
