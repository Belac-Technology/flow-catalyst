//! Applications Admin API
//!
//! REST endpoints for application management.
//! Applications are global platform entities (not client-scoped).

use axum::{
    routing::{get, post, put},
    extract::{State, Path, Query},
    Json, Router,
};
use utoipa::{ToSchema, IntoParams};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::domain::{Application, ServiceAccount, AuthRole, ApplicationClientConfig};
use crate::domain::service_account::RoleAssignment;
use crate::repository::{ApplicationRepository, ServiceAccountRepository, RoleRepository, ApplicationClientConfigRepository, ClientRepository};
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
#[derive(Debug, Default, Deserialize, IntoParams)]
#[serde(rename_all = "camelCase")]
#[into_params(parameter_in = Query)]
pub struct ApplicationsQuery {
    #[serde(flatten)]
    pub pagination: PaginationParams,

    /// Filter by active status
    pub active: Option<bool>,
}

/// Service account response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ServiceAccountResponse {
    pub id: String,
    pub code: String,
    pub name: String,
    pub description: Option<String>,
    pub active: bool,
    pub application_id: Option<String>,
    pub created_at: String,
}

impl From<ServiceAccount> for ServiceAccountResponse {
    fn from(sa: ServiceAccount) -> Self {
        Self {
            id: sa.id,
            code: sa.code,
            name: sa.name,
            description: sa.description,
            active: sa.active,
            application_id: sa.application_id,
            created_at: sa.created_at.to_rfc3339(),
        }
    }
}

/// Application role response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ApplicationRoleResponse {
    pub id: String,
    pub code: String,
    pub display_name: String,
    pub description: Option<String>,
    pub application_code: String,
    pub permissions: Vec<String>,
    pub source: String,
    pub client_managed: bool,
}

impl From<AuthRole> for ApplicationRoleResponse {
    fn from(r: AuthRole) -> Self {
        Self {
            id: r.id,
            code: r.code,
            display_name: r.display_name,
            description: r.description,
            application_code: r.application_code,
            permissions: r.permissions.into_iter().collect(),
            source: format!("{:?}", r.source).to_uppercase(),
            client_managed: r.client_managed,
        }
    }
}

/// Applications service state
#[derive(Clone)]
pub struct ApplicationsState {
    pub application_repo: Arc<ApplicationRepository>,
    pub service_account_repo: Arc<ServiceAccountRepository>,
    pub role_repo: Arc<RoleRepository>,
    pub client_config_repo: Arc<ApplicationClientConfigRepository>,
    pub client_repo: Arc<ClientRepository>,
}

