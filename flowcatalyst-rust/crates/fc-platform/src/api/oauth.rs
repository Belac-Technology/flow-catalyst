//! OAuth2 Authorization Endpoints
//!
//! Implements OAuth2 authorization code flow with PKCE support:
//! - GET /oauth/authorize - Authorization endpoint
//! - POST /oauth/token - Token endpoint
//! - POST /oauth/revoke - Token revocation

use axum::{
    extract::{Query, State},
    http::{header, StatusCode},
    response::{IntoResponse, Redirect, Response},
    routing::{get, post},
    Form, Json, Router,
};
use serde::{Deserialize, Serialize};
use sha2::{Sha256, Digest};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use chrono::{Duration, Utc};
use tracing::{info, warn, error};

use crate::domain::Principal;
use crate::repository::{OAuthClientRepository, PrincipalRepository};
use crate::service::auth::AuthService;
use crate::service::oidc::OidcService;
use crate::error::PlatformError;

/// Authorization request parameters
#[derive(Debug, Deserialize)]
pub struct AuthorizeRequest {
    pub response_type: String,
    pub client_id: String,
    pub redirect_uri: String,
    #[serde(default)]
    pub scope: Option<String>,
    pub state: Option<String>,
    pub nonce: Option<String>,
    /// PKCE code challenge
    pub code_challenge: Option<String>,
    /// PKCE code challenge method (S256 or plain)
    pub code_challenge_method: Option<String>,
    /// Provider ID for external OIDC
    pub provider: Option<String>,
}

/// Token request (form-urlencoded)
#[derive(Debug, Deserialize)]
pub struct TokenRequest {
    pub grant_type: String,
    pub code: Option<String>,
    pub redirect_uri: Option<String>,
    pub client_id: Option<String>,
    pub client_secret: Option<String>,
    /// PKCE code verifier
    pub code_verifier: Option<String>,
    /// For refresh token grant
    pub refresh_token: Option<String>,
    /// For password grant (not recommended)
    pub username: Option<String>,
    pub password: Option<String>,
}

/// Token response
#[derive(Debug, Serialize)]
pub struct TokenResponse {
    pub access_token: String,
    pub token_type: String,
    pub expires_in: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub refresh_token: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub scope: Option<String>,
}

/// Error response (RFC 6749)
#[derive(Debug, Serialize)]
pub struct ErrorResponse {
    pub error: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_description: Option<String>,
}

/// Stored authorization code
#[derive(Debug, Clone)]
pub struct AuthorizationCode {
    pub code: String,
    pub client_id: String,
    pub redirect_uri: String,
    pub principal_id: String,
    pub scope: Option<String>,
    pub code_challenge: Option<String>,
    pub code_challenge_method: Option<String>,
    pub nonce: Option<String>,
    pub expires_at: chrono::DateTime<Utc>,
}

/// OAuth2 state
#[derive(Clone)]
pub struct OAuthState {
    pub oauth_client_repo: Arc<OAuthClientRepository>,
    pub principal_repo: Arc<PrincipalRepository>,
    pub auth_service: Arc<AuthService>,
    pub oidc_service: Arc<OidcService>,
    /// In-memory authorization code storage (should use Redis in production)
    pub auth_codes: Arc<RwLock<HashMap<String, AuthorizationCode>>>,
    /// Pending authorization states (for CSRF protection)
    pub pending_states: Arc<RwLock<HashMap<String, PendingAuth>>>,
}

/// Pending authorization (between authorize and callback)
#[derive(Debug, Clone)]
pub struct PendingAuth {
    pub client_id: String,
    pub redirect_uri: String,
    pub scope: Option<String>,
    pub code_challenge: Option<String>,
    pub code_challenge_method: Option<String>,
    pub nonce: Option<String>,
    pub created_at: chrono::DateTime<Utc>,
}

