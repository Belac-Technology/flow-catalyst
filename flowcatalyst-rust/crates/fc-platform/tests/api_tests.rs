//! Platform API Integration Tests
//!
//! Tests for platform domain models, authorization, and error handling.

use std::collections::HashSet;

use fc_platform::domain::{Principal, UserScope, AuditLog, AuditAction};
use fc_platform::TsidGenerator;

// Unit tests for domain models
mod domain_tests {
    use super::*;

    #[test]
    fn test_principal_user_creation() {
        let principal = Principal::new_user("test@example.com", UserScope::Anchor);
        assert_eq!(principal.scope, UserScope::Anchor);
        assert!(principal.is_user());
        assert!(principal.active);
        assert_eq!(principal.user_identity.as_ref().unwrap().email, "test@example.com");
    }

    #[test]
    fn test_principal_service_creation() {
        let principal = Principal::new_service("client123", "Test Service");
        assert!(!principal.is_user());
        assert!(principal.active);
    }

    #[test]
    fn test_principal_role_assignment() {
        let mut principal = Principal::new_user("test@example.com", UserScope::Anchor);
        principal.assign_role("admin".to_string());
        assert!(principal.has_role("admin"));
        assert!(!principal.has_role("user"));
    }

    #[test]
    fn test_principal_client_scoped_role_assignment() {
        let mut principal = Principal::new_user("test@example.com", UserScope::Partner);
        principal.assign_role_for_client("client-admin".to_string(), "client123".to_string());

        // Should have a role with the client_id set
        let role = principal.roles.iter().find(|r| r.role == "client-admin").unwrap();
        assert_eq!(role.client_id, Some("client123".to_string()));
    }

    #[test]
    fn test_principal_role_removal() {
        let mut principal = Principal::new_user("test@example.com", UserScope::Anchor);
        principal.assign_role("admin".to_string());
        principal.assign_role("user".to_string());

        // Remove role
        principal.roles.retain(|r| r.role != "admin");

        assert!(!principal.has_role("admin"));
        assert!(principal.has_role("user"));
    }

    #[test]
    fn test_principal_client_access() {
        let mut principal = Principal::new_user("test@example.com", UserScope::Partner);
        let client_id = TsidGenerator::generate();

        principal.grant_client_access(client_id.clone());
        assert!(principal.assigned_clients.contains(&client_id));

        principal.revoke_client_access(&client_id);
        assert!(!principal.assigned_clients.contains(&client_id));
    }

    #[test]
    fn test_principal_with_client_id() {
        let principal = Principal::new_user("test@example.com", UserScope::Client)
            .with_client_id("client123".to_string());
        assert_eq!(principal.client_id, Some("client123".to_string()));
    }

    #[test]
    fn test_principal_activation() {
        let mut principal = Principal::new_user("test@example.com", UserScope::Anchor);
        assert!(principal.active);

        principal.deactivate();
        assert!(!principal.active);

        principal.activate();
        assert!(principal.active);
    }
}

// Audit log tests
mod audit_tests {
    use super::*;

    #[test]
    fn test_audit_log_creation() {
        let log = AuditLog::new(AuditAction::Create, "Principal", "Created test principal");

        assert_eq!(log.action, AuditAction::Create);
        assert_eq!(log.entity_type, "Principal");
        assert!(log.entity_id.is_none());
    }

    #[test]
    fn test_audit_log_for_entity() {
        let log = AuditLog::for_entity(
            AuditAction::Create,
            "Principal",
            "test123",
            "Created test principal",
        );

        assert_eq!(log.action, AuditAction::Create);
        assert_eq!(log.entity_type, "Principal");
        assert_eq!(log.entity_id, Some("test123".to_string()));
    }

    #[test]
    fn test_audit_log_with_principal_context() {
        let log = AuditLog::for_entity(
            AuditAction::Update,
            "Client",
            "client123",
            "Updated client settings",
        )
        .with_principal("principal123", Some("admin@example.com".to_string()));

        assert_eq!(log.principal_id, Some("principal123".to_string()));
        assert_eq!(log.principal_email, Some("admin@example.com".to_string()));
    }

    #[test]
    fn test_audit_log_with_client_context() {
        let log = AuditLog::for_entity(
            AuditAction::Update,
            "Subscription",
            "sub123",
            "Updated subscription",
        )
        .with_client("client123");

        assert_eq!(log.client_id, Some("client123".to_string()));
    }

