//! API Layer
//!
//! REST API endpoints for the platform.
//! Includes BFF (Backend-for-Frontend) and Admin APIs.

pub mod common;
pub mod middleware;

// BFF APIs
pub mod events;
pub mod event_types;
pub mod dispatch_jobs;
pub mod filter_options;

// Admin APIs
pub mod clients;
pub mod principals;
pub mod roles;
pub mod subscriptions;
pub mod oauth_clients;
pub mod auth_config;
pub mod audit_logs;
pub mod applications;
pub mod dispatch_pools;
pub mod monitoring;

pub use common::*;
pub use middleware::{AppState, Authenticated, OptionalAuth};

// BFF routers
pub use events::{EventsState, events_router};
pub use event_types::{EventTypesState, event_types_router};
pub use dispatch_jobs::{DispatchJobsState, dispatch_jobs_router};
pub use filter_options::{FilterOptionsState, filter_options_router};

// Admin routers
pub use clients::{ClientsState, clients_router};
pub use principals::{PrincipalsState, principals_router};
pub use roles::{RolesState, roles_router};
pub use subscriptions::{SubscriptionsState, subscriptions_router};
pub use oauth_clients::{OAuthClientsState, oauth_clients_router};
pub use auth_config::{
    AuthConfigState,
    anchor_domains_router,
    client_auth_configs_router,
    idp_role_mappings_router,
};
pub use audit_logs::{AuditLogsState, audit_logs_router};
pub use applications::{ApplicationsState, applications_router};
pub use dispatch_pools::{DispatchPoolsState, dispatch_pools_router};
pub use monitoring::{
    MonitoringState, monitoring_router,
    LeaderState, CircuitBreakerRegistry, InFlightTracker,
};
