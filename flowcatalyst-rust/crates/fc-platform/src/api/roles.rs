//! Roles Admin API
//!
//! REST endpoints for role management.

use axum::{
    routing::{get, post, put, delete},
    extract::{State, Path, Query},
    Json, Router,
};
use utoipa::{ToSchema, IntoParams};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::domain::{AuthRole, RoleSource};
use crate::repository::RoleRepository;
use crate::error::PlatformError;
use crate::api::common::{PaginationParams, CreatedResponse, SuccessResponse};
use crate::api::middleware::Authenticated;

/// Create role request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateRoleRequest {
    /// Application code this role belongs to
    pub application_code: String,

    /// Role name (will be combined with app code to form code)
    pub role_name: String,

    /// Display name
    pub display_name: String,

    /// Description
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,

    /// Initial permissions
    #[serde(default)]
    pub permissions: Vec<String>,

    /// Whether clients can manage this role
    #[serde(default)]
    pub client_managed: bool,
}

/// Update role request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UpdateRoleRequest {
    /// Display name
    pub display_name: Option<String>,

    /// Description
    pub description: Option<String>,

    /// Whether clients can manage this role
    pub client_managed: Option<bool>,
}

/// Grant permission request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct GrantPermissionRequest {
    /// Permission to grant
    pub permission: String,
}

/// Role response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RoleResponse {
    pub id: String,
    pub code: String,
    pub display_name: String,
    pub description: Option<String>,
    pub application_code: String,
    pub permissions: Vec<String>,
    pub source: String,
    pub client_managed: bool,
    pub created_at: String,
    pub updated_at: String,
}

impl From<AuthRole> for RoleResponse {
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
            created_at: r.created_at.to_rfc3339(),
            updated_at: r.updated_at.to_rfc3339(),
        }
    }
}

/// Query parameters for roles list
#[derive(Debug, Default, Deserialize, IntoParams)]
#[serde(rename_all = "camelCase")]
#[into_params(parameter_in = Query)]
pub struct RolesQuery {
    #[serde(flatten)]
    pub pagination: PaginationParams,

    /// Filter by application code
    pub application_code: Option<String>,

    /// Filter by source
    pub source: Option<String>,

    /// Filter client-managed roles only
    pub client_managed: Option<bool>,
}

/// Roles service state
#[derive(Clone)]
pub struct RolesState {
    pub role_repo: Arc<RoleRepository>,
}

fn parse_source(s: &str) -> Result<RoleSource, PlatformError> {
    match s.to_uppercase().as_str() {
        "CODE" => Ok(RoleSource::Code),
        "DATABASE" => Ok(RoleSource::Database),
        "SDK" => Ok(RoleSource::Sdk),
        _ => Err(PlatformError::validation(format!("Invalid source: {}", s))),
    }
}

/// Create a new role
#[utoipa::path(
    post,
    path = "",
    tag = "roles",
    request_body = CreateRoleRequest,
    responses(
        (status = 201, description = "Role created", body = CreatedResponse),
        (status = 400, description = "Validation error"),
        (status = 409, description = "Duplicate role code")
    ),
    security(("bearer_auth" = []))
)]
pub async fn create_role(
    State(state): State<RolesState>,
    auth: Authenticated,
    Json(req): Json<CreateRoleRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    // Only anchor users can create roles
    crate::service::checks::require_anchor(&auth.0)?;

    let role_code = format!("{}:{}", req.application_code, req.role_name);

    // Check for duplicate code
    if let Some(_) = state.role_repo.find_by_code(&role_code).await? {
        return Err(PlatformError::duplicate("Role", "code", &role_code));
    }

    let mut role = AuthRole::new(&req.application_code, &req.role_name, &req.display_name);

    if let Some(desc) = req.description {
        role = role.with_description(desc);
    }

    role = role.with_permissions(req.permissions);
    role = role.with_client_managed(req.client_managed);

    let id = role.id.clone();
    state.role_repo.insert(&role).await?;

    Ok(Json(CreatedResponse::new(id)))
}

