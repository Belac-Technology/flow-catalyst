-- FlowCatalyst Platform Applications Schema
-- Adds application management to the platform module

-- ============================================================================
-- Applications
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_applications (
    id BIGINT PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    icon_url VARCHAR(500),
    default_base_url VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auth_application_code ON auth_applications(code);
CREATE INDEX idx_auth_application_active ON auth_applications(active);

COMMENT ON TABLE auth_applications IS 'Registered applications in the platform ecosystem';
COMMENT ON COLUMN auth_applications.code IS 'Unique application code (e.g., inmotion, dispatch, analytics)';
COMMENT ON COLUMN auth_applications.default_base_url IS 'Default application URL (can be overridden per tenant)';

-- ============================================================================
-- Application Tenant Configuration (per-tenant overrides)
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_application_tenant_config (
    id BIGINT PRIMARY KEY,
    application_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    base_url_override VARCHAR(500),
    config_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT unique_app_tenant UNIQUE (application_id, tenant_id),
    CONSTRAINT fk_app_tenant_config_application FOREIGN KEY (application_id) REFERENCES auth_applications(id),
    CONSTRAINT fk_app_tenant_config_tenant FOREIGN KEY (tenant_id) REFERENCES auth_tenants(id)
);

CREATE INDEX idx_auth_app_tenant_config_app ON auth_application_tenant_config(application_id);
CREATE INDEX idx_auth_app_tenant_config_tenant ON auth_application_tenant_config(tenant_id);

COMMENT ON TABLE auth_application_tenant_config IS 'Per-tenant application configuration and URL overrides';
COMMENT ON COLUMN auth_application_tenant_config.enabled IS 'Whether this tenant has access to this application';
COMMENT ON COLUMN auth_application_tenant_config.base_url_override IS 'Tenant-specific URL (e.g., tenant1.inmotion.com)';
COMMENT ON COLUMN auth_application_tenant_config.config_json IS 'Additional tenant-specific configuration as JSON';
