//! Authentication Configuration Entities
//!
//! Configuration for email domain-based authentication routing.

use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

/// Auth provider type
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AuthProvider {
    /// Internal password-based auth
    Internal,
    /// External OIDC provider
    Oidc,
}

impl Default for AuthProvider {
    fn default() -> Self {
        Self::Internal
    }
}

/// Config type for email domain
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AuthConfigType {
    /// Anchor-level (god mode) access
    Anchor,
    /// Partner-level (multi-client) access
    Partner,
    /// Client-level (single tenant) access
    Client,
}

impl Default for AuthConfigType {
    fn default() -> Self {
        Self::Client
    }
}

/// Anchor domain - email domains with platform admin (god mode) access
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnchorDomain {
    /// TSID as Crockford Base32 string
    #[serde(rename = "_id")]
    pub id: String,

    /// Email domain (e.g., "flowcatalyst.tech")
    pub domain: String,

    /// Audit fields
    pub created_at: DateTime<Utc>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub created_by: Option<String>,
}

impl AnchorDomain {
    pub fn new(domain: impl Into<String>) -> Self {
        Self {
            id: crate::TsidGenerator::generate(),
            domain: domain.into().to_lowercase(),
            created_at: Utc::now(),
            created_by: None,
        }
    }

    pub fn matches_email(&self, email: &str) -> bool {
        email.to_lowercase().ends_with(&format!("@{}", self.domain))
    }
}

/// Client auth configuration - maps email domains to auth settings
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientAuthConfig {
    /// TSID as Crockford Base32 string
    #[serde(rename = "_id")]
    pub id: String,

    /// Email domain this config applies to
    pub email_domain: String,

    /// Config type (determines user scope)
    #[serde(default)]
    pub config_type: AuthConfigType,

    /// Primary client ID (for CLIENT type)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub primary_client_id: Option<String>,

    /// Additional client IDs (for PARTNER type)
    #[serde(default)]
    pub additional_client_ids: Vec<String>,

    /// Granted client IDs (for PARTNER type - access grants)
    #[serde(default)]
    pub granted_client_ids: Vec<String>,

    /// Auth provider
    #[serde(default)]
    pub auth_provider: AuthProvider,

    /// OIDC issuer URL (if OIDC)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub oidc_issuer_url: Option<String>,

    /// OIDC client ID
    #[serde(skip_serializing_if = "Option::is_none")]
    pub oidc_client_id: Option<String>,

    /// OIDC client secret reference
    #[serde(skip_serializing_if = "Option::is_none")]
    pub oidc_client_secret_ref: Option<String>,

    /// Whether OIDC is multi-tenant (Azure AD style)
    #[serde(default)]
    pub oidc_multi_tenant: bool,

    /// OIDC issuer pattern for multi-tenant (e.g., "https://login.microsoftonline.com/{tenant}/v2.0")
    #[serde(skip_serializing_if = "Option::is_none")]
    pub oidc_issuer_pattern: Option<String>,

    /// Audit fields
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub created_by: Option<String>,
}

