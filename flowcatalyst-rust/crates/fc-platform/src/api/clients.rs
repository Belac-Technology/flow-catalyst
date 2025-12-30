//! Clients Admin API
//!
//! REST endpoints for client management.

use salvo::prelude::*;
use salvo::oapi::{ToSchema, endpoint, extract::*};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::domain::Client;
use crate::repository::ClientRepository;
use crate::error::PlatformError;
use crate::api::common::{PaginationParams, CreatedResponse, SuccessResponse};
use crate::api::middleware::Authenticated;

/// Create client request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateClientRequest {
    /// Unique identifier/slug (URL-safe)
    pub identifier: String,

    /// Human-readable name
    pub name: String,

    /// Description
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

/// Update client request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UpdateClientRequest {
    /// Human-readable name
    pub name: Option<String>,

    /// Description
    pub description: Option<String>,
}

/// Client response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ClientResponse {
    pub id: String,
    pub identifier: String,
    pub name: String,
    pub description: Option<String>,
    pub status: String,
    pub created_at: String,
    pub updated_at: String,
}

impl From<Client> for ClientResponse {
    fn from(c: Client) -> Self {
        Self {
            id: c.id,
            identifier: c.identifier,
            name: c.name,
            description: c.description,
            status: format!("{:?}", c.status).to_uppercase(),
            created_at: c.created_at.to_rfc3339(),
            updated_at: c.updated_at.to_rfc3339(),
        }
    }
}

/// Query parameters for clients list
#[derive(Debug, Deserialize, Default, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ClientsQuery {
    #[serde(flatten)]
    pub pagination: PaginationParams,

    /// Filter by status
    pub status: Option<String>,
}

/// Clients service state
#[derive(Clone)]
pub struct ClientsState {
    pub client_repo: Arc<ClientRepository>,
}

/// Create a new client
#[endpoint(tags("clients"))]
pub async fn create_client(
    depot: &mut Depot,
    body: JsonBody<CreateClientRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<ClientsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let req = body.into_inner();

    // Only anchor users can create clients
    crate::service::checks::require_anchor(&auth.0)?;

    // Check for duplicate identifier
    if let Some(_) = state.client_repo.find_by_identifier(&req.identifier).await? {
        return Err(PlatformError::duplicate("Client", "identifier", &req.identifier));
    }

    let mut client = Client::new(&req.name, &req.identifier);
    if let Some(desc) = req.description {
        client = client.with_description(desc);
    }

    let id = client.id.clone();
    state.client_repo.insert(&client).await?;

    Ok(Json(CreatedResponse::new(id)))
}

/// Get client by ID
#[endpoint(tags("clients"))]
pub async fn get_client(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<ClientResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<ClientsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    // Check access
    if !auth.0.is_anchor() && !auth.0.can_access_client(&id) {
        return Err(PlatformError::forbidden("No access to this client"));
    }

    let client = state.client_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Client", &id))?;

    Ok(Json(client.into()))
}

/// List clients
#[endpoint(tags("clients"))]
pub async fn list_clients(
    depot: &mut Depot,
    _query: QueryParam<ClientsQuery, false>,
) -> Result<Json<Vec<ClientResponse>>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<ClientsState>().map_err(|_| PlatformError::internal("State not found"))?;

    let clients = state.client_repo.find_active().await?;

    // Filter by access
    let filtered: Vec<ClientResponse> = clients.into_iter()
        .filter(|c| auth.0.is_anchor() || auth.0.can_access_client(&c.id))
        .map(|c| c.into())
        .collect();

    Ok(Json(filtered))
}

/// Update client
#[endpoint(tags("clients"))]
pub async fn update_client(
    depot: &mut Depot,
    id: PathParam<String>,
    body: JsonBody<UpdateClientRequest>,
) -> Result<Json<ClientResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<ClientsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();
    let req = body.into_inner();

    crate::service::checks::require_anchor(&auth.0)?;

    let mut client = state.client_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Client", &id))?;

    if let Some(name) = req.name {
        client.name = name;
    }
    if let Some(desc) = req.description {
        client.description = Some(desc);
    }
    client.updated_at = chrono::Utc::now();

    state.client_repo.update(&client).await?;

    Ok(Json(client.into()))
}

/// Delete client (soft delete)
#[endpoint(tags("clients"))]
pub async fn delete_client(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<ClientsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::require_anchor(&auth.0)?;

    let mut client = state.client_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Client", &id))?;

    client.delete(None);
    state.client_repo.update(&client).await?;

    Ok(Json(SuccessResponse::ok()))
}

/// Create clients router
pub fn clients_router(state: ClientsState) -> Router {
    Router::new()
        .push(
            Router::new()
                .post(create_client)
                .get(list_clients)
        )
        .push(
            Router::with_path("<id>")
                .get(get_client)
                .put(update_client)
                .delete(delete_client)
        )
        .hoop(affix_state::inject(state))
}
