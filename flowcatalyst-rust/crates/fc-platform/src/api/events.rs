//! Events BFF API
//!
//! REST endpoints for event management.

use salvo::prelude::*;
use salvo::oapi::extract::*;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::domain::{Event, EventRead};
use crate::repository::EventRepository;
use crate::error::PlatformError;
use crate::api::common::{PaginationParams, CreatedResponse};
use crate::api::middleware::Authenticated;

/// Create event request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CreateEventRequest {
    /// Event type code (e.g., "orders:fulfillment:shipment:shipped")
    pub event_type: String,

    /// Event source URI
    pub source: String,

    /// Event subject (optional context)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub subject: Option<String>,

    /// Event payload data
    pub data: serde_json::Value,

    /// Message group for FIFO ordering
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message_group: Option<String>,

    /// Correlation ID for request tracing
    #[serde(skip_serializing_if = "Option::is_none")]
    pub correlation_id: Option<String>,

    /// Causation ID - the event that caused this event
    #[serde(skip_serializing_if = "Option::is_none")]
    pub causation_id: Option<String>,

    /// Deduplication ID for exactly-once delivery
    #[serde(skip_serializing_if = "Option::is_none")]
    pub deduplication_id: Option<String>,

    /// Client ID (optional, defaults to caller's client)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
}

/// Event response DTO
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct EventResponse {
    pub id: String,
    pub event_type: String,
    pub source: String,
    pub subject: Option<String>,
    pub time: String,
    pub data: serde_json::Value,
    pub message_group: Option<String>,
    pub correlation_id: Option<String>,
    pub client_id: Option<String>,
    pub created_at: String,
}

impl From<Event> for EventResponse {
    fn from(e: Event) -> Self {
        Self {
            id: e.id,
            event_type: e.event_type,
            source: e.source,
            subject: e.subject,
            time: e.time.to_rfc3339(),
            data: e.data,
            message_group: e.message_group,
            correlation_id: e.correlation_id,
            client_id: e.client_id,
            created_at: e.created_at.to_rfc3339(),
        }
    }
}

/// Event read projection response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct EventReadResponse {
    pub id: String,
    pub event_type: String,
    pub source: String,
    pub subject: Option<String>,
    pub time: String,
    pub application: Option<String>,
    pub subdomain: Option<String>,
    pub aggregate: Option<String>,
    pub event_name: Option<String>,
    pub message_group: Option<String>,
    pub correlation_id: Option<String>,
    pub client_id: Option<String>,
    pub client_name: Option<String>,
    pub created_at: String,
}

impl From<EventRead> for EventReadResponse {
    fn from(e: EventRead) -> Self {
        Self {
            id: e.id,
            event_type: e.event_type,
            source: e.source,
            subject: e.subject,
            time: e.time.to_rfc3339(),
            application: e.application,
            subdomain: e.subdomain,
            aggregate: e.aggregate,
            event_name: e.event_name,
            message_group: e.message_group,
            correlation_id: e.correlation_id,
            client_id: e.client_id,
            client_name: e.client_name,
            created_at: e.created_at.to_rfc3339(),
        }
    }
}

/// Query parameters for events list
#[derive(Debug, Default, Deserialize, ToParameters)]
#[serde(rename_all = "camelCase")]
#[salvo(parameters(default_parameter_in = Query))]
pub struct EventsQuery {
    #[serde(flatten)]
    pub pagination: PaginationParams,

    /// Filter by event type
    pub event_type: Option<String>,

    /// Filter by correlation ID
    pub correlation_id: Option<String>,

    /// Filter by client ID
    pub client_id: Option<String>,
}

/// Events service state
#[derive(Clone)]
pub struct EventsState {
    pub event_repo: Arc<EventRepository>,
}

/// Create a new event
#[endpoint(tags("Events"))]
pub async fn create_event(
    depot: &mut Depot,
    body: JsonBody<CreateEventRequest>,
) -> Result<Json<CreatedResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<EventsState>().map_err(|_| PlatformError::internal("State not found"))?;

    // Verify permission
    crate::service::checks::can_write_events(&auth.0)?;

    let req = body.into_inner();

    // Determine client ID
    let client_id = req.client_id.or_else(|| {
        if auth.0.is_anchor() {
            None
        } else {
            auth.0.accessible_clients.first().cloned()
        }
    });

    // Validate client access if specified
    if let Some(ref cid) = client_id {
        if !auth.0.can_access_client(cid) {
            return Err(PlatformError::forbidden(format!("No access to client: {}", cid)));
        }
    }

    // Create event
    let mut event = Event::new(&req.event_type, &req.source, req.data);

    if let Some(subject) = req.subject {
        event = event.with_subject(subject);
    }
    if let Some(group) = req.message_group {
        event = event.with_message_group(group);
    }
    if let Some(corr_id) = req.correlation_id {
        event = event.with_correlation_id(corr_id);
    }
    if let Some(cause_id) = req.causation_id {
        event = event.with_causation_id(cause_id);
    }
    if let Some(cid) = client_id {
        event = event.with_client_id(cid);
    }

    let event_id = event.id.clone();
    state.event_repo.insert(&event).await?;

    Ok(Json(CreatedResponse::new(event_id)))
}

