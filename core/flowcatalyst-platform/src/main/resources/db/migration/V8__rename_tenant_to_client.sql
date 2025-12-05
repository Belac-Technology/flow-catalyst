-- Rename Tenant to Client throughout the schema
-- This migration renames all tenant-related tables and columns to use "client" terminology

-- ============================================================================
-- Rename Tables
-- ============================================================================

-- Rename auth_tenants to auth_clients
ALTER TABLE auth_tenants RENAME TO auth_clients;

-- Rename auth_tenant_access_grants to auth_client_access_grants
ALTER TABLE auth_tenant_access_grants RENAME TO auth_client_access_grants;

-- Rename auth_tenant_auth_config to auth_client_auth_config
ALTER TABLE auth_tenant_auth_config RENAME TO auth_client_auth_config;

-- Rename auth_application_tenant_config to auth_application_client_config
ALTER TABLE auth_application_tenant_config RENAME TO auth_application_client_config;

-- ============================================================================
-- Rename Columns
-- ============================================================================

-- auth_principals: tenant_id -> client_id
ALTER TABLE auth_principals RENAME COLUMN tenant_id TO client_id;

-- auth_client_access_grants: tenant_id -> client_id
ALTER TABLE auth_client_access_grants RENAME COLUMN tenant_id TO client_id;

-- auth_application_client_config: tenant_id -> client_id
ALTER TABLE auth_application_client_config RENAME COLUMN tenant_id TO client_id;

-- auth_oauth_clients: tenant_id -> client_id (note: this is the OAuth client's owning client, not the OAuth client_id)
ALTER TABLE auth_oauth_clients RENAME COLUMN tenant_id TO owner_client_id;

-- auth_authorization_codes: tenant_id -> client_id (the selected client context)
ALTER TABLE auth_authorization_codes RENAME COLUMN tenant_id TO context_client_id;

-- auth_refresh_tokens: tenant_id -> client_id
ALTER TABLE auth_refresh_tokens RENAME COLUMN tenant_id TO context_client_id;

-- ============================================================================
-- Rename Indexes
-- ============================================================================

-- auth_clients (formerly auth_tenants)
ALTER INDEX idx_auth_tenant_identifier RENAME TO idx_auth_client_identifier;
ALTER INDEX idx_auth_tenant_status RENAME TO idx_auth_client_status;

-- auth_principals
ALTER INDEX idx_auth_principal_tenant_id RENAME TO idx_auth_principal_client_id;

-- auth_client_access_grants (formerly auth_tenant_access_grants)
ALTER INDEX idx_auth_tenant_grants_principal RENAME TO idx_auth_client_grants_principal;
ALTER INDEX idx_auth_tenant_grants_tenant RENAME TO idx_auth_client_grants_client;

-- auth_client_auth_config (formerly auth_tenant_auth_config)
ALTER INDEX idx_auth_tenant_config_domain RENAME TO idx_auth_client_config_domain;

-- auth_application_client_config (formerly auth_application_tenant_config)
ALTER INDEX idx_auth_app_tenant_config_app RENAME TO idx_auth_app_client_config_app;
ALTER INDEX idx_auth_app_tenant_config_tenant RENAME TO idx_auth_app_client_config_client;

-- auth_oauth_clients
ALTER INDEX idx_oauth_client_tenant RENAME TO idx_oauth_client_owner;

-- ============================================================================
-- Rename Constraints
-- ============================================================================

-- auth_client_access_grants
ALTER TABLE auth_client_access_grants RENAME CONSTRAINT unique_principal_tenant TO unique_principal_client;

-- auth_application_client_config
ALTER TABLE auth_application_client_config RENAME CONSTRAINT unique_app_tenant TO unique_app_client;
ALTER TABLE auth_application_client_config RENAME CONSTRAINT fk_app_tenant_config_application TO fk_app_client_config_application;
ALTER TABLE auth_application_client_config RENAME CONSTRAINT fk_app_tenant_config_tenant TO fk_app_client_config_client;

-- ============================================================================
-- Update Comments
-- ============================================================================

COMMENT ON TABLE auth_clients IS 'Customer client organizations';
COMMENT ON COLUMN auth_principals.client_id IS 'Home client (NULL for partners/anchor users)';
COMMENT ON TABLE auth_client_access_grants IS 'Grants partners access to customer clients';
COMMENT ON TABLE auth_client_auth_config IS 'Authentication configuration per email domain';
COMMENT ON TABLE auth_application_client_config IS 'Per-client application configuration and URL overrides';
COMMENT ON COLUMN auth_application_client_config.enabled IS 'Whether this client has access to this application';
COMMENT ON COLUMN auth_application_client_config.base_url_override IS 'Client-specific URL (e.g., client1.inmotion.com)';
COMMENT ON COLUMN auth_application_client_config.config_json IS 'Additional client-specific configuration as JSON';
