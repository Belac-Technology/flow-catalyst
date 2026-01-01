//! Service Accounts Admin API
//!
//! REST endpoints for service account management.
//! Base path: /api/admin/platform/service-accounts

use axum::{
    routing::{get, post, put},
    extract::{State, Path, Query},
    http::StatusCode,
    response::IntoResponse,
    Json, Router,
};
use utoipa::ToSchema;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use chrono::Utc;
use rand::Rng;

use crate::domain::{ServiceAccount, WebhookCredentials, WebhookAuthType};
use crate::domain::service_account::RoleAssignment;
use crate::repository::ServiceAccountRepository;
use crate::error::PlatformError;
use crate::api::middleware::Authenticated;

// ============================================================================
// Request/Response DTOs
// ============================================================================

/// Create service account request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateServiceAccountRequest {
    /// Unique code (1-50 chars)
    pub code: String,

    /// Human-readable name (1-100 chars)
    pub name: String,

    /// Optional description (max 500 chars)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,

    /// Client IDs this account can access
    #[serde(default)]
    pub client_ids: Vec<String>,

    /// Application ID (if created for an application)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub application_id: Option<String>,
}

/// Update service account request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UpdateServiceAccountRequest {
    /// Updated name
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,

    /// Updated description
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,

    /// Updated client IDs
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_ids: Option<Vec<String>>,
}

/// Update auth token request (custom value)
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UpdateAuthTokenRequest {
    /// Custom auth token
    pub auth_token: String,
}

/// Assign roles request (declarative - replaces all)
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct AssignRolesRequest {
    /// Role names to assign
    pub roles: Vec<String>,
}

/// Query parameters for service accounts list
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServiceAccountsQuery {
    /// Filter by client ID
    pub client_id: Option<String>,

    /// Filter by application ID
    pub application_id: Option<String>,

    /// Filter by active status
    pub active: Option<bool>,
}

/// Service account list response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ServiceAccountListResponse {
    pub service_accounts: Vec<ServiceAccountResponse>,
    pub total: usize,
}

/// Service account response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ServiceAccountResponse {
    pub id: String,
    pub code: String,
    pub name: String,
    pub description: Option<String>,
    pub client_ids: Vec<String>,
    pub application_id: Option<String>,
    pub active: bool,
    pub auth_type: String,
    pub roles: Vec<String>,
    pub last_used_at: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

impl From<ServiceAccount> for ServiceAccountResponse {
    fn from(sa: ServiceAccount) -> Self {
        Self {
            id: sa.id,
            code: sa.code,
            name: sa.name,
            description: sa.description,
            client_ids: sa.client_ids,
            application_id: sa.application_id,
            active: sa.active,
            auth_type: format!("{:?}", sa.webhook_credentials.auth_type).to_uppercase(),
            roles: sa.roles.iter().map(|r| r.role.clone()).collect(),
            last_used_at: sa.last_used_at.map(|t| t.to_rfc3339()),
            created_at: sa.created_at.to_rfc3339(),
            updated_at: sa.updated_at.to_rfc3339(),
        }
    }
}

/// Create service account response (includes one-time secrets)
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateServiceAccountResponse {
    pub service_account: ServiceAccountResponse,
    /// Auth token (shown only once)
    pub auth_token: String,
    /// Signing secret (shown only once)
    pub signing_secret: String,
}

/// Regenerate token response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RegenerateTokenResponse {
    /// New auth token (shown only once)
    pub auth_token: String,
}

/// Regenerate secret response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RegenerateSecretResponse {
    /// New signing secret (shown only once)
    pub signing_secret: String,
}

/// Role assignment response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RoleAssignmentResponse {
    pub role_name: String,
    pub assignment_source: Option<String>,
    pub assigned_at: String,
}

/// Roles response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RolesResponse {
    pub roles: Vec<RoleAssignmentResponse>,
}

/// Assign roles response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct AssignRolesResponse {
    pub roles: Vec<RoleAssignmentResponse>,
    pub added_roles: Vec<String>,
    pub removed_roles: Vec<String>,
}

// ============================================================================
// State
// ============================================================================

/// Service accounts API state
#[derive(Clone)]
pub struct ServiceAccountsState {
    pub repo: Arc<ServiceAccountRepository>,
}

// ============================================================================
// Helpers
// ============================================================================

