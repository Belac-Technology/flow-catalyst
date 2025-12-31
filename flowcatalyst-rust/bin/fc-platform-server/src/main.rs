//! FlowCatalyst Platform Server
//!
//! Production server for platform REST APIs:
//! - BFF APIs: events, event-types, dispatch-jobs, filter-options
//! - Admin APIs: clients, principals, roles, subscriptions, etc.
//! - Monitoring APIs: health, metrics, leader status
//!
//! ## Environment Variables
//!
//! | Variable | Default | Description |
//! |----------|---------|-------------|
//! | `FC_API_PORT` | `8080` | HTTP API port |
//! | `FC_METRICS_PORT` | `9090` | Metrics/health port |
//! | `FC_MONGO_URL` | `mongodb://localhost:27017` | MongoDB connection URL |
//! | `FC_MONGO_DB` | `flowcatalyst` | MongoDB database name |
//! | `FC_JWT_PRIVATE_KEY_PATH` | - | Path to RSA private key PEM |
//! | `FC_JWT_PUBLIC_KEY_PATH` | - | Path to RSA public key PEM |
//! | `FLOWCATALYST_JWT_PRIVATE_KEY` | - | RSA private key PEM content (env) |
//! | `FLOWCATALYST_JWT_PUBLIC_KEY` | - | RSA public key PEM content (env) |
//! | `FC_JWT_ISSUER` | `flowcatalyst` | JWT issuer claim |
//! | `RUST_LOG` | `info` | Log level |

use std::sync::Arc;
use axum::{
    routing::get,
    response::Json,
    Router,
};
use tower_http::cors::{CorsLayer, Any};
use tower_http::trace::TraceLayer;
use anyhow::Result;
use tracing::info;
use tracing_subscriber::EnvFilter;
use tokio::{signal, net::TcpListener};

