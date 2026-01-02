//! Principals Admin API
//!
//! REST endpoints for principal (user/service account) management.

use axum::{
    routing::{get, post, delete},
    extract::{State, Path, Query},
    Json, Router,
};
use utoipa::ToSchema;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::domain::{Principal, UserScope, UserIdentity};
use crate::domain::service_account::RoleAssignment;
use crate::repository::PrincipalRepository;
use crate::error::PlatformError;
use crate::api::common::{PaginationParams, CreatedResponse, SuccessResponse};
use crate::api::middleware::Authenticated;
use crate::service::{AuditService, PasswordService};

/// Create user request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateUserRequest {
    /// Email address
    pub email: String,

    /// First name
    #[serde(skip_serializing_if = "Option::is_none")]
    pub first_name: Option<String>,

    /// Last name
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_name: Option<String>,

    /// User scope
    pub scope: String,

    /// Home client ID (for CLIENT scope)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,

    /// Initial roles to assign
    #[serde(default)]
    pub roles: Vec<String>,
}

/// Update principal request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UpdatePrincipalRequest {
    /// Display name
    pub name: Option<String>,

    /// First name (for users)
    pub first_name: Option<String>,

    /// Last name (for users)
    pub last_name: Option<String>,

    /// Active status
    pub active: Option<bool>,
}

/// Assign role request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct AssignRoleRequest {
    /// Role code
    pub role: String,

    /// Client ID (optional, for client-scoped roles)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
}

/// Grant client access request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct GrantClientAccessRequest {
    /// Client ID to grant access to
    pub client_id: String,
}

/// Client access grant response (matches Java ClientAccessGrantDto)
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ClientAccessGrantResponse {
    pub id: String,
    pub client_id: String,
    pub granted_at: String,
    pub expires_at: Option<String>,
}

/// Client access list response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ClientAccessListResponse {
    pub grants: Vec<ClientAccessGrantResponse>,
}

/// Reset password request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ResetPasswordRequest {
    /// New password (min 12 characters)
    pub new_password: String,
}

/// Status change response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct StatusChangeResponse {
    pub message: String,
}

/// Role assignment response (for individual role details)
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RoleAssignmentResponse {
    pub role: String,
    pub client_id: Option<String>,
    pub assigned_at: String,
}

impl From<&RoleAssignment> for RoleAssignmentResponse {
    fn from(r: &RoleAssignment) -> Self {
        Self {
            role: r.role.clone(),
            client_id: r.client_id.clone(),
            assigned_at: r.assigned_at.to_rfc3339(),
        }
    }
}

/// Role assignment DTO (matches Java RoleAssignmentDto for GET /roles)
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RoleAssignmentDto {
    pub id: String,
    pub role_name: String,
    pub assignment_source: String,
    pub assigned_at: String,
}

/// Roles list response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RolesListResponse {
    pub roles: Vec<RoleAssignmentDto>,
}

/// User identity response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UserIdentityResponse {
    pub email: String,
    pub email_verified: bool,
    pub first_name: Option<String>,
    pub last_name: Option<String>,
    pub picture_url: Option<String>,
    pub last_login_at: Option<String>,
}

impl From<&UserIdentity> for UserIdentityResponse {
    fn from(i: &UserIdentity) -> Self {
        Self {
            email: i.email.clone(),
            email_verified: i.email_verified,
            first_name: i.first_name.clone(),
            last_name: i.last_name.clone(),
            picture_url: i.picture_url.clone(),
            last_login_at: i.last_login_at.map(|t| t.to_rfc3339()),
        }
    }
}

/// Principal response DTO (matches Java PrincipalDto)
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct PrincipalResponse {
    pub id: String,
    pub principal_type: String,
    pub scope: String,
    pub name: String,
    pub active: bool,
    pub client_id: Option<String>,
    pub application_id: Option<String>,
    pub user_identity: Option<UserIdentityResponse>,
    pub service_account_id: Option<String>,
    /// Role names (just strings, matching Java's Set<String>)
    pub roles: Vec<String>,
    pub assigned_clients: Vec<String>,
    pub created_at: String,
    pub updated_at: String,
}

