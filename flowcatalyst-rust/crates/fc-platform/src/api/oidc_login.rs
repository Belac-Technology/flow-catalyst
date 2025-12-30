//! OIDC Federation Login Endpoints
//!
//! Handles login flows where FlowCatalyst acts as an OIDC client,
//! federating authentication to external identity providers (Entra ID, Keycloak, etc.)
//!
//! Flow:
//! 1. POST /auth/check-domain - Check auth method for email domain
//! 2. GET /auth/oidc/login?domain=example.com - Redirects to external IDP
//! 3. User authenticates at external IDP
//! 4. GET /auth/oidc/callback?code=...&state=... - Handles callback, creates session

use salvo::prelude::*;
use salvo::oapi::extract::*;
use salvo::http::cookie::Cookie;
use serde::{Deserialize, Serialize};
use sha2::{Sha256, Digest};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use std::sync::Arc;
use chrono::Utc;
use tracing::{info, warn, error, debug};
use rand::Rng;

use crate::domain::{ClientAuthConfig, AuthProvider, AuthConfigType, UserScope};
use crate::repository::{
    ClientAuthConfigRepository, OidcLoginStateRepository, AnchorDomainRepository,
};
use crate::service::{AuthService, OidcSyncService};

/// OIDC Login API State
#[derive(Clone)]
pub struct OidcLoginApiState {
    pub client_auth_config_repo: Arc<ClientAuthConfigRepository>,
    pub anchor_domain_repo: Arc<AnchorDomainRepository>,
    pub oidc_login_state_repo: Arc<OidcLoginStateRepository>,
    pub oidc_sync_service: Arc<OidcSyncService>,
    pub auth_service: Arc<AuthService>,
    /// External base URL for callbacks (e.g., "https://platform.example.com")
    pub external_base_url: Option<String>,
    /// Session cookie settings
    pub session_cookie_name: String,
    pub session_cookie_secure: bool,
    pub session_cookie_same_site: String,
    pub session_token_expiry_secs: i64,
}

impl OidcLoginApiState {
    pub fn new(
        client_auth_config_repo: Arc<ClientAuthConfigRepository>,
        anchor_domain_repo: Arc<AnchorDomainRepository>,
        oidc_login_state_repo: Arc<OidcLoginStateRepository>,
        oidc_sync_service: Arc<OidcSyncService>,
        auth_service: Arc<AuthService>,
    ) -> Self {
        Self {
            client_auth_config_repo,
            anchor_domain_repo,
            oidc_login_state_repo,
            oidc_sync_service,
            auth_service,
            external_base_url: None,
            session_cookie_name: "fc_session".to_string(),
            session_cookie_secure: true,
            session_cookie_same_site: "Lax".to_string(),
            session_token_expiry_secs: 86400, // 24 hours
        }
    }

    pub fn with_external_base_url(mut self, url: impl Into<String>) -> Self {
        self.external_base_url = Some(url.into());
        self
    }

    pub fn with_session_cookie_settings(
        mut self,
        name: impl Into<String>,
        secure: bool,
        same_site: impl Into<String>,
        expiry_secs: i64,
    ) -> Self {
        self.session_cookie_name = name.into();
        self.session_cookie_secure = secure;
        self.session_cookie_same_site = same_site.into();
        self.session_token_expiry_secs = expiry_secs;
        self
    }
}

// ==================== Request/Response Types ====================

/// Domain check request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct DomainCheckRequest {
    pub email: String,
}

/// Domain check response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct DomainCheckResponse {
    /// "internal" for password auth, "external" for OIDC
    pub auth_method: String,
    /// URL to redirect to for login (for external: /auth/oidc/login?domain=...)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub login_url: Option<String>,
    /// External IDP issuer URL (informational)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub idp_issuer: Option<String>,
}

/// OIDC login query parameters
#[derive(Debug, Deserialize, ToParameters)]
#[salvo(parameters(default_parameter_in = Query))]
pub struct OidcLoginParams {
    /// Email domain to authenticate
    pub domain: String,
    /// URL to return to after login
    pub return_url: Option<String>,
    // OAuth flow chaining parameters
    pub oauth_client_id: Option<String>,
    pub oauth_redirect_uri: Option<String>,
    pub oauth_scope: Option<String>,
    pub oauth_state: Option<String>,
    pub oauth_code_challenge: Option<String>,
    pub oauth_code_challenge_method: Option<String>,
    pub oauth_nonce: Option<String>,
}