use fc_platform::service::{AuthService, AuthConfig, AuthorizationService, AuditService};
use fc_platform::api::middleware::{AppState, AuthLayer};
use fc_platform::api::{
    EventsState, events_router,
    EventTypesState, event_types_router,
    DispatchJobsState, dispatch_jobs_router,
    FilterOptionsState, filter_options_router,
    ClientsState, clients_router,
    PrincipalsState, principals_router,
    RolesState, roles_router,
    SubscriptionsState, subscriptions_router,
    OAuthClientsState, oauth_clients_router,
    AuthConfigState, anchor_domains_router, client_auth_configs_router, idp_role_mappings_router,
    AuditLogsState, audit_logs_router,
    ApplicationsState, applications_router,
    DispatchPoolsState, dispatch_pools_router,
    MonitoringState, monitoring_router, LeaderState, CircuitBreakerRegistry, InFlightTracker,
};
use fc_platform::repository::{
    EventRepository, EventTypeRepository, DispatchJobRepository, DispatchPoolRepository,
    SubscriptionRepository, ServiceAccountRepository, PrincipalRepository, ClientRepository,
    ApplicationRepository, RoleRepository, OAuthClientRepository,
    AnchorDomainRepository, ClientAuthConfigRepository, ClientAccessGrantRepository, IdpRoleMappingRepository,
    AuditLogRepository,
};

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn env_or_parse<T: std::str::FromStr>(key: &str, default: T) -> T {
    std::env::var(key)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::from_default_env()
                .add_directive(tracing::Level::INFO.into())
        )
        .init();

    info!("Starting FlowCatalyst Platform Server");

    // Configuration from environment
    let api_port: u16 = env_or_parse("FC_API_PORT", 8080);
    let metrics_port: u16 = env_or_parse("FC_METRICS_PORT", 9090);
    let mongo_url = env_or("FC_MONGO_URL", "mongodb://localhost:27017");
    let mongo_db = env_or("FC_MONGO_DB", "flowcatalyst");
    let jwt_issuer = env_or("FC_JWT_ISSUER", "flowcatalyst");

    // Connect to MongoDB
    info!("Connecting to MongoDB: {}/{}", mongo_url, mongo_db);
    let mongo_client = mongodb::Client::with_uri_str(&mongo_url).await?;
    let db = mongo_client.database(&mongo_db);

    // Initialize repositories
    let event_repo = Arc::new(EventRepository::new(&db));
    let event_type_repo = Arc::new(EventTypeRepository::new(&db));
    let dispatch_job_repo = Arc::new(DispatchJobRepository::new(&db));
    let dispatch_pool_repo = Arc::new(DispatchPoolRepository::new(&db));
    let subscription_repo = Arc::new(SubscriptionRepository::new(&db));
    let _service_account_repo = Arc::new(ServiceAccountRepository::new(&db));
    let principal_repo = Arc::new(PrincipalRepository::new(&db));
    let client_repo = Arc::new(ClientRepository::new(&db));
    let application_repo = Arc::new(ApplicationRepository::new(&db));
    let role_repo = Arc::new(RoleRepository::new(&db));
    let oauth_client_repo = Arc::new(OAuthClientRepository::new(&db));
    let anchor_domain_repo = Arc::new(AnchorDomainRepository::new(&db));
    let client_auth_config_repo = Arc::new(ClientAuthConfigRepository::new(&db));
    let _client_access_grant_repo = Arc::new(ClientAccessGrantRepository::new(&db));
    let idp_role_mapping_repo = Arc::new(IdpRoleMappingRepository::new(&db));
    let audit_log_repo = Arc::new(AuditLogRepository::new(&db));
    info!("Repositories initialized");

    // Initialize auth (load or generate RSA keys)
    let private_key_path = std::env::var("FC_JWT_PRIVATE_KEY_PATH").ok();
    let public_key_path = std::env::var("FC_JWT_PUBLIC_KEY_PATH").ok();

    let (private_key, public_key) = AuthConfig::load_or_generate_rsa_keys(
        private_key_path.as_deref(),
        public_key_path.as_deref(),
    )?;

    let auth_config = AuthConfig {
        rsa_private_key: Some(private_key),
        rsa_public_key: Some(public_key),
        secret_key: String::new(),
        issuer: jwt_issuer,
        audience: "flowcatalyst".to_string(),
        access_token_expiry_secs: 3600,
        session_token_expiry_secs: 28800,
        refresh_token_expiry_secs: 86400 * 30,
    };
    let auth_service = Arc::new(AuthService::new(auth_config));
    let authz_service = Arc::new(AuthorizationService::new(role_repo.clone()));
    info!("Auth services initialized");

    // Create AppState
    let app_state = AppState {
        auth_service,
        authz_service,
    };

    // Build API states
    let events_state = EventsState { event_repo };
    let event_types_state = EventTypesState { event_type_repo: event_type_repo.clone() };
    let dispatch_jobs_state = DispatchJobsState { dispatch_job_repo: dispatch_job_repo.clone() };
    let filter_options_state = FilterOptionsState {
        client_repo: client_repo.clone(),
        event_type_repo,
        subscription_repo: subscription_repo.clone(),
        dispatch_pool_repo: dispatch_pool_repo.clone(),
        application_repo: application_repo.clone(),
    };
    let clients_state = ClientsState { client_repo };
    let audit_service = Arc::new(AuditService::new(audit_log_repo.clone()));
    let principals_state = PrincipalsState {
        principal_repo,
        audit_service: Some(audit_service),
    };
    let roles_state = RolesState { role_repo };
    let subscriptions_state = SubscriptionsState { subscription_repo };
    let oauth_clients_state = OAuthClientsState { oauth_client_repo };
    let auth_config_state = AuthConfigState {
        anchor_domain_repo,
        client_auth_config_repo,
        idp_role_mapping_repo,
    };
    let audit_logs_state = AuditLogsState { audit_log_repo };
    let applications_state = ApplicationsState { application_repo };
    let dispatch_pools_state = DispatchPoolsState { dispatch_pool_repo };

    let monitoring_state = MonitoringState {
        leader_state: LeaderState::new(uuid::Uuid::new_v4().to_string()),
        circuit_breakers: CircuitBreakerRegistry::new(),
        in_flight: InFlightTracker::new(),
        dispatch_job_repo,
        start_time: std::time::Instant::now(),
    };

    // Build platform API router
    let app = Router::new()
        // BFF APIs
        .nest("/api/bff/events", events_router(events_state))
        .nest("/api/bff/event-types", event_types_router(event_types_state))
        .nest("/api/bff/dispatch-jobs", dispatch_jobs_router(dispatch_jobs_state))
        .nest("/api/bff/filter-options", filter_options_router(filter_options_state))
        // Admin APIs
        .nest("/api/admin/clients", clients_router(clients_state))
        .nest("/api/admin/principals", principals_router(principals_state))
        .nest("/api/admin/roles", roles_router(roles_state))
        .nest("/api/admin/subscriptions", subscriptions_router(subscriptions_state))
        .nest("/api/admin/oauth-clients", oauth_clients_router(oauth_clients_state))
        .nest("/api/admin/anchor-domains", anchor_domains_router(auth_config_state.clone()))
        .nest("/api/admin/client-auth-configs", client_auth_configs_router(auth_config_state.clone()))
        .nest("/api/admin/idp-role-mappings", idp_role_mappings_router(auth_config_state))
        .nest("/api/admin/audit-logs", audit_logs_router(audit_logs_state))
        .nest("/api/admin/applications", applications_router(applications_state))
        .nest("/api/admin/dispatch-pools", dispatch_pools_router(dispatch_pools_state))
        // Monitoring APIs
        .nest("/api/monitoring", monitoring_router(monitoring_state))
        // Auth middleware
        .layer(AuthLayer::new(app_state))
        .layer(TraceLayer::new_for_http())
        .layer(CorsLayer::new().allow_origin(Any).allow_methods(Any).allow_headers(Any));

    // Start API server
    let api_addr = format!("0.0.0.0:{}", api_port);
    info!("API server listening on http://{}", api_addr);

    let api_listener = TcpListener::bind(&api_addr).await?;
    let api_task = tokio::spawn(async move {
        axum::serve(api_listener, app).await.unwrap();
    });

    // Start metrics server
    let metrics_addr = format!("0.0.0.0:{}", metrics_port);
    info!("Metrics server listening on http://{}/metrics", metrics_addr);

    let metrics_app = Router::new()
        .route("/metrics", get(metrics_handler))
        .route("/health", get(health_handler))
        .route("/ready", get(ready_handler));

    let metrics_listener = TcpListener::bind(&metrics_addr).await?;
    let metrics_task = tokio::spawn(async move {
        axum::serve(metrics_listener, metrics_app).await.unwrap();
    });

    info!("FlowCatalyst Platform Server started");
    info!("Press Ctrl+C to shutdown");

    // Wait for shutdown
    shutdown_signal().await;
    info!("Shutdown signal received...");

    api_task.abort();
    metrics_task.abort();

    info!("FlowCatalyst Platform Server shutdown complete");
    Ok(())
}

async fn metrics_handler() -> &'static str {
    "# HELP fc_platform_up Platform is up\n# TYPE fc_platform_up gauge\nfc_platform_up 1\n"
}

async fn health_handler() -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "status": "UP",
        "version": env!("CARGO_PKG_VERSION")
    }))
}

async fn ready_handler() -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "status": "READY"
    }))
}

async fn shutdown_signal() {
    let ctrl_c = async {
        signal::ctrl_c().await.expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        signal::unix::signal(signal::unix::SignalKind::terminate())
            .expect("failed to install signal handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
