//! Auth Configuration Admin API
//!
//! REST endpoints for authentication configuration management.
//! Includes anchor domains, client auth configs, and IDP role mappings.

use axum::{
    routing::{get, post, delete},
    extract::{State, Path, Query},
    Json, Router,
};
use utoipa::{ToSchema, IntoParams};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::domain::{
    AnchorDomain, ClientAuthConfig, IdpRoleMapping,
    AuthConfigType, AuthProvider,
};
use crate::repository::{
    AnchorDomainRepository, ClientAuthConfigRepository, IdpRoleMappingRepository,
};
use crate::error::PlatformError;
use crate::api::common::{CreatedResponse, SuccessResponse};
use crate::api::middleware::Authenticated;

// ============================================================================
// Anchor Domains
// ============================================================================

/// Create anchor domain request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateAnchorDomainRequest {
    /// Email domain (e.g., "flowcatalyst.tech")
    pub domain: String,
}

/// Anchor domain response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct AnchorDomainResponse {
    pub id: String,
    pub domain: String,
    pub created_at: String,
    pub created_by: Option<String>,
}

impl From<AnchorDomain> for AnchorDomainResponse {
    fn from(d: AnchorDomain) -> Self {
        Self {
            id: d.id,
            domain: d.domain,
            created_at: d.created_at.to_rfc3339(),
            created_by: d.created_by,
        }
    }
}

// ============================================================================
// Client Auth Configs
// ============================================================================

/// Create client auth config request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateClientAuthConfigRequest {
    /// Email domain this config applies to
    pub email_domain: String,

    /// Config type: ANCHOR, PARTNER, or CLIENT
    #[serde(default)]
    pub config_type: Option<String>,

    /// Primary client ID (for CLIENT type)
    pub primary_client_id: Option<String>,

    /// Auth provider: INTERNAL or OIDC
    #[serde(default)]
    pub auth_provider: Option<String>,

    /// OIDC issuer URL
    pub oidc_issuer_url: Option<String>,

    /// OIDC client ID
    pub oidc_client_id: Option<String>,
}

/// Update client auth config request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UpdateClientAuthConfigRequest {
    /// Primary client ID
    pub primary_client_id: Option<String>,

    /// Auth provider
    pub auth_provider: Option<String>,

    /// OIDC issuer URL
    pub oidc_issuer_url: Option<String>,

    /// OIDC client ID
    pub oidc_client_id: Option<String>,

    /// Additional client IDs
    pub additional_client_ids: Option<Vec<String>>,
}

/// Client auth config response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ClientAuthConfigResponse {
    pub id: String,
    pub email_domain: String,
    pub config_type: String,
    pub primary_client_id: Option<String>,
    pub additional_client_ids: Vec<String>,
    pub auth_provider: String,
    pub oidc_issuer_url: Option<String>,
    pub oidc_client_id: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

impl From<ClientAuthConfig> for ClientAuthConfigResponse {
    fn from(c: ClientAuthConfig) -> Self {
        Self {
            id: c.id,
            email_domain: c.email_domain,
            config_type: format!("{:?}", c.config_type).to_uppercase(),
            primary_client_id: c.primary_client_id,
            additional_client_ids: c.additional_client_ids,
            auth_provider: format!("{:?}", c.auth_provider).to_uppercase(),
            oidc_issuer_url: c.oidc_issuer_url,
            oidc_client_id: c.oidc_client_id,
            created_at: c.created_at.to_rfc3339(),
            updated_at: c.updated_at.to_rfc3339(),
        }
    }
}

// ============================================================================
// IDP Role Mappings
// ============================================================================

/// Create IDP role mapping request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateIdpRoleMappingRequest {
    /// IDP type (e.g., "OIDC", "AZURE_AD")
    pub idp_type: String,

    /// Role name from the IDP
    pub idp_role_name: String,

    /// Platform role name to map to
    pub platform_role_name: String,
}

/// IDP role mapping response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct IdpRoleMappingResponse {
    pub id: String,
    pub idp_type: String,
    pub idp_role_name: String,
    pub platform_role_name: String,
    pub created_at: String,
}