/// OIDC callback query parameters
#[derive(Debug, Deserialize, ToParameters)]
#[salvo(parameters(default_parameter_in = Query))]
pub struct OidcCallbackParams {
    pub code: Option<String>,
    pub state: Option<String>,
    pub error: Option<String>,
    pub error_description: Option<String>,
}

/// Error response
#[derive(Debug, Serialize, ToSchema)]
pub struct ErrorResponse {
    pub error: String,
}

// ==================== Endpoints ====================

/// Check authentication method for email domain
#[endpoint(tags("Auth Discovery"))]
pub async fn check_domain(
    depot: &mut Depot,
    body: JsonBody<DomainCheckRequest>,
    res: &mut Response,
) {
    let state = match depot.obtain::<OidcLoginApiState>() {
        Ok(s) => s,
        Err(_) => {
            res.status_code(StatusCode::INTERNAL_SERVER_ERROR);
            res.render(Json(ErrorResponse {
                error: "Internal configuration error".to_string(),
            }));
            return;
        }
    };

    let email = body.email.trim().to_lowercase();

    // Validate email format
    let at_index = match email.find('@') {
        Some(idx) if idx > 0 && idx < email.len() - 1 => idx,
        _ => {
            res.status_code(StatusCode::BAD_REQUEST);
            res.render(Json(ErrorResponse {
                error: "Invalid email format".to_string(),
            }));
            return;
        }
    };

    let domain = &email[at_index + 1..];
    debug!(domain = %domain, "Checking auth method");

    // Check if anchor domain (god mode)
    match state.anchor_domain_repo.is_anchor_domain(domain).await {
        Ok(true) => {
            // Anchor domains can use internal auth
            res.render(Json(DomainCheckResponse {
                auth_method: "internal".to_string(),
                login_url: None,
                idp_issuer: None,
            }));
            return;
        }
        Ok(false) => {}
        Err(e) => {
            error!(error = %e, "Failed to check anchor domain");
            res.status_code(StatusCode::INTERNAL_SERVER_ERROR);
            res.render(Json(ErrorResponse {
                error: "Failed to check domain".to_string(),
            }));
            return;
        }
    }

    // Look up auth config for this domain
    let config = match state.client_auth_config_repo.find_by_email_domain(domain).await {
        Ok(Some(c)) => c,
        Ok(None) => {
            // Default to internal auth if no config
            debug!(domain = %domain, "No auth config, defaulting to internal");
            res.render(Json(DomainCheckResponse {
                auth_method: "internal".to_string(),
                login_url: None,
                idp_issuer: None,
            }));
            return;
        }
        Err(e) => {
            error!(error = %e, "Failed to lookup auth config");
            res.status_code(StatusCode::INTERNAL_SERVER_ERROR);
            res.render(Json(ErrorResponse {
                error: "Failed to check domain".to_string(),
            }));
            return;
        }
    };

    if config.auth_provider == AuthProvider::Oidc && config.oidc_issuer_url.is_some() {
        let login_url = format!("/auth/oidc/login?domain={}", domain);
        debug!(domain = %domain, login_url = %login_url, "Domain uses OIDC");
        res.render(Json(DomainCheckResponse {
            auth_method: "external".to_string(),
            login_url: Some(login_url),
            idp_issuer: config.oidc_issuer_url,
        }));
    } else {
        res.render(Json(DomainCheckResponse {
            auth_method: "internal".to_string(),
            login_url: None,
            idp_issuer: None,
        }));
    }
}

