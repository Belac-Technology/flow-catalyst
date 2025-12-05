-- FlowCatalyst Role and Permission Tables
-- Enables role management from code, admin UI, and external SDK applications

-- ============================================================================
-- Roles Table
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_roles (
    id BIGINT PRIMARY KEY,
    application_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100),
    description VARCHAR(500),
    permissions JSONB NOT NULL DEFAULT '[]',
    source VARCHAR(20) NOT NULL DEFAULT 'DATABASE',
    client_managed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_auth_role_application FOREIGN KEY (application_id) REFERENCES auth_applications(id),
    CONSTRAINT unique_auth_role_name UNIQUE (name)
);

CREATE INDEX idx_auth_roles_application ON auth_roles(application_id);
CREATE INDEX idx_auth_roles_name ON auth_roles(name);
CREATE INDEX idx_auth_roles_source ON auth_roles(source);

COMMENT ON TABLE auth_roles IS 'Role definitions from code, admin UI, or SDK';
COMMENT ON COLUMN auth_roles.name IS 'Full role name with app prefix (e.g., platform:tenant-admin)';
COMMENT ON COLUMN auth_roles.display_name IS 'Human-readable name (e.g., Tenant Administrator)';
COMMENT ON COLUMN auth_roles.permissions IS 'JSON array of permission strings';
COMMENT ON COLUMN auth_roles.source IS 'CODE (from @Role classes), DATABASE (admin-created), SDK (external app)';
COMMENT ON COLUMN auth_roles.client_managed IS 'If true, syncs to IDPs configured for client-managed roles';

-- ============================================================================
-- Permissions Table (Optional - for external apps via SDK)
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_permissions (
    id BIGINT PRIMARY KEY,
    application_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    display_name VARCHAR(100),
    description VARCHAR(500),
    source VARCHAR(20) NOT NULL DEFAULT 'SDK',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_auth_permission_application FOREIGN KEY (application_id) REFERENCES auth_applications(id),
    CONSTRAINT unique_auth_permission_name UNIQUE (name)
);

CREATE INDEX idx_auth_permissions_application ON auth_permissions(application_id);
CREATE INDEX idx_auth_permissions_name ON auth_permissions(name);

COMMENT ON TABLE auth_permissions IS 'Permission definitions from external apps via SDK';
COMMENT ON COLUMN auth_permissions.name IS 'Full permission name (e.g., myapp:orders:order:create)';
COMMENT ON COLUMN auth_permissions.source IS 'SDK (external app) or DATABASE (admin-created)';

-- ============================================================================
-- Update IDP Role Mappings with sync mode
-- ============================================================================

ALTER TABLE idp_role_mappings
ADD COLUMN IF NOT EXISTS sync_mode VARCHAR(20) NOT NULL DEFAULT 'GLOBAL';

COMMENT ON COLUMN idp_role_mappings.sync_mode IS 'GLOBAL (sync all roles) or CLIENT_MANAGED (only client_managed=true roles)';
