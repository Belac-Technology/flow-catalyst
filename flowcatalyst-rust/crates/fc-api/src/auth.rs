//! Authentication middleware for FlowCatalyst API
//!
//! Supports:
//! - BasicAuth with configurable username/password
//! - OIDC pass-through (validates tokens from OIDC provider)
//! - No authentication (for development)

use axum::{
    extract::Request,
    http::{header, HeaderName, HeaderValue, StatusCode},
    middleware::Next,
    response::{IntoResponse, Response},
};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tracing::{debug, warn};

/// Authentication mode
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum AuthMode {
    /// No authentication required
    #[default]
    None,
    /// HTTP Basic Authentication
    Basic,
    /// OpenID Connect authentication
    Oidc,
}

/// Authentication configuration
#[derive(Debug, Clone)]
pub struct AuthConfig {
    /// Authentication mode
    pub mode: AuthMode,
    /// BasicAuth username (required if mode is Basic)
    pub basic_username: Option<String>,
    /// BasicAuth password (required if mode is Basic)
    pub basic_password: Option<String>,
    /// OIDC issuer URL (required if mode is Oidc)
    pub oidc_issuer: Option<String>,
    /// OIDC client ID
    pub oidc_client_id: Option<String>,
    /// OIDC audience for token validation
    pub oidc_audience: Option<String>,
}

impl Default for AuthConfig {
    fn default() -> Self {
        Self {
            mode: AuthMode::None,
            basic_username: None,
            basic_password: None,
            oidc_issuer: None,
            oidc_client_id: None,
            oidc_audience: None,
        }
    }
}

impl AuthConfig {
    /// Create config for BasicAuth
    pub fn basic(username: impl Into<String>, password: impl Into<String>) -> Self {
        Self {
            mode: AuthMode::Basic,
            basic_username: Some(username.into()),
            basic_password: Some(password.into()),
            ..Default::default()
        }
    }

    /// Create config for OIDC
    pub fn oidc(issuer: impl Into<String>, client_id: impl Into<String>) -> Self {
        Self {
            mode: AuthMode::Oidc,
            oidc_issuer: Some(issuer.into()),
            oidc_client_id: Some(client_id.into()),
            ..Default::default()
        }
    }

    /// Create config from environment variables
    pub fn from_env() -> Self {
        let mode = std::env::var("AUTH_MODE")
            .ok()
            .and_then(|m| match m.to_uppercase().as_str() {
                "BASIC" => Some(AuthMode::Basic),
                "OIDC" => Some(AuthMode::Oidc),
                "NONE" | "" => Some(AuthMode::None),
                _ => None,
            })
            .unwrap_or(AuthMode::None);

        Self {
            mode,
            basic_username: std::env::var("AUTH_BASIC_USERNAME").ok(),
            basic_password: std::env::var("AUTH_BASIC_PASSWORD").ok(),
            oidc_issuer: std::env::var("OIDC_ISSUER").ok(),
            oidc_client_id: std::env::var("OIDC_CLIENT_ID").ok(),
            oidc_audience: std::env::var("OIDC_AUDIENCE").ok(),
        }
    }
}

/// Authentication state for middleware
#[derive(Clone)]
pub struct AuthState {
    pub config: Arc<AuthConfig>,
}

impl AuthState {
    pub fn new(config: AuthConfig) -> Self {
        Self {
            config: Arc::new(config),
        }
    }
}

/// Authentication middleware
pub async fn auth_middleware(
    state: axum::extract::State<AuthState>,
    request: Request,
    next: Next,
) -> Response {
    match state.config.mode {
        AuthMode::None => {
            // No authentication required
            next.run(request).await
        }
        AuthMode::Basic => basic_auth(&state.config, request, next).await,
        AuthMode::Oidc => oidc_auth(&state.config, request, next).await,
    }
}