/// Initiate OIDC login - redirects to external IDP
#[endpoint(tags("OIDC Federation"))]
pub async fn oidc_login(
    depot: &mut Depot,
    req: &mut Request,
    params: OidcLoginParams,
    res: &mut Response,
) {
    let state = match depot.obtain::<OidcLoginApiState>() {
        Ok(s) => s,
        Err(_) => {
            res.status_code(StatusCode::INTERNAL_SERVER_ERROR);
            res.render(Json(ErrorResponse {
                error: "Internal configuration error".to_string(),
            }));
            return;
        }
    };

    let domain = params.domain.trim().to_lowercase();

    if domain.is_empty() {
        res.status_code(StatusCode::BAD_REQUEST);
        res.render(Json(ErrorResponse {
            error: "domain parameter is required".to_string(),
        }));
        return;
    }

    // Look up auth config for this domain
    let config = match state.client_auth_config_repo.find_by_email_domain(&domain).await {
        Ok(Some(c)) => c,
        Ok(None) => {
            res.status_code(StatusCode::NOT_FOUND);
            res.render(Json(ErrorResponse {
                error: format!("No authentication configuration found for domain: {}", domain),
            }));
            return;
        }
        Err(e) => {
            error!(error = %e, "Failed to lookup auth config");
            res.status_code(StatusCode::INTERNAL_SERVER_ERROR);
            res.render(Json(ErrorResponse {
                error: "Internal error".to_string(),
            }));
            return;
        }
    };

    if config.auth_provider != AuthProvider::Oidc {
        res.status_code(StatusCode::BAD_REQUEST);
        res.render(Json(ErrorResponse {
            error: format!("Domain {} uses internal authentication, not OIDC", domain),
        }));
        return;
    }

    if config.oidc_issuer_url.is_none() || config.oidc_client_id.is_none() {
        res.status_code(StatusCode::INTERNAL_SERVER_ERROR);
        res.render(Json(ErrorResponse {
            error: format!("OIDC configuration incomplete for domain: {}", domain),
        }));
        return;
    }

    // Generate state, nonce, and PKCE
    let oidc_state = generate_random_string(32);
    let nonce = generate_random_string(32);
    let code_verifier = generate_code_verifier();
    let code_challenge = generate_code_challenge(&code_verifier);

    // Build login state
    let login_state = crate::domain::OidcLoginState::new(
        &oidc_state,
        &domain,
        &config.id,
        &nonce,
        &code_verifier,
    )
    .with_oauth_params(
        params.oauth_client_id,
        params.oauth_redirect_uri,
        params.oauth_scope,
        params.oauth_state,
        params.oauth_code_challenge,
        params.oauth_code_challenge_method,
        params.oauth_nonce,
    );

    // Store state
    if let Err(e) = state.oidc_login_state_repo.insert(&login_state).await {
        error!(error = %e, "Failed to store login state");
        res.status_code(StatusCode::INTERNAL_SERVER_ERROR);
        res.render(Json(ErrorResponse {
            error: "Failed to initiate login".to_string(),
        }));
        return;
    }

    // Build authorization URL
    let callback_url = get_callback_url(&state, req);
    let auth_url = build_authorization_url(
        &config,
        &oidc_state,
        &nonce,
        &code_challenge,
        &callback_url,
    );

    info!(
        domain = %domain,
        issuer = %config.oidc_issuer_url.as_deref().unwrap_or(""),
        "Redirecting to OIDC provider"
    );

    // Redirect to IDP
    res.status_code(StatusCode::SEE_OTHER);
    res.headers_mut().insert(
        salvo::http::header::LOCATION,
        auth_url.parse().unwrap(),
    );
}

