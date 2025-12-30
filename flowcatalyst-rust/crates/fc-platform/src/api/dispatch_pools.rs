//! Dispatch Pools Admin API
//!
//! REST endpoints for dispatch pool management.

use salvo::prelude::*;
use salvo::oapi::extract::*;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::domain::{DispatchPool, DispatchPoolStatus};
use crate::repository::DispatchPoolRepository;
use crate::error::PlatformError;
use crate::api::common::{PaginationParams, CreatedResponse, SuccessResponse};
use crate::api::middleware::Authenticated;

/// Create dispatch pool request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateDispatchPoolRequest {
    /// Unique code (URL-safe)
    pub code: String,

    /// Human-readable name
    pub name: String,

    /// Description
    pub description: Option<String>,

    /// Client ID (null for anchor-level)
    pub client_id: Option<String>,

    /// Rate limit (messages per minute)
    pub rate_limit: Option<u32>,

    /// Max concurrent dispatches
    pub concurrency: Option<u32>,
}

/// Update dispatch pool request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UpdateDispatchPoolRequest {
    /// Human-readable name
    pub name: Option<String>,

    /// Description
    pub description: Option<String>,

    /// Rate limit (messages per minute)
    pub rate_limit: Option<u32>,

    /// Max concurrent dispatches
    pub concurrency: Option<u32>,
}

/// Dispatch pool response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct DispatchPoolResponse {
    pub id: String,
    pub code: String,
    pub name: String,
    pub description: Option<String>,
    pub client_id: Option<String>,
    pub status: String,
    pub rate_limit: Option<u32>,
    pub concurrency: Option<u32>,
    pub created_at: String,
    pub updated_at: String,
}

impl From<DispatchPool> for DispatchPoolResponse {
    fn from(p: DispatchPool) -> Self {
        Self {
            id: p.id,
            code: p.code,
            name: p.name,
            description: p.description,
            client_id: p.client_id,
            status: format!("{:?}", p.status).to_uppercase(),
            rate_limit: p.rate_limit,
            concurrency: p.concurrency,
            created_at: p.created_at.to_rfc3339(),
            updated_at: p.updated_at.to_rfc3339(),
        }
    }
}

/// Query parameters for dispatch pools list
#[derive(Debug, Default, Deserialize, ToParameters)]
#[serde(rename_all = "camelCase")]
#[salvo(parameters(default_parameter_in = Query))]
pub struct DispatchPoolsQuery {
    #[serde(flatten)]
    pub pagination: PaginationParams,

    /// Filter by client ID
    pub client_id: Option<String>,

    /// Filter by status
    pub status: Option<String>,
}

/// Dispatch pools service state
#[derive(Clone)]
pub struct DispatchPoolsState {
    pub dispatch_pool_repo: Arc<DispatchPoolRepository>,
}

fn parse_status(s: &str) -> Option<DispatchPoolStatus> {
    match s.to_uppercase().as_str() {
        "ACTIVE" => Some(DispatchPoolStatus::Active),
        "ARCHIVED" => Some(DispatchPoolStatus::Archived),
        _ => None,
    }
}

/// Create a new dispatch pool
#[endpoint(tags("DispatchPools"))]
pub async fn create_dispatch_pool(
    depot: &mut Depot,
    body: JsonBody<CreateDispatchPoolRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<DispatchPoolsState>().map_err(|_| PlatformError::internal("State not found"))?;

    let req = body.into_inner();

    // Check access - anchor or client admin
    if !auth.0.is_anchor() {
        if let Some(ref client_id) = req.client_id {
            if !auth.0.can_access_client(client_id) {
                return Err(PlatformError::forbidden("No access to this client"));
            }
        } else {
            return Err(PlatformError::forbidden("Client ID required for non-anchor users"));
        }
    }

    // Check for duplicate code
    if let Some(_) = state.dispatch_pool_repo.find_by_code(&req.code).await? {
        return Err(PlatformError::duplicate("DispatchPool", "code", &req.code));
    }

    let mut pool = DispatchPool::new(&req.code, &req.name);

    if let Some(desc) = req.description {
        pool = pool.with_description(desc);
    }

    if let Some(ref client_id) = req.client_id {
        pool = pool.with_client_id(client_id);
    }

    if let Some(rate) = req.rate_limit {
        pool = pool.with_rate_limit(rate);
    }

    if let Some(conc) = req.concurrency {
        pool = pool.with_concurrency(conc);
    }

    let id = pool.id.clone();
    state.dispatch_pool_repo.insert(&pool).await?;

    Ok(Json(CreatedResponse::new(id)))
}