/// Generate a bearer token with fc_ prefix
fn generate_auth_token() -> String {
    let random_part: String = (0..32)
        .map(|_| {
            let idx = rand::thread_rng().gen_range(0..36);
            if idx < 10 {
                (b'0' + idx) as char
            } else {
                (b'a' + idx - 10) as char
            }
        })
        .collect();
    format!("fc_{}", random_part)
}

/// Generate a signing secret (URL-safe base64)
fn generate_signing_secret() -> String {
    let bytes: [u8; 32] = rand::thread_rng().gen();
    base64::Engine::encode(&base64::engine::general_purpose::URL_SAFE_NO_PAD, &bytes)
}

// ============================================================================
// Endpoints
// ============================================================================

/// List service accounts
#[utoipa::path(
    get,
    path = "",
    tag = "service-accounts",
    params(
        ("clientId" = Option<String>, Query, description = "Filter by client ID"),
        ("applicationId" = Option<String>, Query, description = "Filter by application ID"),
        ("active" = Option<bool>, Query, description = "Filter by active status")
    ),
    responses(
        (status = 200, description = "List of service accounts", body = ServiceAccountListResponse)
    ),
    security(("bearer_auth" = []))
)]
pub async fn list_service_accounts(
    State(state): State<ServiceAccountsState>,
    _auth: Authenticated,
    Query(query): Query<ServiceAccountsQuery>,
) -> Result<Json<ServiceAccountListResponse>, PlatformError> {
    let mut accounts = if let Some(client_id) = query.client_id {
        state.repo.find_by_client(&client_id).await?
    } else if let Some(app_id) = query.application_id {
        state.repo.find_by_application(&app_id).await?
    } else if query.active == Some(true) {
        state.repo.find_active().await?
    } else {
        // Find all - fallback to active for now
        state.repo.find_active().await?
    };

    // Apply active filter if specified
    if let Some(is_active) = query.active {
        accounts.retain(|a| a.active == is_active);
    }

    let total = accounts.len();
    let service_accounts: Vec<ServiceAccountResponse> = accounts.into_iter()
        .map(ServiceAccountResponse::from)
        .collect();

    Ok(Json(ServiceAccountListResponse {
        service_accounts,
        total,
    }))
}

/// Get service account by ID
#[utoipa::path(
    get,
    path = "/{id}",
    tag = "service-accounts",
    params(
        ("id" = String, Path, description = "Service account ID")
    ),
    responses(
        (status = 200, description = "Service account found", body = ServiceAccountResponse),
        (status = 404, description = "Service account not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_service_account(
    State(state): State<ServiceAccountsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<ServiceAccountResponse>, PlatformError> {
    let account = state.repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::ServiceAccountNotFound { id: id.clone() })?;

    Ok(Json(ServiceAccountResponse::from(account)))
}

/// Get service account by code
#[utoipa::path(
    get,
    path = "/code/{code}",
    tag = "service-accounts",
    params(
        ("code" = String, Path, description = "Service account code")
    ),
    responses(
        (status = 200, description = "Service account found", body = ServiceAccountResponse),
        (status = 404, description = "Service account not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_service_account_by_code(
    State(state): State<ServiceAccountsState>,
    _auth: Authenticated,
    Path(code): Path<String>,
) -> Result<Json<ServiceAccountResponse>, PlatformError> {
    let account = state.repo.find_by_code(&code).await?
        .ok_or_else(|| PlatformError::ServiceAccountNotFound { id: code.clone() })?;

    Ok(Json(ServiceAccountResponse::from(account)))
}

/// Create service account
#[utoipa::path(
    post,
    path = "",
    tag = "service-accounts",
    request_body = CreateServiceAccountRequest,
    responses(
        (status = 201, description = "Service account created", body = CreateServiceAccountResponse),
        (status = 400, description = "Validation error"),
        (status = 409, description = "Duplicate code")
    ),
    security(("bearer_auth" = []))
)]
pub async fn create_service_account(
    State(state): State<ServiceAccountsState>,
    auth: Authenticated,
    Json(req): Json<CreateServiceAccountRequest>,
) -> Result<Json<CreateServiceAccountResponse>, PlatformError> {
    // Validate code
    if req.code.is_empty() || req.code.len() > 50 {
        return Err(PlatformError::bad_request("Code must be 1-50 characters"));
    }

    // Validate name
    if req.name.is_empty() || req.name.len() > 100 {
        return Err(PlatformError::bad_request("Name must be 1-100 characters"));
    }

    // Validate description
    if let Some(ref desc) = req.description {
        if desc.len() > 500 {
            return Err(PlatformError::bad_request("Description must be max 500 characters"));
        }
    }

    // Check for duplicate code
    if state.repo.find_by_code(&req.code).await?.is_some() {
        return Err(PlatformError::conflict("Service account with this code already exists"));
    }

    // Generate credentials
    let auth_token = generate_auth_token();
    let signing_secret = generate_signing_secret();

    // Create service account
    let mut account = ServiceAccount::new(&req.code, &req.name);
    account.description = req.description;
    account.client_ids = req.client_ids;
    account.application_id = req.application_id;
    account.webhook_credentials = WebhookCredentials::bearer_token(&auth_token);
    account.webhook_credentials.signing_secret = Some(signing_secret.clone());
    account.created_by = Some(auth.0.principal_id.clone());

    state.repo.insert(&account).await?;

    Ok(Json(CreateServiceAccountResponse {
        service_account: ServiceAccountResponse::from(account),
        auth_token,
        signing_secret,
    }))
}