impl From<Principal> for PrincipalResponse {
    fn from(p: Principal) -> Self {
        Self {
            id: p.id,
            principal_type: format!("{:?}", p.principal_type).to_uppercase(),
            scope: format!("{:?}", p.scope).to_uppercase(),
            name: p.name,
            active: p.active,
            client_id: p.client_id,
            application_id: p.application_id,
            user_identity: p.user_identity.as_ref().map(|i| i.into()),
            service_account_id: p.service_account_id,
            // Return just the role names as strings (matches Java)
            roles: p.roles.iter().map(|r| r.role.clone()).collect(),
            assigned_clients: p.assigned_clients,
            created_at: p.created_at.to_rfc3339(),
            updated_at: p.updated_at.to_rfc3339(),
        }
    }
}

/// Principal list response (matches Java PrincipalListResponse)
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct PrincipalListResponse {
    pub principals: Vec<PrincipalResponse>,
    pub total: usize,
}

/// Query parameters for principals list
#[derive(Debug, Deserialize, Default, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct PrincipalsQuery {
    #[serde(flatten)]
    pub pagination: PaginationParams,

    /// Filter by type
    #[serde(rename = "type")]
    pub principal_type: Option<String>,

    /// Filter by scope
    pub scope: Option<String>,

    /// Filter by client ID
    pub client_id: Option<String>,
}

/// Principals service state
#[derive(Clone)]
pub struct PrincipalsState {
    pub principal_repo: Arc<PrincipalRepository>,
    pub audit_service: Option<Arc<AuditService>>,
    pub password_service: Option<Arc<PasswordService>>,
}

fn parse_scope(s: &str) -> Result<UserScope, PlatformError> {
    match s.to_uppercase().as_str() {
        "ANCHOR" => Ok(UserScope::Anchor),
        "PARTNER" => Ok(UserScope::Partner),
        "CLIENT" => Ok(UserScope::Client),
        _ => Err(PlatformError::validation(format!("Invalid scope: {}", s))),
    }
}

/// Create a new user principal
#[utoipa::path(
    post,
    path = "",
    tag = "principals",
    request_body = CreateUserRequest,
    responses(
        (status = 201, description = "User created", body = CreatedResponse),
        (status = 400, description = "Validation error"),
        (status = 409, description = "Duplicate email")
    ),
    security(("bearer_auth" = []))
)]
pub async fn create_user(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Json(req): Json<CreateUserRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    // Only anchor or appropriate access
    crate::service::checks::require_anchor(&auth.0)?;

    // Check for duplicate email
    if let Some(_) = state.principal_repo.find_by_email(&req.email).await? {
        return Err(PlatformError::duplicate("Principal", "email", &req.email));
    }

    let scope = parse_scope(&req.scope)?;

    // Validate client_id requirement
    if scope == UserScope::Client && req.client_id.is_none() {
        return Err(PlatformError::validation("CLIENT scope requires client_id"));
    }

    let mut principal = Principal::new_user(&req.email, scope);

    // Update name if provided
    if let Some(ref identity) = principal.user_identity {
        let mut new_identity = identity.clone();
        if let Some(first) = req.first_name.clone() {
            new_identity.first_name = Some(first);
        }
        if let Some(last) = req.last_name.clone() {
            new_identity.last_name = Some(last);
        }
        principal.name = new_identity.display_name();
        principal.user_identity = Some(new_identity);
    }

    if let Some(cid) = req.client_id.clone() {
        principal = principal.with_client_id(cid);
    }

    // Assign initial roles
    for role in req.roles.clone() {
        principal.assign_role(role);
    }

    let id = principal.id.clone();
    state.principal_repo.insert(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_create(&auth.0, "Principal", &id, format!("Created user {}", req.email)).await;
    }

    Ok(Json(CreatedResponse::new(id)))
}

