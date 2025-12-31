//! Principal Domain Events

use serde::{Deserialize, Serialize};
use crate::usecase::ExecutionContext;
use crate::usecase::domain_event::EventMetadata;
use crate::TsidGenerator;
use crate::impl_domain_event;
use crate::domain::UserScope;

/// Event emitted when a new user is created.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UserCreated {
    #[serde(flatten)]
    pub metadata: EventMetadata,

    pub principal_id: String,
    pub email: String,
    pub name: String,
    pub scope: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
}

impl_domain_event!(UserCreated);

impl UserCreated {
    const EVENT_TYPE: &'static str = "platform:iam:user:created";
    const SPEC_VERSION: &'static str = "1.0";
    const SOURCE: &'static str = "platform:iam";

    pub fn new(
        ctx: &ExecutionContext,
        principal_id: &str,
        email: &str,
        name: &str,
        scope: UserScope,
        client_id: Option<&str>,
    ) -> Self {
        let event_id = TsidGenerator::generate();
        let subject = format!("platform.user.{}", principal_id);
        let message_group = format!("platform:user:{}", principal_id);

        Self {
            metadata: EventMetadata::new(
                event_id,
                Self::EVENT_TYPE,
                Self::SPEC_VERSION,
                Self::SOURCE,
                subject,
                message_group,
                ctx.execution_id.clone(),
                ctx.correlation_id.clone(),
                ctx.causation_id.clone(),
                ctx.principal_id.clone(),
            ),
            principal_id: principal_id.to_string(),
            email: email.to_string(),
            name: name.to_string(),
            scope: format!("{:?}", scope).to_uppercase(),
            client_id: client_id.map(String::from),
        }
    }
}

/// Event emitted when a user is updated.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UserUpdated {
    #[serde(flatten)]
    pub metadata: EventMetadata,

    pub principal_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
}

impl_domain_event!(UserUpdated);

impl UserUpdated {
    const EVENT_TYPE: &'static str = "platform:iam:user:updated";
    const SPEC_VERSION: &'static str = "1.0";
    const SOURCE: &'static str = "platform:iam";

    pub fn new(
        ctx: &ExecutionContext,
        principal_id: &str,
        name: Option<&str>,
        email: Option<&str>,
    ) -> Self {
        let event_id = TsidGenerator::generate();
        let subject = format!("platform.user.{}", principal_id);
        let message_group = format!("platform:user:{}", principal_id);

        Self {
            metadata: EventMetadata::new(
                event_id,
                Self::EVENT_TYPE,
                Self::SPEC_VERSION,
                Self::SOURCE,
                subject,
                message_group,
                ctx.execution_id.clone(),
                ctx.correlation_id.clone(),
                ctx.causation_id.clone(),
                ctx.principal_id.clone(),
            ),
            principal_id: principal_id.to_string(),
            name: name.map(String::from),
            email: email.map(String::from),
        }
    }
}

/// Event emitted when a user is activated.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UserActivated {
    #[serde(flatten)]
    pub metadata: EventMetadata,

    pub principal_id: String,
}

impl_domain_event!(UserActivated);

impl UserActivated {
    const EVENT_TYPE: &'static str = "platform:iam:user:activated";
    const SPEC_VERSION: &'static str = "1.0";
    const SOURCE: &'static str = "platform:iam";

    pub fn new(ctx: &ExecutionContext, principal_id: &str) -> Self {
        let event_id = TsidGenerator::generate();
        let subject = format!("platform.user.{}", principal_id);
        let message_group = format!("platform:user:{}", principal_id);

        Self {
            metadata: EventMetadata::new(
                event_id,
                Self::EVENT_TYPE,
                Self::SPEC_VERSION,
                Self::SOURCE,
                subject,
                message_group,
                ctx.execution_id.clone(),
                ctx.correlation_id.clone(),
                ctx.causation_id.clone(),
                ctx.principal_id.clone(),
            ),
            principal_id: principal_id.to_string(),
        }
    }
}

/// Event emitted when a user is deactivated.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UserDeactivated {
    #[serde(flatten)]
    pub metadata: EventMetadata,

    pub principal_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

impl_domain_event!(UserDeactivated);

