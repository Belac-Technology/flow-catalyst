//! Event Types BFF API
//!
//! REST endpoints for event type management.

use salvo::prelude::*;
use salvo::oapi::extract::*;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::domain::{EventType, SpecVersion};
use crate::repository::EventTypeRepository;
use crate::error::PlatformError;
use crate::api::common::{PaginationParams, CreatedResponse, SuccessResponse};
use crate::api::middleware::Authenticated;

/// Create event type request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateEventTypeRequest {
    /// Event type code (e.g., "orders:fulfillment:shipment:shipped")
    /// Format: {application}:{subdomain}:{aggregate}:{event}
    pub code: String,

    /// Human-readable name
    pub name: String,

    /// Description
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,

    /// Initial JSON schema
    #[serde(skip_serializing_if = "Option::is_none")]
    pub schema: Option<serde_json::Value>,

    /// Client ID (optional, null = anchor-level)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
}

/// Update event type request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct UpdateEventTypeRequest {
    /// Human-readable name
    pub name: Option<String>,

    /// Description
    pub description: Option<String>,
}

/// Add schema version request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct AddSchemaVersionRequest {
    /// JSON schema for this version
    pub schema: serde_json::Value,
}

/// Event type response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct EventTypeResponse {
    pub id: String,
    pub code: String,
    pub name: String,
    pub description: Option<String>,
    pub application: String,
    pub subdomain: String,
    pub aggregate: String,
    pub event_name: String,
    pub status: String,
    pub spec_versions: Vec<SpecVersionResponse>,
    pub client_id: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

/// Schema version response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct SpecVersionResponse {
    pub version: u32,
    pub status: String,
    pub schema: serde_json::Value,
    pub created_at: String,
}

impl From<SpecVersion> for SpecVersionResponse {
    fn from(v: SpecVersion) -> Self {
        Self {
            version: v.version,
            status: format!("{:?}", v.status).to_uppercase(),
            schema: v.schema,
            created_at: v.created_at.to_rfc3339(),
        }
    }
}

impl From<EventType> for EventTypeResponse {
    fn from(et: EventType) -> Self {
        Self {
            id: et.id,
            code: et.code,
            name: et.name,
            description: et.description,
            application: et.application,
            subdomain: et.subdomain,
            aggregate: et.aggregate,
            event_name: et.event_name,
            status: format!("{:?}", et.status).to_uppercase(),
            spec_versions: et.spec_versions.into_iter().map(|v| v.into()).collect(),
            client_id: et.client_id,
            created_at: et.created_at.to_rfc3339(),
            updated_at: et.updated_at.to_rfc3339(),
        }
    }
}

/// Query parameters for event types list
#[derive(Debug, Default, Deserialize, ToParameters)]
#[serde(rename_all = "camelCase")]
#[salvo(parameters(default_parameter_in = Query))]
pub struct EventTypesQuery {
    #[serde(flatten)]
    pub pagination: PaginationParams,

    /// Filter by application
    pub application: Option<String>,

    /// Filter by client ID
    pub client_id: Option<String>,

    /// Filter by status
    pub status: Option<String>,
}

/// Event types service state
#[derive(Clone)]
pub struct EventTypesState {
    pub event_type_repo: Arc<EventTypeRepository>,
}

/// Create a new event type
#[endpoint(tags("EventTypes"))]
pub async fn create_event_type(
    depot: &mut Depot,
    body: JsonBody<CreateEventTypeRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<EventTypesState>().map_err(|_| PlatformError::internal("State not found"))?;

    crate::service::checks::can_write_event_types(&auth.0)?;

    let req = body.into_inner();

    // Validate client access if specified
    if let Some(ref cid) = req.client_id {
        if !auth.0.can_access_client(cid) {
            return Err(PlatformError::forbidden(format!("No access to client: {}", cid)));
        }
    } else if !auth.0.is_anchor() {
        return Err(PlatformError::forbidden("Only anchor users can create anchor-level event types"));
    }

    // Check for duplicate code
    if let Some(_) = state.event_type_repo.find_by_code(&req.code).await? {
        return Err(PlatformError::duplicate("EventType", "code", &req.code));
    }

    // Create event type (code is parsed to extract application:subdomain:aggregate:event)
    let mut event_type = EventType::new(&req.code, &req.name)
        .map_err(|e| PlatformError::validation(e))?;

    if let Some(desc) = req.description {
        event_type = event_type.with_description(desc);
    }
    if let Some(cid) = req.client_id {
        event_type = event_type.with_client_id(cid);
    }
    if let Some(schema) = req.schema {
        event_type.add_schema_version(schema);
    }

    let id = event_type.id.clone();
    state.event_type_repo.insert(&event_type).await?;

    Ok(Json(CreatedResponse::new(id)))
}

/// Get event type by ID
#[endpoint(tags("EventTypes"))]
pub async fn get_event_type(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<EventTypeResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<EventTypesState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::can_read_event_types(&auth.0)?;

    let event_type = state.event_type_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("EventType", &id))?;

    // Check client access
    if let Some(ref cid) = event_type.client_id {
        if !auth.0.can_access_client(cid) {
            return Err(PlatformError::forbidden("No access to this event type"));
        }
    }

    Ok(Json(event_type.into()))
}