/// Get principal by ID
#[utoipa::path(
    get,
    path = "/{id}",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID")
    ),
    responses(
        (status = 200, description = "Principal found", body = PrincipalResponse),
        (status = 404, description = "Principal not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_principal(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<PrincipalResponse>, PlatformError> {
    let principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    // Check access - anchor can see all, others only their client
    if !auth.0.is_anchor() {
        if let Some(ref cid) = principal.client_id {
            if !auth.0.can_access_client(cid) {
                return Err(PlatformError::forbidden("No access to this principal"));
            }
        }
    }

    Ok(Json(principal.into()))
}

/// List principals
#[utoipa::path(
    get,
    path = "",
    tag = "principals",
    params(
        ("page" = Option<u32>, Query, description = "Page number"),
        ("limit" = Option<u32>, Query, description = "Items per page"),
        ("type" = Option<String>, Query, description = "Filter by type"),
        ("scope" = Option<String>, Query, description = "Filter by scope"),
        ("client_id" = Option<String>, Query, description = "Filter by client ID")
    ),
    responses(
        (status = 200, description = "List of principals", body = PrincipalListResponse)
    ),
    security(("bearer_auth" = []))
)]
pub async fn list_principals(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Query(query): Query<PrincipalsQuery>,
) -> Result<Json<PrincipalListResponse>, PlatformError> {
    let principals = if let Some(ref client_id) = query.client_id {
        if !auth.0.can_access_client(client_id) {
            return Err(PlatformError::forbidden(format!("No access to client: {}", client_id)));
        }
        state.principal_repo.find_by_client(client_id).await?
    } else if let Some(ref scope) = query.scope {
        let s = parse_scope(scope)?;
        state.principal_repo.find_by_scope(s).await?
    } else if query.principal_type.as_deref() == Some("USER") {
        state.principal_repo.find_users().await?
    } else if query.principal_type.as_deref() == Some("SERVICE") {
        state.principal_repo.find_services().await?
    } else {
        state.principal_repo.find_active().await?
    };

    // Filter by access
    let filtered: Vec<PrincipalResponse> = principals.into_iter()
        .filter(|p| {
            if auth.0.is_anchor() {
                return true;
            }
            match &p.client_id {
                Some(cid) => auth.0.can_access_client(cid),
                None => p.scope == UserScope::Anchor && auth.0.is_anchor(),
            }
        })
        .map(|p| p.into())
        .collect();

    let total = filtered.len();
    Ok(Json(PrincipalListResponse { principals: filtered, total }))
}

/// Update principal
#[utoipa::path(
    put,
    path = "/{id}",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID")
    ),
    request_body = UpdatePrincipalRequest,
    responses(
        (status = 200, description = "Principal updated", body = PrincipalResponse),
        (status = 404, description = "Principal not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn update_principal(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path(id): Path<String>,
    Json(req): Json<UpdatePrincipalRequest>,
) -> Result<Json<PrincipalResponse>, PlatformError> {
    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    // Check access
    if !auth.0.is_anchor() {
        if let Some(ref cid) = principal.client_id {
            if !auth.0.can_access_client(cid) {
                return Err(PlatformError::forbidden("No access to this principal"));
            }
        } else {
            return Err(PlatformError::forbidden("Only anchor users can modify anchor-level principals"));
        }
    }

    // Update fields
    if let Some(name) = req.name {
        principal.name = name;
    }
    if let Some(active) = req.active {
        if active {
            principal.activate();
        } else {
            principal.deactivate();
        }
    }

    // Update user identity if applicable
    if principal.is_user() {
        if let Some(ref mut identity) = principal.user_identity {
            if let Some(first) = req.first_name {
                identity.first_name = Some(first);
            }
            if let Some(last) = req.last_name {
                identity.last_name = Some(last);
            }
        }
    }

    principal.updated_at = chrono::Utc::now();
    state.principal_repo.update(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_update(&auth.0, "Principal", &id, format!("Updated principal {}", principal.name)).await;
    }

    Ok(Json(principal.into()))
}

/// Get roles assigned to a principal
#[utoipa::path(
    get,
    path = "/{id}/roles",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID")
    ),
    responses(
        (status = 200, description = "List of roles", body = RolesListResponse),
        (status = 404, description = "Principal not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_roles(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<RolesListResponse>, PlatformError> {
    let principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    // Check access
    if !auth.0.is_anchor() {
        if let Some(ref cid) = principal.client_id {
            if !auth.0.can_access_client(cid) {
                return Err(PlatformError::forbidden("No access to this principal"));
            }
        }
    }

    // Convert role assignments to DTOs
    let roles: Vec<RoleAssignmentDto> = principal.roles.iter()
        .enumerate()
        .map(|(i, r)| RoleAssignmentDto {
            id: format!("{}-role-{}", id, i),
            role_name: r.role.clone(),
            assignment_source: "ADMIN".to_string(), // Default source
            assigned_at: r.assigned_at.to_rfc3339(),
        })
        .collect();

    Ok(Json(RolesListResponse { roles }))
}

/// Assign role to principal
#[utoipa::path(
    post,
    path = "/{id}/roles",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID")
    ),
    request_body = AssignRoleRequest,
    responses(
        (status = 200, description = "Role assigned", body = PrincipalResponse),
        (status = 404, description = "Principal not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn assign_role(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path(id): Path<String>,
    Json(req): Json<AssignRoleRequest>,
) -> Result<Json<PrincipalResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    let role = req.role.clone();
    let client_id = req.client_id.clone();

    if let Some(cid) = req.client_id {
        principal.assign_role_for_client(req.role, cid);
    } else {
        principal.assign_role(req.role);
    }

    state.principal_repo.update(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_role_assigned(&auth.0, &id, &role, client_id.as_deref()).await;
    }

    Ok(Json(principal.into()))
}