impl From<IdpRoleMapping> for IdpRoleMappingResponse {
    fn from(m: IdpRoleMapping) -> Self {
        Self {
            id: m.id,
            idp_type: m.idp_type,
            idp_role_name: m.idp_role_name,
            platform_role_name: m.platform_role_name,
            created_at: m.created_at.to_rfc3339(),
        }
    }
}

// ============================================================================
// State and Helpers
// ============================================================================

/// Auth config service state
#[derive(Clone)]
pub struct AuthConfigState {
    pub anchor_domain_repo: Arc<AnchorDomainRepository>,
    pub client_auth_config_repo: Arc<ClientAuthConfigRepository>,
    pub idp_role_mapping_repo: Arc<IdpRoleMappingRepository>,
}

fn parse_config_type(s: &str) -> AuthConfigType {
    match s.to_uppercase().as_str() {
        "ANCHOR" => AuthConfigType::Anchor,
        "PARTNER" => AuthConfigType::Partner,
        _ => AuthConfigType::Client,
    }
}

fn parse_auth_provider(s: &str) -> AuthProvider {
    match s.to_uppercase().as_str() {
        "OIDC" => AuthProvider::Oidc,
        _ => AuthProvider::Internal,
    }
}

// ============================================================================
// Anchor Domain Handlers
// ============================================================================

/// Create anchor domain
#[utoipa::path(
    post,
    path = "",
    tag = "auth-config",
    request_body = CreateAnchorDomainRequest,
    responses(
        (status = 201, description = "Anchor domain created", body = CreatedResponse),
        (status = 400, description = "Validation error"),
        (status = 409, description = "Duplicate domain")
    ),
    security(("bearer_auth" = []))
)]
pub async fn create_anchor_domain(
    State(state): State<AuthConfigState>,
    auth: Authenticated,
    Json(req): Json<CreateAnchorDomainRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let domain = req.domain.to_lowercase();

    // Check for duplicate
    if state.anchor_domain_repo.is_anchor_domain(&domain).await? {
        return Err(PlatformError::duplicate("AnchorDomain", "domain", &domain));
    }

    let anchor_domain = AnchorDomain::new(&domain);
    let id = anchor_domain.id.clone();

    state.anchor_domain_repo.insert(&anchor_domain).await?;

    Ok(Json(CreatedResponse::new(id)))
}

/// List anchor domains
#[utoipa::path(
    get,
    path = "",
    tag = "auth-config",
    responses(
        (status = 200, description = "List of anchor domains", body = Vec<AnchorDomainResponse>)
    ),
    security(("bearer_auth" = []))
)]
pub async fn list_anchor_domains(
    State(state): State<AuthConfigState>,
    auth: Authenticated,
) -> Result<Json<Vec<AnchorDomainResponse>>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let domains = state.anchor_domain_repo.find_all().await?;
    let response: Vec<AnchorDomainResponse> = domains.into_iter()
        .map(|d| d.into())
        .collect();

    Ok(Json(response))
}

/// Delete anchor domain
#[utoipa::path(
    delete,
    path = "/{id}",
    tag = "auth-config",
    params(
        ("id" = String, Path, description = "Anchor domain ID")
    ),
    responses(
        (status = 200, description = "Anchor domain deleted", body = SuccessResponse),
        (status = 404, description = "Anchor domain not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn delete_anchor_domain(
    State(state): State<AuthConfigState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let exists = state.anchor_domain_repo.find_by_id(&id).await?.is_some();
    if !exists {
        return Err(PlatformError::not_found("AnchorDomain", &id));
    }

    state.anchor_domain_repo.delete(&id).await?;

    Ok(Json(SuccessResponse::ok()))
}

// ============================================================================
// Client Auth Config Handlers
// ============================================================================

/// Create client auth config
#[utoipa::path(
    post,
    path = "",
    tag = "auth-config",
    request_body = CreateClientAuthConfigRequest,
    responses(
        (status = 201, description = "Client auth config created", body = CreatedResponse),
        (status = 400, description = "Validation error"),
        (status = 409, description = "Duplicate email domain")
    ),
    security(("bearer_auth" = []))
)]
pub async fn create_client_auth_config(
    State(state): State<AuthConfigState>,
    auth: Authenticated,
    Json(req): Json<CreateClientAuthConfigRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let email_domain = req.email_domain.to_lowercase();

    // Check for duplicate
    if state.client_auth_config_repo.find_by_email_domain(&email_domain).await?.is_some() {
        return Err(PlatformError::duplicate("ClientAuthConfig", "emailDomain", &email_domain));
    }

    let config_type = req.config_type.as_deref()
        .map(parse_config_type)
        .unwrap_or(AuthConfigType::Client);

    let mut config = match config_type {
        AuthConfigType::Partner => ClientAuthConfig::new_partner(&email_domain),
        _ => {
            let client_id = req.primary_client_id.unwrap_or_default();
            ClientAuthConfig::new_client(&email_domain, &client_id)
        }
    };

    if let Some(ref provider) = req.auth_provider {
        config.auth_provider = parse_auth_provider(provider);
    }

    if let Some(ref issuer) = req.oidc_issuer_url {
        if let Some(ref client_id) = req.oidc_client_id {
            config = config.with_oidc(issuer, client_id);
        }
    }

    let id = config.id.clone();
    state.client_auth_config_repo.insert(&config).await?;

    Ok(Json(CreatedResponse::new(id)))
}

