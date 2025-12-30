//! Roles Admin API
//!
//! REST endpoints for role management.

use salvo::prelude::*;
use salvo::oapi::extract::*;
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
#[derive(Debug, Default, Deserialize, ToParameters)]
#[serde(rename_all = "camelCase")]
#[salvo(parameters(default_parameter_in = Query))]
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
#[endpoint(tags("Roles"))]
pub async fn create_role(
    depot: &mut Depot,
    body: JsonBody<CreateRoleRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<RolesState>().map_err(|_| PlatformError::internal("State not found"))?;

    // Only anchor users can create roles
    crate::service::checks::require_anchor(&auth.0)?;

    let req = body.into_inner();
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
#[endpoint(tags("Roles"))]
pub async fn get_role(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<RoleResponse>, PlatformError> {
    let _auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<RolesState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    // Roles are readable by any authenticated user
    let role = state.role_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Role", &id))?;

    Ok(Json(role.into()))
}

/// Get role by code
#[endpoint(tags("Roles"))]
pub async fn get_role_by_code(
    depot: &mut Depot,
    code: PathParam<String>,
) -> Result<Json<RoleResponse>, PlatformError> {
    let _auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<RolesState>().map_err(|_| PlatformError::internal("State not found"))?;
    let code = code.into_inner();

    let role = state.role_repo.find_by_code(&code).await?
        .ok_or_else(|| PlatformError::not_found("Role", &code))?;

    Ok(Json(role.into()))
}

/// List roles
#[endpoint(tags("Roles"))]
pub async fn list_roles(
    depot: &mut Depot,
    query: RolesQuery,
) -> Result<Json<Vec<RoleResponse>>, PlatformError> {
    let _auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<RolesState>().map_err(|_| PlatformError::internal("State not found"))?;

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
#[endpoint(tags("Roles"))]
pub async fn update_role(
    depot: &mut Depot,
    id: PathParam<String>,
    body: JsonBody<UpdateRoleRequest>,
) -> Result<Json<RoleResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<RolesState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

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

    let req = body.into_inner();
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
#[endpoint(tags("Roles"))]
pub async fn grant_permission(
    depot: &mut Depot,
    id: PathParam<String>,
    body: JsonBody<GrantPermissionRequest>,
) -> Result<Json<RoleResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<RolesState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::require_anchor(&auth.0)?;

    let mut role = state.role_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Role", &id))?;

    if !role.can_modify() {
        return Err(PlatformError::validation("This role cannot be modified"));
    }

    let req = body.into_inner();
    role.grant_permission(req.permission);
    state.role_repo.update(&role).await?;

    Ok(Json(role.into()))
}

/// Revoke permission from role
#[endpoint(tags("Roles"))]
pub async fn revoke_permission(
    depot: &mut Depot,
    id: PathParam<String>,
    permission: PathParam<String>,
) -> Result<Json<RoleResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<RolesState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();
    let permission = permission.into_inner();

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
#[endpoint(tags("Roles"))]
pub async fn delete_role(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<RolesState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

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
        .push(
            Router::new()
                .post(create_role)
                .get(list_roles)
        )
        .push(
            Router::with_path("<id>")
                .get(get_role)
                .put(update_role)
                .delete(delete_role)
        )
        .push(
            Router::with_path("by-code/<code>")
                .get(get_role_by_code)
        )
        .push(
            Router::with_path("<id>/permissions")
                .post(grant_permission)
        )
        .push(
            Router::with_path("<id>/permissions/<permission>")
                .delete(revoke_permission)
        )
        .hoop(affix_state::inject(state))
}