/// Create a new application
#[utoipa::path(
    post,
    path = "",
    tag = "applications",
    request_body = CreateApplicationRequest,
    responses(
        (status = 201, description = "Application created", body = CreatedResponse),
        (status = 400, description = "Validation error"),
        (status = 409, description = "Duplicate code")
    ),
    security(("bearer_auth" = []))
)]
pub async fn create_application(
    State(state): State<ApplicationsState>,
    auth: Authenticated,
    Json(req): Json<CreateApplicationRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    // Only anchor users can manage applications
    crate::service::checks::require_anchor(&auth.0)?;

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
#[utoipa::path(
    get,
    path = "/{id}",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID")
    ),
    responses(
        (status = 200, description = "Application found", body = ApplicationResponse),
        (status = 404, description = "Application not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_application(
    State(state): State<ApplicationsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<ApplicationResponse>, PlatformError> {
    let app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    Ok(Json(app.into()))
}

/// List applications
#[utoipa::path(
    get,
    path = "",
    tag = "applications",
    params(ApplicationsQuery),
    responses(
        (status = 200, description = "List of applications", body = Vec<ApplicationResponse>)
    ),
    security(("bearer_auth" = []))
)]
pub async fn list_applications(
    State(state): State<ApplicationsState>,
    _auth: Authenticated,
    Query(query): Query<ApplicationsQuery>,
) -> Result<Json<Vec<ApplicationResponse>>, PlatformError> {
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
#[utoipa::path(
    put,
    path = "/{id}",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID")
    ),
    request_body = UpdateApplicationRequest,
    responses(
        (status = 200, description = "Application updated", body = ApplicationResponse),
        (status = 404, description = "Application not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn update_application(
    State(state): State<ApplicationsState>,
    auth: Authenticated,
    Path(id): Path<String>,
    Json(req): Json<UpdateApplicationRequest>,
) -> Result<Json<ApplicationResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

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
#[utoipa::path(
    delete,
    path = "/{id}",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID")
    ),
    responses(
        (status = 200, description = "Application deleted", body = SuccessResponse),
        (status = 404, description = "Application not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn delete_application(
    State(state): State<ApplicationsState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    app.deactivate();
    state.application_repo.update(&app).await?;

    Ok(Json(SuccessResponse::ok()))
}

/// Activate application
#[utoipa::path(
    post,
    path = "/{id}/activate",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID")
    ),
    responses(
        (status = 200, description = "Application activated", body = ApplicationResponse),
        (status = 404, description = "Application not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn activate_application(
    State(state): State<ApplicationsState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<ApplicationResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    app.activate();
    state.application_repo.update(&app).await?;

    Ok(Json(app.into()))
}

/// Deactivate application
#[utoipa::path(
    post,
    path = "/{id}/deactivate",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID")
    ),
    responses(
        (status = 200, description = "Application deactivated", body = ApplicationResponse),
        (status = 404, description = "Application not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn deactivate_application(
    State(state): State<ApplicationsState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<ApplicationResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    app.deactivate();
    state.application_repo.update(&app).await?;

    Ok(Json(app.into()))
}

/// Get application by code
#[utoipa::path(
    get,
    path = "/by-code/{code}",
    tag = "applications",
    params(
        ("code" = String, Path, description = "Application code")
    ),
    responses(
        (status = 200, description = "Application found", body = ApplicationResponse),
        (status = 404, description = "Application not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_application_by_code(
    State(state): State<ApplicationsState>,
    _auth: Authenticated,
    Path(code): Path<String>,
) -> Result<Json<ApplicationResponse>, PlatformError> {
    let app = state.application_repo.find_by_code(&code).await?
        .ok_or_else(|| PlatformError::not_found("Application", &code))?;

    Ok(Json(app.into()))
}

/// Provision a service account for an application
#[utoipa::path(
    post,
    path = "/{id}/provision-service-account",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID")
    ),
    responses(
        (status = 201, description = "Service account provisioned", body = ServiceAccountResponse),
        (status = 404, description = "Application not found"),
        (status = 409, description = "Service account already exists")
    ),
    security(("bearer_auth" = []))
)]
pub async fn provision_service_account(
    State(state): State<ApplicationsState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<ServiceAccountResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    // Get the application
    let mut app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    // Check if already has a service account
    if app.service_account_id.is_some() {
        return Err(PlatformError::conflict(
            "Application already has a service account provisioned"
        ));
    }

    // Create the service account
    let sa_code = format!("app:{}", app.code);
    let sa_name = format!("{} Service Account", app.name);

    let mut service_account = ServiceAccount::new(&sa_code, &sa_name)
        .with_application_id(&app.id)
        .with_description(format!("Service account for application: {}", app.name));

    // Assign the application-service role
    service_account.roles.push(RoleAssignment::with_source(
        "platform:application-service",
        "SYSTEM",
    ));

    // Save the service account
    state.service_account_repo.insert(&service_account).await?;

    // Update the application with the service account ID
    app.service_account_id = Some(service_account.id.clone());
    app.updated_at = chrono::Utc::now();
    state.application_repo.update(&app).await?;

    Ok(Json(service_account.into()))
}