/// HTTP Basic Authentication
async fn basic_auth(config: &AuthConfig, request: Request, next: Next) -> Response {
    let auth_header = request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|h| h.to_str().ok());

    match auth_header {
        Some(auth) if auth.starts_with("Basic ") => {
            let encoded = &auth[6..];
            match BASE64.decode(encoded) {
                Ok(decoded) => {
                    if let Ok(credentials) = String::from_utf8(decoded) {
                        if let Some((username, password)) = credentials.split_once(':') {
                            let expected_username = config.basic_username.as_deref().unwrap_or("");
                            let expected_password = config.basic_password.as_deref().unwrap_or("");

                            if username == expected_username && password == expected_password {
                                debug!(username = %username, "BasicAuth successful");
                                return next.run(request).await;
                            }
                        }
                    }
                }
                Err(e) => {
                    warn!(error = %e, "Invalid base64 in Authorization header");
                }
            }
        }
        _ => {}
    }

    // Authentication failed
    warn!("BasicAuth failed");
    let mut response = (StatusCode::UNAUTHORIZED, "Unauthorized").into_response();
    response.headers_mut().insert(
        header::WWW_AUTHENTICATE,
        HeaderValue::from_static("Basic realm=\"FlowCatalyst\""),
    );
    response.headers_mut().insert(
        HeaderName::from_static("x-auth-mode"),
        HeaderValue::from_static("BASIC"),
    );
    response
}

/// OIDC Authentication (validates Bearer token)
async fn oidc_auth(_config: &AuthConfig, request: Request, next: Next) -> Response {
    let auth_header = request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|h| h.to_str().ok());

    match auth_header {
        Some(auth) if auth.starts_with("Bearer ") => {
            let token = &auth[7..];

            // For now, we just verify the token is present and non-empty
            // In production, you would validate against the OIDC provider
            // using the issuer URL and verify signature, expiry, audience, etc.
            if !token.is_empty() {
                debug!("OIDC token present, allowing request");
                return next.run(request).await;
            }
        }
        _ => {}
    }

    // For OIDC, we might redirect to login page or return 401
    // For API calls, return 401 with OIDC mode indicator
    warn!("OIDC auth failed - no valid Bearer token");

    let mut response = (StatusCode::UNAUTHORIZED, "Unauthorized").into_response();
    response.headers_mut().insert(
        header::WWW_AUTHENTICATE,
        HeaderValue::from_static("Bearer realm=\"FlowCatalyst\""),
    );
    response.headers_mut().insert(
        HeaderName::from_static("x-auth-mode"),
        HeaderValue::from_static("OIDC"),
    );
    response
}

/// Create authentication state for use with middleware
pub fn create_auth_state(config: AuthConfig) -> AuthState {
    AuthState::new(config)
}

/// List of paths that should be public (no authentication)
pub fn is_public_path(path: &str) -> bool {
    matches!(
        path,
        "/health"
            | "/health/live"
            | "/health/ready"
            | "/health/startup"
            | "/q/health"
            | "/q/health/live"
            | "/q/health/ready"
            | "/metrics"
            | "/q/metrics"
            | "/swagger-ui"
            | "/swagger-ui/"
            | "/api-doc/openapi.json"
    ) || path.starts_with("/swagger-ui/")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_auth_mode_default() {
        let config = AuthConfig::default();
        assert_eq!(config.mode, AuthMode::None);
    }

    #[test]
    fn test_basic_auth_config() {
        let config = AuthConfig::basic("admin", "secret");
        assert_eq!(config.mode, AuthMode::Basic);
        assert_eq!(config.basic_username, Some("admin".to_string()));
        assert_eq!(config.basic_password, Some("secret".to_string()));
    }

    #[test]
    fn test_public_paths() {
        assert!(is_public_path("/health"));
        assert!(is_public_path("/health/live"));
        assert!(is_public_path("/health/ready"));
        assert!(is_public_path("/metrics"));
        assert!(!is_public_path("/monitoring/health"));
        assert!(!is_public_path("/warnings"));
    }
}
