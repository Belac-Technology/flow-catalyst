//! Audit Log Entity
//!
//! Records all significant actions in the platform for compliance and debugging.

use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use bson::serde_helpers::chrono_datetime_as_bson_datetime;

/// Audit action type
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AuditAction {
    /// Entity created
    Create,
    /// Entity updated
    Update,
    /// Entity deleted
    Delete,
    /// Entity archived
    Archive,
    /// Login attempt
    Login,
    /// Logout
    Logout,
    /// Token issued
    TokenIssued,
    /// Token revoked
    TokenRevoked,
    /// Permission granted
    PermissionGranted,
    /// Permission revoked
    PermissionRevoked,
    /// Role assigned
    RoleAssigned,
    /// Role unassigned
    RoleUnassigned,
    /// Client access granted
    ClientAccessGranted,
    /// Client access revoked
    ClientAccessRevoked,
    /// Subscription paused
    SubscriptionPaused,
    /// Subscription resumed
    SubscriptionResumed,
    /// Dispatch pool paused
    PoolPaused,
    /// Dispatch pool resumed
    PoolResumed,
    /// Configuration changed
    ConfigChanged,
    /// Other custom action
    Other,
}

/// Audit log entry
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuditLog {
    /// TSID as Crockford Base32 string
    #[serde(rename = "_id")]
    pub id: String,

    /// Action performed
    pub action: AuditAction,

    /// Entity type affected (e.g., "Client", "Principal", "Role")
    pub entity_type: String,

    /// Entity ID affected
    #[serde(skip_serializing_if = "Option::is_none")]
    pub entity_id: Option<String>,

    /// Description of the action
    pub description: String,

    /// Principal who performed the action
    #[serde(skip_serializing_if = "Option::is_none")]
    pub principal_id: Option<String>,

    /// Principal email (denormalized for display)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub principal_email: Option<String>,

    /// Client context (if applicable)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,

    /// IP address of the request
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ip_address: Option<String>,

    /// User agent string
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user_agent: Option<String>,

    /// Request ID for correlation
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,

    /// Additional context data
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metadata: Option<serde_json::Value>,

    /// Previous state (for updates)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub previous_state: Option<serde_json::Value>,

    /// New state (for updates)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub new_state: Option<serde_json::Value>,

    /// Timestamp
    pub created_at: DateTime<Utc>,
}

impl AuditLog {
    pub fn new(
        action: AuditAction,
        entity_type: impl Into<String>,
        description: impl Into<String>,
    ) -> Self {
        Self {
            id: crate::TsidGenerator::generate(),
            action,
            entity_type: entity_type.into(),
            entity_id: None,
            description: description.into(),
            principal_id: None,
            principal_email: None,
            client_id: None,
            ip_address: None,
            user_agent: None,
            request_id: None,
            metadata: None,
            previous_state: None,
            new_state: None,
            created_at: Utc::now(),
        }
    }

    pub fn for_entity(
        action: AuditAction,
        entity_type: impl Into<String>,
        entity_id: impl Into<String>,
        description: impl Into<String>,
    ) -> Self {
        Self {
            id: crate::TsidGenerator::generate(),
            action,
            entity_type: entity_type.into(),
            entity_id: Some(entity_id.into()),
            description: description.into(),
            principal_id: None,
            principal_email: None,
            client_id: None,
            ip_address: None,
            user_agent: None,
            request_id: None,
            metadata: None,
            previous_state: None,
            new_state: None,
            created_at: Utc::now(),
        }
    }

    pub fn with_principal(mut self, principal_id: impl Into<String>, email: Option<String>) -> Self {
        self.principal_id = Some(principal_id.into());
        self.principal_email = email;
        self
    }

    pub fn with_client(mut self, client_id: impl Into<String>) -> Self {
        self.client_id = Some(client_id.into());
        self
    }

    pub fn with_request_context(
        mut self,
        request_id: Option<String>,
        ip_address: Option<String>,
        user_agent: Option<String>,
    ) -> Self {
        self.request_id = request_id;
        self.ip_address = ip_address;
        self.user_agent = user_agent;
        self
    }

    pub fn with_metadata(mut self, metadata: serde_json::Value) -> Self {
        self.metadata = Some(metadata);
        self
    }

    pub fn with_state_change(
        mut self,
        previous: Option<serde_json::Value>,
        new: Option<serde_json::Value>,
    ) -> Self {
        self.previous_state = previous;
        self.new_state = new;
        self
    }
}

/// Audit log builder for fluent construction
pub struct AuditLogBuilder {
    action: AuditAction,
    entity_type: String,
    entity_id: Option<String>,
    description: String,
    principal_id: Option<String>,
    principal_email: Option<String>,
    client_id: Option<String>,
    ip_address: Option<String>,
    user_agent: Option<String>,
    request_id: Option<String>,
    metadata: Option<serde_json::Value>,
}

impl AuditLogBuilder {
    pub fn new(action: AuditAction, entity_type: impl Into<String>) -> Self {
        Self {
            action,
            entity_type: entity_type.into(),
            entity_id: None,
            description: String::new(),
            principal_id: None,
            principal_email: None,
            client_id: None,
            ip_address: None,
            user_agent: None,
            request_id: None,
            metadata: None,
        }
    }

    pub fn entity_id(mut self, id: impl Into<String>) -> Self {
        self.entity_id = Some(id.into());
        self
    }

    pub fn description(mut self, desc: impl Into<String>) -> Self {
        self.description = desc.into();
        self
    }

    pub fn principal(mut self, id: impl Into<String>, email: Option<String>) -> Self {
        self.principal_id = Some(id.into());
        self.principal_email = email;
        self
    }

    pub fn client(mut self, id: impl Into<String>) -> Self {
        self.client_id = Some(id.into());
        self
    }

    pub fn ip(mut self, addr: impl Into<String>) -> Self {
        self.ip_address = Some(addr.into());
        self
    }

    pub fn build(self) -> AuditLog {
        AuditLog {
            id: crate::TsidGenerator::generate(),
            action: self.action,
            entity_type: self.entity_type,
            entity_id: self.entity_id,
            description: self.description,
            principal_id: self.principal_id,
            principal_email: self.principal_email,
            client_id: self.client_id,
            ip_address: self.ip_address,
            user_agent: self.user_agent,
            request_id: self.request_id,
            metadata: self.metadata,
            previous_state: None,
            new_state: None,
            created_at: Utc::now(),
        }
    }
}