/// Get client auth config by ID
#[utoipa::path(
    get,
    path = "/{id}",
    tag = "auth-config",
    params(
        ("id" = String, Path, description = "Client auth config ID")
    ),
    responses(
        (status = 200, description = "Client auth config found", body = ClientAuthConfigResponse),
        (status = 404, description = "Client auth config not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_client_auth_config(
    State(state): State<AuthConfigState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<ClientAuthConfigResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let config = state.client_auth_config_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("ClientAuthConfig", &id))?;

    Ok(Json(config.into()))
}

/// List client auth configs
#[utoipa::path(
    get,
    path = "",
    tag = "auth-config",
    responses(
        (status = 200, description = "List of client auth configs", body = Vec<ClientAuthConfigResponse>)
    ),
    security(("bearer_auth" = []))
)]
pub async fn list_client_auth_configs(
    State(state): State<AuthConfigState>,
    auth: Authenticated,
) -> Result<Json<Vec<ClientAuthConfigResponse>>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let configs = state.client_auth_config_repo.find_all().await?;
    let response: Vec<ClientAuthConfigResponse> = configs.into_iter()
        .map(|c| c.into())
        .collect();

    Ok(Json(response))
}

/// Update client auth config
#[utoipa::path(
    put,
    path = "/{id}",
    tag = "auth-config",
    params(
        ("id" = String, Path, description = "Client auth config ID")
    ),
    request_body = UpdateClientAuthConfigRequest,
    responses(
        (status = 200, description = "Client auth config updated", body = ClientAuthConfigResponse),
        (status = 404, description = "Client auth config not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn update_client_auth_config(
    State(state): State<AuthConfigState>,
    auth: Authenticated,
    Path(id): Path<String>,
    Json(req): Json<UpdateClientAuthConfigRequest>,
) -> Result<Json<ClientAuthConfigResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut config = state.client_auth_config_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("ClientAuthConfig", &id))?;

    if let Some(client_id) = req.primary_client_id {
        config.primary_client_id = Some(client_id);
    }
    if let Some(ref provider) = req.auth_provider {
        config.auth_provider = parse_auth_provider(provider);
    }
    if let Some(issuer) = req.oidc_issuer_url {
        config.oidc_issuer_url = Some(issuer);
    }
    if let Some(client_id) = req.oidc_client_id {
        config.oidc_client_id = Some(client_id);
    }
    if let Some(additional) = req.additional_client_ids {
        config.additional_client_ids = additional;
    }

    config.updated_at = chrono::Utc::now();
    state.client_auth_config_repo.update(&config).await?;

    Ok(Json(config.into()))
}