impl ClientAuthConfig {
    pub fn new_client(email_domain: impl Into<String>, client_id: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            id: crate::TsidGenerator::generate(),
            email_domain: email_domain.into().to_lowercase(),
            config_type: AuthConfigType::Client,
            primary_client_id: Some(client_id.into()),
            additional_client_ids: vec![],
            granted_client_ids: vec![],
            auth_provider: AuthProvider::Internal,
            oidc_issuer_url: None,
            oidc_client_id: None,
            oidc_client_secret_ref: None,
            oidc_multi_tenant: false,
            oidc_issuer_pattern: None,
            created_at: now,
            updated_at: now,
            created_by: None,
        }
    }

    pub fn new_partner(email_domain: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            id: crate::TsidGenerator::generate(),
            email_domain: email_domain.into().to_lowercase(),
            config_type: AuthConfigType::Partner,
            primary_client_id: None,
            additional_client_ids: vec![],
            granted_client_ids: vec![],
            auth_provider: AuthProvider::Internal,
            oidc_issuer_url: None,
            oidc_client_id: None,
            oidc_client_secret_ref: None,
            oidc_multi_tenant: false,
            oidc_issuer_pattern: None,
            created_at: now,
            updated_at: now,
            created_by: None,
        }
    }

    pub fn with_oidc(
        mut self,
        issuer_url: impl Into<String>,
        client_id: impl Into<String>,
    ) -> Self {
        self.auth_provider = AuthProvider::Oidc;
        self.oidc_issuer_url = Some(issuer_url.into());
        self.oidc_client_id = Some(client_id.into());
        self
    }

    pub fn with_oidc_secret_ref(mut self, secret_ref: impl Into<String>) -> Self {
        self.oidc_client_secret_ref = Some(secret_ref.into());
        self
    }

    pub fn add_client_access(&mut self, client_id: impl Into<String>) {
        let id = client_id.into();
        if !self.granted_client_ids.contains(&id) {
            self.granted_client_ids.push(id);
            self.updated_at = Utc::now();
        }
    }

    pub fn remove_client_access(&mut self, client_id: &str) {
        self.granted_client_ids.retain(|id| id != client_id);
        self.updated_at = Utc::now();
    }

    pub fn matches_email(&self, email: &str) -> bool {
        email.to_lowercase().ends_with(&format!("@{}", self.email_domain))
    }

    pub fn is_oidc(&self) -> bool {
        self.auth_provider == AuthProvider::Oidc
    }

    pub fn is_internal(&self) -> bool {
        self.auth_provider == AuthProvider::Internal
    }

    /// Get all accessible client IDs for this config
    pub fn accessible_clients(&self) -> Vec<String> {
        let mut clients = Vec::new();
        if let Some(ref primary) = self.primary_client_id {
            clients.push(primary.clone());
        }
        clients.extend(self.additional_client_ids.clone());
        clients.extend(self.granted_client_ids.clone());
        clients
    }
}

/// Client access grant - explicit access grant for partner users
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientAccessGrant {
    /// TSID as Crockford Base32 string
    #[serde(rename = "_id")]
    pub id: String,

    /// Principal ID receiving the grant
    pub principal_id: String,

    /// Client ID being granted access to
    pub client_id: String,

    /// When the grant was created
    pub granted_at: DateTime<Utc>,

    /// Optional expiration
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expires_at: Option<DateTime<Utc>>,

    /// Who granted access
    #[serde(skip_serializing_if = "Option::is_none")]
    pub granted_by: Option<String>,
}

impl ClientAccessGrant {
    pub fn new(principal_id: impl Into<String>, client_id: impl Into<String>) -> Self {
        Self {
            id: crate::TsidGenerator::generate(),
            principal_id: principal_id.into(),
            client_id: client_id.into(),
            granted_at: Utc::now(),
            expires_at: None,
            granted_by: None,
        }
    }

    pub fn with_expiry(mut self, expires_at: DateTime<Utc>) -> Self {
        self.expires_at = Some(expires_at);
        self
    }

    pub fn with_grantor(mut self, grantor: impl Into<String>) -> Self {
        self.granted_by = Some(grantor.into());
        self
    }

    pub fn is_expired(&self) -> bool {
        self.expires_at.map(|exp| Utc::now() > exp).unwrap_or(false)
    }

    pub fn is_active(&self) -> bool {
        !self.is_expired()
    }
}

/// IDP role mapping - maps external IDP roles to platform roles
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IdpRoleMapping {
    /// TSID as Crockford Base32 string
    #[serde(rename = "_id")]
    pub id: String,

    /// IDP type (matches ClientAuthConfig auth provider)
    pub idp_type: String,

    /// Role name from the IDP (e.g., "Admin", "Viewer")
    pub idp_role_name: String,

    /// Platform role name to map to (e.g., "platform:admin")
    pub platform_role_name: String,

    /// Audit fields
    pub created_at: DateTime<Utc>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub created_by: Option<String>,
}

impl IdpRoleMapping {
    pub fn new(
        idp_type: impl Into<String>,
        idp_role_name: impl Into<String>,
        platform_role_name: impl Into<String>,
    ) -> Self {
        Self {
            id: crate::TsidGenerator::generate(),
            idp_type: idp_type.into(),
            idp_role_name: idp_role_name.into(),
            platform_role_name: platform_role_name.into(),
            created_at: Utc::now(),
            created_by: None,
        }
    }
}