/// Update service account
#[utoipa::path(
    put,
    path = "/{id}",
    tag = "service-accounts",
    params(
        ("id" = String, Path, description = "Service account ID")
    ),
    request_body = UpdateServiceAccountRequest,
    responses(
        (status = 200, description = "Service account updated", body = ServiceAccountResponse),
        (status = 404, description = "Service account not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn update_service_account(
    State(state): State<ServiceAccountsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
    Json(req): Json<UpdateServiceAccountRequest>,
) -> Result<Json<ServiceAccountResponse>, PlatformError> {
    let mut account = state.repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::ServiceAccountNotFound { id: id.clone() })?;

    // Apply updates
    if let Some(name) = req.name {
        if name.is_empty() || name.len() > 100 {
            return Err(PlatformError::bad_request("Name must be 1-100 characters"));
        }
        account.name = name;
    }

    if let Some(description) = req.description {
        if description.len() > 500 {
            return Err(PlatformError::bad_request("Description must be max 500 characters"));
        }
        account.description = Some(description);
    }

    if let Some(client_ids) = req.client_ids {
        account.client_ids = client_ids;
    }

    account.updated_at = Utc::now();

    state.repo.update(&account).await?;

    Ok(Json(ServiceAccountResponse::from(account)))
}

/// Delete service account
#[utoipa::path(
    delete,
    path = "/{id}",
    tag = "service-accounts",
    params(
        ("id" = String, Path, description = "Service account ID")
    ),
    responses(
        (status = 204, description = "Service account deleted"),
        (status = 404, description = "Service account not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn delete_service_account(
    State(state): State<ServiceAccountsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
) -> Result<impl IntoResponse, PlatformError> {
    let deleted = state.repo.delete(&id).await?;

    if deleted {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(PlatformError::ServiceAccountNotFound { id: id.clone() })
    }
}

/// Regenerate auth token
#[utoipa::path(
    post,
    path = "/{id}/regenerate-token",
    tag = "service-accounts",
    params(
        ("id" = String, Path, description = "Service account ID")
    ),
    responses(
        (status = 200, description = "Token regenerated", body = RegenerateTokenResponse),
        (status = 404, description = "Service account not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn regenerate_auth_token(
    State(state): State<ServiceAccountsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<RegenerateTokenResponse>, PlatformError> {
    let mut account = state.repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::ServiceAccountNotFound { id: id.clone() })?;

    let auth_token = generate_auth_token();
    account.webhook_credentials.token = Some(auth_token.clone());
    account.webhook_credentials.auth_type = WebhookAuthType::BearerToken;
    account.updated_at = Utc::now();

    state.repo.update(&account).await?;

    Ok(Json(RegenerateTokenResponse { auth_token }))
}

/// Update auth token (custom value)
#[utoipa::path(
    put,
    path = "/{id}/auth-token",
    tag = "service-accounts",
    params(
        ("id" = String, Path, description = "Service account ID")
    ),
    request_body = UpdateAuthTokenRequest,
    responses(
        (status = 200, description = "Token updated", body = ServiceAccountResponse),
        (status = 404, description = "Service account not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn update_auth_token(
    State(state): State<ServiceAccountsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
    Json(req): Json<UpdateAuthTokenRequest>,
) -> Result<Json<ServiceAccountResponse>, PlatformError> {
    let mut account = state.repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::ServiceAccountNotFound { id: id.clone() })?;

    account.webhook_credentials.token = Some(req.auth_token);
    account.webhook_credentials.auth_type = WebhookAuthType::BearerToken;
    account.updated_at = Utc::now();

    state.repo.update(&account).await?;

    Ok(Json(ServiceAccountResponse::from(account)))
}

/// Regenerate signing secret
#[utoipa::path(
    post,
    path = "/{id}/regenerate-secret",
    tag = "service-accounts",
    params(
        ("id" = String, Path, description = "Service account ID")
    ),
    responses(
        (status = 200, description = "Secret regenerated", body = RegenerateSecretResponse),
        (status = 404, description = "Service account not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn regenerate_signing_secret(
    State(state): State<ServiceAccountsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<RegenerateSecretResponse>, PlatformError> {
    let mut account = state.repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::ServiceAccountNotFound { id: id.clone() })?;

    let signing_secret = generate_signing_secret();
    account.webhook_credentials.signing_secret = Some(signing_secret.clone());
    account.updated_at = Utc::now();

    state.repo.update(&account).await?;

    Ok(Json(RegenerateSecretResponse { signing_secret }))
}

/// Get assigned roles
#[utoipa::path(
    get,
    path = "/{id}/roles",
    tag = "service-accounts",
    params(
        ("id" = String, Path, description = "Service account ID")
    ),
    responses(
        (status = 200, description = "Roles retrieved", body = RolesResponse),
        (status = 404, description = "Service account not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_roles(
    State(state): State<ServiceAccountsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<RolesResponse>, PlatformError> {
    let account = state.repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::ServiceAccountNotFound { id: id.clone() })?;

    let roles: Vec<RoleAssignmentResponse> = account.roles.iter()
        .map(|r| RoleAssignmentResponse {
            role_name: r.role.clone(),
            assignment_source: r.assignment_source.clone(),
            assigned_at: r.assigned_at.to_rfc3339(),
        })
        .collect();

    Ok(Json(RolesResponse { roles }))
}

/// Assign roles (declarative - replaces all)
#[utoipa::path(
    put,
    path = "/{id}/roles",
    tag = "service-accounts",
    params(
        ("id" = String, Path, description = "Service account ID")
    ),
    request_body = AssignRolesRequest,
    responses(
        (status = 200, description = "Roles assigned", body = AssignRolesResponse),
        (status = 404, description = "Service account not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn assign_roles(
    State(state): State<ServiceAccountsState>,
    _auth: Authenticated,
    Path(id): Path<String>,
    Json(req): Json<AssignRolesRequest>,
) -> Result<Json<AssignRolesResponse>, PlatformError> {
    let mut account = state.repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::ServiceAccountNotFound { id: id.clone() })?;

    // Calculate diff
    let current_roles: std::collections::HashSet<String> = account.roles.iter()
        .map(|r| r.role.clone())
        .collect();
    let new_roles: std::collections::HashSet<String> = req.roles.iter().cloned().collect();

    let added_roles: Vec<String> = new_roles.difference(&current_roles).cloned().collect();
    let removed_roles: Vec<String> = current_roles.difference(&new_roles).cloned().collect();

    // Replace roles
    account.roles = req.roles.iter()
        .map(|r| RoleAssignment::new(r))
        .collect();
    account.updated_at = Utc::now();

    state.repo.update(&account).await?;

    let roles: Vec<RoleAssignmentResponse> = account.roles.iter()
        .map(|r| RoleAssignmentResponse {
            role_name: r.role.clone(),
            assignment_source: r.assignment_source.clone(),
            assigned_at: r.assigned_at.to_rfc3339(),
        })
        .collect();

    Ok(Json(AssignRolesResponse {
        roles,
        added_roles,
        removed_roles,
    }))
}

// ============================================================================
// Router
// ============================================================================

/// Create the service accounts router
pub fn service_accounts_router(state: ServiceAccountsState) -> Router {
    Router::new()
        .route("/", get(list_service_accounts).post(create_service_account))
        .route("/:id", get(get_service_account).put(update_service_account).delete(delete_service_account))
        .route("/code/:code", get(get_service_account_by_code))
        .route("/:id/auth-token", put(update_auth_token))
        .route("/:id/regenerate-token", post(regenerate_auth_token))
        .route("/:id/regenerate-secret", post(regenerate_signing_secret))
        .route("/:id/roles", get(get_roles).put(assign_roles))
        .with_state(state)
}