impl OAuthState {
    pub fn new(
        oauth_client_repo: Arc<OAuthClientRepository>,
        principal_repo: Arc<PrincipalRepository>,
        auth_service: Arc<AuthService>,
        oidc_service: Arc<OidcService>,
    ) -> Self {
        Self {
            oauth_client_repo,
            principal_repo,
            auth_service,
            oidc_service,
            auth_codes: Arc::new(RwLock::new(HashMap::new())),
            pending_states: Arc::new(RwLock::new(HashMap::new())),
        }
    }
}

/// Authorization endpoint - initiates the OAuth2 flow
pub async fn authorize(
    State(state): State<OAuthState>,
    Query(req): Query<AuthorizeRequest>,
) -> Response {
    // Validate response_type
    if req.response_type != "code" {
        return error_redirect(
            &req.redirect_uri,
            "unsupported_response_type",
            "Only 'code' response type is supported",
            req.state.as_deref(),
        );
    }

    // Validate client
    let client = match state.oauth_client_repo.find_by_client_id(&req.client_id).await {
        Ok(Some(c)) if c.active => c,
        Ok(Some(_)) => {
            return error_redirect(
                &req.redirect_uri,
                "unauthorized_client",
                "Client is not active",
                req.state.as_deref(),
            );
        }
        Ok(None) => {
            return error_redirect(
                &req.redirect_uri,
                "unauthorized_client",
                "Unknown client",
                req.state.as_deref(),
            );
        }
        Err(e) => {
            error!(error = %e, "Failed to lookup client");
            return error_redirect(
                &req.redirect_uri,
                "server_error",
                "Internal error",
                req.state.as_deref(),
            );
        }
    };

    // Validate redirect_uri
    if !client.redirect_uris.contains(&req.redirect_uri) {
        return (
            StatusCode::BAD_REQUEST,
            Json(ErrorResponse {
                error: "invalid_request".to_string(),
                error_description: Some("Invalid redirect_uri".to_string()),
            }),
        ).into_response();
    }

    // Validate PKCE if required
    if client.pkce_required && req.code_challenge.is_none() {
        return error_redirect(
            &req.redirect_uri,
            "invalid_request",
            "PKCE code_challenge is required",
            req.state.as_deref(),
        );
    }

    // Validate code_challenge_method
    if let Some(ref method) = req.code_challenge_method {
        if method != "S256" && method != "plain" {
            return error_redirect(
                &req.redirect_uri,
                "invalid_request",
                "Invalid code_challenge_method",
                req.state.as_deref(),
            );
        }
    }

    // Generate state for CSRF protection if not provided
    let state_param = req.state.clone().unwrap_or_else(|| generate_random_string(32));

    // Store pending authorization
    let pending = PendingAuth {
        client_id: req.client_id.clone(),
        redirect_uri: req.redirect_uri.clone(),
        scope: req.scope.clone(),
        code_challenge: req.code_challenge.clone(),
        code_challenge_method: req.code_challenge_method.clone(),
        nonce: req.nonce.clone(),
        created_at: Utc::now(),
    };

    {
        let mut pending_states = state.pending_states.write().await;
        pending_states.insert(state_param.clone(), pending);
    }

    // If external provider specified, redirect to OIDC provider
    if let Some(provider_id) = req.provider {
        match state.oidc_service.get_authorization_url(&provider_id, &state_param, req.nonce.as_deref()).await {
            Ok(url) => {
                info!(provider = %provider_id, "Redirecting to OIDC provider");
                return Redirect::temporary(&url).into_response();
            }
            Err(e) => {
                error!(error = %e, "Failed to get authorization URL");
                return error_redirect(
                    &req.redirect_uri,
                    "server_error",
                    "Failed to initialize OIDC flow",
                    req.state.as_deref(),
                );
            }
        }
    }

    // For now, return a simple login form or redirect to login page
    // In production, this would render a login page or SSO flow
    let login_url = format!(
        "/oauth/login?state={}&client_id={}&redirect_uri={}",
        urlencoding::encode(&state_param),
        urlencoding::encode(&req.client_id),
        urlencoding::encode(&req.redirect_uri),
    );

    Redirect::temporary(&login_url).into_response()
}