    #[test]
    fn test_audit_log_with_request_context() {
        let log = AuditLog::new(AuditAction::Login, "Session", "User logged in")
            .with_request_context(
                Some("req123".to_string()),
                Some("192.168.1.1".to_string()),
                Some("Mozilla/5.0".to_string()),
            );

        assert_eq!(log.request_id, Some("req123".to_string()));
        assert_eq!(log.ip_address, Some("192.168.1.1".to_string()));
        assert_eq!(log.user_agent, Some("Mozilla/5.0".to_string()));
    }

    #[test]
    fn test_audit_log_action_types() {
        let actions = vec![
            AuditAction::Create,
            AuditAction::Update,
            AuditAction::Delete,
            AuditAction::Archive,
            AuditAction::Login,
            AuditAction::Logout,
            AuditAction::TokenIssued,
            AuditAction::TokenRevoked,
            AuditAction::RoleAssigned,
            AuditAction::RoleUnassigned,
            AuditAction::ClientAccessGranted,
            AuditAction::ClientAccessRevoked,
            AuditAction::SubscriptionPaused,
            AuditAction::SubscriptionResumed,
            AuditAction::PoolPaused,
            AuditAction::PoolResumed,
            AuditAction::ConfigChanged,
        ];

        for action in actions {
            let log = AuditLog::new(action, "Test", "Test action");
            assert_eq!(log.action, action);
        }
    }
}

// Authorization context tests
mod authorization_tests {
    use super::*;
    use fc_platform::service::authorization::AuthContext;

    fn create_auth_context(permissions: Vec<&str>, scope: &str, clients: Vec<&str>) -> AuthContext {
        AuthContext {
            principal_id: TsidGenerator::generate(),
            principal_type: "USER".to_string(),
            scope: scope.to_string(),
            email: Some("test@example.com".to_string()),
            name: "Test User".to_string(),
            accessible_clients: clients.into_iter().map(String::from).collect(),
            permissions: permissions.into_iter().map(String::from).collect(),
            roles: vec!["admin".to_string()],
        }
    }

    #[test]
    fn test_anchor_scope() {
        let ctx = create_auth_context(vec![], "ANCHOR", vec!["*"]);
        assert!(ctx.is_anchor());
    }

    #[test]
    fn test_client_scope() {
        let ctx = create_auth_context(vec![], "CLIENT", vec!["client123"]);
        assert!(!ctx.is_anchor());
    }

    #[test]
    fn test_partner_scope() {
        let ctx = create_auth_context(vec![], "PARTNER", vec!["client1", "client2"]);
        assert!(!ctx.is_anchor());
        assert!(ctx.can_access_client("client1"));
        assert!(ctx.can_access_client("client2"));
    }

    #[test]
    fn test_direct_permission() {
        let ctx = create_auth_context(vec!["events:read"], "CLIENT", vec!["client123"]);
        assert!(ctx.has_permission("events:read"));
        assert!(!ctx.has_permission("events:write"));
    }

    #[test]
    fn test_wildcard_permission() {
        let ctx = create_auth_context(vec!["events:*"], "CLIENT", vec!["client123"]);
        assert!(ctx.has_permission("events:read"));
        assert!(ctx.has_permission("events:write"));
        assert!(!ctx.has_permission("users:read"));
    }

    #[test]
    fn test_superuser_permission() {
        let ctx = create_auth_context(vec!["*:*"], "ANCHOR", vec!["*"]);
        assert!(ctx.has_permission("events:read"));
        assert!(ctx.has_permission("users:write"));
        assert!(ctx.has_permission("anything:everything"));
    }

    #[test]
    fn test_client_access_specific() {
        let ctx = create_auth_context(vec![], "CLIENT", vec!["client1", "client2"]);
        assert!(ctx.can_access_client("client1"));
        assert!(ctx.can_access_client("client2"));
        assert!(!ctx.can_access_client("client3"));
    }

    #[test]
    fn test_anchor_all_clients() {
        let ctx = create_auth_context(vec![], "ANCHOR", vec!["*"]);
        assert!(ctx.can_access_client("any_client"));
        assert!(ctx.can_access_client("another_client"));
    }

    #[test]
    fn test_has_all_permissions() {
        let ctx = create_auth_context(
            vec!["events:read", "events:write", "subscriptions:read"],
            "CLIENT",
            vec!["client1"],
        );
        assert!(ctx.has_all_permissions(&["events:read", "events:write"]));
        assert!(!ctx.has_all_permissions(&["events:read", "users:write"]));
    }

    #[test]
    fn test_has_any_permission() {
        let ctx = create_auth_context(vec!["events:read"], "CLIENT", vec!["client1"]);
        assert!(ctx.has_any_permission(&["events:read", "events:write"]));
        assert!(ctx.has_any_permission(&["users:read", "events:read"]));
        assert!(!ctx.has_any_permission(&["users:read", "users:write"]));
    }

