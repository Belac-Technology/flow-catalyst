//! API Middleware
//!
//! Authentication and authorization middleware for Salvo.

use salvo::prelude::*;
use salvo::http::header::AUTHORIZATION;
use std::sync::Arc;
use crate::service::{AuthService, AuthorizationService, AuthContext};
use crate::api::common::ApiError;

/// Application state containing shared services
#[derive(Clone)]
pub struct AppState {
    pub auth_service: Arc<AuthService>,
    pub authz_service: Arc<AuthorizationService>,
}

/// Authentication middleware handler
/// Validates JWT and injects AuthContext into depot
pub struct AuthHandler {
    pub app_state: AppState,
}

impl AuthHandler {
    pub fn new(app_state: AppState) -> Self {
        Self { app_state }
    }
}

#[async_trait]
impl Handler for AuthHandler {
    async fn handle(&self, req: &mut Request, depot: &mut Depot, res: &mut Response, ctrl: &mut FlowCtrl) {
        // Extract authorization header
        let auth_header = match req.headers().get(AUTHORIZATION).and_then(|v| v.to_str().ok()) {
            Some(h) => h,
            None => {
                let error = ApiError {
                    error: "UNAUTHORIZED".to_string(),
                    message: "Missing Authorization header".to_string(),
                    details: None,
                };
                res.status_code(StatusCode::UNAUTHORIZED);
                res.render(Json(error));
                ctrl.skip_rest();
                return;
            }
        };

        // Extract bearer token
        let token = match crate::service::extract_bearer_token(auth_header) {
            Some(t) => t,
            None => {
                let error = ApiError {
                    error: "UNAUTHORIZED".to_string(),
                    message: "Invalid Authorization header format".to_string(),
                    details: None,
                };
                res.status_code(StatusCode::UNAUTHORIZED);
                res.render(Json(error));
                ctrl.skip_rest();
                return;
            }
        };

        // Validate token
        let claims = match self.app_state.auth_service.validate_token(token) {
            Ok(c) => c,
            Err(e) => {
                let error = ApiError {
                    error: "UNAUTHORIZED".to_string(),
                    message: e.to_string(),
                    details: None,
                };
                res.status_code(StatusCode::UNAUTHORIZED);
                res.render(Json(error));
                ctrl.skip_rest();
                return;
            }
        };

        // Build auth context with resolved permissions
        let context = match self.app_state.authz_service.build_context(&claims).await {
            Ok(ctx) => ctx,
            Err(e) => {
                let error = ApiError {
                    error: "UNAUTHORIZED".to_string(),
                    message: e.to_string(),
                    details: None,
                };
                res.status_code(StatusCode::UNAUTHORIZED);
                res.render(Json(error));
                ctrl.skip_rest();
                return;
            }
        };

        // Store auth context in depot for handlers to access
        depot.inject(context);
        ctrl.call_next(req, depot, res).await;
    }
}

/// Optional authentication middleware handler
/// Tries to validate JWT but allows unauthenticated requests
pub struct OptionalAuthHandler {
    pub app_state: AppState,
}

impl OptionalAuthHandler {
    pub fn new(app_state: AppState) -> Self {
        Self { app_state }
    }
}

#[async_trait]
impl Handler for OptionalAuthHandler {
    async fn handle(&self, req: &mut Request, depot: &mut Depot, res: &mut Response, ctrl: &mut FlowCtrl) {
        // Try to get authorization header
        if let Some(auth_header) = req.headers().get(AUTHORIZATION).and_then(|v| v.to_str().ok()) {
            // Try to extract bearer token
            if let Some(token) = crate::service::extract_bearer_token(auth_header) {
                // Try to validate token
                if let Ok(claims) = self.app_state.auth_service.validate_token(token) {
                    // Try to build context
                    if let Ok(context) = self.app_state.authz_service.build_context(&claims).await {
                        depot.inject(context);
                    }
                }
            }
        }

        ctrl.call_next(req, depot, res).await;
    }
}

/// Helper trait to get auth context from depot
pub trait AuthExt {
    fn auth_context(&self) -> Option<&AuthContext>;
    fn require_auth(&self) -> Result<&AuthContext, crate::error::PlatformError>;
}

impl AuthExt for Depot {
    fn auth_context(&self) -> Option<&AuthContext> {
        self.obtain::<AuthContext>().ok()
    }

    fn require_auth(&self) -> Result<&AuthContext, crate::error::PlatformError> {
        self.obtain::<AuthContext>()
            .map_err(|_| crate::error::PlatformError::unauthorized("Authentication required"))
    }
}

/// Wrapper types for backwards compatibility
/// These can be used in function signatures
pub struct Authenticated(pub AuthContext);

impl Authenticated {
    pub fn from_depot(depot: &Depot) -> Result<Self, crate::error::PlatformError> {
        depot.require_auth().map(|ctx| Authenticated(ctx.clone()))
    }
}

pub struct OptionalAuth(pub Option<AuthContext>);

impl OptionalAuth {
    pub fn from_depot(depot: &Depot) -> Self {
        OptionalAuth(depot.auth_context().cloned())
    }
}
