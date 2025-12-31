//! Auth API Endpoints
//!
//! Embedded authentication endpoints for direct login/logout.
//! - POST /auth/login - Password-based login
//! - POST /auth/logout - Logout / token revocation
//! - GET /auth/check-domain - Check if email domain requires external IDP
//! - GET /auth/me - Get current user info

use axum::{
    routing::{get, post},
    extract::{Query, State, Extension},
    Json, Router,
    http::StatusCode,
    response::IntoResponse,
};
use utoipa::{ToSchema, IntoParams};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

use crate::repository::{PrincipalRepository, RefreshTokenRepository};
use crate::domain::RefreshToken;
use crate::service::auth::AuthService;
use crate::service::password::PasswordService;
use crate::error::PlatformError;
use crate::api::middleware::Authenticated;

/// Login request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct LoginRequest {
    /// Email address
    pub email: String,

    /// Password
    pub password: String,

    /// Remember me (extends session duration)
    #[serde(default)]
    pub remember_me: bool,
}

/// Login response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct LoginResponse {
    /// Access token
    pub access_token: String,

    /// Token type (always "Bearer")
    pub token_type: String,

    /// Expiration time in seconds
    pub expires_in: i64,

    /// Refresh token (if remember_me)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub refresh_token: Option<String>,
}

/// Domain check request
#[derive(Debug, Deserialize, ToSchema, IntoParams)]
#[serde(rename_all = "camelCase")]
#[into_params(parameter_in = Query)]
pub struct DomainCheckRequest {
    /// Email address to check
    pub email: String,
}

/// Domain check response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct DomainCheckResponse {
    /// The email domain
    pub domain: String,

    /// Authentication method for this domain
    pub auth_method: AuthMethod,

    /// Provider ID if external IDP is required
    #[serde(skip_serializing_if = "Option::is_none")]
    pub provider_id: Option<String>,

    /// Authorization URL if external IDP
    #[serde(skip_serializing_if = "Option::is_none")]
    pub authorization_url: Option<String>,
}

/// Authentication method
#[derive(Debug, Clone, Serialize, ToSchema)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AuthMethod {
    /// Internal username/password authentication
    Internal,
    /// External OIDC identity provider
    Oidc,
    /// External SAML identity provider
    Saml,
}

/// Current user info response
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct CurrentUserResponse {
    /// Principal ID
    pub id: String,

    /// Principal type (USER, SERVICE)
    pub principal_type: String,

    /// Email address
    #[serde(skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,

    /// Display name
    pub name: String,

    /// User scope (ANCHOR, PARTNER, CLIENT)
    pub scope: String,

    /// Client ID (for CLIENT scope users)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,

    /// Accessible client IDs
    pub clients: Vec<String>,

    /// Assigned roles
    pub roles: Vec<String>,
}

/// Auth service state
#[derive(Clone)]
pub struct AuthState {
    pub auth_service: Arc<AuthService>,
    pub principal_repo: Arc<PrincipalRepository>,
    pub password_service: Arc<PasswordService>,
    pub refresh_token_repo: Arc<RefreshTokenRepository>,
}

