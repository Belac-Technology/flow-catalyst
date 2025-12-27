//! API Middleware
//!
//! Authentication and authorization middleware for Axum.

use axum::{
    extract::FromRequestParts,
    http::{request::Parts, StatusCode, header::AUTHORIZATION},
    response::{IntoResponse, Response},
    Json,
};
use std::sync::Arc;
use crate::service::{AuthService, AuthorizationService, AuthContext};
use crate::api::common::ApiError;

/// Application state containing shared services
#[derive(Clone)]
pub struct AppState {
    pub auth_service: Arc<AuthService>,
    pub authz_service: Arc<AuthorizationService>,
}

/// Extractor for authenticated requests
/// Validates JWT and builds AuthContext with resolved permissions
pub struct Authenticated(pub AuthContext);

#[axum::async_trait]
impl<S> FromRequestParts<S> for Authenticated
where
    S: Send + Sync,
{
    type Rejection = Response;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        // Extract authorization header
        let auth_header = parts
            .headers
            .get(AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| {
                let error = ApiError {
                    error: "UNAUTHORIZED".to_string(),
                    message: "Missing Authorization header".to_string(),
                    details: None,
                };
                (StatusCode::UNAUTHORIZED, Json(error)).into_response()
            })?;

        // Extract bearer token
        let token = crate::service::extract_bearer_token(auth_header)
            .ok_or_else(|| {
                let error = ApiError {
                    error: "UNAUTHORIZED".to_string(),
                    message: "Invalid Authorization header format".to_string(),
                    details: None,
                };
                (StatusCode::UNAUTHORIZED, Json(error)).into_response()
            })?;

        // Get app state (this requires the state to be Arc<AppState> or similar)
        // For now, we'll use a simpler approach with Extension
        let app_state = parts
            .extensions
            .get::<AppState>()
            .ok_or_else(|| {
                let error = ApiError {
                    error: "INTERNAL_ERROR".to_string(),
                    message: "AppState not found".to_string(),
                    details: None,
                };
                (StatusCode::INTERNAL_SERVER_ERROR, Json(error)).into_response()
            })?;

        // Validate token
        let claims = app_state
            .auth_service
            .validate_token(token)
            .map_err(|e| e.into_response())?;

        // Build auth context with resolved permissions
        let context = app_state
            .authz_service
            .build_context(&claims)
            .await
            .map_err(|e| e.into_response())?;

        Ok(Authenticated(context))
    }
}

/// Extractor for optionally authenticated requests
pub struct OptionalAuth(pub Option<AuthContext>);

#[axum::async_trait]
impl<S> FromRequestParts<S> for OptionalAuth
where
    S: Send + Sync,
{
    type Rejection = Response;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        // Try to get authorization header
        let auth_header = match parts.headers.get(AUTHORIZATION).and_then(|v| v.to_str().ok()) {
            Some(h) => h,
            None => return Ok(OptionalAuth(None)),
        };

        // Try to extract bearer token
        let token = match crate::service::extract_bearer_token(auth_header) {
            Some(t) => t,
            None => return Ok(OptionalAuth(None)),
        };

        // Try to get app state
        let app_state = match parts.extensions.get::<AppState>() {
            Some(s) => s,
            None => return Ok(OptionalAuth(None)),
        };

        // Try to validate token
        let claims = match app_state.auth_service.validate_token(token) {
            Ok(c) => c,
            Err(_) => return Ok(OptionalAuth(None)),
        };

        // Try to build context
        match app_state.authz_service.build_context(&claims).await {
            Ok(ctx) => Ok(OptionalAuth(Some(ctx))),
            Err(_) => Ok(OptionalAuth(None)),
        }
    }
}

/// Extension trait for AuthContext to add to request extensions
pub trait AuthContextExt {
    fn with_auth_context(self, ctx: AuthContext) -> Self;
}
