//! Audit Service
//!
//! Provides centralized audit logging for all platform mutations.

use std::sync::Arc;
use tracing::{info, error};

use crate::domain::{AuditLog, AuditAction};
use crate::repository::AuditLogRepository;
use crate::service::authorization::AuthContext;
use crate::error::Result;

/// Audit service for recording platform actions
#[derive(Clone)]
pub struct AuditService {
    repo: Arc<AuditLogRepository>,
}

impl AuditService {
    pub fn new(repo: Arc<AuditLogRepository>) -> Self {
        Self { repo }
    }

    /// Log a create action
    pub async fn log_create(
        &self,
        auth: &AuthContext,
        entity_type: &str,
        entity_id: &str,
        description: impl Into<String>,
    ) -> Result<()> {
        let log = self.build_log(auth, AuditAction::Create, entity_type, Some(entity_id), description);
        self.insert(log).await
    }

    /// Log an update action
    pub async fn log_update(
        &self,
        auth: &AuthContext,
        entity_type: &str,
        entity_id: &str,
        description: impl Into<String>,
    ) -> Result<()> {
        let log = self.build_log(auth, AuditAction::Update, entity_type, Some(entity_id), description);
        self.insert(log).await
    }

    /// Log a delete action
    pub async fn log_delete(
        &self,
        auth: &AuthContext,
        entity_type: &str,
        entity_id: &str,
        description: impl Into<String>,
    ) -> Result<()> {
        let log = self.build_log(auth, AuditAction::Delete, entity_type, Some(entity_id), description);
        self.insert(log).await
    }

    /// Log an archive action
    pub async fn log_archive(
        &self,
        auth: &AuthContext,
        entity_type: &str,
        entity_id: &str,
        description: impl Into<String>,
    ) -> Result<()> {
        let log = self.build_log(auth, AuditAction::Archive, entity_type, Some(entity_id), description);
        self.insert(log).await
    }

    /// Log a role assignment
    pub async fn log_role_assigned(
        &self,
        auth: &AuthContext,
        principal_id: &str,
        role: &str,
        client_id: Option<&str>,
    ) -> Result<()> {
        let desc = match client_id {
            Some(cid) => format!("Assigned role '{}' for client {}", role, cid),
            None => format!("Assigned global role '{}'", role),
        };
        let log = self.build_log(auth, AuditAction::RoleAssigned, "Principal", Some(principal_id), desc);
        self.insert(log).await
    }

    /// Log a role removal
    pub async fn log_role_unassigned(
        &self,
        auth: &AuthContext,
        principal_id: &str,
        role: &str,
    ) -> Result<()> {
        let desc = format!("Removed role '{}'", role);
        let log = self.build_log(auth, AuditAction::RoleUnassigned, "Principal", Some(principal_id), desc);
        self.insert(log).await
    }

    /// Log client access granted
    pub async fn log_client_access_granted(
        &self,
        auth: &AuthContext,
        principal_id: &str,
        client_id: &str,
    ) -> Result<()> {
        let desc = format!("Granted access to client {}", client_id);
        let log = self.build_log(auth, AuditAction::ClientAccessGranted, "Principal", Some(principal_id), desc);
        self.insert(log).await
    }

    /// Log client access revoked
    pub async fn log_client_access_revoked(
        &self,
        auth: &AuthContext,
        principal_id: &str,
        client_id: &str,
    ) -> Result<()> {
        let desc = format!("Revoked access to client {}", client_id);
        let log = self.build_log(auth, AuditAction::ClientAccessRevoked, "Principal", Some(principal_id), desc);
        self.insert(log).await
    }

    /// Log subscription paused
    pub async fn log_subscription_paused(
        &self,
        auth: &AuthContext,
        subscription_id: &str,
        subscription_code: &str,
    ) -> Result<()> {
        let desc = format!("Paused subscription '{}'", subscription_code);
        let log = self.build_log(auth, AuditAction::SubscriptionPaused, "Subscription", Some(subscription_id), desc);
        self.insert(log).await
    }

