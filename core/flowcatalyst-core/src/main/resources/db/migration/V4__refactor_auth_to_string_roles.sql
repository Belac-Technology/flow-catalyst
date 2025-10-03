-- Auth Refactor: Code-First Permissions and String-Based Roles
--
-- This migration refactors the authorization system from database-centric
-- to code-first with string-based role names.
--
-- Changes:
-- 1. Drop roles table (roles are now defined in code)
-- 2. Update principal_roles to use role_name instead of role_id
-- 3. Update idp_role_mappings to use internal_role_name instead of internal_role_id
--
-- IMPORTANT: This is a breaking change. Existing role assignments will be lost.
-- If you have production data, you need to create a data migration script
-- to map existing role IDs to role name strings.

-- Step 1: Drop foreign key constraints that reference roles table
ALTER TABLE principal_roles DROP CONSTRAINT IF EXISTS fk_principal_role_role;
ALTER TABLE idp_role_mappings DROP CONSTRAINT IF EXISTS fk_idp_role_mapping_role;

-- Step 2: Create new tables with string-based role names

-- New principal_roles table with role_name
CREATE TABLE principal_roles_new (
    id BIGINT PRIMARY KEY,
    principal_id BIGINT NOT NULL,
    role_name VARCHAR(100) NOT NULL,  -- String role name (e.g., "platform:tenant-admin")
    assignment_source VARCHAR(50),    -- Source of assignment (e.g., "IDP_SYNC", "MANUAL")
    assigned_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_principal_role_principal_new FOREIGN KEY (principal_id) REFERENCES principals(id),
    CONSTRAINT unique_principal_role_new UNIQUE (principal_id, role_name)
);

CREATE INDEX idx_principal_roles_principal_new ON principal_roles_new(principal_id);
CREATE INDEX idx_principal_roles_role_name ON principal_roles_new(role_name);

-- New idp_role_mappings table with internal_role_name
CREATE TABLE idp_role_mappings_new (
    id BIGINT PRIMARY KEY,
    idp_role_name VARCHAR(100) NOT NULL UNIQUE,        -- IDP role name (from external IDP token)
    internal_role_name VARCHAR(100) NOT NULL,          -- Internal role name string (e.g., "platform:tenant-admin")
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_idp_role_name_new ON idp_role_mappings_new(idp_role_name);
CREATE INDEX idx_internal_role_name ON idp_role_mappings_new(internal_role_name);

-- Step 3: Drop old tables
DROP TABLE principal_roles;
DROP TABLE idp_role_mappings;
DROP TABLE roles;

-- Step 4: Rename new tables
ALTER TABLE principal_roles_new RENAME TO principal_roles;
ALTER TABLE idp_role_mappings_new RENAME TO idp_role_mappings;

-- Step 5: Rename constraints and indexes to remove "_new" suffix
ALTER TABLE principal_roles RENAME CONSTRAINT fk_principal_role_principal_new TO fk_principal_role_principal;
ALTER TABLE principal_roles RENAME CONSTRAINT unique_principal_role_new TO unique_principal_role;
ALTER INDEX idx_principal_roles_principal_new RENAME TO idx_principal_roles_principal;
ALTER INDEX idx_idp_role_name_new RENAME TO idx_idp_role_name;

-- Note: IDP sync configuration is now in application.properties
-- Configure platform and anchor tenant IDPs via config properties, not database.
--
-- After this migration, configure your IDPs in application.properties:
--
-- Platform IDP (internal Keycloak):
--   flowcatalyst.idp.platform.enabled=true
--   flowcatalyst.idp.platform.type=KEYCLOAK
--   flowcatalyst.idp.platform.keycloak.admin-url=http://localhost:8080
--   flowcatalyst.idp.platform.keycloak.realm=flowcatalyst
--   flowcatalyst.idp.platform.keycloak.client-id=admin-cli
--   flowcatalyst.idp.platform.keycloak.client-secret=${KEYCLOAK_ADMIN_SECRET}
--
-- Anchor Tenant IDP (optional, e.g., Entra):
--   flowcatalyst.idp.anchor.enabled=true
--   flowcatalyst.idp.anchor.type=ENTRA
--   flowcatalyst.idp.anchor.entra.tenant-id=xxx
--   flowcatalyst.idp.anchor.entra.client-id=yyy
--   flowcatalyst.idp.anchor.entra.client-secret=${ANCHOR_ENTRA_SECRET}
--   flowcatalyst.idp.anchor.entra.application-object-id=zzz
--
-- Then run: ./gradlew quarkusDev -- idp sync
--
-- External tenant IDPs use idp_role_mappings table for security whitelist.
