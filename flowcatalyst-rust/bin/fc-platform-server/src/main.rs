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
use utoipa::OpenApi;
use utoipa_swagger_ui::SwaggerUi;

use fc_platform::service::{AuthService, AuthConfig, AuthorizationService, AuditService};
use fc_platform::api::middleware::{AppState, AuthLayer};
use fc_platform::api::{
    EventsState, events_router,
    EventTypesState, event_types_router,
    DispatchJobsState, dispatch_jobs_router,
    FilterOptionsState, filter_options_router, event_type_filters_router,
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
    DebugState, debug_events_router, debug_dispatch_jobs_router,
    AuthState, auth_router,
    platform_config_router,
    ServiceAccountsState, service_accounts_router,
    PlatformApiDoc,
};
use fc_platform::repository::{
    EventRepository, EventTypeRepository, DispatchJobRepository, DispatchPoolRepository,
    SubscriptionRepository, ServiceAccountRepository, PrincipalRepository, ClientRepository,
    ApplicationRepository, RoleRepository, OAuthClientRepository,
    AnchorDomainRepository, ClientAuthConfigRepository, ClientAccessGrantRepository, IdpRoleMappingRepository,
    AuditLogRepository, ApplicationClientConfigRepository, OidcLoginStateRepository, RefreshTokenRepository,
};
use fc_platform::usecase::MongoUnitOfWork;
use fc_platform::operations::{
    // Service Account use cases
    CreateServiceAccountUseCase, UpdateServiceAccountUseCase, DeleteServiceAccountUseCase,
    AssignRolesUseCase, RegenerateAuthTokenUseCase, RegenerateSigningSecretUseCase,
    // Application use cases
    CreateApplicationUseCase, UpdateApplicationUseCase,
    ActivateApplicationUseCase, DeactivateApplicationUseCase,
    // Dispatch Pool use cases
    CreateDispatchPoolUseCase, UpdateDispatchPoolUseCase,
    ArchiveDispatchPoolUseCase, DeleteDispatchPoolUseCase,
};
use fc_platform::service::PasswordService;
use fc_platform::service::OidcSyncService;
use fc_platform::api::{OidcLoginApiState, oidc_login_router};
use fc_platform::seed::DevDataSeeder;


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

    // Seed development data if in dev mode
    let dev_mode = std::env::var("FC_DEV_MODE")
        .map(|v| v == "true" || v == "1")
        .unwrap_or(false);
    if dev_mode {
        let seeder = DevDataSeeder::new(db.clone());
        if let Err(e) = seeder.seed().await {
            tracing::warn!("Dev data seeding skipped (data may already exist): {}", e);
        }
    }

    // Initialize repositories
    let event_repo = Arc::new(EventRepository::new(&db));
    let event_type_repo = Arc::new(EventTypeRepository::new(&db));
    let dispatch_job_repo = Arc::new(DispatchJobRepository::new(&db));
    let dispatch_pool_repo = Arc::new(DispatchPoolRepository::new(&db));
    let subscription_repo = Arc::new(SubscriptionRepository::new(&db));
    let service_account_repo = Arc::new(ServiceAccountRepository::new(&db));
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
    let application_client_config_repo = Arc::new(ApplicationClientConfigRepository::new(&db));
    let oidc_login_state_repo = Arc::new(OidcLoginStateRepository::new(&db));
    let refresh_token_repo = Arc::new(RefreshTokenRepository::new(&db));
    info!("Repositories initialized");

    // Sync code-defined roles to database (always, not just in dev mode)
    {
        let role_sync = fc_platform::service::RoleSyncService::new(
            fc_platform::repository::RoleRepository::new(&db)
        );
        if let Err(e) = role_sync.sync_code_defined_roles().await {
            tracing::warn!("Role sync failed: {}", e);
        }
    }

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
    let password_service = Arc::new(PasswordService::default());
    let oidc_sync_service = Arc::new(OidcSyncService::new(
        principal_repo.clone(),
        idp_role_mapping_repo.clone(),
    ));
    info!("Auth services initialized");

    // Create AppState
    let app_state = AppState {
        auth_service: auth_service.clone(),
        authz_service,
    };

    // Build API states
    let events_state = EventsState { event_repo: event_repo.clone() };
    let event_types_state = EventTypesState { event_type_repo: event_type_repo.clone() };
    let dispatch_jobs_state = DispatchJobsState { dispatch_job_repo: dispatch_job_repo.clone() };
    let debug_state = DebugState {
        event_repo,
        dispatch_job_repo: dispatch_job_repo.clone(),
    };
    let filter_options_state = FilterOptionsState {
        client_repo: client_repo.clone(),
        event_type_repo,
        subscription_repo: subscription_repo.clone(),
        dispatch_pool_repo: dispatch_pool_repo.clone(),
        application_repo: application_repo.clone(),
    };
    let audit_service = Arc::new(AuditService::new(audit_log_repo.clone()));
    let clients_state = ClientsState {
        client_repo: client_repo.clone(),
        application_repo: Some(application_repo.clone()),
        application_client_config_repo: Some(application_client_config_repo.clone()),
        audit_service: Some(audit_service.clone()),
    };
    let principals_state = PrincipalsState {
        principal_repo: principal_repo.clone(),
        audit_service: Some(audit_service),
        password_service: None, // TODO: Configure password service for password reset
        anchor_domain_repo: Some(anchor_domain_repo.clone()),
        client_auth_config_repo: Some(client_auth_config_repo.clone()),
    };
    let roles_state = RolesState { role_repo: role_repo.clone(), application_repo: Some(application_repo.clone()) };
    let subscriptions_state = SubscriptionsState { subscription_repo };
    let oauth_clients_state = OAuthClientsState { oauth_client_repo };
    let auth_config_state = AuthConfigState {
        anchor_domain_repo: anchor_domain_repo.clone(),
        client_auth_config_repo: client_auth_config_repo.clone(),
        idp_role_mapping_repo: idp_role_mapping_repo.clone(),
    };
    let external_base_url = std::env::var("FC_EXTERNAL_BASE_URL").ok();
    let oidc_login_state = OidcLoginApiState::new(
        client_auth_config_repo,
        anchor_domain_repo,
        oidc_login_state_repo,
        oidc_sync_service,
        auth_service.clone(),
    ).with_session_cookie_settings("fc_session", false, "Lax", 86400);
    let oidc_login_state = if let Some(url) = external_base_url {
        oidc_login_state.with_external_base_url(url)
    } else {
        oidc_login_state
    };
    let embedded_auth_state = AuthState::new(
        auth_service.clone(),
        principal_repo.clone(),
        password_service,
        refresh_token_repo,
    );
    let audit_logs_state = AuditLogsState { audit_log_repo };

    // Create UnitOfWork for atomic commits with events and audit logs
    let unit_of_work = Arc::new(MongoUnitOfWork::new(mongo_client.clone(), db.clone()));

    // Create Service Account use cases
    let create_sa_use_case = Arc::new(CreateServiceAccountUseCase::new(
        service_account_repo.clone(),
        unit_of_work.clone(),
    ));
    let update_sa_use_case = Arc::new(UpdateServiceAccountUseCase::new(
        service_account_repo.clone(),
        unit_of_work.clone(),
    ));
    let delete_sa_use_case = Arc::new(DeleteServiceAccountUseCase::new(
        service_account_repo.clone(),
        unit_of_work.clone(),
    ));
    let assign_roles_use_case = Arc::new(AssignRolesUseCase::new(
        service_account_repo.clone(),
        unit_of_work.clone(),
    ));
    let regenerate_token_use_case = Arc::new(RegenerateAuthTokenUseCase::new(
        service_account_repo.clone(),
        unit_of_work.clone(),
    ));
    let regenerate_secret_use_case = Arc::new(RegenerateSigningSecretUseCase::new(
        service_account_repo.clone(),
        unit_of_work.clone(),
    ));

    // Create Application use cases
    let create_app_use_case = Arc::new(CreateApplicationUseCase::new(
        application_repo.clone(),
        unit_of_work.clone(),
    ));
    let update_app_use_case = Arc::new(UpdateApplicationUseCase::new(
        application_repo.clone(),
        unit_of_work.clone(),
    ));
    let activate_app_use_case = Arc::new(ActivateApplicationUseCase::new(
        application_repo.clone(),
        unit_of_work.clone(),
    ));
    let deactivate_app_use_case = Arc::new(DeactivateApplicationUseCase::new(
        application_repo.clone(),
        unit_of_work.clone(),
    ));

    // Create Dispatch Pool use cases
    let create_pool_use_case = Arc::new(CreateDispatchPoolUseCase::new(
        dispatch_pool_repo.clone(),
        unit_of_work.clone(),
    ));
    let update_pool_use_case = Arc::new(UpdateDispatchPoolUseCase::new(
        dispatch_pool_repo.clone(),
        unit_of_work.clone(),
    ));
    let archive_pool_use_case = Arc::new(ArchiveDispatchPoolUseCase::new(
        dispatch_pool_repo.clone(),
        unit_of_work.clone(),
    ));
    let delete_pool_use_case = Arc::new(DeleteDispatchPoolUseCase::new(
        dispatch_pool_repo.clone(),
        unit_of_work.clone(),
    ));

    // Build API states with use cases
    let applications_state = ApplicationsState {
        application_repo,
        service_account_repo: service_account_repo.clone(),
        role_repo,
        client_config_repo: application_client_config_repo,
        client_repo,
        create_use_case: create_app_use_case,
        update_use_case: update_app_use_case,
        activate_use_case: activate_app_use_case,
        deactivate_use_case: deactivate_app_use_case,
    };
    let service_accounts_state = ServiceAccountsState {
        repo: service_account_repo,
        create_use_case: create_sa_use_case,
        update_use_case: update_sa_use_case,
        delete_use_case: delete_sa_use_case,
        assign_roles_use_case,
        regenerate_token_use_case,
        regenerate_secret_use_case,
    };
    let dispatch_pools_state = DispatchPoolsState {
        dispatch_pool_repo: dispatch_pool_repo.clone(),
        create_use_case: create_pool_use_case,
        update_use_case: update_pool_use_case,
        archive_use_case: archive_pool_use_case,
        delete_use_case: delete_pool_use_case,
    };

    let monitoring_state = MonitoringState {
        leader_state: LeaderState::new(uuid::Uuid::new_v4().to_string()),
        circuit_breakers: CircuitBreakerRegistry::new(),
        in_flight: InFlightTracker::new(),
        dispatch_job_repo,
        start_time: std::time::Instant::now(),
    };

    // Build platform API router
    let app = Router::new()
        // BFF APIs (under /bff to match frontend expectations)
        .nest("/bff/events", events_router(events_state))
        .nest("/bff/event-types", event_types_router(event_types_state))
        .nest("/bff/event-types/filters", event_type_filters_router(filter_options_state.clone()))
        .nest("/bff/dispatch-jobs", dispatch_jobs_router(dispatch_jobs_state))
        .nest("/bff/filter-options", filter_options_router(filter_options_state))
        .nest("/bff/roles", roles_router(roles_state.clone()))
        // Debug BFF APIs (raw data access)
        .nest("/bff/debug/events", debug_events_router(debug_state.clone()))
        .nest("/bff/debug/dispatch-jobs", debug_dispatch_jobs_router(debug_state))
        // Admin APIs (under /api/admin/platform to match Java paths)
        .nest("/api/admin/platform/clients", clients_router(clients_state))
        .nest("/api/admin/platform/principals", principals_router(principals_state))
        .nest("/api/admin/platform/roles", roles_router(roles_state))
        .nest("/api/admin/platform/subscriptions", subscriptions_router(subscriptions_state))
        .nest("/api/admin/platform/oauth-clients", oauth_clients_router(oauth_clients_state))
        .nest("/api/admin/platform/anchor-domains", anchor_domains_router(auth_config_state.clone()))
        .nest("/api/admin/platform/auth-configs", client_auth_configs_router(auth_config_state.clone()))
        .nest("/api/admin/platform/idp-role-mappings", idp_role_mappings_router(auth_config_state))
        .nest("/api/admin/platform/audit-logs", audit_logs_router(audit_logs_state))
        .nest("/api/admin/platform/applications", applications_router(applications_state))
        .nest("/api/admin/platform/dispatch-pools", dispatch_pools_router(dispatch_pools_state))
        .nest("/api/admin/platform/service-accounts", service_accounts_router(service_accounts_state))
        // Monitoring APIs
        .nest("/api/monitoring", monitoring_router(monitoring_state))
        // Auth APIs (OIDC login, embedded login, check-domain, etc.)
        .nest("/auth", oidc_login_router(oidc_login_state).merge(auth_router(embedded_auth_state)))
        // Platform config (public)
        .nest("/api/config", platform_config_router())
        // OpenAPI / Swagger UI
        .merge(SwaggerUi::new("/swagger-ui").url("/q/openapi", PlatformApiDoc::openapi()))
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
