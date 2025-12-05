-- FlowCatalyst Platform Auth Schema
-- Creates authentication and authorization tables for the platform module

-- ============================================================================
-- Tenants
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_tenants (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    identifier VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    status_reason VARCHAR(100),
    status_changed_at TIMESTAMP,
    notes JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auth_tenant_identifier ON auth_tenants(identifier);
CREATE INDEX idx_auth_tenant_status ON auth_tenants(status);

COMMENT ON TABLE auth_tenants IS 'Customer tenant organizations';
COMMENT ON COLUMN auth_tenants.status IS 'ACTIVE, SUSPENDED, DEACTIVATED';
COMMENT ON COLUMN auth_tenants.notes IS 'JSONB array of audit notes';

-- ============================================================================
-- Principals (Users and Service Accounts)
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_principals (
    id BIGINT PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    tenant_id BIGINT,
    name VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- UserIdentity embedded fields (for USER type)
    user_email VARCHAR(255),
    user_email_domain VARCHAR(255),
    user_idp_type VARCHAR(20),
    user_external_idp_id VARCHAR(255),
    user_password_hash VARCHAR(255),
    user_last_login_at TIMESTAMP,

    -- ServiceAccount embedded fields (for SERVICE type)
    sa_code VARCHAR(100),
    sa_description VARCHAR(500),
    sa_client_id VARCHAR(100),
    sa_client_secret_hash VARCHAR(255),
    sa_last_used_at TIMESTAMP,

    CONSTRAINT chk_principal_type CHECK (type IN ('USER', 'SERVICE'))
);

CREATE INDEX idx_auth_principal_tenant_id ON auth_principals(tenant_id);
CREATE INDEX idx_auth_principal_type ON auth_principals(type);
CREATE INDEX idx_auth_principal_user_email ON auth_principals(user_email);
CREATE INDEX idx_auth_principal_sa_client_id ON auth_principals(sa_client_id);
CREATE UNIQUE INDEX idx_auth_principal_user_email_unique ON auth_principals(user_email) WHERE user_email IS NOT NULL;
CREATE UNIQUE INDEX idx_auth_principal_sa_client_id_unique ON auth_principals(sa_client_id) WHERE sa_client_id IS NOT NULL;

COMMENT ON TABLE auth_principals IS 'Unified users and service accounts';
COMMENT ON COLUMN auth_principals.type IS 'USER or SERVICE';
COMMENT ON COLUMN auth_principals.tenant_id IS 'Home tenant (NULL for partners/anchor users)';

-- ============================================================================
-- Principal Roles (String-based role assignment)
-- ============================================================================

CREATE TABLE IF NOT EXISTS principal_roles (
    id BIGINT PRIMARY KEY,
    principal_id BIGINT NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    assignment_source VARCHAR(50),
    assigned_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT unique_principal_role UNIQUE (principal_id, role_name)
);

CREATE INDEX idx_principal_roles_principal ON principal_roles(principal_id);
CREATE INDEX idx_principal_roles_role_name ON principal_roles(role_name);

COMMENT ON TABLE principal_roles IS 'Role assignments using string-based role names';
COMMENT ON COLUMN principal_roles.role_name IS 'Role name from PermissionRegistry (e.g., platform:tenant-admin)';
COMMENT ON COLUMN principal_roles.assignment_source IS 'MANUAL or IDP_SYNC';

-- ============================================================================
-- Tenant Access Grants (Cross-tenant access for partners)
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_tenant_access_grants (
    id BIGINT PRIMARY KEY,
    principal_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP,

    CONSTRAINT unique_principal_tenant UNIQUE (principal_id, tenant_id)
);

CREATE INDEX idx_auth_tenant_grants_principal ON auth_tenant_access_grants(principal_id);
CREATE INDEX idx_auth_tenant_grants_tenant ON auth_tenant_access_grants(tenant_id);

COMMENT ON TABLE auth_tenant_access_grants IS 'Grants partners access to customer tenants';

-- ============================================================================
-- Anchor Domains (God-mode email domains)
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_anchor_domains (
    id BIGINT PRIMARY KEY,
    domain VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auth_anchor_domain ON auth_anchor_domains(domain);

COMMENT ON TABLE auth_anchor_domains IS 'Email domains with access to all tenants';

-- ============================================================================
-- IDP Role Mappings (Security whitelist for federated roles)
-- ============================================================================

CREATE TABLE IF NOT EXISTS idp_role_mappings (
    id BIGINT PRIMARY KEY,
    idp_role_name VARCHAR(100) NOT NULL UNIQUE,
    internal_role_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_idp_role_name ON idp_role_mappings(idp_role_name);
CREATE INDEX idx_internal_role_name ON idp_role_mappings(internal_role_name);

COMMENT ON TABLE idp_role_mappings IS 'SECURITY: Whitelist of authorized IDP role mappings';
COMMENT ON COLUMN idp_role_mappings.idp_role_name IS 'Role name from external IDP (e.g., Keycloak)';
COMMENT ON COLUMN idp_role_mappings.internal_role_name IS 'Internal role name from PermissionRegistry';

-- ============================================================================
-- Tenant Auth Configuration (per-domain auth provider settings)
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_tenant_auth_config (
    id BIGINT PRIMARY KEY,
    email_domain VARCHAR(255) NOT NULL UNIQUE,
    auth_provider VARCHAR(20) NOT NULL,
    oidc_issuer_url VARCHAR(500),
    oidc_client_id VARCHAR(255),
    oidc_client_secret_encrypted VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_auth_provider CHECK (auth_provider IN ('INTERNAL', 'OIDC'))
);

CREATE INDEX idx_auth_tenant_config_domain ON auth_tenant_auth_config(email_domain);

COMMENT ON TABLE auth_tenant_auth_config IS 'Authentication configuration per email domain';
COMMENT ON COLUMN auth_tenant_auth_config.auth_provider IS 'INTERNAL (password) or OIDC (external IDP)';
