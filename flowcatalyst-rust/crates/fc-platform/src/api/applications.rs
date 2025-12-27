//! Applications Admin API
//!
//! REST endpoints for application management.
//! Applications are global platform entities (not client-scoped).

use axum::{
    extract::{Path, Query, State},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use utoipa::ToSchema;

use crate::domain::Application;
use crate::repository::ApplicationRepository;
use crate::error::PlatformError;
use crate::api::common::{ApiResult, PaginationParams, CreatedResponse, SuccessResponse};
use crate::api::middleware::Authenticated;

/// Create application request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateApplicationRequest {
    /// Unique identifier/code (URL-safe)
    pub code: String,

    /// Human-readable name
    pub name: String,

    /// Description
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,

    /// Application type: APPLICATION or INTEGRATION
    #[serde(rename = "type")]
    pub application_type: Option<String>,

    /// Default base URL
    pub default_base_url: Option<String>,

    /// Icon URL
    pub icon_url: Option<String>,
}

/// Update application request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UpdateApplicationRequest {
    /// Human-readable name
    pub name: Option<String>,

    /// Description
    pub description: Option<String>,

    /// Default base URL
    pub default_base_url: Option<String>,

    /// Icon URL
    pub icon_url: Option<String>,
}

/// Application response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ApplicationResponse {
    pub id: String,
    pub code: String,
    pub name: String,
    pub description: Option<String>,
    #[serde(rename = "type")]
    pub application_type: String,
    pub default_base_url: Option<String>,
    pub icon_url: Option<String>,
    pub service_account_id: Option<String>,
    pub active: bool,
    pub created_at: String,
    pub updated_at: String,
}

impl From<Application> for ApplicationResponse {
    fn from(a: Application) -> Self {
        Self {
            id: a.id,
            code: a.code,
            name: a.name,
            description: a.description,
            application_type: format!("{:?}", a.application_type).to_uppercase(),
            default_base_url: a.default_base_url,
            icon_url: a.icon_url,
            service_account_id: a.service_account_id,
            active: a.active,
            created_at: a.created_at.to_rfc3339(),
            updated_at: a.updated_at.to_rfc3339(),
        }
    }
}

/// Query parameters for applications list
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApplicationsQuery {
    #[serde(flatten)]
    pub pagination: PaginationParams,

    /// Filter by active status
    pub active: Option<bool>,
}

/// Applications service state
#[derive(Clone)]
pub struct ApplicationsState {
    pub application_repo: Arc<ApplicationRepository>,
}

/// Create a new application
pub async fn create_application(
    State(state): State<ApplicationsState>,
    Authenticated(auth): Authenticated,
    Json(req): Json<CreateApplicationRequest>,
) -> ApiResult<CreatedResponse> {
    // Only anchor users can manage applications
    crate::service::checks::require_anchor(&auth)?;

    // Check for duplicate code
    if let Some(_) = state.application_repo.find_by_code(&req.code).await? {
        return Err(PlatformError::duplicate("Application", "code", &req.code));
    }

    let mut app = if req.application_type.as_deref() == Some("INTEGRATION") {
        Application::integration(&req.code, &req.name)
    } else {
        Application::new(&req.code, &req.name)
    };

    if let Some(desc) = req.description {
        app = app.with_description(desc);
    }

    if let Some(url) = req.default_base_url {
        app = app.with_base_url(url);
    }

    if let Some(url) = req.icon_url {
        app = app.with_icon_url(url);
    }

    let id = app.id.clone();
    state.application_repo.insert(&app).await?;

    Ok(Json(CreatedResponse::new(id)))
}

/// Get application by ID
pub async fn get_application(
    State(state): State<ApplicationsState>,
    Authenticated(_auth): Authenticated,
    Path(id): Path<String>,
) -> ApiResult<ApplicationResponse> {
    let app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    Ok(Json(app.into()))
}

/// List applications
pub async fn list_applications(
    State(state): State<ApplicationsState>,
    Authenticated(_auth): Authenticated,
    Query(query): Query<ApplicationsQuery>,
) -> ApiResult<Vec<ApplicationResponse>> {
    let apps = if query.active.unwrap_or(true) {
        state.application_repo.find_active().await?
    } else {
        state.application_repo.find_all().await?
    };

    let response: Vec<ApplicationResponse> = apps.into_iter()
        .map(|a| a.into())
        .collect();

    Ok(Json(response))
}

/// Update application
pub async fn update_application(
    State(state): State<ApplicationsState>,
    Authenticated(auth): Authenticated,
    Path(id): Path<String>,
    Json(req): Json<UpdateApplicationRequest>,
) -> ApiResult<ApplicationResponse> {
    crate::service::checks::require_anchor(&auth)?;

    let mut app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    if let Some(name) = req.name {
        app.name = name;
    }
    if let Some(desc) = req.description {
        app.description = Some(desc);
    }
    if let Some(url) = req.default_base_url {
        app.default_base_url = Some(url);
    }
    if let Some(url) = req.icon_url {
        app.icon_url = Some(url);
    }

    app.updated_at = chrono::Utc::now();
    state.application_repo.update(&app).await?;

    Ok(Json(app.into()))
}

/// Delete application (deactivate)
pub async fn delete_application(
    State(state): State<ApplicationsState>,
    Authenticated(auth): Authenticated,
    Path(id): Path<String>,
) -> ApiResult<SuccessResponse> {
    crate::service::checks::require_anchor(&auth)?;

    let mut app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    app.deactivate();
    state.application_repo.update(&app).await?;

    Ok(Json(SuccessResponse::ok()))
}

/// Create applications router
pub fn applications_router(state: ApplicationsState) -> Router {
    Router::new()
        .route("/", post(create_application).get(list_applications))
        .route("/:id", get(get_application).put(update_application).delete(delete_application))
        .with_state(state)
}