/// Token endpoint - exchanges authorization code for tokens
pub async fn token(
    State(state): State<OAuthState>,
    Form(req): Form<TokenRequest>,
) -> Response {
    match req.grant_type.as_str() {
        "authorization_code" => handle_authorization_code_grant(state, req).await,
        "refresh_token" => handle_refresh_token_grant(state, req).await,
        "client_credentials" => handle_client_credentials_grant(state, req).await,
        _ => {
            (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: "unsupported_grant_type".to_string(),
                    error_description: Some(format!("Grant type '{}' is not supported", req.grant_type)),
                }),
            ).into_response()
        }
    }
}

async fn handle_authorization_code_grant(state: OAuthState, req: TokenRequest) -> Response {
    let code = match req.code {
        Some(c) => c,
        None => {
            return (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: "invalid_request".to_string(),
                    error_description: Some("Missing 'code' parameter".to_string()),
                }),
            ).into_response();
        }
    };

    // Retrieve and remove authorization code
    let auth_code = {
        let mut codes = state.auth_codes.write().await;
        codes.remove(&code)
    };

    let auth_code = match auth_code {
        Some(c) => c,
        None => {
            return (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: "invalid_grant".to_string(),
                    error_description: Some("Invalid or expired authorization code".to_string()),
                }),
            ).into_response();
        }
    };

    // Check expiration
    if Utc::now() > auth_code.expires_at {
        return (
            StatusCode::BAD_REQUEST,
            Json(ErrorResponse {
                error: "invalid_grant".to_string(),
                error_description: Some("Authorization code has expired".to_string()),
            }),
        ).into_response();
    }

    // Validate redirect_uri
    if req.redirect_uri.as_deref() != Some(&auth_code.redirect_uri) {
        return (
            StatusCode::BAD_REQUEST,
            Json(ErrorResponse {
                error: "invalid_grant".to_string(),
                error_description: Some("Redirect URI mismatch".to_string()),
            }),
        ).into_response();
    }

    // Validate client_id
    if req.client_id.as_deref() != Some(&auth_code.client_id) {
        return (
            StatusCode::BAD_REQUEST,
            Json(ErrorResponse {
                error: "invalid_grant".to_string(),
                error_description: Some("Client ID mismatch".to_string()),
            }),
        ).into_response();
    }

    // Validate PKCE if code_challenge was provided
    if let Some(ref challenge) = auth_code.code_challenge {
        let verifier = match req.code_verifier {
            Some(v) => v,
            None => {
                return (
                    StatusCode::BAD_REQUEST,
                    Json(ErrorResponse {
                        error: "invalid_grant".to_string(),
                        error_description: Some("Missing code_verifier".to_string()),
                    }),
                ).into_response();
            }
        };

        let method = auth_code.code_challenge_method.as_deref().unwrap_or("S256");
        let computed_challenge = if method == "S256" {
            let mut hasher = Sha256::new();
            hasher.update(verifier.as_bytes());
            URL_SAFE_NO_PAD.encode(hasher.finalize())
        } else {
            verifier.clone()
        };

        if computed_challenge != *challenge {
            return (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: "invalid_grant".to_string(),
                    error_description: Some("Invalid code_verifier".to_string()),
                }),
            ).into_response();
        }
    }

    // Get the principal
    let principal = match state.principal_repo.find_by_id(&auth_code.principal_id).await {
        Ok(Some(p)) => p,
        Ok(None) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: "invalid_grant".to_string(),
                    error_description: Some("Principal not found".to_string()),
                }),
            ).into_response();
        }
        Err(e) => {
            error!(error = %e, "Failed to get principal");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: "server_error".to_string(),
                    error_description: None,
                }),
            ).into_response();
        }
    };

    // Generate tokens
    let access_token = match state.auth_service.generate_access_token(&principal) {
        Ok(t) => t,
        Err(e) => {
            error!(error = %e, "Failed to generate access token");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: "server_error".to_string(),
                    error_description: None,
                }),
            ).into_response();
        }
    };

    info!(principal_id = %principal.id, client_id = %auth_code.client_id, "Token issued via authorization code grant");

    (
        StatusCode::OK,
        [(header::CACHE_CONTROL, "no-store"), (header::PRAGMA, "no-cache")],
        Json(TokenResponse {
            access_token,
            token_type: "Bearer".to_string(),
            expires_in: 3600,
            refresh_token: None, // TODO: Implement refresh tokens
            scope: auth_code.scope,
        }),
    ).into_response()
}