    /// Log subscription resumed
    pub async fn log_subscription_resumed(
        &self,
        auth: &AuthContext,
        subscription_id: &str,
        subscription_code: &str,
    ) -> Result<()> {
        let desc = format!("Resumed subscription '{}'", subscription_code);
        let log = self.build_log(auth, AuditAction::SubscriptionResumed, "Subscription", Some(subscription_id), desc);
        self.insert(log).await
    }

    /// Log dispatch pool paused
    pub async fn log_pool_paused(
        &self,
        auth: &AuthContext,
        pool_id: &str,
        pool_code: &str,
    ) -> Result<()> {
        let desc = format!("Paused dispatch pool '{}'", pool_code);
        let log = self.build_log(auth, AuditAction::PoolPaused, "DispatchPool", Some(pool_id), desc);
        self.insert(log).await
    }

    /// Log dispatch pool resumed
    pub async fn log_pool_resumed(
        &self,
        auth: &AuthContext,
        pool_id: &str,
        pool_code: &str,
    ) -> Result<()> {
        let desc = format!("Resumed dispatch pool '{}'", pool_code);
        let log = self.build_log(auth, AuditAction::PoolResumed, "DispatchPool", Some(pool_id), desc);
        self.insert(log).await
    }

    /// Log a login attempt
    pub async fn log_login(
        &self,
        email: &str,
        success: bool,
        ip_address: Option<&str>,
    ) -> Result<()> {
        let desc = if success {
            format!("Successful login for {}", email)
        } else {
            format!("Failed login attempt for {}", email)
        };

        let mut log = AuditLog::new(AuditAction::Login, "Session", desc);
        if let Some(ip) = ip_address {
            log = log.with_request_context(None, Some(ip.to_string()), None);
        }
        self.insert(log).await
    }

    /// Log a logout
    pub async fn log_logout(&self, auth: &AuthContext) -> Result<()> {
        let log = self.build_log(auth, AuditAction::Logout, "Session", None, "User logged out");
        self.insert(log).await
    }

    /// Log a token issued
    pub async fn log_token_issued(
        &self,
        auth: &AuthContext,
        token_type: &str,
    ) -> Result<()> {
        let desc = format!("{} token issued", token_type);
        let log = self.build_log(auth, AuditAction::TokenIssued, "Token", None, desc);
        self.insert(log).await
    }

    /// Log a token revoked
    pub async fn log_token_revoked(
        &self,
        auth: &AuthContext,
        token_type: &str,
    ) -> Result<()> {
        let desc = format!("{} token revoked", token_type);
        let log = self.build_log(auth, AuditAction::TokenRevoked, "Token", None, desc);
        self.insert(log).await
    }

    /// Log a configuration change
    pub async fn log_config_changed(
        &self,
        auth: &AuthContext,
        config_type: &str,
        description: impl Into<String>,
    ) -> Result<()> {
        let log = self.build_log(auth, AuditAction::ConfigChanged, config_type, None, description);
        self.insert(log).await
    }

    /// Build an audit log from auth context
    fn build_log(
        &self,
        auth: &AuthContext,
        action: AuditAction,
        entity_type: &str,
        entity_id: Option<&str>,
        description: impl Into<String>,
    ) -> AuditLog {
        let mut log = match entity_id {
            Some(id) => AuditLog::for_entity(action, entity_type, id, description),
            None => AuditLog::new(action, entity_type, description),
        };

        log = log.with_principal(&auth.principal_id, auth.email.clone());

        // Use first accessible client if not anchor (anchor has "*")
        if !auth.is_anchor() {
            if let Some(client_id) = auth.accessible_clients.first() {
                if client_id != "*" {
                    log = log.with_client(client_id);
                }
            }
        }

        log
    }

    /// Insert an audit log
    async fn insert(&self, log: AuditLog) -> Result<()> {
        info!(
            action = ?log.action,
            entity_type = %log.entity_type,
            entity_id = ?log.entity_id,
            principal_id = ?log.principal_id,
            "Audit log recorded"
        );

        if let Err(e) = self.repo.insert(&log).await {
            error!(error = %e, "Failed to insert audit log");
            // Don't fail the operation if audit logging fails
            // but log the error for monitoring
        }

        Ok(())
    }
}
