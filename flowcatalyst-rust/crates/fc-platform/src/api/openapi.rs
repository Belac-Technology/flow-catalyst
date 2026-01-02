//! OpenAPI Documentation
//!
//! Central OpenAPI specification for all platform APIs.

use utoipa::OpenApi;

/// Platform API OpenAPI Documentation
#[derive(OpenApi)]
#[openapi(
    info(
        title = "FlowCatalyst Platform API",
        version = "1.0.0",
        description = "REST APIs for events, subscriptions, and administration"
    ),
    servers(
        (url = "http://localhost:8080", description = "Local development")
    ),
    tags(
        (name = "events", description = "Event management"),
        (name = "event-types", description = "Event type definitions"),
        (name = "dispatch-jobs", description = "Dispatch job tracking"),
        (name = "filter-options", description = "Filter options for UI dropdowns"),
        (name = "subscriptions", description = "Webhook subscriptions"),
        (name = "clients", description = "Client/tenant management"),
        (name = "principals", description = "User and service account management"),
        (name = "roles", description = "Role management"),
        (name = "applications", description = "Application management"),
        (name = "service-accounts", description = "Service account management"),
        (name = "dispatch-pools", description = "Dispatch pool management"),
        (name = "auth", description = "Authentication"),
        (name = "monitoring", description = "Health and monitoring"),
        (name = "audit-logs", description = "Audit logs")
    ),
    paths(
        // Events BFF API
        super::events::create_event,
        super::events::get_event,
        super::events::list_events,
        super::events::batch_create_events,
        // Event Types BFF API
        super::event_types::create_event_type,
        super::event_types::get_event_type,
        super::event_types::get_event_type_by_code,
        super::event_types::list_event_types,
        super::event_types::update_event_type,
        super::event_types::add_schema_version,
        super::event_types::delete_event_type,
        // Dispatch Jobs BFF API
        super::dispatch_jobs::get_dispatch_job,
        super::dispatch_jobs::list_dispatch_jobs,
        super::dispatch_jobs::get_jobs_for_event,
        super::dispatch_jobs::create_dispatch_job,
        super::dispatch_jobs::batch_create_dispatch_jobs,
        super::dispatch_jobs::get_dispatch_job_attempts,
        // Filter Options BFF API
        super::filter_options::get_client_options,
        super::filter_options::get_event_type_options,
        super::filter_options::get_subscription_options,
        super::filter_options::get_dispatch_pool_options,
        super::filter_options::get_all_options,
        super::filter_options::get_events_filter_options,
        super::filter_options::get_dispatch_jobs_filter_options,
        super::filter_options::get_event_type_applications,
        super::filter_options::get_event_type_subdomains,
        super::filter_options::get_event_type_aggregates,
        // Auth API
        super::auth::login,
        super::auth::logout,
        super::auth::check_domain,
        super::auth::get_current_user,
        super::auth::refresh_token,
        // Subscriptions Admin API
        super::subscriptions::create_subscription,
        super::subscriptions::get_subscription,
        super::subscriptions::list_subscriptions,
        super::subscriptions::update_subscription,
        super::subscriptions::pause_subscription,
        super::subscriptions::resume_subscription,
        super::subscriptions::delete_subscription,
        super::subscriptions::reactivate_subscription,
        // Clients Admin API
        super::clients::create_client,
        super::clients::get_client,
        super::clients::list_clients,
        super::clients::update_client,
        super::clients::delete_client,
        super::clients::activate_client,
        super::clients::suspend_client,
        super::clients::deactivate_client,
        super::clients::search_clients,
        super::clients::get_client_by_identifier,
        super::clients::add_note,
        super::clients::get_client_applications,
        super::clients::enable_application,
        super::clients::disable_application,
        super::clients::update_client_applications,
        // Principals Admin API
        super::principals::create_user,
        super::principals::get_principal,
        super::principals::list_principals,
        super::principals::update_principal,
        super::principals::get_roles,
        super::principals::assign_role,
        super::principals::batch_assign_roles,
        super::principals::remove_role,
        super::principals::get_client_access,
        super::principals::grant_client_access,
        super::principals::revoke_client_access,
        super::principals::delete_principal,
        super::principals::activate_principal,
        super::principals::deactivate_principal,
        super::principals::reset_password,
        super::principals::check_email_domain,
        // Roles Admin API
        super::roles::create_role,
        super::roles::get_role,
        super::roles::get_role_by_code,
        super::roles::list_roles,
        super::roles::update_role,
        super::roles::grant_permission,
        super::roles::revoke_permission,
        super::roles::delete_role,
        super::roles::get_filter_applications,
        super::roles::list_permissions,
        super::roles::get_permission,
        // Applications Admin API
        super::applications::create_application,
        super::applications::get_application,
        super::applications::list_applications,
        super::applications::update_application,
        super::applications::delete_application,
        super::applications::activate_application,
        super::applications::deactivate_application,
        super::applications::get_application_by_code,
        super::applications::provision_service_account,
        super::applications::get_application_service_account,
        super::applications::list_application_roles,
        super::applications::list_client_configs,
        super::applications::update_client_config,
        super::applications::enable_for_client,
        super::applications::disable_for_client,
        // Service Accounts Admin API
        super::service_accounts::list_service_accounts,
        super::service_accounts::get_service_account,
        super::service_accounts::get_service_account_by_code,
        super::service_accounts::create_service_account,
        super::service_accounts::update_service_account,
        super::service_accounts::delete_service_account,
        super::service_accounts::regenerate_auth_token,
        super::service_accounts::regenerate_signing_secret,
        super::service_accounts::get_roles,
        super::service_accounts::assign_roles,
        // Dispatch Pools Admin API
        super::dispatch_pools::create_dispatch_pool,
        super::dispatch_pools::get_dispatch_pool,
        super::dispatch_pools::list_dispatch_pools,
        super::dispatch_pools::update_dispatch_pool,
        super::dispatch_pools::archive_dispatch_pool,
        super::dispatch_pools::delete_dispatch_pool,
        // OAuth Clients Admin API
        super::oauth_clients::create_oauth_client,
        super::oauth_clients::get_oauth_client,
        super::oauth_clients::list_oauth_clients,
        super::oauth_clients::update_oauth_client,
        super::oauth_clients::delete_oauth_client,
        // Audit Logs Admin API
        super::audit_logs::get_entity_types,
        super::audit_logs::get_operations,
        super::audit_logs::get_audit_log,
        super::audit_logs::list_audit_logs,
        super::audit_logs::get_entity_audit_logs,
        super::audit_logs::get_principal_audit_logs,
        super::audit_logs::get_recent_audit_logs,
        // Monitoring API
        super::monitoring::get_standby_status,
        super::monitoring::get_dashboard,
        super::monitoring::get_circuit_breakers,
        super::monitoring::get_in_flight_messages,
        super::monitoring::get_platform_stats,
        super::monitoring::get_pool_stats,
        // OIDC Login API
        super::oidc_login::check_domain,
        super::oidc_login::oidc_login,
        super::oidc_login::oidc_callback,
    ),
    components(
        schemas(
            // Event schemas
            super::events::CreateEventRequest,
            super::events::CreateEventResponse,
            super::events::EventResponse,
            super::events::BatchCreateEventsRequest,
            super::events::BatchCreateResponse,
            super::events::ContextDataDto,
            // Event Type schemas
            super::event_types::CreateEventTypeRequest,
            super::event_types::UpdateEventTypeRequest,
            super::event_types::EventTypeResponse,
            // Dispatch Job schemas
            super::dispatch_jobs::DispatchJobResponse,
            super::dispatch_jobs::DispatchAttemptResponse,
            super::dispatch_jobs::CreateDispatchJobRequest,
            super::dispatch_jobs::BatchCreateDispatchJobsRequest,
            super::dispatch_jobs::BatchCreateDispatchJobsResponse,
            // Filter Options schemas
            super::filter_options::FilterOption,
            super::filter_options::AllFilterOptions,
            super::filter_options::EventsFilterOptions,
            super::filter_options::DispatchJobsFilterOptions,
            // Monitoring schemas
            super::monitoring::StandbyStatus,
            super::monitoring::CircuitBreakersResponse,
            super::monitoring::InFlightMessagesResponse,
            super::monitoring::PlatformStats,
            super::monitoring::PoolStatsResponse,
            // Common schemas
            super::common::CreatedResponse,
            super::common::SuccessResponse,
            super::common::PaginationParams,
        )
    )
)]
pub struct PlatformApiDoc;
