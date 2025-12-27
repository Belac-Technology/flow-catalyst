//! Role and Permission Entities
//!
//! Authorization model for role-based access control.

use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use std::collections::HashSet;

/// Role source - where the role definition came from
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RoleSource {
    /// Defined in code (cannot be modified)
    Code,
    /// Defined in database (can be modified)
    Database,
    /// Synced from external SDK/IDP
    Sdk,
}

impl Default for RoleSource {
    fn default() -> Self {
        Self::Database
    }
}

/// Permission definition
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Permission {
    /// Permission string (e.g., "orders:read", "users:write")
    pub permission: String,

    /// Human-readable name
    pub name: String,

    /// Description
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,

    /// Category for grouping in UI
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
}

impl Permission {
    pub fn new(permission: impl Into<String>, name: impl Into<String>) -> Self {
        Self {
            permission: permission.into(),
            name: name.into(),
            description: None,
            category: None,
        }
    }

    pub fn with_description(mut self, description: impl Into<String>) -> Self {
        self.description = Some(description.into());
        self
    }

    pub fn with_category(mut self, category: impl Into<String>) -> Self {
        self.category = Some(category.into());
        self
    }
}

/// Role definition
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthRole {
    /// TSID as Crockford Base32 string
    #[serde(rename = "_id")]
    pub id: String,

    /// Role code/name (unique per application)
    /// Format: {application}:{role_name} e.g., "orders:admin"
    pub code: String,

    /// Human-readable display name
    pub display_name: String,

    /// Description
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,

    /// Application this role belongs to
    pub application_code: String,

    /// Permissions granted by this role
    #[serde(default)]
    pub permissions: HashSet<String>,

    /// Where the role came from
    #[serde(default)]
    pub source: RoleSource,

    /// Whether clients can manage this role
    #[serde(default)]
    pub client_managed: bool,

    /// Audit fields
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub created_by: Option<String>,
}

impl AuthRole {
    pub fn new(
        application_code: impl Into<String>,
        role_name: impl Into<String>,
        display_name: impl Into<String>,
    ) -> Self {
        let app = application_code.into();
        let name = role_name.into();
        let now = Utc::now();

        Self {
            id: crate::TsidGenerator::generate(),
            code: format!("{}:{}", app, name),
            display_name: display_name.into(),
            description: None,
            application_code: app,
            permissions: HashSet::new(),
            source: RoleSource::Database,
            client_managed: false,
            created_at: now,
            updated_at: now,
            created_by: None,
        }
    }

    pub fn with_description(mut self, description: impl Into<String>) -> Self {
        self.description = Some(description.into());
        self
    }

    pub fn with_permission(mut self, permission: impl Into<String>) -> Self {
        self.permissions.insert(permission.into());
        self
    }

    pub fn with_permissions(mut self, permissions: impl IntoIterator<Item = impl Into<String>>) -> Self {
        for p in permissions {
            self.permissions.insert(p.into());
        }
        self
    }

    pub fn with_source(mut self, source: RoleSource) -> Self {
        self.source = source;
        self
    }

    pub fn with_client_managed(mut self, client_managed: bool) -> Self {
        self.client_managed = client_managed;
        self
    }

    pub fn grant_permission(&mut self, permission: impl Into<String>) {
        self.permissions.insert(permission.into());
        self.updated_at = Utc::now();
    }

    pub fn revoke_permission(&mut self, permission: &str) {
        self.permissions.remove(permission);
        self.updated_at = Utc::now();
    }

    pub fn has_permission(&self, permission: &str) -> bool {
        self.permissions.contains(permission) || self.has_wildcard_permission(permission)
    }

    /// Check for wildcard permissions (e.g., "orders:*" matches "orders:read")
    fn has_wildcard_permission(&self, permission: &str) -> bool {
        let parts: Vec<&str> = permission.split(':').collect();
        if parts.len() < 2 {
            return false;
        }

        // Check for resource:* pattern
        let wildcard = format!("{}:*", parts[0]);
        if self.permissions.contains(&wildcard) {
            return true;
        }

        // Check for *:* (superuser)
        self.permissions.contains("*:*")
    }

    pub fn can_modify(&self) -> bool {
        self.source == RoleSource::Database
    }

    /// Extract role name from code
    pub fn role_name(&self) -> &str {
        self.code.split(':').nth(1).unwrap_or(&self.code)
    }
}

/// Common platform permissions
pub mod permissions {
    // Event permissions
    pub const EVENTS_READ: &str = "events:read";
    pub const EVENTS_WRITE: &str = "events:write";

    // Event type permissions
    pub const EVENT_TYPES_READ: &str = "event-types:read";
    pub const EVENT_TYPES_WRITE: &str = "event-types:write";
    pub const EVENT_TYPES_DELETE: &str = "event-types:delete";

    // Dispatch job permissions
    pub const DISPATCH_JOBS_READ: &str = "dispatch-jobs:read";
    pub const DISPATCH_JOBS_WRITE: &str = "dispatch-jobs:write";

    // Subscription permissions
    pub const SUBSCRIPTIONS_READ: &str = "subscriptions:read";
    pub const SUBSCRIPTIONS_WRITE: &str = "subscriptions:write";
    pub const SUBSCRIPTIONS_DELETE: &str = "subscriptions:delete";

    // User management permissions
    pub const USERS_READ: &str = "users:read";
    pub const USERS_WRITE: &str = "users:write";
    pub const USERS_DELETE: &str = "users:delete";

    // Role management permissions
    pub const ROLES_READ: &str = "roles:read";
    pub const ROLES_WRITE: &str = "roles:write";
    pub const ROLES_DELETE: &str = "roles:delete";

    // Client management permissions
    pub const CLIENTS_READ: &str = "clients:read";
    pub const CLIENTS_WRITE: &str = "clients:write";
    pub const CLIENTS_DELETE: &str = "clients:delete";

    // Application permissions
    pub const APPLICATIONS_READ: &str = "applications:read";
    pub const APPLICATIONS_WRITE: &str = "applications:write";
    pub const APPLICATIONS_DELETE: &str = "applications:delete";

    // Admin/anchor permissions
    pub const ADMIN_ALL: &str = "*:*";
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_permission_matching() {
        let role = AuthRole::new("orders", "admin", "Orders Admin")
            .with_permission("orders:read")
            .with_permission("orders:write")
            .with_permission("users:*");

        assert!(role.has_permission("orders:read"));
        assert!(role.has_permission("orders:write"));
        assert!(!role.has_permission("orders:delete"));

        // Wildcard matching
        assert!(role.has_permission("users:read"));
        assert!(role.has_permission("users:write"));
        assert!(role.has_permission("users:delete"));
    }

    #[test]
    fn test_superuser_permission() {
        let role = AuthRole::new("platform", "superuser", "Superuser")
            .with_permission("*:*");

        assert!(role.has_permission("orders:read"));
        assert!(role.has_permission("users:delete"));
        assert!(role.has_permission("anything:everything"));
    }
}