/// Handle OIDC callback from external IDP
#[endpoint(tags("OIDC Federation"))]
pub async fn oidc_callback(
    depot: &mut Depot,
    req: &mut Request,
    params: OidcCallbackParams,
    res: &mut Response,
) {
    let state = match depot.obtain::<OidcLoginApiState>() {
        Ok(s) => s,
        Err(_) => {
            error_redirect(res, "Internal configuration error");
            return;
        }
    };

    // Handle IDP errors
    if let Some(error) = &params.error {
        warn!(
            error = %error,
            description = params.error_description.as_deref().unwrap_or(""),
            "OIDC callback error"
        );
        error_redirect(res, params.error_description.as_deref().unwrap_or(error));
        return;
    }

    let code = match &params.code {
        Some(c) if !c.is_empty() => c,
        _ => {
            error_redirect(res, "No authorization code received");
            return;
        }
    };

    let oidc_state = match &params.state {
        Some(s) if !s.is_empty() => s,
        _ => {
            error_redirect(res, "No state parameter received");
            return;
        }
    };

    // Validate state
    let login_state = match state.oidc_login_state_repo.find_valid_state(oidc_state).await {
        Ok(Some(s)) => s,
        Ok(None) => {
            warn!(state = %oidc_state, "Invalid or expired OIDC state");
            error_redirect(res, "Invalid or expired login session. Please try again.");
            return;
        }
        Err(e) => {
            error!(error = %e, "Failed to validate state");
            error_redirect(res, "Failed to validate login session");
            return;
        }
    };

    // Delete state immediately (single use)
    if let Err(e) = state.oidc_login_state_repo.delete_by_state(oidc_state).await {
        warn!(error = %e, "Failed to delete login state");
    }

    // Look up auth config
    let config = match state.client_auth_config_repo.find_by_id(&login_state.auth_config_id).await {
        Ok(Some(c)) => c,
        Ok(None) => {
            error_redirect(res, "Authentication configuration no longer exists");
            return;
        }
        Err(e) => {
            error!(error = %e, "Failed to lookup auth config");
            error_redirect(res, "Failed to validate configuration");
            return;
        }
    };

    // Exchange code for tokens
    let callback_url = get_callback_url(&state, req);
    let tokens = match exchange_code_for_tokens(&config, code, &login_state.code_verifier, &callback_url).await {
        Ok(t) => t,
        Err(e) => {
            error!(error = %e, "Token exchange failed");
            error_redirect(res, "Failed to exchange authorization code");
            return;
        }
    };

    // Parse and validate ID token
    let claims = match parse_and_validate_id_token(&tokens.id_token, &config, &login_state.nonce) {
        Ok(c) => c,
        Err(e) => {
            error!(error = %e, "ID token validation failed");
            error_redirect(res, "Failed to validate identity token");
            return;
        }
    };

    // Determine user scope based on config type
    let user_scope = match config.config_type {
        AuthConfigType::Anchor => UserScope::Anchor,
        AuthConfigType::Partner => UserScope::Partner,
        AuthConfigType::Client => UserScope::Client,
    };

    // Sync user and roles
    let principal = match state.oidc_sync_service.sync_oidc_login(
        &claims.email,
        claims.name.as_deref().unwrap_or(&claims.email),
        &claims.subject,
        config.oidc_issuer_url.as_deref().unwrap_or("unknown"),
        config.primary_client_id.as_deref(),
        user_scope,
        &claims.roles.unwrap_or_default(),
    ).await {
        Ok(p) => p,
        Err(e) => {
            error!(error = %e, "User sync failed");
            error_redirect(res, "Failed to create user session");
            return;
        }
    };

    // Issue session token using the principal (which has roles already synced)
    let session_token = match state.auth_service.generate_access_token(&principal) {
        Ok(t) => t,
        Err(e) => {
            error!(error = %e, "Failed to issue session token");
            error_redirect(res, "Failed to create session");
            return;
        }
    };

    // Build session cookie
    let mut cookie = Cookie::new(state.session_cookie_name.clone(), session_token);
    cookie.set_path("/");
    cookie.set_http_only(true);
    if state.session_cookie_secure {
        cookie.set_secure(true);
    }
    // Convert i64 seconds to time::Duration
    cookie.set_max_age(time::Duration::seconds(state.session_token_expiry_secs));
    res.add_cookie(cookie);

    // Determine redirect URL
    let redirect_url = determine_redirect_url(&state, req, &login_state);

    info!(
        email = %claims.email,
        principal_id = %principal.id,
        "OIDC login successful"
    );

    // Redirect
    res.status_code(StatusCode::SEE_OTHER);
    res.headers_mut().insert(
        salvo::http::header::LOCATION,
        redirect_url.parse().unwrap(),
    );
}

// ==================== Helper Functions ====================

fn generate_random_string(length: usize) -> String {
    let bytes: Vec<u8> = (0..length).map(|_| rand::thread_rng().gen()).collect();
    URL_SAFE_NO_PAD.encode(&bytes)
}

fn generate_code_verifier() -> String {
    generate_random_string(32)
}

fn generate_code_challenge(verifier: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(verifier.as_bytes());
    let hash = hasher.finalize();
    URL_SAFE_NO_PAD.encode(hash)
}

fn get_external_base_url(state: &OidcLoginApiState, req: &Request) -> String {
    state.external_base_url.clone().unwrap_or_else(|| {
        // Fall back to request host
        let scheme = if state.session_cookie_secure { "https" } else { "http" };
        let host = req.uri().host().unwrap_or("localhost");
        let port = req.uri().port_u16();
        match port {
            Some(p) if (scheme == "http" && p != 80) || (scheme == "https" && p != 443) => {
                format!("{}://{}:{}", scheme, host, p)
            }
            _ => format!("{}://{}", scheme, host),
        }
    })
}