/// Login with email and password
///
/// Authenticates a user with email and password credentials.
/// Returns an access token on success.
#[utoipa::path(
    post,
    path = "/login",
    tag = "auth",
    request_body = LoginRequest,
    responses(
        (status = 200, description = "Login successful", body = LoginResponse),
        (status = 401, description = "Invalid credentials")
    )
)]
pub async fn login(
    State(state): State<AuthState>,
    Json(req): Json<LoginRequest>,
) -> Result<Json<LoginResponse>, PlatformError> {
    // Find principal by email
    let principal = state
        .principal_repo
        .find_by_email(&req.email)
        .await?
        .ok_or_else(|| PlatformError::Unauthorized {
            message: "Invalid credentials".to_string(),
        })?;

    // Verify password using Argon2id
    let password_valid = principal.user_identity
        .as_ref()
        .and_then(|id| id.password_hash.as_ref())
        .map(|hash| {
            state.password_service
                .verify_password(&req.password, hash)
                .unwrap_or(false)
        })
        .unwrap_or(false);

    if !password_valid {
        return Err(PlatformError::Unauthorized {
            message: "Invalid credentials".to_string(),
        });
    }

    // Check if user is active
    if !principal.active {
        return Err(PlatformError::Unauthorized {
            message: "Account is not active".to_string(),
        });
    }

    // Generate access token
    let access_token = state.auth_service.generate_access_token(&principal)?;

    // Generate refresh token if remember_me is set
    let refresh_token = if req.remember_me {
        let (raw_token, token_entity) = RefreshToken::generate_token_pair(&principal.id);

        // Determine accessible clients based on scope
        let accessible_clients = match principal.scope {
            crate::domain::UserScope::Anchor => vec!["*".to_string()],
            crate::domain::UserScope::Partner => principal.assigned_clients.clone(),
            crate::domain::UserScope::Client => principal.client_id.clone().into_iter().collect(),
        };

        let token_entity = token_entity
            .with_accessible_clients(accessible_clients);

        // Store the token
        state.refresh_token_repo.insert(&token_entity).await?;

        Some(raw_token)
    } else {
        None
    };

    Ok(Json(LoginResponse {
        access_token,
        token_type: "Bearer".to_string(),
        expires_in: 3600, // Would come from config
        refresh_token,
    }))
}

/// Logout / revoke token
///
/// Invalidates the current session. For stateless JWTs, this is a no-op
/// on the server side, but clears the client's token.
#[utoipa::path(
    post,
    path = "/logout",
    tag = "auth",
    responses(
        (status = 204, description = "Logout successful")
    )
)]
pub async fn logout(
    auth: Authenticated,
) -> impl IntoResponse {
    // Verify token is valid (the Authenticated extractor handles this)
    let _ctx = &auth.0;

    // For stateless JWTs, nothing to do server-side
    // In production, you might want to add the token to a blocklist

    StatusCode::NO_CONTENT
}

/// Check email domain authentication method
///
/// Determines how a user with the given email should authenticate:
/// - Internal: username/password
/// - OIDC: external identity provider
///
/// This is called before showing the login form to determine
/// if the user should be redirected to an external IDP.
#[utoipa::path(
    get,
    path = "/check-domain",
    tag = "auth",
    params(DomainCheckRequest),
    responses(
        (status = 200, description = "Domain check result", body = DomainCheckResponse)
    )
)]
pub async fn check_domain(
    Query(req): Query<DomainCheckRequest>,
) -> Json<DomainCheckResponse> {
    // Extract domain from email
    let domain = req
        .email
        .split('@')
        .nth(1)
        .unwrap_or("")
        .to_lowercase();

    // TODO: Look up domain configuration from database
    // For now, default to internal auth
    Json(DomainCheckResponse {
        domain,
        auth_method: AuthMethod::Internal,
        provider_id: None,
        authorization_url: None,
    })
}

/// Get current user info
///
/// Returns information about the currently authenticated user.
#[utoipa::path(
    get,
    path = "/me",
    tag = "auth",
    responses(
        (status = 200, description = "Current user info", body = CurrentUserResponse),
        (status = 401, description = "Not authenticated")
    )
)]
pub async fn get_current_user(
    auth: Authenticated,
) -> Result<Json<CurrentUserResponse>, PlatformError> {
    let ctx = &auth.0;

    Ok(Json(CurrentUserResponse {
        id: ctx.principal_id.clone(),
        principal_type: ctx.principal_type.clone(),
        email: ctx.email.clone(),
        name: ctx.name.clone(),
        scope: ctx.scope.clone(),
        client_id: if ctx.scope == "CLIENT" {
            ctx.accessible_clients.first().cloned()
        } else {
            None
        },
        clients: ctx.accessible_clients.clone(),
        roles: ctx.roles.clone(),
    }))
}

