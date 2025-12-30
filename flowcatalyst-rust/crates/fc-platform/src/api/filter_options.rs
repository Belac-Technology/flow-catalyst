//! Filter Options BFF API
//!
//! REST endpoints for fetching filter options for UI dropdowns.

use salvo::prelude::*;
use serde::Serialize;
use std::sync::Arc;

use crate::repository::{
    ClientRepository, EventTypeRepository, SubscriptionRepository,
    DispatchPoolRepository, ApplicationRepository,
};
use crate::error::PlatformError;
use crate::api::middleware::Authenticated;

/// Filter option item
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct FilterOption {
    pub value: String,
    pub label: String,
}

/// Client filter options response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ClientFilterOptions {
    pub clients: Vec<FilterOption>,
}

/// Event type filter options response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct EventTypeFilterOptions {
    pub event_types: Vec<FilterOption>,
    pub applications: Vec<FilterOption>,
    pub subdomains: Vec<FilterOption>,
}

/// Subscription filter options response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct SubscriptionFilterOptions {
    pub subscriptions: Vec<FilterOption>,
}

/// Dispatch pool filter options response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct DispatchPoolFilterOptions {
    pub dispatch_pools: Vec<FilterOption>,
}

/// All filter options combined
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct AllFilterOptions {
    pub clients: Vec<FilterOption>,
    pub event_types: Vec<FilterOption>,
    pub applications: Vec<FilterOption>,
    pub subscriptions: Vec<FilterOption>,
    pub dispatch_pools: Vec<FilterOption>,
}

/// Filter options service state
#[derive(Clone)]
pub struct FilterOptionsState {
    pub client_repo: Arc<ClientRepository>,
    pub event_type_repo: Arc<EventTypeRepository>,
    pub subscription_repo: Arc<SubscriptionRepository>,
    pub dispatch_pool_repo: Arc<DispatchPoolRepository>,
    pub application_repo: Arc<ApplicationRepository>,
}

/// Get client filter options
#[endpoint(tags("FilterOptions"))]
pub async fn get_client_options(
    depot: &mut Depot,
) -> Result<Json<ClientFilterOptions>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<FilterOptionsState>().map_err(|_| PlatformError::internal("State not found"))?;

    let clients = state.client_repo.find_active().await?;

    // Filter by access
    let options: Vec<FilterOption> = clients.into_iter()
        .filter(|c| auth.0.is_anchor() || auth.0.can_access_client(&c.id))
        .map(|c| FilterOption {
            value: c.id,
            label: c.name,
        })
        .collect();

    Ok(Json(ClientFilterOptions { clients: options }))
}

/// Get event type filter options
#[endpoint(tags("FilterOptions"))]
pub async fn get_event_type_options(
    depot: &mut Depot,
) -> Result<Json<EventTypeFilterOptions>, PlatformError> {
    let _auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<FilterOptionsState>().map_err(|_| PlatformError::internal("State not found"))?;

    let event_types = state.event_type_repo.find_active().await?;

    // Build event type options
    let event_type_options: Vec<FilterOption> = event_types.iter()
        .map(|et| FilterOption {
            value: et.code.clone(),
            label: et.name.clone(),
        })
        .collect();

    // Extract unique applications
    let mut applications: Vec<FilterOption> = event_types.iter()
        .map(|et| et.application.clone())
        .collect::<std::collections::HashSet<_>>()
        .into_iter()
        .map(|app| FilterOption {
            value: app.clone(),
            label: app,
        })
        .collect();
    applications.sort_by(|a, b| a.label.cmp(&b.label));

    // Extract unique subdomains
    let mut subdomains: Vec<FilterOption> = event_types.iter()
        .map(|et| et.subdomain.clone())
        .collect::<std::collections::HashSet<_>>()
        .into_iter()
        .map(|sub| FilterOption {
            value: sub.clone(),
            label: sub,
        })
        .collect();
    subdomains.sort_by(|a, b| a.label.cmp(&b.label));

    Ok(Json(EventTypeFilterOptions {
        event_types: event_type_options,
        applications,
        subdomains,
    }))
}

