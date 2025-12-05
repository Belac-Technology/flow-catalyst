-- OAuth2 Tables for Authorization Code Flow with PKCE
--
-- These tables support the OAuth2 authorization code flow for SPAs and mobile apps.
-- Includes support for PKCE (Proof Key for Code Exchange) and refresh token rotation.

-- ============================================================================
-- OAuth Clients (SPAs, mobile apps, service clients)
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_oauth_clients (
    id BIGINT PRIMARY KEY,
    client_id VARCHAR(100) UNIQUE NOT NULL,
    client_name VARCHAR(255) NOT NULL,
    client_type VARCHAR(20) NOT NULL,
    client_secret_hash VARCHAR(500),
    redirect_uris VARCHAR(2000) NOT NULL,
    grant_types VARCHAR(200) NOT NULL,
    default_scopes VARCHAR(500),
    pkce_required BOOLEAN NOT NULL DEFAULT TRUE,
    tenant_id BIGINT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_oauth_client_type CHECK (client_type IN ('PUBLIC', 'CONFIDENTIAL'))
);

CREATE INDEX idx_oauth_client_client_id ON auth_oauth_clients(client_id);
CREATE INDEX idx_oauth_client_tenant ON auth_oauth_clients(tenant_id);

COMMENT ON TABLE auth_oauth_clients IS 'Registered OAuth2 clients (SPAs, mobile apps, services)';
COMMENT ON COLUMN auth_oauth_clients.client_type IS 'PUBLIC=no secret (SPA/mobile), CONFIDENTIAL=has secret (server)';
COMMENT ON COLUMN auth_oauth_clients.redirect_uris IS 'Comma-separated list of allowed redirect URIs';
COMMENT ON COLUMN auth_oauth_clients.grant_types IS 'Comma-separated list (authorization_code,refresh_token,client_credentials)';

-- ============================================================================
-- Authorization Codes (short-lived, single-use)
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_authorization_codes (
    code VARCHAR(64) PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    principal_id BIGINT NOT NULL,
    redirect_uri VARCHAR(1000) NOT NULL,
    scope VARCHAR(500),
    code_challenge VARCHAR(128),
    code_challenge_method VARCHAR(10),
    nonce VARCHAR(128),
    state VARCHAR(128),
    tenant_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_auth_code_expires ON auth_authorization_codes(expires_at);
CREATE INDEX idx_auth_code_principal ON auth_authorization_codes(principal_id);

COMMENT ON TABLE auth_authorization_codes IS 'OAuth2 authorization codes (10 min TTL, single use)';
COMMENT ON COLUMN auth_authorization_codes.code_challenge IS 'PKCE code challenge (required for public clients)';
COMMENT ON COLUMN auth_authorization_codes.code_challenge_method IS 'S256 (recommended) or plain';

-- ============================================================================
-- Refresh Tokens (long-lived, with rotation)
-- ============================================================================

CREATE TABLE IF NOT EXISTS auth_refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    principal_id BIGINT NOT NULL,
    client_id VARCHAR(100),
    tenant_id BIGINT,
    scope VARCHAR(500),
    token_family VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMP,
    replaced_by VARCHAR(64)
);

CREATE INDEX idx_refresh_token_principal ON auth_refresh_tokens(principal_id);
CREATE INDEX idx_refresh_token_family ON auth_refresh_tokens(token_family);
CREATE INDEX idx_refresh_token_expires ON auth_refresh_tokens(expires_at);
CREATE INDEX idx_refresh_token_client ON auth_refresh_tokens(client_id);

COMMENT ON TABLE auth_refresh_tokens IS 'OAuth2 refresh tokens with rotation support';
COMMENT ON COLUMN auth_refresh_tokens.token_hash IS 'SHA-256 hash of the token (not the actual token)';
COMMENT ON COLUMN auth_refresh_tokens.token_family IS 'Groups related tokens for reuse detection';
COMMENT ON COLUMN auth_refresh_tokens.replaced_by IS 'Hash of the token that replaced this one (rotation tracking)';

-- ============================================================================
-- Default OAuth Client for Development
-- ============================================================================

-- Insert a default SPA client for development/testing
-- In production, clients should be registered via admin API
INSERT INTO auth_oauth_clients (
    id, client_id, client_name, client_type, redirect_uris, grant_types,
    default_scopes, pkce_required, active, created_at, updated_at
) VALUES (
    1,
    'flowcatalyst-dev-spa',
    'FlowCatalyst Development SPA',
    'PUBLIC',
    'http://localhost:3000/callback,http://localhost:5173/callback,http://127.0.0.1:3000/callback',
    'authorization_code,refresh_token',
    'openid profile',
    true,
    true,
    NOW(),
    NOW()
) ON CONFLICT (client_id) DO NOTHING;