/// Refresh token request
#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RefreshTokenRequest {
    /// The refresh token
    pub refresh_token: String,
}

/// Refresh access token
///
/// Exchange a refresh token for a new access token.
/// The refresh token is rotated (old one invalidated, new one issued).
#[utoipa::path(
    post,
    path = "/refresh",
    tag = "auth",
    request_body = RefreshTokenRequest,
    responses(
        (status = 200, description = "Token refreshed", body = LoginResponse),
        (status = 401, description = "Invalid refresh token")
    )
)]
pub async fn refresh_token(
    State(state): State<AuthState>,
    Json(req): Json<RefreshTokenRequest>,
) -> Result<Json<LoginResponse>, PlatformError> {
    // Hash the provided token and look it up
    let token_hash = RefreshToken::hash_token(&req.refresh_token);

    let stored_token = state.refresh_token_repo
        .find_valid_by_hash(&token_hash)
        .await?
        .ok_or_else(|| PlatformError::InvalidToken {
            message: "Invalid or expired refresh token".to_string(),
        })?;

    // Revoke the old token (token rotation for security)
    state.refresh_token_repo.revoke_by_hash(&token_hash).await?;

    // Find the principal
    let principal = state.principal_repo
        .find_by_id(&stored_token.principal_id)
        .await?
        .ok_or_else(|| PlatformError::InvalidToken {
            message: "Principal not found".to_string(),
        })?;

    // Check if principal is still active
    if !principal.active {
        return Err(PlatformError::Unauthorized {
            message: "Account is not active".to_string(),
        });
    }

    // Generate new access token
    let access_token = state.auth_service.generate_access_token(&principal)?;

    // Generate new refresh token (rotation)
    let (raw_token, token_entity) = RefreshToken::generate_token_pair(&principal.id);
    let token_entity = token_entity
        .with_accessible_clients(stored_token.accessible_clients.clone());

    state.refresh_token_repo.insert(&token_entity).await?;

    Ok(Json(LoginResponse {
        access_token,
        token_type: "Bearer".to_string(),
        expires_in: 3600,
        refresh_token: Some(raw_token),
    }))
}

/// Create the auth router
pub fn auth_router(state: AuthState) -> Router {
    Router::new()
        .route("/login", post(login))
        .route("/logout", post(logout))
        .route("/check-domain", get(check_domain))
        .route("/me", get(get_current_user))
        .route("/refresh", post(refresh_token))
        .with_state(state)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_login_request_deserialization() {
        let json = r#"{"email":"test@example.com","password":"secret","rememberMe":true}"#;
        let req: LoginRequest = serde_json::from_str(json).unwrap();
        assert_eq!(req.email, "test@example.com");
        assert_eq!(req.password, "secret");
        assert!(req.remember_me);
    }

    #[test]
    fn test_login_response_serialization() {
        let response = LoginResponse {
            access_token: "token123".to_string(),
            token_type: "Bearer".to_string(),
            expires_in: 3600,
            refresh_token: None,
        };

        let json = serde_json::to_string(&response).unwrap();
        assert!(json.contains("accessToken"));
        assert!(json.contains("tokenType"));
        assert!(json.contains("expiresIn"));
    }

    #[test]
    fn test_auth_method_serialization() {
        assert_eq!(
            serde_json::to_string(&AuthMethod::Internal).unwrap(),
            "\"INTERNAL\""
        );
        assert_eq!(
            serde_json::to_string(&AuthMethod::Oidc).unwrap(),
            "\"OIDC\""
        );
    }

    #[test]
    fn test_domain_extraction() {
        let email = "user@example.com";
        let domain = email.split('@').nth(1).unwrap_or("");
        assert_eq!(domain, "example.com");
    }
}
