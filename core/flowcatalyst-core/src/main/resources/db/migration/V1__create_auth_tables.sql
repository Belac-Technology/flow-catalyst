-- FlowCatalyst Auth Schema Migration
-- Creates all authentication and authorization tables

-- Tenants table
CREATE TABLE tenants (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    identifier VARCHAR(100) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_tenant_identifier ON tenants(identifier);

-- Roles table with JSONB permissions
CREATE TABLE roles (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    is_system BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL,
    permissions JSONB
);

CREATE INDEX idx_role_name ON roles(name);

-- Principals table (unified users and service accounts)
CREATE TABLE principals (
    id BIGINT PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    tenant_id BIGINT,
    name VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    -- UserIdentity embedded fields
    user_email VARCHAR(255),
    user_email_domain VARCHAR(255),
    user_idp_type VARCHAR(20),
    user_external_idp_id VARCHAR(255),
    user_password_hash VARCHAR(255),
    user_last_login_at TIMESTAMP,

    -- ServiceAccount embedded fields
    sa_code VARCHAR(100),
    sa_description VARCHAR(500),
    sa_client_id VARCHAR(100),
    sa_client_secret_hash VARCHAR(255),
    sa_last_used_at TIMESTAMP,

    CONSTRAINT fk_principal_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);

CREATE INDEX idx_principal_tenant_id ON principals(tenant_id);
CREATE INDEX idx_principal_type ON principals(type);
CREATE INDEX idx_principal_user_email ON principals(user_email);
CREATE INDEX idx_principal_sa_client_id ON principals(sa_client_id);
CREATE UNIQUE INDEX idx_principal_user_email_unique ON principals(user_email) WHERE user_email IS NOT NULL;
CREATE UNIQUE INDEX idx_principal_sa_client_id_unique ON principals(sa_client_id) WHERE sa_client_id IS NOT NULL;

-- Principal-Role junction table
CREATE TABLE principal_roles (
    id BIGINT PRIMARY KEY,
    principal_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    assignment_source VARCHAR(50),
    assigned_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_principal_role_principal FOREIGN KEY (principal_id) REFERENCES principals(id),
    CONSTRAINT fk_principal_role_role FOREIGN KEY (role_id) REFERENCES roles(id),
    CONSTRAINT unique_principal_role UNIQUE (principal_id, role_id)
);

CREATE INDEX idx_principal_roles_principal ON principal_roles(principal_id);
CREATE INDEX idx_principal_roles_role ON principal_roles(role_id);

-- Tenant Access Grants table
CREATE TABLE tenant_access_grants (
    id BIGINT PRIMARY KEY,
    principal_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    granted_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_tenant_grant_principal FOREIGN KEY (principal_id) REFERENCES principals(id),
    CONSTRAINT fk_tenant_grant_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT unique_principal_tenant UNIQUE (principal_id, tenant_id)
);

CREATE INDEX idx_tenant_grants_principal ON tenant_access_grants(principal_id);
CREATE INDEX idx_tenant_grants_tenant ON tenant_access_grants(tenant_id);

-- Anchor Domains table
CREATE TABLE anchor_domains (
    id BIGINT PRIMARY KEY,
    domain VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_anchor_domain ON anchor_domains(domain);

-- IDP Role Mappings table
CREATE TABLE idp_role_mappings (
    id BIGINT PRIMARY KEY,
    idp_role_name VARCHAR(100) NOT NULL UNIQUE,
    internal_role_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_idp_role_mapping_role FOREIGN KEY (internal_role_id) REFERENCES roles(id)
);

CREATE INDEX idx_idp_role_name ON idp_role_mappings(idp_role_name);
