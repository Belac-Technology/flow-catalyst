//! Service Layer
//!
//! Business logic services for the platform.
//! Includes authentication, authorization, event processing, and dispatch services.

pub mod audit;
pub mod auth;
pub mod authorization;
pub mod dispatch;
pub mod projections;
pub mod oidc;
pub mod oidc_sync;
pub mod password;

pub use audit::AuditService;
pub use auth::{AuthService, AuthConfig, AccessTokenClaims, extract_bearer_token};
pub use authorization::{AuthorizationService, AuthContext, checks};
pub use dispatch::{
    DispatchScheduler, DispatchConfig, EventDispatcher,
    BlockOnErrorChecker, StaleQueuedJobPoller, BlockedMessageGroup,
};
pub use projections::{EventProjectionWriter, DispatchJobProjectionWriter};
pub use oidc::{OidcService, OidcProviderConfig, IdTokenClaims, TokenResponse};
pub use oidc_sync::{OidcSyncService, IDP_SYNC_SOURCE};
pub use password::{PasswordService, PasswordPolicy, Argon2Config, PasswordResetToken};