/// Get service account for an application
#[utoipa::path(
    get,
    path = "/{id}/service-account",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID")
    ),
    responses(
        (status = 200, description = "Service account found", body = ServiceAccountResponse),
        (status = 404, description = "Application or service account not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_application_service_account(
    State(state): State<ApplicationsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<ServiceAccountResponse>, PlatformError> {
    // Get the application
    let app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    // Get the service account
    let sa_id = app.service_account_id
        .ok_or_else(|| PlatformError::not_found("ServiceAccount", "for application"))?;

    let service_account = state.service_account_repo.find_by_id(&sa_id).await?
        .ok_or_else(|| PlatformError::not_found("ServiceAccount", &sa_id))?;

    Ok(Json(service_account.into()))
}

/// List roles for an application
#[utoipa::path(
    get,
    path = "/{id}/roles",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID")
    ),
    responses(
        (status = 200, description = "Application roles", body = Vec<ApplicationRoleResponse>),
        (status = 404, description = "Application not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn list_application_roles(
    State(state): State<ApplicationsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<Vec<ApplicationRoleResponse>>, PlatformError> {
    // Get the application
    let app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    // Find roles by application code
    let roles = state.role_repo.find_by_application(&app.code).await?;

    let response: Vec<ApplicationRoleResponse> = roles.into_iter()
        .map(|r| r.into())
        .collect();

    Ok(Json(response))
}

/// Client config response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ClientConfigResponse {
    pub id: String,
    pub application_id: String,
    pub client_id: String,
    pub client_name: String,
    pub client_identifier: String,
    pub enabled: bool,
    pub base_url_override: Option<String>,
    pub effective_base_url: Option<String>,
    pub config: std::collections::HashMap<String, serde_json::Value>,
}

/// Client configs list response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ClientConfigsResponse {
    pub client_configs: Vec<ClientConfigResponse>,
    pub total: usize,
}

/// Client config request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ClientConfigRequest {
    pub enabled: Option<bool>,
    pub base_url_override: Option<String>,
    pub config: Option<std::collections::HashMap<String, serde_json::Value>>,
}

/// List client configs for an application
#[utoipa::path(
    get,
    path = "/{id}/clients",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID")
    ),
    responses(
        (status = 200, description = "Client configurations", body = ClientConfigsResponse),
        (status = 404, description = "Application not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn list_client_configs(
    State(state): State<ApplicationsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<ClientConfigsResponse>, PlatformError> {
    // Verify application exists
    let app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    let configs = state.client_config_repo.find_by_application(&id).await?;

    let mut client_configs = Vec::new();
    for config in configs {
        // Get client details
        if let Some(client) = state.client_repo.find_by_id(&config.client_id).await? {
            client_configs.push(ClientConfigResponse {
                id: config.id,
                application_id: config.application_id,
                client_id: config.client_id,
                client_name: client.name,
                client_identifier: client.identifier,
                enabled: config.enabled,
                base_url_override: config.base_url_override.clone(),
                effective_base_url: config.base_url_override.or(app.default_base_url.clone()),
                config: config.config_json,
            });
        }
    }

    let total = client_configs.len();
    Ok(Json(ClientConfigsResponse {
        client_configs,
        total,
    }))
}

/// Update client config for an application
#[utoipa::path(
    put,
    path = "/{id}/clients/{client_id}",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID"),
        ("client_id" = String, Path, description = "Client ID")
    ),
    request_body = ClientConfigRequest,
    responses(
        (status = 200, description = "Configuration updated", body = ClientConfigResponse),
        (status = 404, description = "Application or client not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn update_client_config(
    State(state): State<ApplicationsState>,
    auth: Authenticated,
    Path((id, client_id)): Path<(String, String)>,
    Json(req): Json<ClientConfigRequest>,
) -> Result<Json<ClientConfigResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    // Verify application exists
    let app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    // Verify client exists
    let client = state.client_repo.find_by_id(&client_id).await?
        .ok_or_else(|| PlatformError::not_found("Client", &client_id))?;

    // Get or create config
    let mut config = if let Some(existing) = state.client_config_repo
        .find_by_application_and_client(&id, &client_id).await?
    {
        existing
    } else {
        ApplicationClientConfig::new(&id, &client_id)
    };

    // Apply updates
    if let Some(enabled) = req.enabled {
        config.enabled = enabled;
    }
    if let Some(url) = req.base_url_override {
        config.base_url_override = if url.is_empty() { None } else { Some(url) };
    }
    if let Some(cfg) = req.config {
        config.config_json = cfg;
    }
    config.updated_at = chrono::Utc::now();

    // Save
    if state.client_config_repo.find_by_id(&config.id).await?.is_some() {
        state.client_config_repo.update(&config).await?;
    } else {
        state.client_config_repo.insert(&config).await?;
    }

    Ok(Json(ClientConfigResponse {
        id: config.id,
        application_id: config.application_id,
        client_id: config.client_id,
        client_name: client.name,
        client_identifier: client.identifier,
        enabled: config.enabled,
        base_url_override: config.base_url_override.clone(),
        effective_base_url: config.base_url_override.or(app.default_base_url),
        config: config.config_json,
    }))
}

/// Enable application for a client
#[utoipa::path(
    post,
    path = "/{id}/clients/{client_id}/enable",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID"),
        ("client_id" = String, Path, description = "Client ID")
    ),
    responses(
        (status = 200, description = "Application enabled for client", body = ClientConfigResponse),
        (status = 404, description = "Application or client not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn enable_for_client(
    State(state): State<ApplicationsState>,
    auth: Authenticated,
    Path((id, client_id)): Path<(String, String)>,
) -> Result<Json<ClientConfigResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    // Verify application exists
    let app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    // Verify client exists
    let client = state.client_repo.find_by_id(&client_id).await?
        .ok_or_else(|| PlatformError::not_found("Client", &client_id))?;

    // Get or create config
    let mut config = if let Some(existing) = state.client_config_repo
        .find_by_application_and_client(&id, &client_id).await?
    {
        existing
    } else {
        ApplicationClientConfig::new(&id, &client_id)
    };

    config.enable();

    // Save
    if state.client_config_repo.find_by_id(&config.id).await?.is_some() {
        state.client_config_repo.update(&config).await?;
    } else {
        state.client_config_repo.insert(&config).await?;
    }

    Ok(Json(ClientConfigResponse {
        id: config.id,
        application_id: config.application_id,
        client_id: config.client_id,
        client_name: client.name,
        client_identifier: client.identifier,
        enabled: config.enabled,
        base_url_override: config.base_url_override.clone(),
        effective_base_url: config.base_url_override.or(app.default_base_url),
        config: config.config_json,
    }))
}