/// Get dispatch pool by ID
#[endpoint(tags("DispatchPools"))]
pub async fn get_dispatch_pool(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<DispatchPoolResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<DispatchPoolsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    let pool = state.dispatch_pool_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("DispatchPool", &id))?;

    // Check access
    if !auth.0.is_anchor() {
        if let Some(ref client_id) = pool.client_id {
            if !auth.0.can_access_client(client_id) {
                return Err(PlatformError::forbidden("No access to this dispatch pool"));
            }
        }
    }

    Ok(Json(pool.into()))
}

/// List dispatch pools
#[endpoint(tags("DispatchPools"))]
pub async fn list_dispatch_pools(
    depot: &mut Depot,
    query: DispatchPoolsQuery,
) -> Result<Json<Vec<DispatchPoolResponse>>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<DispatchPoolsState>().map_err(|_| PlatformError::internal("State not found"))?;

    let pools = if let Some(ref client_id) = query.client_id {
        // Check access
        if !auth.0.is_anchor() && !auth.0.can_access_client(client_id) {
            return Err(PlatformError::forbidden("No access to this client"));
        }
        state.dispatch_pool_repo.find_by_client(Some(client_id.as_str())).await?
    } else {
        // Get active pools by default, or all pools accessible to user
        state.dispatch_pool_repo.find_active().await?
    };

    // Filter by status if specified
    let status_filter = query.status.as_deref().and_then(parse_status);

    // Filter by access for non-anchor users and by status
    let filtered: Vec<DispatchPoolResponse> = pools.into_iter()
        .filter(|p| {
            // Status filter
            if let Some(ref status) = status_filter {
                if p.status != *status {
                    return false;
                }
            }
            // Access filter
            if auth.0.is_anchor() {
                true
            } else if let Some(ref cid) = p.client_id {
                auth.0.can_access_client(cid)
            } else {
                // Anchor-level pools visible to all authenticated users
                true
            }
        })
        .map(|p| p.into())
        .collect();

    Ok(Json(filtered))
}

/// Update dispatch pool
#[endpoint(tags("DispatchPools"))]
pub async fn update_dispatch_pool(
    depot: &mut Depot,
    id: PathParam<String>,
    body: JsonBody<UpdateDispatchPoolRequest>,
) -> Result<Json<DispatchPoolResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<DispatchPoolsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    let mut pool = state.dispatch_pool_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("DispatchPool", &id))?;

    // Check access
    if !auth.0.is_anchor() {
        if let Some(ref client_id) = pool.client_id {
            if !auth.0.can_access_client(client_id) {
                return Err(PlatformError::forbidden("No access to this dispatch pool"));
            }
        } else {
            return Err(PlatformError::forbidden("Cannot update anchor-level dispatch pool"));
        }
    }

    let req = body.into_inner();
    if let Some(name) = req.name {
        pool.name = name;
    }
    if let Some(desc) = req.description {
        pool.description = Some(desc);
    }
    if let Some(rate) = req.rate_limit {
        pool.rate_limit = Some(rate);
    }
    if let Some(conc) = req.concurrency {
        pool.concurrency = Some(conc);
    }

    pool.updated_at = chrono::Utc::now();
    state.dispatch_pool_repo.update(&pool).await?;

    Ok(Json(pool.into()))
}

/// Archive dispatch pool
#[endpoint(tags("DispatchPools"))]
pub async fn archive_dispatch_pool(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<DispatchPoolResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<DispatchPoolsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    let mut pool = state.dispatch_pool_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("DispatchPool", &id))?;

    // Check access
    if !auth.0.is_anchor() {
        if let Some(ref client_id) = pool.client_id {
            if !auth.0.can_access_client(client_id) {
                return Err(PlatformError::forbidden("No access to this dispatch pool"));
            }
        } else {
            return Err(PlatformError::forbidden("Cannot archive anchor-level dispatch pool"));
        }
    }

    pool.archive();
    state.dispatch_pool_repo.update(&pool).await?;

    Ok(Json(pool.into()))
}

/// Delete dispatch pool
#[endpoint(tags("DispatchPools"))]
pub async fn delete_dispatch_pool(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<DispatchPoolsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::require_anchor(&auth.0)?;

    let exists = state.dispatch_pool_repo.find_by_id(&id).await?.is_some();
    if !exists {
        return Err(PlatformError::not_found("DispatchPool", &id));
    }

    state.dispatch_pool_repo.delete(&id).await?;

    Ok(Json(SuccessResponse::ok()))
}

/// Create dispatch pools router
pub fn dispatch_pools_router(state: DispatchPoolsState) -> Router {
    Router::new()
        .push(
            Router::new()
                .post(create_dispatch_pool)
                .get(list_dispatch_pools)
        )
        .push(
            Router::with_path("<id>")
                .get(get_dispatch_pool)
                .put(update_dispatch_pool)
                .delete(delete_dispatch_pool)
        )
        .push(
            Router::with_path("<id>/archive")
                .post(archive_dispatch_pool)
        )
        .hoop(affix_state::inject(state))
}
