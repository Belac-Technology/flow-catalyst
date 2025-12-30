//! Applications Admin API
//!
//! REST endpoints for application management.
//! Applications are global platform entities (not client-scoped).

use salvo::prelude::*;
use salvo::oapi::extract::*;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::domain::Application;
use crate::repository::ApplicationRepository;
use crate::error::PlatformError;
use crate::api::common::{PaginationParams, CreatedResponse, SuccessResponse};
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
#[derive(Debug, Default, Deserialize, ToParameters)]
#[serde(rename_all = "camelCase")]
#[salvo(parameters(default_parameter_in = Query))]
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
#[endpoint(tags("Applications"))]
pub async fn create_application(
    depot: &mut Depot,
    body: JsonBody<CreateApplicationRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<ApplicationsState>().map_err(|_| PlatformError::internal("State not found"))?;

    // Only anchor users can manage applications
    crate::service::checks::require_anchor(&auth.0)?;

    let req = body.into_inner();

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
#[endpoint(tags("Applications"))]
pub async fn get_application(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<ApplicationResponse>, PlatformError> {
    let _auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<ApplicationsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    let app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    Ok(Json(app.into()))
}

/// List applications
#[endpoint(tags("Applications"))]
pub async fn list_applications(
    depot: &mut Depot,
    query: ApplicationsQuery,
) -> Result<Json<Vec<ApplicationResponse>>, PlatformError> {
    let _auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<ApplicationsState>().map_err(|_| PlatformError::internal("State not found"))?;

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
#[endpoint(tags("Applications"))]
pub async fn update_application(
    depot: &mut Depot,
    id: PathParam<String>,
    body: JsonBody<UpdateApplicationRequest>,
) -> Result<Json<ApplicationResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<ApplicationsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::require_anchor(&auth.0)?;

    let mut app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    let req = body.into_inner();
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
#[endpoint(tags("Applications"))]
pub async fn delete_application(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<ApplicationsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::require_anchor(&auth.0)?;

    let mut app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    app.deactivate();
    state.application_repo.update(&app).await?;

    Ok(Json(SuccessResponse::ok()))
}

/// Create applications router
pub fn applications_router(state: ApplicationsState) -> Router {
    Router::new()
        .push(
            Router::new()
                .post(create_application)
                .get(list_applications)
        )
        .push(
            Router::with_path("<id>")
                .get(get_application)
                .put(update_application)
                .delete(delete_application)
        )
        .hoop(affix_state::inject(state))
}
