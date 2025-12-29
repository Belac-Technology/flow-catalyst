//! Principals Admin API
//!
//! REST endpoints for principal (user/service account) management.

use axum::{
    extract::{Path, Query, State},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use utoipa::ToSchema;

use crate::domain::{Principal, UserScope, UserIdentity};
use crate::domain::service_account::RoleAssignment;
use crate::repository::PrincipalRepository;
use crate::error::PlatformError;
use crate::api::common::{ApiResult, PaginationParams, CreatedResponse, SuccessResponse};
use crate::api::middleware::Authenticated;
use crate::service::AuditService;

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

/// Role assignment response
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

/// Principal response DTO
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
    pub roles: Vec<RoleAssignmentResponse>,
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
            roles: p.roles.iter().map(|r| r.into()).collect(),
            assigned_clients: p.assigned_clients,
            created_at: p.created_at.to_rfc3339(),
            updated_at: p.updated_at.to_rfc3339(),
        }
    }
}

/// Query parameters for principals list
#[derive(Debug, Deserialize)]
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
pub async fn create_user(
    State(state): State<PrincipalsState>,
    Authenticated(auth): Authenticated,
    Json(req): Json<CreateUserRequest>,
) -> ApiResult<CreatedResponse> {
    // Only anchor or appropriate access
    crate::service::checks::require_anchor(&auth)?;

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
        if let Some(first) = req.first_name {
            new_identity.first_name = Some(first);
        }
        if let Some(last) = req.last_name {
            new_identity.last_name = Some(last);
        }
        principal.name = new_identity.display_name();
        principal.user_identity = Some(new_identity);
    }

    if let Some(cid) = req.client_id {
        principal = principal.with_client_id(cid);
    }

    // Assign initial roles
    for role in req.roles {
        principal.assign_role(role);
    }

    let id = principal.id.clone();
    state.principal_repo.insert(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_create(&auth, "Principal", &id, format!("Created user {}", req.email)).await;
    }

    Ok(Json(CreatedResponse::new(id)))
}

/// Get principal by ID
pub async fn get_principal(
    State(state): State<PrincipalsState>,
    Authenticated(auth): Authenticated,
    Path(id): Path<String>,
) -> ApiResult<PrincipalResponse> {
    let principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    // Check access - anchor can see all, others only their client
    if !auth.is_anchor() {
        if let Some(ref cid) = principal.client_id {
            if !auth.can_access_client(cid) {
                return Err(PlatformError::forbidden("No access to this principal"));
            }
        }
    }

    Ok(Json(principal.into()))
}

/// List principals
pub async fn list_principals(
    State(state): State<PrincipalsState>,
    Authenticated(auth): Authenticated,
    Query(query): Query<PrincipalsQuery>,
) -> ApiResult<Vec<PrincipalResponse>> {
    let principals = if let Some(ref client_id) = query.client_id {
        if !auth.can_access_client(client_id) {
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
            if auth.is_anchor() {
                return true;
            }
            match &p.client_id {
                Some(cid) => auth.can_access_client(cid),
                None => p.scope == UserScope::Anchor && auth.is_anchor(),
            }
        })
        .map(|p| p.into())
        .collect();

    Ok(Json(filtered))
}

/// Update principal
pub async fn update_principal(
    State(state): State<PrincipalsState>,
    Authenticated(auth): Authenticated,
    Path(id): Path<String>,
    Json(req): Json<UpdatePrincipalRequest>,
) -> ApiResult<PrincipalResponse> {
    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    // Check access
    if !auth.is_anchor() {
        if let Some(ref cid) = principal.client_id {
            if !auth.can_access_client(cid) {
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
        let _ = audit.log_update(&auth, "Principal", &id, format!("Updated principal {}", principal.name)).await;
    }

    Ok(Json(principal.into()))
}

/// Assign role to principal
pub async fn assign_role(
    State(state): State<PrincipalsState>,
    Authenticated(auth): Authenticated,
    Path(id): Path<String>,
    Json(req): Json<AssignRoleRequest>,
) -> ApiResult<PrincipalResponse> {
    crate::service::checks::require_anchor(&auth)?;

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
        let _ = audit.log_role_assigned(&auth, &id, &role, client_id.as_deref()).await;
    }

    Ok(Json(principal.into()))
}

/// Remove role from principal
pub async fn remove_role(
    State(state): State<PrincipalsState>,
    Authenticated(auth): Authenticated,
    Path((id, role)): Path<(String, String)>,
) -> ApiResult<PrincipalResponse> {
    crate::service::checks::require_anchor(&auth)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    principal.roles.retain(|r| r.role != role);
    principal.updated_at = chrono::Utc::now();

    state.principal_repo.update(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_role_unassigned(&auth, &id, &role).await;
    }

    Ok(Json(principal.into()))
}

/// Grant client access to principal
pub async fn grant_client_access(
    State(state): State<PrincipalsState>,
    Authenticated(auth): Authenticated,
    Path(id): Path<String>,
    Json(req): Json<GrantClientAccessRequest>,
) -> ApiResult<PrincipalResponse> {
    crate::service::checks::require_anchor(&auth)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    let client_id = req.client_id.clone();
    principal.grant_client_access(req.client_id);
    state.principal_repo.update(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_client_access_granted(&auth, &id, &client_id).await;
    }

    Ok(Json(principal.into()))
}

/// Revoke client access from principal
pub async fn revoke_client_access(
    State(state): State<PrincipalsState>,
    Authenticated(auth): Authenticated,
    Path((id, client_id)): Path<(String, String)>,
) -> ApiResult<PrincipalResponse> {
    crate::service::checks::require_anchor(&auth)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    principal.revoke_client_access(&client_id);
    state.principal_repo.update(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_client_access_revoked(&auth, &id, &client_id).await;
    }

    Ok(Json(principal.into()))
}

/// Delete principal (deactivate)
pub async fn delete_principal(
    State(state): State<PrincipalsState>,
    Authenticated(auth): Authenticated,
    Path(id): Path<String>,
) -> ApiResult<SuccessResponse> {
    crate::service::checks::require_anchor(&auth)?;

    let mut principal = state.principal_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Principal", &id))?;

    principal.deactivate();
    state.principal_repo.update(&principal).await?;

    // Audit log
    if let Some(ref audit) = state.audit_service {
        let _ = audit.log_archive(&auth, "Principal", &id, format!("Deactivated principal {}", principal.name)).await;
    }

    Ok(Json(SuccessResponse::ok()))
}

/// Create principals router
pub fn principals_router(state: PrincipalsState) -> Router {
    Router::new()
        .route("/", post(create_user).get(list_principals))
        .route("/:id", get(get_principal).put(update_principal).delete(delete_principal))
        .route("/:id/roles", post(assign_role))
        .route("/:id/roles/:role", axum::routing::delete(remove_role))
        .route("/:id/clients", post(grant_client_access))
        .route("/:id/clients/:client_id", axum::routing::delete(revoke_client_access))
        .with_state(state)
}
