//! API Middleware
//!
//! Authentication and authorization middleware for Axum.

use axum::{
    async_trait,
    extract::FromRequestParts,
    http::{header::AUTHORIZATION, request::Parts, StatusCode, HeaderValue},
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

/// Authenticated user extractor
/// Validates JWT and extracts AuthContext from the request
pub struct Authenticated(pub AuthContext);

impl std::ops::Deref for Authenticated {
    type Target = AuthContext;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

/// Error response for authentication failures
pub struct AuthError {
    pub status: StatusCode,
    pub message: String,
}

impl IntoResponse for AuthError {
    fn into_response(self) -> Response {
        let body = ApiError {
            error: "UNAUTHORIZED".to_string(),
            message: self.message,
            details: None,
        };
        (self.status, Json(body)).into_response()
    }
}

#[async_trait]
impl<S> FromRequestParts<S> for Authenticated
where
    S: Send + Sync,
{
    type Rejection = AuthError;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        // Get AppState from extensions (set by middleware layer)
        let app_state = parts.extensions.get::<AppState>()
            .ok_or_else(|| AuthError {
                status: StatusCode::INTERNAL_SERVER_ERROR,
                message: "Auth service not configured".to_string(),
            })?;

        // Extract authorization header
        let auth_header = parts.headers
            .get(AUTHORIZATION)
            .and_then(|v: &HeaderValue| v.to_str().ok())
            .ok_or_else(|| AuthError {
                status: StatusCode::UNAUTHORIZED,
                message: "Missing Authorization header".to_string(),
            })?;

        // Extract bearer token
        let token = crate::service::extract_bearer_token(auth_header)
            .ok_or_else(|| AuthError {
                status: StatusCode::UNAUTHORIZED,
                message: "Invalid Authorization header format".to_string(),
            })?;

        // Validate token
        let claims = app_state.auth_service.validate_token(token)
            .map_err(|e: crate::error::PlatformError| AuthError {
                status: StatusCode::UNAUTHORIZED,
                message: e.to_string(),
            })?;

        // Build auth context with resolved permissions
        let context = app_state.authz_service.build_context(&claims).await
            .map_err(|e: crate::error::PlatformError| AuthError {
                status: StatusCode::UNAUTHORIZED,
                message: e.to_string(),
            })?;

        Ok(Authenticated(context))
    }
}

/// Optional authentication extractor
/// Tries to validate JWT but allows unauthenticated requests
pub struct OptionalAuth(pub Option<AuthContext>);

impl std::ops::Deref for OptionalAuth {
    type Target = Option<AuthContext>;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

#[async_trait]
impl<S> FromRequestParts<S> for OptionalAuth
where
    S: Send + Sync,
{
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        // Get AppState from extensions
        let Some(app_state) = parts.extensions.get::<AppState>() else {
            return Ok(OptionalAuth(None));
        };

        // Try to get authorization header
        let Some(auth_header) = parts.headers.get(AUTHORIZATION).and_then(|v| v.to_str().ok()) else {
            return Ok(OptionalAuth(None));
        };

        // Try to extract bearer token
        let Some(token) = crate::service::extract_bearer_token(auth_header) else {
            return Ok(OptionalAuth(None));
        };

        // Try to validate token
        let Ok(claims) = app_state.auth_service.validate_token(token) else {
            return Ok(OptionalAuth(None));
        };

        // Try to build context
        let Ok(context) = app_state.authz_service.build_context(&claims).await else {
            return Ok(OptionalAuth(None));
        };

        Ok(OptionalAuth(Some(context)))
    }
}

/// Middleware layer that injects AppState into request extensions
/// This enables the Authenticated extractor to work
use tower::Layer;
use tower::Service;
use std::task::{Context, Poll};
use std::future::Future;
use std::pin::Pin;

#[derive(Clone)]
pub struct AuthLayer {
    state: AppState,
}

impl AuthLayer {
    pub fn new(state: AppState) -> Self {
        Self { state }
    }
}

impl<S> Layer<S> for AuthLayer {
    type Service = AuthMiddleware<S>;

    fn layer(&self, inner: S) -> Self::Service {
        AuthMiddleware {
            inner,
            state: self.state.clone(),
        }
    }
}

#[derive(Clone)]
pub struct AuthMiddleware<S> {
    inner: S,
    state: AppState,
}

impl<S, B> Service<axum::http::Request<B>> for AuthMiddleware<S>
where
    S: Service<axum::http::Request<B>, Response = Response> + Send + Clone + 'static,
    S::Future: Send + 'static,
    B: Send + 'static,
{
    type Response = S::Response;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, mut req: axum::http::Request<B>) -> Self::Future {
        // Insert AppState into request extensions
        req.extensions_mut().insert(self.state.clone());

        let future = self.inner.call(req);
        Box::pin(async move { future.await })
    }
}