/// Get event by ID
#[endpoint(tags("Events"))]
pub async fn get_event(
    depot: &mut Depot,
    id: PathParam<String>,
) -> Result<Json<EventResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<EventsState>().map_err(|_| PlatformError::internal("State not found"))?;
    let id = id.into_inner();

    crate::service::checks::can_read_events(&auth.0)?;

    let event = state.event_repo.find_by_id(&id).await?
        .ok_or_else(|| PlatformError::not_found("Event", &id))?;

    // Check client access
    if let Some(ref cid) = event.client_id {
        if !auth.0.can_access_client(cid) {
            return Err(PlatformError::forbidden("No access to this event"));
        }
    }

    Ok(Json(event.into()))
}

/// List events
#[endpoint(tags("Events"))]
pub async fn list_events(
    depot: &mut Depot,
    query: EventsQuery,
) -> Result<Json<Vec<EventResponse>>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<EventsState>().map_err(|_| PlatformError::internal("State not found"))?;

    crate::service::checks::can_read_events(&auth.0)?;

    let events = if let Some(ref corr_id) = query.correlation_id {
        state.event_repo.find_by_correlation_id(corr_id).await?
    } else if let Some(ref event_type) = query.event_type {
        state.event_repo.find_by_type(event_type, query.pagination.limit as i64).await?
    } else if let Some(ref client_id) = query.client_id {
        if !auth.0.can_access_client(client_id) {
            return Err(PlatformError::forbidden(format!("No access to client: {}", client_id)));
        }
        state.event_repo.find_by_client(client_id, query.pagination.limit as i64).await?
    } else {
        // Return empty for now - need proper listing with pagination
        vec![]
    };

    // Filter by client access
    let filtered: Vec<EventResponse> = events.into_iter()
        .filter(|e| {
            match &e.client_id {
                Some(cid) => auth.0.can_access_client(cid),
                None => auth.0.is_anchor(), // Anchor-level events only visible to anchors
            }
        })
        .map(|e| e.into())
        .collect();

    Ok(Json(filtered))
}

/// Batch create events request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct BatchCreateEventsRequest {
    pub events: Vec<CreateEventRequest>,
}

/// Batch create response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct BatchCreateResponse {
    pub created: Vec<String>,
    pub failed: Vec<BatchFailure>,
    pub total_created: usize,
    pub total_failed: usize,
}

/// Batch failure info
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct BatchFailure {
    pub index: usize,
    pub error: String,
}

/// Batch create events
#[endpoint(tags("Events"))]
pub async fn batch_create_events(
    depot: &mut Depot,
    body: JsonBody<BatchCreateEventsRequest>,
) -> Result<Json<BatchCreateResponse>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<EventsState>().map_err(|_| PlatformError::internal("State not found"))?;

    crate::service::checks::can_write_events(&auth.0)?;

    let req = body.into_inner();
    let mut created = Vec::new();
    let mut failed = Vec::new();

    for (index, event_req) in req.events.into_iter().enumerate() {
        // Determine client ID
        let client_id = event_req.client_id.or_else(|| {
            if auth.0.is_anchor() {
                None
            } else {
                auth.0.accessible_clients.first().cloned()
            }
        });

        // Validate client access if specified
        if let Some(ref cid) = client_id {
            if !auth.0.can_access_client(cid) {
                failed.push(BatchFailure {
                    index,
                    error: format!("No access to client: {}", cid),
                });
                continue;
            }
        }

        // Create event
        let mut event = Event::new(&event_req.event_type, &event_req.source, event_req.data);

        if let Some(subject) = event_req.subject {
            event = event.with_subject(subject);
        }
        if let Some(group) = event_req.message_group {
            event = event.with_message_group(group);
        }
        if let Some(corr_id) = event_req.correlation_id {
            event = event.with_correlation_id(corr_id);
        }
        if let Some(cause_id) = event_req.causation_id {
            event = event.with_causation_id(cause_id);
        }
        if let Some(cid) = client_id {
            event = event.with_client_id(cid);
        }

        let event_id = event.id.clone();
        match state.event_repo.insert(&event).await {
            Ok(_) => created.push(event_id),
            Err(e) => failed.push(BatchFailure {
                index,
                error: e.to_string(),
            }),
        }
    }

    let total_created = created.len();
    let total_failed = failed.len();

    Ok(Json(BatchCreateResponse {
        created,
        failed,
        total_created,
        total_failed,
    }))
}

/// Create events router
pub fn events_router(state: EventsState) -> Router {
    Router::new()
        .push(
            Router::new()
                .post(create_event)
                .get(list_events)
        )
        .push(
            Router::with_path("batch")
                .post(batch_create_events)
        )
        .push(
            Router::with_path("<id>")
                .get(get_event)
        )
        .hoop(affix_state::inject(state))
}
