//! Auth Configuration Admin API
//!
//! REST endpoints for authentication configuration management.
//! Includes anchor domains, client auth configs, and IDP role mappings.

use salvo::prelude::*;
use salvo::oapi::extract::*;
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
#[endpoint(tags("AuthConfig"))]
pub async fn create_anchor_domain(
    depot: &mut Depot,
    body: JsonBody<CreateAnchorDomainRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<AuthConfigState>().map_err(|_| PlatformError::internal("State not found"))?;

    crate::service::checks::require_anchor(&auth.0)?;

    let req = body.into_inner();
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
#[endpoint(tags("AuthConfig"))]
pub async fn list_anchor_domains(
    depot: &mut Depot,
) -> Result<Json<Vec<AnchorDomainResponse>>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<AuthConfigState>().map_err(|_| PlatformError::internal("State not found"))?;

    crate::service::checks::require_anchor(&auth.0)?;

    let domains = state.anchor_domain_repo.find_all().await?;
    let response: Vec<AnchorDomainResponse> = domains.into_iter()
        .map(|d| d.into())
        .collect();

    Ok(Json(response))
}

/// Delete anchor domain
#[endpoint(tags("AuthConfig"))]
pub async fn delete_anchor_domain(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<AuthConfigState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

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
#[endpoint(tags("AuthConfig"))]
pub async fn create_client_auth_config(
    depot: &mut Depot,
    body: JsonBody<CreateClientAuthConfigRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<AuthConfigState>().map_err(|_| PlatformError::internal("State not found"))?;

    crate::service::checks::require_anchor(&auth.0)?;

    let req = body.into_inner();
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
#[endpoint(tags("AuthConfig"))]
pub async fn get_client_auth_config(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<ClientAuthConfigResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<AuthConfigState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::require_anchor(&auth.0)?;

    let config = state.client_auth_config_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("ClientAuthConfig", &id))?;

    Ok(Json(config.into()))
}

/// List client auth configs
#[endpoint(tags("AuthConfig"))]
pub async fn list_client_auth_configs(
    depot: &mut Depot,
) -> Result<Json<Vec<ClientAuthConfigResponse>>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<AuthConfigState>().map_err(|_| PlatformError::internal("State not found"))?;

    crate::service::checks::require_anchor(&auth.0)?;

    let configs = state.client_auth_config_repo.find_all().await?;
    let response: Vec<ClientAuthConfigResponse> = configs.into_iter()
        .map(|c| c.into())
        .collect();

    Ok(Json(response))
}

/// Update client auth config
#[endpoint(tags("AuthConfig"))]
pub async fn update_client_auth_config(
    depot: &mut Depot,
    id: PathParam<String>,
    body: JsonBody<UpdateClientAuthConfigRequest>,
) -> Result<Json<ClientAuthConfigResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<AuthConfigState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::require_anchor(&auth.0)?;

    let mut config = state.client_auth_config_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("ClientAuthConfig", &id))?;

    let req = body.into_inner();
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
#[endpoint(tags("AuthConfig"))]
pub async fn delete_client_auth_config(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<AuthConfigState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

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
#[endpoint(tags("AuthConfig"))]
pub async fn create_idp_role_mapping(
    depot: &mut Depot,
    body: JsonBody<CreateIdpRoleMappingRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<AuthConfigState>().map_err(|_| PlatformError::internal("State not found"))?;

    crate::service::checks::require_anchor(&auth.0)?;

    let req = body.into_inner();

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
#[derive(Debug, Default, Deserialize, ToParameters)]
#[serde(rename_all = "camelCase")]
#[salvo(parameters(default_parameter_in = Query))]
pub struct IdpRoleMappingQuery {
    pub idp_type: Option<String>,
}

/// List IDP role mappings
#[endpoint(tags("AuthConfig"))]
pub async fn list_idp_role_mappings(
    depot: &mut Depot,
    query: IdpRoleMappingQuery,
) -> Result<Json<Vec<IdpRoleMappingResponse>>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<AuthConfigState>().map_err(|_| PlatformError::internal("State not found"))?;

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
#[endpoint(tags("AuthConfig"))]
pub async fn delete_idp_role_mapping(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<AuthConfigState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

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
        .push(
            Router::new()
                .post(create_anchor_domain)
                .get(list_anchor_domains)
        )
        .push(
            Router::with_path("<id>")
                .delete(delete_anchor_domain)
        )
        .hoop(affix_state::inject(state))
}

/// Create client auth configs router
pub fn client_auth_configs_router(state: AuthConfigState) -> Router {
    Router::new()
        .push(
            Router::new()
                .post(create_client_auth_config)
                .get(list_client_auth_configs)
        )
        .push(
            Router::with_path("<id>")
                .get(get_client_auth_config)
                .put(update_client_auth_config)
                .delete(delete_client_auth_config)
        )
        .hoop(affix_state::inject(state))
}

/// Create IDP role mappings router
pub fn idp_role_mappings_router(state: AuthConfigState) -> Router {
    Router::new()
        .push(
            Router::new()
                .post(create_idp_role_mapping)
                .get(list_idp_role_mappings)
        )
        .push(
            Router::with_path("<id>")
                .delete(delete_idp_role_mapping)
        )
        .hoop(affix_state::inject(state))
}