/// Remove role from principal
#[utoipa::path(
    delete,
    path = "/{id}/roles/{role}",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID"),
        ("role" = String, Path, description = "Role to remove")
    ),
    responses(
        (status = 200, description = "Role removed", body = PrincipalResponse),
        (status = 404, description = "Principal not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn remove_role(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path((id, role)): Path<(String, String)>,
) -> Result<Json<PrincipalResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    principal.roles.retain(|r| r.role != role);
    principal.updated_at = chrono::Utc::now();

    state.principal_repo.update(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_role_unassigned(&auth.0, &id, &role).await;
    }

    Ok(Json(principal.into()))
}

/// Get client access grants for a principal
#[utoipa::path(
    get,
    path = "/{id}/client-access",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID")
    ),
    responses(
        (status = 200, description = "Client access grants", body = ClientAccessListResponse),
        (status = 404, description = "Principal not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_client_access(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<ClientAccessListResponse>, PlatformError> {
    let principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    // Check access
    if !auth.0.is_anchor() {
        if let Some(ref cid) = principal.client_id {
            if !auth.0.can_access_client(cid) {
                return Err(PlatformError::forbidden("No access to this principal"));
            }
        }
    }

    // Convert assigned_clients to grants (synthesized since we don't store grant metadata)
    let grants: Vec<ClientAccessGrantResponse> = principal.assigned_clients.iter()
        .enumerate()
        .map(|(i, client_id)| ClientAccessGrantResponse {
            id: format!("{}-{}", id, i), // Synthetic ID
            client_id: client_id.clone(),
            granted_at: principal.created_at.to_rfc3339(), // Use principal creation as fallback
            expires_at: None,
        })
        .collect();

    Ok(Json(ClientAccessListResponse { grants }))
}

/// Grant client access to principal
#[utoipa::path(
    post,
    path = "/{id}/client-access",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID")
    ),
    request_body = GrantClientAccessRequest,
    responses(
        (status = 201, description = "Client access granted", body = ClientAccessGrantResponse),
        (status = 404, description = "Principal not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn grant_client_access(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path(id): Path<String>,
    Json(req): Json<GrantClientAccessRequest>,
) -> Result<Json<ClientAccessGrantResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    let client_id = req.client_id.clone();
    let granted_at = chrono::Utc::now();
    principal.grant_client_access(req.client_id);
    state.principal_repo.update(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_client_access_granted(&auth.0, &id, &client_id).await;
    }

    Ok(Json(ClientAccessGrantResponse {
        id: format!("{}-{}", id, principal.assigned_clients.len() - 1),
        client_id,
        granted_at: granted_at.to_rfc3339(),
        expires_at: None,
    }))
}

/// Revoke client access from principal
#[utoipa::path(
    delete,
    path = "/{id}/client-access/{client_id}",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID"),
        ("client_id" = String, Path, description = "Client ID to revoke")
    ),
    responses(
        (status = 204, description = "Client access revoked"),
        (status = 404, description = "Principal not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn revoke_client_access(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path((id, client_id)): Path<(String, String)>,
) -> Result<Json<PrincipalResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    principal.revoke_client_access(&client_id);
    state.principal_repo.update(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_client_access_revoked(&auth.0, &id, &client_id).await;
    }

    Ok(Json(principal.into()))
}