/// Get event type by code
#[endpoint(tags("EventTypes"))]
pub async fn get_event_type_by_code(
    depot: &mut Depot,
    code: PathParam<String>,
) -> Result<Json<EventTypeResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<EventTypesState>().map_err(|_| PlatformError::internal("State not found"))?;
    let code = code.into_inner();

    crate::service::checks::can_read_event_types(&auth.0)?;

    let event_type = state.event_type_repo.find_by_code(&code).await?
        .ok_or_else(|| PlatformError::EventTypeNotFound { code: code.clone() })?;

    // Check client access
    if let Some(ref cid) = event_type.client_id {
        if !auth.0.can_access_client(cid) {
            return Err(PlatformError::forbidden("No access to this event type"));
        }
    }

    Ok(Json(event_type.into()))
}

/// List event types
#[endpoint(tags("EventTypes"))]
pub async fn list_event_types(
    depot: &mut Depot,
    query: EventTypesQuery,
) -> Result<Json<Vec<EventTypeResponse>>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<EventTypesState>().map_err(|_| PlatformError::internal("State not found"))?;

    crate::service::checks::can_read_event_types(&auth.0)?;

    let event_types = if let Some(ref app) = query.application {
        state.event_type_repo.find_by_application(app).await?
    } else {
        state.event_type_repo.find_active().await?
    };

    // Filter by client access
    let filtered: Vec<EventTypeResponse> = event_types.into_iter()
        .filter(|et| {
            match &et.client_id {
                Some(cid) => auth.0.can_access_client(cid),
                None => true, // Anchor-level event types visible to all
            }
        })
        .map(|et| et.into())
        .collect();

    Ok(Json(filtered))
}

/// Update event type
#[endpoint(tags("EventTypes"))]
pub async fn update_event_type(
    depot: &mut Depot,
    id: PathParam<String>,
    body: JsonBody<UpdateEventTypeRequest>,
) -> Result<Json<EventTypeResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<EventTypesState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::can_write_event_types(&auth.0)?;

    let mut event_type = state.event_type_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("EventType", &id))?;

    // Check client access
    if let Some(ref cid) = event_type.client_id {
        if !auth.0.can_access_client(cid) {
            return Err(PlatformError::forbidden("No access to this event type"));
        }
    } else if !auth.0.is_anchor() {
        return Err(PlatformError::forbidden("Only anchor users can modify anchor-level event types"));
    }

    // Update fields
    let req = body.into_inner();
    if let Some(name) = req.name {
        event_type.name = name;
    }
    if let Some(desc) = req.description {
        event_type.description = Some(desc);
    }
    event_type.updated_at = chrono::Utc::now();

    state.event_type_repo.update(&event_type).await?;

    Ok(Json(event_type.into()))
}

/// Add schema version to event type
#[endpoint(tags("EventTypes"))]
pub async fn add_schema_version(
    depot: &mut Depot,
    id: PathParam<String>,
    body: JsonBody<AddSchemaVersionRequest>,
) -> Result<Json<EventTypeResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<EventTypesState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::can_write_event_types(&auth.0)?;

    let mut event_type = state.event_type_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("EventType", &id))?;

    // Check client access
    if let Some(ref cid) = event_type.client_id {
        if !auth.0.can_access_client(cid) {
            return Err(PlatformError::forbidden("No access to this event type"));
        }
    }

    let req = body.into_inner();
    event_type.add_schema_version(req.schema);
    state.event_type_repo.update(&event_type).await?;

    Ok(Json(event_type.into()))
}

/// Delete event type (archive)
#[endpoint(tags("EventTypes"))]
pub async fn delete_event_type(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<SuccessResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<EventTypesState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::can_write_event_types(&auth.0)?;

    let mut event_type = state.event_type_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("EventType", &id))?;

    // Check client access
    if let Some(ref cid) = event_type.client_id {
        if !auth.0.can_access_client(cid) {
            return Err(PlatformError::forbidden("No access to this event type"));
        }
    } else if !auth.0.is_anchor() {
        return Err(PlatformError::forbidden("Only anchor users can delete anchor-level event types"));
    }

    event_type.archive();
    state.event_type_repo.update(&event_type).await?;

    Ok(Json(SuccessResponse::ok()))
}

/// Create event types router
pub fn event_types_router(state: EventTypesState) -> Router {
    Router::new()
        .push(
            Router::new()
                .post(create_event_type)
                .get(list_event_types)
        )
        .push(
            Router::with_path("<id>")
                .get(get_event_type)
                .put(update_event_type)
                .delete(delete_event_type)
        )
        .push(
            Router::with_path("by-code/<code>")
                .get(get_event_type_by_code)
        )
        .push(
            Router::with_path("<id>/versions")
                .post(add_schema_version)
        )
        .hoop(affix_state::inject(state))
}