/// Get subscription filter options
#[endpoint(tags("FilterOptions"))]
pub async fn get_subscription_options(
    depot: &mut Depot,
) -> Result<Json<SubscriptionFilterOptions>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<FilterOptionsState>().map_err(|_| PlatformError::internal("State not found"))?;

    let subscriptions = state.subscription_repo.find_active().await?;

    // Filter by access
    let options: Vec<FilterOption> = subscriptions.into_iter()
        .filter(|s| {
            match &s.client_id {
                Some(cid) => auth.0.is_anchor() || auth.0.can_access_client(cid),
                None => auth.0.is_anchor(),
            }
        })
        .map(|s| FilterOption {
            value: s.id,
            label: s.name,
        })
        .collect();

    Ok(Json(SubscriptionFilterOptions { subscriptions: options }))
}

/// Get dispatch pool filter options
#[endpoint(tags("FilterOptions"))]
pub async fn get_dispatch_pool_options(
    depot: &mut Depot,
) -> Result<Json<DispatchPoolFilterOptions>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<FilterOptionsState>().map_err(|_| PlatformError::internal("State not found"))?;

    let pools = state.dispatch_pool_repo.find_active().await?;

    // Filter by access
    let options: Vec<FilterOption> = pools.into_iter()
        .filter(|p| {
            match &p.client_id {
                Some(cid) => auth.0.is_anchor() || auth.0.can_access_client(cid),
                None => true, // Anchor-level pools visible to all
            }
        })
        .map(|p| FilterOption {
            value: p.id,
            label: p.name,
        })
        .collect();

    Ok(Json(DispatchPoolFilterOptions { dispatch_pools: options }))
}

/// Get all filter options at once
#[endpoint(tags("FilterOptions"))]
pub async fn get_all_options(
    depot: &mut Depot,
) -> Result<Json<AllFilterOptions>, PlatformError> {
    let auth = Authenticated::from_depot(depot)?;
    let state = depot.obtain::<FilterOptionsState>().map_err(|_| PlatformError::internal("State not found"))?;

    // Get clients
    let clients = state.client_repo.find_active().await?;
    let client_options: Vec<FilterOption> = clients.into_iter()
        .filter(|c| auth.0.is_anchor() || auth.0.can_access_client(&c.id))
        .map(|c| FilterOption {
            value: c.id,
            label: c.name,
        })
        .collect();

    // Get event types
    let event_types = state.event_type_repo.find_active().await?;
    let event_type_options: Vec<FilterOption> = event_types.iter()
        .map(|et| FilterOption {
            value: et.code.clone(),
            label: et.name.clone(),
        })
        .collect();

    // Get applications
    let apps = state.application_repo.find_active().await?;
    let app_options: Vec<FilterOption> = apps.into_iter()
        .map(|a| FilterOption {
            value: a.code,
            label: a.name,
        })
        .collect();

    // Get subscriptions
    let subscriptions = state.subscription_repo.find_active().await?;
    let subscription_options: Vec<FilterOption> = subscriptions.into_iter()
        .filter(|s| {
            match &s.client_id {
                Some(cid) => auth.0.is_anchor() || auth.0.can_access_client(cid),
                None => auth.0.is_anchor(),
            }
        })
        .map(|s| FilterOption {
            value: s.id,
            label: s.name,
        })
        .collect();

    // Get dispatch pools
    let pools = state.dispatch_pool_repo.find_active().await?;
    let pool_options: Vec<FilterOption> = pools.into_iter()
        .filter(|p| {
            match &p.client_id {
                Some(cid) => auth.0.is_anchor() || auth.0.can_access_client(cid),
                None => true,
            }
        })
        .map(|p| FilterOption {
            value: p.id,
            label: p.name,
        })
        .collect();

    Ok(Json(AllFilterOptions {
        clients: client_options,
        event_types: event_type_options,
        applications: app_options,
        subscriptions: subscription_options,
        dispatch_pools: pool_options,
    }))
}

/// Create filter options router
pub fn filter_options_router(state: FilterOptionsState) -> Router {
    Router::new()
        .push(
            Router::new()
                .get(get_all_options)
        )
        .push(
            Router::with_path("clients")
                .get(get_client_options)
        )
        .push(
            Router::with_path("event-types")
                .get(get_event_type_options)
        )
        .push(
            Router::with_path("subscriptions")
                .get(get_subscription_options)
        )
        .push(
            Router::with_path("dispatch-pools")
                .get(get_dispatch_pool_options)
        )
        .hoop(affix_state::inject(state))
}