/// Get role by ID
#[utoipa::path(
    get,
    path = "/{id}",
    tag = "roles",
    params(
        ("id" = String, Path, description = "Role ID")
    ),
    responses(
        (status = 200, description = "Role found", body = RoleResponse),
        (status = 404, description = "Role not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_role(
    State(state): State<RolesState>,
    _auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<RoleResponse>, PlatformError> {
    // Roles are readable by any authenticated user
    let role = state.role_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Role", &id))?;

    Ok(Json(role.into()))
}

/// Get role by code
#[utoipa::path(
    get,
    path = "/by-code/{code}",
    tag = "roles",
    params(
        ("code" = String, Path, description = "Role code")
    ),
    responses(
        (status = 200, description = "Role found", body = RoleResponse),
        (status = 404, description = "Role not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn get_role_by_code(
    State(state): State<RolesState>,
    _auth: Authenticated,
    Path(code): Path<String>,
) -> Result<Json<RoleResponse>, PlatformError> {
    let role = state.role_repo.find_by_code(&code).await?
        .ok_or_else(|| PlatformError::not_found("Role", &code))?;

    Ok(Json(role.into()))
}

/// List roles
#[utoipa::path(
    get,
    path = "",
    tag = "roles",
    params(RolesQuery),
    responses(
        (status = 200, description = "List of roles", body = Vec<RoleResponse>)
    ),
    security(("bearer_auth" = []))
)]
pub async fn list_roles(
    State(state): State<RolesState>,
    _auth: Authenticated,
    Query(query): Query<RolesQuery>,
) -> Result<Json<Vec<RoleResponse>>, PlatformError> {
    let roles = if let Some(ref app) = query.application_code {
        state.role_repo.find_by_application(app).await?
    } else if let Some(ref source) = query.source {
        let s = parse_source(source)?;
        state.role_repo.find_by_source(s).await?
    } else if query.client_managed == Some(true) {
        state.role_repo.find_client_managed().await?
    } else {
        state.role_repo.find_all().await?
    };

    let responses: Vec<RoleResponse> = roles.into_iter()
        .map(|r| r.into())
        .collect();

    Ok(Json(responses))
}

/// Update role
#[utoipa::path(
    put,
    path = "/{id}",
    tag = "roles",
    params(
        ("id" = String, Path, description = "Role ID")
    ),
    request_body = UpdateRoleRequest,
    responses(
        (status = 200, description = "Role updated", body = RoleResponse),
        (status = 404, description = "Role not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn update_role(
    State(state): State<RolesState>,
    auth: Authenticated,
    Path(id): Path<String>,
    Json(req): Json<UpdateRoleRequest>,
) -> Result<Json<RoleResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut role = state.role_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Role", &id))?;

    // Check if role can be modified
    if !role.can_modify() {
        return Err(PlatformError::validation(format!(
            "Role {} is from source {:?} and cannot be modified",
            role.code, role.source
        )));
    }

    if let Some(display_name) = req.display_name {
        role.display_name = display_name;
    }
    if let Some(desc) = req.description {
        role.description = Some(desc);
    }
    if let Some(client_managed) = req.client_managed {
        role.client_managed = client_managed;
    }

    role.updated_at = chrono::Utc::now();
    state.role_repo.update(&role).await?;

    Ok(Json(role.into()))
}

/// Grant permission to role
#[utoipa::path(
    post,
    path = "/{id}/permissions",
    tag = "roles",
    params(
        ("id" = String, Path, description = "Role ID")
    ),
    request_body = GrantPermissionRequest,
    responses(
        (status = 200, description = "Permission granted", body = RoleResponse),
        (status = 404, description = "Role not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn grant_permission(
    State(state): State<RolesState>,
    auth: Authenticated,
    Path(id): Path<String>,
    Json(req): Json<GrantPermissionRequest>,
) -> Result<Json<RoleResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut role = state.role_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Role", &id))?;

    if !role.can_modify() {
        return Err(PlatformError::validation("This role cannot be modified"));
    }

    role.grant_permission(req.permission);
    state.role_repo.update(&role).await?;

    Ok(Json(role.into()))
}

/// Revoke permission from role
#[utoipa::path(
    delete,
    path = "/{id}/permissions/{permission}",
    tag = "roles",
    params(
        ("id" = String, Path, description = "Role ID"),
        ("permission" = String, Path, description = "Permission to revoke")
    ),
    responses(
        (status = 200, description = "Permission revoked", body = RoleResponse),
        (status = 404, description = "Role not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn revoke_permission(
    State(state): State<RolesState>,
    auth: Authenticated,
    Path((id, permission)): Path<(String, String)>,
) -> Result<Json<RoleResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let mut role = state.role_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Role", &id))?;

    if !role.can_modify() {
        return Err(PlatformError::validation("This role cannot be modified"));
    }

    role.revoke_permission(&permission);
    state.role_repo.update(&role).await?;

    Ok(Json(role.into()))
}

/// Delete role
#[utoipa::path(
    delete,
    path = "/{id}",
    tag = "roles",
    params(
        ("id" = String, Path, description = "Role ID")
    ),
    responses(
        (status = 200, description = "Role deleted", body = SuccessResponse),
        (status = 404, description = "Role not found")
    ),
    security(("bearer_auth" = []))
)]
pub async fn delete_role(
    State(state): State<RolesState>,
    auth: Authenticated,
    Path(id): Path<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    crate::service::checks::require_anchor(&auth.0)?;

    let role = state.role_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Role", &id))?;

    if !role.can_modify() {
        return Err(PlatformError::validation("This role cannot be deleted"));
    }

    state.role_repo.delete(&id).await?;

    Ok(Json(SuccessResponse::ok()))
}

/// Create roles router
pub fn roles_router(state: RolesState) -> Router {
    Router::new()
        .route("/", post(create_role).get(list_roles))
        .route("/:id", get(get_role).put(update_role).delete(delete_role))
        .route("/by-code/:code", get(get_role_by_code))
        .route("/:id/permissions", post(grant_permission))
        .route("/:id/permissions/:permission", delete(revoke_permission))
        .with_state(state)
}