async fn handle_refresh_token_grant(_state: OAuthState, _req: TokenRequest) -> Response {
    // TODO: Implement refresh token grant
    (
        StatusCode::BAD_REQUEST,
        Json(ErrorResponse {
            error: "unsupported_grant_type".to_string(),
            error_description: Some("Refresh token grant not yet implemented".to_string()),
        }),
    ).into_response()
}

async fn handle_client_credentials_grant(state: OAuthState, req: TokenRequest) -> Response {
    let client_id = match req.client_id {
        Some(id) => id,
        None => {
            return (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: "invalid_request".to_string(),
                    error_description: Some("Missing client_id".to_string()),
                }),
            ).into_response();
        }
    };

    let client_secret = match req.client_secret {
        Some(s) => s,
        None => {
            return (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: "invalid_request".to_string(),
                    error_description: Some("Missing client_secret".to_string()),
                }),
            ).into_response();
        }
    };

    // Lookup client
    let client = match state.oauth_client_repo.find_by_client_id(&client_id).await {
        Ok(Some(c)) if c.active => c,
        Ok(_) => {
            return (
                StatusCode::UNAUTHORIZED,
                Json(ErrorResponse {
                    error: "invalid_client".to_string(),
                    error_description: Some("Invalid client credentials".to_string()),
                }),
            ).into_response();
        }
        Err(e) => {
            error!(error = %e, "Failed to lookup client");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: "server_error".to_string(),
                    error_description: None,
                }),
            ).into_response();
        }
    };

    // Verify client type is CONFIDENTIAL
    if client.client_type != crate::domain::OAuthClientType::Confidential {
        return (
            StatusCode::UNAUTHORIZED,
            Json(ErrorResponse {
                error: "unauthorized_client".to_string(),
                error_description: Some("Public clients cannot use client_credentials grant".to_string()),
            }),
        ).into_response();
    }

    // TODO: Verify client_secret (need to integrate with secrets provider)
    // For now, this is a placeholder - in production, you'd verify against stored secret
    if client_secret.is_empty() {
        return (
            StatusCode::UNAUTHORIZED,
            Json(ErrorResponse {
                error: "invalid_client".to_string(),
                error_description: Some("Invalid client credentials".to_string()),
            }),
        ).into_response();
    }

    // Find or create service account principal for this client
    // In a real implementation, clients would have linked service accounts
    let principal = Principal::new_service(&client_id, &client.client_name);

    let access_token = match state.auth_service.generate_access_token(&principal) {
        Ok(t) => t,
        Err(e) => {
            error!(error = %e, "Failed to generate access token");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: "server_error".to_string(),
                    error_description: None,
                }),
            ).into_response();
        }
    };

    info!(client_id = %client_id, "Token issued via client credentials grant");

    (
        StatusCode::OK,
        [(header::CACHE_CONTROL, "no-store"), (header::PRAGMA, "no-cache")],
        Json(TokenResponse {
            access_token,
            token_type: "Bearer".to_string(),
            expires_in: 3600,
            refresh_token: None,
            scope: None,
        }),
    ).into_response()
}