fn get_callback_url(state: &OidcLoginApiState, req: &Request) -> String {
    format!("{}/auth/oidc/callback", get_external_base_url(state, req))
}

fn build_authorization_url(
    config: &ClientAuthConfig,
    state: &str,
    nonce: &str,
    code_challenge: &str,
    callback_url: &str,
) -> String {
    let issuer = config.oidc_issuer_url.as_deref().unwrap_or("");
    let auth_endpoint = get_authorization_endpoint(issuer);
    let client_id = config.oidc_client_id.as_deref().unwrap_or("");

    format!(
        "{}?response_type=code&client_id={}&redirect_uri={}&scope={}&state={}&nonce={}&code_challenge={}&code_challenge_method=S256",
        auth_endpoint,
        urlencoding::encode(client_id),
        urlencoding::encode(callback_url),
        urlencoding::encode("openid profile email"),
        urlencoding::encode(state),
        urlencoding::encode(nonce),
        urlencoding::encode(code_challenge),
    )
}

fn get_authorization_endpoint(issuer_url: &str) -> String {
    if issuer_url.contains("login.microsoftonline.com") {
        issuer_url.replace("/v2.0", "/oauth2/v2.0/authorize")
    } else {
        let base = issuer_url.trim_end_matches('/');
        format!("{}/authorize", base)
    }
}

fn get_token_endpoint(issuer_url: &str) -> String {
    if issuer_url.contains("login.microsoftonline.com") {
        issuer_url.replace("/v2.0", "/oauth2/v2.0/token")
    } else {
        let base = issuer_url.trim_end_matches('/');
        format!("{}/token", base)
    }
}

#[derive(Debug)]
#[allow(dead_code)]
struct TokenExchangeResponse {
    access_token: String,
    id_token: String,
    refresh_token: Option<String>,
}

async fn exchange_code_for_tokens(
    config: &ClientAuthConfig,
    code: &str,
    code_verifier: &str,
    callback_url: &str,
) -> Result<TokenExchangeResponse, String> {
    let issuer = config.oidc_issuer_url.as_deref().ok_or("Missing issuer URL")?;
    let token_endpoint = get_token_endpoint(issuer);
    let client_id = config.oidc_client_id.as_deref().ok_or("Missing client ID")?;

    let mut params = vec![
        ("grant_type", "authorization_code"),
        ("code", code),
        ("redirect_uri", callback_url),
        ("client_id", client_id),
        ("code_verifier", code_verifier),
    ];

    // Add client secret if present
    let client_secret = config.oidc_client_secret_ref.clone();
    if let Some(ref secret) = client_secret {
        params.push(("client_secret", secret));
    }

    let client = reqwest::Client::new();
    let response = client
        .post(&token_endpoint)
        .form(&params)
        .timeout(std::time::Duration::from_secs(30))
        .send()
        .await
        .map_err(|e| format!("HTTP request failed: {}", e))?;

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        return Err(format!("Token endpoint returned {}: {}", status, body));
    }

    let json: serde_json::Value = response.json().await
        .map_err(|e| format!("Failed to parse token response: {}", e))?;

    let id_token = json["id_token"]
        .as_str()
        .ok_or("No ID token in response")?
        .to_string();

    Ok(TokenExchangeResponse {
        access_token: json["access_token"].as_str().unwrap_or("").to_string(),
        id_token,
        refresh_token: json["refresh_token"].as_str().map(String::from),
    })
}

#[derive(Debug)]
#[allow(dead_code)]
struct IdTokenClaims {
    issuer: String,
    subject: String,
    email: String,
    name: Option<String>,
    roles: Option<Vec<String>>,
}

