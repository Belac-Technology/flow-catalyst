//! Repository Layer
//!
//! MongoDB repositories for all domain entities.

pub mod event;
pub mod event_type;
pub mod dispatch_job;
pub mod dispatch_pool;
pub mod subscription;
pub mod service_account;
pub mod principal;
pub mod client;
pub mod application;
pub mod role;
pub mod oauth_client;
pub mod auth_config;
pub mod audit_log;
pub mod oidc_login_state;
pub mod refresh_token;
pub mod authorization_code;
pub mod indexes;
pub mod application_client_config;

pub use event::EventRepository;
pub use event_type::EventTypeRepository;
pub use dispatch_job::DispatchJobRepository;
pub use dispatch_pool::DispatchPoolRepository;
pub use subscription::SubscriptionRepository;
pub use service_account::ServiceAccountRepository;
pub use principal::PrincipalRepository;
pub use client::ClientRepository;
pub use application::ApplicationRepository;
pub use role::RoleRepository;
pub use oauth_client::OAuthClientRepository;
pub use auth_config::{
    AnchorDomainRepository,
    ClientAuthConfigRepository,
    ClientAccessGrantRepository,
    IdpRoleMappingRepository,
};
pub use audit_log::AuditLogRepository;
pub use oidc_login_state::OidcLoginStateRepository;
pub use refresh_token::RefreshTokenRepository;
pub use authorization_code::AuthorizationCodeRepository;
pub use application_client_config::ApplicationClientConfigRepository;