/// OIDC callback endpoint
pub async fn oidc_callback(
    State(state): State<OAuthState>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    let _oidc_code = match params.get("code") {
        Some(c) => c,
        None => {
            let error = params.get("error").cloned().unwrap_or_else(|| "unknown".to_string());
            let error_desc = params.get("error_description").cloned();
            warn!(error = %error, "OIDC callback received error");
            return (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error,
                    error_description: error_desc,
                }),
            ).into_response();
        }
    };

    let state_param = match params.get("state") {
        Some(s) => s,
        None => {
            return (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: "invalid_request".to_string(),
                    error_description: Some("Missing state parameter".to_string()),
                }),
            ).into_response();
        }
    };

    // Retrieve and validate pending authorization
    let pending = {
        let mut pending_states = state.pending_states.write().await;
        pending_states.remove(state_param)
    };

    let pending = match pending {
        Some(p) => p,
        None => {
            return (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: "invalid_request".to_string(),
                    error_description: Some("Invalid or expired state".to_string()),
                }),
            ).into_response();
        }
    };

    // Exchange code for tokens with the OIDC provider
    // This would need the provider_id stored in pending state
    // For now, just generate an authorization code and redirect back

    let auth_code = generate_random_string(32);

    // Store the authorization code
    {
        let mut codes = state.auth_codes.write().await;
        codes.insert(auth_code.clone(), AuthorizationCode {
            code: auth_code.clone(),
            client_id: pending.client_id.clone(),
            redirect_uri: pending.redirect_uri.clone(),
            principal_id: "placeholder".to_string(), // Would be populated from OIDC token
            scope: pending.scope.clone(),
            code_challenge: pending.code_challenge.clone(),
            code_challenge_method: pending.code_challenge_method.clone(),
            nonce: pending.nonce.clone(),
            expires_at: Utc::now() + Duration::minutes(10),
        });
    }

    // Redirect back to client
    let mut redirect_url = pending.redirect_uri.clone();
    redirect_url.push_str(&format!("?code={}", urlencoding::encode(&auth_code)));
    if let Some(s) = params.get("state") {
        redirect_url.push_str(&format!("&state={}", urlencoding::encode(s)));
    }

    Redirect::temporary(&redirect_url).into_response()
}

/// Issue authorization code after successful login
pub async fn issue_code(
    state: &OAuthState,
    principal_id: &str,
    pending_state: &str,
) -> Result<String, PlatformError> {
    let pending = {
        let mut pending_states = state.pending_states.write().await;
        pending_states.remove(pending_state)
    };

    let pending = pending.ok_or_else(|| PlatformError::InvalidToken {
        message: "Invalid or expired state".to_string(),
    })?;

    let auth_code = generate_random_string(32);

    // Store the authorization code
    {
        let mut codes = state.auth_codes.write().await;
        codes.insert(auth_code.clone(), AuthorizationCode {
            code: auth_code.clone(),
            client_id: pending.client_id,
            redirect_uri: pending.redirect_uri,
            principal_id: principal_id.to_string(),
            scope: pending.scope,
            code_challenge: pending.code_challenge,
            code_challenge_method: pending.code_challenge_method,
            nonce: pending.nonce,
            expires_at: Utc::now() + Duration::minutes(10),
        });
    }

    Ok(auth_code)
}

fn error_redirect(redirect_uri: &str, error: &str, description: &str, state: Option<&str>) -> Response {
    let mut url = redirect_uri.to_string();
    url.push_str(&format!(
        "?error={}&error_description={}",
        urlencoding::encode(error),
        urlencoding::encode(description),
    ));
    if let Some(s) = state {
        url.push_str(&format!("&state={}", urlencoding::encode(s)));
    }
    Redirect::temporary(&url).into_response()
}

fn generate_random_string(len: usize) -> String {
    use rand::Rng;
    const CHARSET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    let mut rng = rand::thread_rng();
    (0..len)
        .map(|_| CHARSET[rng.gen_range(0..CHARSET.len())] as char)
        .collect()
}

/// Create OAuth router
pub fn oauth_router(state: OAuthState) -> Router {
    Router::new()
        .route("/authorize", get(authorize))
        .route("/token", post(token))
        .route("/callback", get(oidc_callback))
        .with_state(state)
}