fn parse_and_validate_id_token(
    id_token: &str,
    config: &ClientAuthConfig,
    expected_nonce: &str,
) -> Result<IdTokenClaims, String> {
    let parts: Vec<&str> = id_token.split('.').collect();
    if parts.len() != 3 {
        return Err("Invalid ID token format".to_string());
    }

    // Decode payload
    let payload_bytes = URL_SAFE_NO_PAD.decode(parts[1])
        .map_err(|e| format!("Failed to decode token payload: {}", e))?;
    let payload: serde_json::Value = serde_json::from_slice(&payload_bytes)
        .map_err(|e| format!("Failed to parse token payload: {}", e))?;

    // Extract claims
    let issuer = payload["iss"].as_str().ok_or("Missing issuer claim")?.to_string();
    let subject = payload["sub"].as_str().ok_or("Missing subject claim")?.to_string();
    let exp = payload["exp"].as_i64().unwrap_or(0);
    let nonce = payload["nonce"].as_str();

    // Validate issuer
    if !config.is_valid_issuer(&issuer) {
        return Err(format!("Invalid issuer: {}", issuer));
    }

    // Validate expiration
    let now = Utc::now().timestamp();
    if exp < now {
        return Err("ID token has expired".to_string());
    }

    // Validate nonce
    if nonce != Some(expected_nonce) {
        return Err("Nonce mismatch".to_string());
    }

    // Extract email
    let email = payload["email"]
        .as_str()
        .or_else(|| payload["preferred_username"].as_str())
        .ok_or("No email claim in ID token")?
        .to_lowercase();

    // Extract name
    let name = payload["name"].as_str().map(String::from);

    // Extract roles (various claim names used by different IDPs)
    let roles = payload["roles"]
        .as_array()
        .or_else(|| payload["groups"].as_array())
        .or_else(|| payload["realm_access"]["roles"].as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str())
                .map(String::from)
                .collect()
        });

    Ok(IdTokenClaims {
        issuer,
        subject,
        email,
        name,
        roles,
    })
}

#[allow(dead_code)]
fn determine_accessible_clients(_principal: &crate::domain::Principal, config: &ClientAuthConfig) -> Vec<String> {
    match config.config_type {
        AuthConfigType::Anchor => vec!["*".to_string()],
        AuthConfigType::Client | AuthConfigType::Partner => {
            config.accessible_clients()
        }
    }
}

fn determine_redirect_url(
    state: &OidcLoginApiState,
    req: &Request,
    login_state: &crate::domain::OidcLoginState,
) -> String {
    let base_url = get_external_base_url(state, req);

    // If this was part of an OAuth flow, redirect back to authorize endpoint
    if let Some(ref client_id) = login_state.oauth_client_id {
        let mut url = format!("{}/oauth/authorize?response_type=code&client_id={}", base_url, urlencoding::encode(client_id));

        if let Some(ref uri) = login_state.oauth_redirect_uri {
            url.push_str(&format!("&redirect_uri={}", urlencoding::encode(uri)));
        }
        if let Some(ref scope) = login_state.oauth_scope {
            url.push_str(&format!("&scope={}", urlencoding::encode(scope)));
        }
        if let Some(ref state) = login_state.oauth_state {
            url.push_str(&format!("&state={}", urlencoding::encode(state)));
        }
        if let Some(ref challenge) = login_state.oauth_code_challenge {
            url.push_str(&format!("&code_challenge={}", urlencoding::encode(challenge)));
        }
        if let Some(ref method) = login_state.oauth_code_challenge_method {
            url.push_str(&format!("&code_challenge_method={}", urlencoding::encode(method)));
        }
        if let Some(ref nonce) = login_state.oauth_nonce {
            url.push_str(&format!("&nonce={}", urlencoding::encode(nonce)));
        }

        return url;
    }

    // Return to specified URL or default to dashboard
    if let Some(ref return_url) = login_state.return_url {
        if !return_url.is_empty() {
            if return_url.starts_with('/') {
                return format!("{}{}", base_url, return_url);
            }
            return return_url.clone();
        }
    }

    format!("{}/dashboard", base_url)
}

fn error_redirect(res: &mut Response, message: &str) {
    let error_url = format!("/?error={}", urlencoding::encode(message));
    res.status_code(StatusCode::SEE_OTHER);
    res.headers_mut().insert(
        salvo::http::header::LOCATION,
        error_url.parse().unwrap(),
    );
}

/// Create the OIDC login router
pub fn oidc_login_router() -> Router {
    Router::new()
        .push(Router::with_path("check-domain").post(check_domain))
        .push(
            Router::with_path("oidc")
                .push(Router::with_path("login").get(oidc_login))
                .push(Router::with_path("callback").get(oidc_callback))
        )
}