impl UserDeactivated {
    const EVENT_TYPE: &'static str = "platform:iam:user:deactivated";
    const SPEC_VERSION: &'static str = "1.0";
    const SOURCE: &'static str = "platform:iam";

    pub fn new(ctx: &ExecutionContext, principal_id: &str, reason: Option<&str>) -> Self {
        let event_id = TsidGenerator::generate();
        let subject = format!("platform.user.{}", principal_id);
        let message_group = format!("platform:user:{}", principal_id);

        Self {
            metadata: EventMetadata::new(
                event_id,
                Self::EVENT_TYPE,
                Self::SPEC_VERSION,
                Self::SOURCE,
                subject,
                message_group,
                ctx.execution_id.clone(),
                ctx.correlation_id.clone(),
                ctx.causation_id.clone(),
                ctx.principal_id.clone(),
            ),
            principal_id: principal_id.to_string(),
            reason: reason.map(String::from),
        }
    }
}

/// Event emitted when a user is deleted.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UserDeleted {
    #[serde(flatten)]
    pub metadata: EventMetadata,

    pub principal_id: String,
}

impl_domain_event!(UserDeleted);

impl UserDeleted {
    const EVENT_TYPE: &'static str = "platform:iam:user:deleted";
    const SPEC_VERSION: &'static str = "1.0";
    const SOURCE: &'static str = "platform:iam";

    pub fn new(ctx: &ExecutionContext, principal_id: &str) -> Self {
        let event_id = TsidGenerator::generate();
        let subject = format!("platform.user.{}", principal_id);
        let message_group = format!("platform:user:{}", principal_id);

        Self {
            metadata: EventMetadata::new(
                event_id,
                Self::EVENT_TYPE,
                Self::SPEC_VERSION,
                Self::SOURCE,
                subject,
                message_group,
                ctx.execution_id.clone(),
                ctx.correlation_id.clone(),
                ctx.causation_id.clone(),
                ctx.principal_id.clone(),
            ),
            principal_id: principal_id.to_string(),
        }
    }
}

/// Event emitted when roles are assigned to a user.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RolesAssigned {
    #[serde(flatten)]
    pub metadata: EventMetadata,

    pub principal_id: String,
    pub roles: Vec<String>,
    pub added: Vec<String>,
    pub removed: Vec<String>,
}

impl_domain_event!(RolesAssigned);

impl RolesAssigned {
    const EVENT_TYPE: &'static str = "platform:iam:user:roles-assigned";
    const SPEC_VERSION: &'static str = "1.0";
    const SOURCE: &'static str = "platform:iam";

    pub fn new(
        ctx: &ExecutionContext,
        principal_id: &str,
        roles: Vec<String>,
        added: Vec<String>,
        removed: Vec<String>,
    ) -> Self {
        let event_id = TsidGenerator::generate();
        let subject = format!("platform.user.{}", principal_id);
        let message_group = format!("platform:user:{}", principal_id);

        Self {
            metadata: EventMetadata::new(
                event_id,
                Self::EVENT_TYPE,
                Self::SPEC_VERSION,
                Self::SOURCE,
                subject,
                message_group,
                ctx.execution_id.clone(),
                ctx.correlation_id.clone(),
                ctx.causation_id.clone(),
                ctx.principal_id.clone(),
            ),
            principal_id: principal_id.to_string(),
            roles,
            added,
            removed,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::usecase::DomainEvent;

    #[test]
    fn test_user_created_event() {
        let ctx = ExecutionContext::create("admin-123");
        let event = UserCreated::new(
            &ctx,
            "user-1",
            "user@example.com",
            "Test User",
            UserScope::Client,
            Some("client-1"),
        );

        assert_eq!(event.event_type(), "platform:iam:user:created");
        assert_eq!(event.principal_id, "user-1");
        assert_eq!(event.email, "user@example.com");
        assert_eq!(event.scope, "CLIENT");
    }

    #[test]
    fn test_user_deactivated_event() {
        let ctx = ExecutionContext::create("admin-123");
        let event = UserDeactivated::new(&ctx, "user-1", Some("Policy violation"));

        assert_eq!(event.event_type(), "platform:iam:user:deactivated");
        assert_eq!(event.reason, Some("Policy violation".to_string()));
    }
}