/// Delete client auth config
#[utoipa::path(
    delete,
    path = "/{id}",
    tag = "auth-config",
    params(
        ("id" = String, Path, description = "Client auth config ID")
    ),
    responses(
        (status = 200, description = "Client auth config deleted", body = SuccessResponse),
        (status = 404, description = "Client auth config not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn delete_client_auth_config(
    State(state): State<AuthConfigState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let exists = state.client_auth_config_repo.find_by_id(&id).await?.is_some();
    if !exists {
        return Err(PlatformError::not_found("ClientAuthConfig", &id));
    }

    state.client_auth_config_repo.delete(&id).await?;

    Ok(Json(SuccessResponse::ok()))
}

// ============================================================================
// IDP Role Mapping Handlers
// ============================================================================

/// Create IDP role mapping
#[utoipa::path(
    post,
    path = "",
    tag = "auth-config",
    request_body = CreateIdpRoleMappingRequest,
    responses(
        (status = 201, description = "IDP role mapping created", body = CreatedResponse),
        (status = 400, description = "Validation error"),
        (status = 409, description = "Duplicate mapping")
    ),
    security(("bearer_auth" = []))
)]
pub async fn create_idp_role_mapping(
    State(state): State<AuthConfigState>,
    auth: Authenticated,
    Json(req): Json<CreateIdpRoleMappingRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    // Check for duplicate
    if state.idp_role_mapping_repo.find_by_idp_role(&req.idp_type, &req.idp_role_name).await?.is_some() {
        return Err(PlatformError::duplicate("IdpRoleMapping", "idpRole", &format!("{}:{}", req.idp_type, req.idp_role_name)));
    }

    let mapping = IdpRoleMapping::new(&req.idp_type, &req.idp_role_name, &req.platform_role_name);
    let id = mapping.id.clone();

    state.idp_role_mapping_repo.insert(&mapping).await?;

    Ok(Json(CreatedResponse::new(id)))
}

/// Query parameters for IDP role mappings
#[derive(Debug, Default, Deserialize, IntoParams)]
#[serde(rename_all = "camelCase")]
#[into_params(parameter_in = Query)]
pub struct IdpRoleMappingQuery {
    pub idp_type: Option<String>,
}

/// List IDP role mappings
#[utoipa::path(
    get,
    path = "",
    tag = "auth-config",
    params(IdpRoleMappingQuery),
    responses(
        (status = 200, description = "List of IDP role mappings", body = Vec<IdpRoleMappingResponse>)
    ),
    security(("bearer_auth" = []))
)]
pub async fn list_idp_role_mappings(
    State(state): State<AuthConfigState>,
    auth: Authenticated,
    Query(query): Query<IdpRoleMappingQuery>,
) -> Result<Json<Vec<IdpRoleMappingResponse>>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mappings = if let Some(ref idp_type) = query.idp_type {
        state.idp_role_mapping_repo.find_by_idp_type(idp_type).await?
    } else {
        state.idp_role_mapping_repo.find_all().await?
    };

    let response: Vec<IdpRoleMappingResponse> = mappings.into_iter()
        .map(|m| m.into())
        .collect();

    Ok(Json(response))
}

/// Delete IDP role mapping
#[utoipa::path(
    delete,
    path = "/{id}",
    tag = "auth-config",
    params(
        ("id" = String, Path, description = "IDP role mapping ID")
    ),
    responses(
        (status = 200, description = "IDP role mapping deleted", body = SuccessResponse),
        (status = 404, description = "IDP role mapping not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn delete_idp_role_mapping(
    State(state): State<AuthConfigState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let exists = state.idp_role_mapping_repo.find_by_id(&id).await?.is_some();
    if !exists {
        return Err(PlatformError::not_found("IdpRoleMapping", &id));
    }

    state.idp_role_mapping_repo.delete(&id).await?;

    Ok(Json(SuccessResponse::ok()))
}

// ============================================================================
// Routers
// ============================================================================

/// Create anchor domains router
pub fn anchor_domains_router(state: AuthConfigState) -> Router {
    Router::new()
        .route("/", post(create_anchor_domain).get(list_anchor_domains))
        .route("/:id", delete(delete_anchor_domain))
        .with_state(state)
}

/// Create client auth configs router
pub fn client_auth_configs_router(state: AuthConfigState) -> Router {
    Router::new()
        .route("/", post(create_client_auth_config).get(list_client_auth_configs))
        .route("/:id", get(get_client_auth_config).put(update_client_auth_config).delete(delete_client_auth_config))
        .with_state(state)
}

/// Create IDP role mappings router
pub fn idp_role_mappings_router(state: AuthConfigState) -> Router {
    Router::new()
        .route("/", post(create_idp_role_mapping).get(list_idp_role_mappings))
        .route("/:id", delete(delete_idp_role_mapping))
        .with_state(state)
}