    #[test]
    fn test_has_role() {
        let ctx = create_auth_context(vec![], "CLIENT", vec!["client1"]);
        assert!(ctx.has_role("admin"));
        assert!(!ctx.has_role("viewer"));
    }
}

// TSID generation tests
mod tsid_tests {
    use super::*;

    #[test]
    fn test_tsid_format() {
        let id = TsidGenerator::generate();

        // TSID should be 13 characters in Crockford Base32
        assert_eq!(id.len(), 13);

        // Should only contain valid Crockford Base32 characters (uppercase)
        assert!(id.chars().all(|c| {
            matches!(c, '0'..='9' | 'A'..='H' | 'J'..='K' | 'M'..='N' | 'P'..='T' | 'V'..='Z')
        }));
    }

    #[test]
    fn test_tsid_uniqueness() {
        let ids: HashSet<String> = (0..1000)
            .map(|_| TsidGenerator::generate())
            .collect();

        // All 1000 IDs should be unique
        assert_eq!(ids.len(), 1000);
    }

    #[test]
    fn test_tsid_sortability() {
        let id1 = TsidGenerator::generate();
        std::thread::sleep(std::time::Duration::from_millis(2));
        let id2 = TsidGenerator::generate();

        // Newer IDs should sort after older ones lexicographically
        assert!(id2 > id1, "id2 ({}) should be greater than id1 ({})", id2, id1);
    }

    #[test]
    fn test_multiple_tsids_time_ordered() {
        let ids: Vec<String> = (0..100)
            .map(|_| {
                let id = TsidGenerator::generate();
                std::thread::sleep(std::time::Duration::from_millis(1));
                id
            })
            .collect();

        // Sort the IDs and verify they're still in the same order (time-ordered)
        let mut sorted_ids = ids.clone();
        sorted_ids.sort();

        assert_eq!(ids, sorted_ids, "TSIDs should be lexicographically sortable by creation time");
    }
}

// Error handling tests
mod error_tests {
    use fc_platform::error::PlatformError;

    #[test]
    fn test_not_found_error() {
        let err = PlatformError::not_found("Principal", "test123");
        let msg = err.to_string();
        assert!(msg.contains("Principal"));
        assert!(msg.contains("test123"));
    }

    #[test]
    fn test_duplicate_error() {
        let err = PlatformError::duplicate("Principal", "email", "test@example.com");
        let msg = err.to_string();
        assert!(msg.contains("Principal"));
        assert!(msg.contains("email"));
        assert!(msg.contains("test@example.com"));
    }

    #[test]
    fn test_validation_error() {
        let err = PlatformError::validation("Invalid email format");
        assert!(err.to_string().contains("Invalid email format"));
    }

    #[test]
    fn test_forbidden_error() {
        let err = PlatformError::forbidden("Insufficient permissions");
        assert!(err.to_string().contains("Insufficient permissions"));
    }

    #[test]
    fn test_unauthorized_error() {
        let err = PlatformError::unauthorized("Token expired");
        assert!(err.to_string().contains("Token expired"));
    }

    #[test]
    fn test_error_variants() {
        // Test various error variants compile and display correctly
        let errors = vec![
            PlatformError::InvalidCredentials,
            PlatformError::TokenExpired,
            PlatformError::InvalidToken { message: "Malformed JWT".to_string() },
            PlatformError::Configuration { message: "Missing key".to_string() },
            PlatformError::Internal { message: "Unexpected error".to_string() },
            PlatformError::EventTypeNotFound { code: "order.created".to_string() },
            PlatformError::SubscriptionNotFound { code: "my-webhook".to_string() },
            PlatformError::ClientNotFound { id: "client123".to_string() },
            PlatformError::PrincipalNotFound { id: "user123".to_string() },
        ];

        for err in errors {
            // Ensure all errors can be converted to string
            let _ = err.to_string();
        }
    }
}

// User scope tests
mod scope_tests {
    use super::*;

    #[test]
    fn test_anchor_scope() {
        let principal = Principal::new_user("admin@example.com", UserScope::Anchor);
        assert_eq!(principal.scope, UserScope::Anchor);
    }

    #[test]
    fn test_partner_scope() {
        let principal = Principal::new_user("partner@example.com", UserScope::Partner);
        assert_eq!(principal.scope, UserScope::Partner);
    }

    #[test]
    fn test_client_scope() {
        let principal = Principal::new_user("user@client.com", UserScope::Client);
        assert_eq!(principal.scope, UserScope::Client);
    }
}