/// Delete principal (deactivate)
#[utoipa::path(
    delete,
    path = "/{id}",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID")
    ),
    responses(
        (status = 200, description = "Principal deleted", body = SuccessResponse),
        (status = 404, description = "Principal not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn delete_principal(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    principal.deactivate();
    state.principal_repo.update(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_archive(&auth.0, "Principal", &id, format!("Deactivated principal {}", principal.name)).await;
    }

    Ok(Json(SuccessResponse::ok()))
}

// ============================================================================
// Status Management Endpoints
// ============================================================================

/// Activate a principal
///
/// Reactivates a deactivated principal.
#[utoipa::path(
    post,
    path = "/{id}/activate",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID")
    ),
    responses(
        (status = 200, description = "Principal activated", body = StatusChangeResponse),
        (status = 404, description = "Principal not found"),
        (status = 403, description = "Insufficient permissions")
    ),
    security(("bearer_auth" = []))
)]
pub async fn activate_principal(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<StatusChangeResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    principal.activate();
    state.principal_repo.update(&principal).await?;

    tracing::info!(principal_id = %id, admin_id = %auth.0.principal_id, "Principal activated");

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_update(&auth.0, "Principal", &id, "Activated principal".to_string()).await;
    }

    Ok(Json(StatusChangeResponse {
        message: "Principal activated".to_string(),
    }))
}

/// Deactivate a principal
///
/// Deactivates an active principal.
#[utoipa::path(
    post,
    path = "/{id}/deactivate",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID")
    ),
    responses(
        (status = 200, description = "Principal deactivated", body = StatusChangeResponse),
        (status = 404, description = "Principal not found"),
        (status = 403, description = "Insufficient permissions")
    ),
    security(("bearer_auth" = []))
)]
pub async fn deactivate_principal(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<StatusChangeResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    principal.deactivate();
    state.principal_repo.update(&principal).await?;

    tracing::info!(principal_id = %id, admin_id = %auth.0.principal_id, "Principal deactivated");

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_update(&auth.0, "Principal", &id, "Deactivated principal".to_string()).await;
    }

    Ok(Json(StatusChangeResponse {
        message: "Principal deactivated".to_string(),
    }))
}

/// Reset a user's password
///
/// Resets the password for an internal auth user. Does not work for OIDC users.
#[utoipa::path(
    post,
    path = "/{id}/reset-password",
    tag = "principals",
    params(
        ("id" = String, Path, description = "Principal ID")
    ),
    request_body = ResetPasswordRequest,
    responses(
        (status = 200, description = "Password reset", body = StatusChangeResponse),
        (status = 400, description = "User is not internal auth or invalid password"),
        (status = 404, description = "Principal not found"),
        (status = 403, description = "Insufficient permissions")
    ),
    security(("bearer_auth" = []))
)]
pub async fn reset_password(
    State(state): State<PrincipalsState>,
    auth: Authenticated,
    Path(id): Path<String>,
    Json(req): Json<ResetPasswordRequest>,
) -> Result<Json<StatusChangeResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    // Get password service
    let password_service = state.password_service.as_ref()
        .ok_or_else(|| PlatformError::internal("Password service not configured"))?;

    // Validate password
    password_service.validate_password(&req.new_password)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    // Check that this is a user with internal auth
    if !principal.is_user() {
        return Err(PlatformError::validation("Password reset only applies to users"));
    }

    // Check for OIDC user (cannot reset password)
    if principal.external_identity.is_some() {
        return Err(PlatformError::validation(
            "Cannot reset password for OIDC-authenticated users"
        ));
    }

    // Hash the new password
    let password_hash = password_service.hash_password(&req.new_password)?;

    // Update the password hash
    if let Some(ref mut identity) = principal.user_identity {
        identity.password_hash = Some(password_hash);
    }

    principal.updated_at = chrono::Utc::now();
    state.principal_repo.update(&principal).await?;

    tracing::info!(principal_id = %id, admin_id = %auth.0.principal_id, "Password reset");

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_update(&auth.0, "Principal", &id, "Password reset by admin".to_string()).await;
    }

    Ok(Json(StatusChangeResponse {
        message: "Password reset successfully".to_string(),
    }))
}

/// Create principals router
pub fn principals_router(state: PrincipalsState) -> Router {
    Router::new()
        .route("/", post(create_user).get(list_principals))
        .route("/:id", get(get_principal).put(update_principal).delete(delete_principal))
        .route("/:id/activate", post(activate_principal))
        .route("/:id/deactivate", post(deactivate_principal))
        .route("/:id/reset-password", post(reset_password))
        .route("/:id/roles", get(get_roles).post(assign_role))
        .route("/:id/roles/:role", delete(remove_role))
        .route("/:id/client-access", get(get_client_access).post(grant_client_access))
        .route("/:id/client-access/:client_id", delete(revoke_client_access))
        .with_state(state)
}