/// Disable application for a client
#[utoipa::path(
    post,
    path = "/{id}/clients/{client_id}/disable",
    tag = "applications",
    params(
        ("id" = String, Path, description = "Application ID"),
        ("client_id" = String, Path, description = "Client ID")
    ),
    responses(
        (status = 200, description = "Application disabled for client", body = ClientConfigResponse),
        (status = 404, description = "Application or client not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn disable_for_client(
    State(state): State<ApplicationsState>,
    auth: Authenticated,
    Path((id, client_id)): Path<(String, String)>,
) -> Result<Json<ClientConfigResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    // Verify application exists
    let app = state.application_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Application", &id))?;

    // Verify client exists
    let client = state.client_repo.find_by_id(&client_id).await?
        .ok_or_else(|| PlatformError::not_found("Client", &client_id))?;

    // Get or create config
    let mut config = if let Some(existing) = state.client_config_repo
        .find_by_application_and_client(&id, &client_id).await?
    {
        existing
    } else {
        ApplicationClientConfig::new(&id, &client_id)
    };

    config.disable();

    // Save
    if state.client_config_repo.find_by_id(&config.id).await?.is_some() {
        state.client_config_repo.update(&config).await?;
    } else {
        state.client_config_repo.insert(&config).await?;
    }

    Ok(Json(ClientConfigResponse {
        id: config.id,
        application_id: config.application_id,
        client_id: config.client_id,
        client_name: client.name,
        client_identifier: client.identifier,
        enabled: config.enabled,
        base_url_override: config.base_url_override.clone(),
        effective_base_url: config.base_url_override.or(app.default_base_url),
        config: config.config_json,
    }))
}

/// Create applications router
pub fn applications_router(state: ApplicationsState) -> Router {
    Router::new()
        .route("/", post(create_application).get(list_applications))
        .route("/:id", get(get_application).put(update_application).delete(delete_application))
        .route("/:id/activate", post(activate_application))
        .route("/:id/deactivate", post(deactivate_application))
        .route("/:id/provision-service-account", post(provision_service_account))
        .route("/:id/service-account", get(get_application_service_account))
        .route("/:id/roles", get(list_application_roles))
        .route("/:id/clients", get(list_client_configs))
        .route("/:id/clients/:client_id", put(update_client_config))
        .route("/:id/clients/:client_id/enable", post(enable_for_client))
        .route("/:id/clients/:client_id/disable", post(disable_for_client))
        .route("/by-code/:code", get(get_application_by_code))
        .with_state(state)
}
