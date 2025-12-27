//! Platform Error Types

use thiserror::Error;

#[derive(Error, Debug)]
pub enum PlatformError {
    #[error("Entity not found: {entity_type} with id {id}")]
    NotFound { entity_type: String, id: String },

    #[error("Duplicate entity: {entity_type} with {field}={value}")]
    Duplicate { entity_type: String, field: String, value: String },

    #[error("Validation error: {message}")]
    Validation { message: String },

    #[error("Authorization error: {message}")]
    Unauthorized { message: String },

    #[error("Forbidden: {message}")]
    Forbidden { message: String },

    #[error("Database error: {0}")]
    Database(#[from] mongodb::error::Error),

    #[error("Serialization error: {0}")]
    Serialization(#[from] bson::ser::Error),

    #[error("Deserialization error: {0}")]
    Deserialization(#[from] bson::de::Error),

    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),

    #[error("Invalid TSID: {0}")]
    InvalidTsid(String),

    #[error("Configuration error: {message}")]
    Configuration { message: String },

    #[error("Event type not found: {code}")]
    EventTypeNotFound { code: String },

    #[error("Subscription not found: {code}")]
    SubscriptionNotFound { code: String },

    #[error("Client not found: {id}")]
    ClientNotFound { id: String },

    #[error("Principal not found: {id}")]
    PrincipalNotFound { id: String },

    #[error("Service account not found: {id}")]
    ServiceAccountNotFound { id: String },

    #[error("Invalid credentials")]
    InvalidCredentials,

    #[error("Token expired")]
    TokenExpired,

    #[error("Invalid token: {message}")]
    InvalidToken { message: String },

    #[error("Schema validation failed: {message}")]
    SchemaValidation { message: String },

    #[error("Dispatch error: {message}")]
    Dispatch { message: String },

    #[error("Internal error: {message}")]
    Internal { message: String },
}

impl PlatformError {
    pub fn not_found(entity_type: impl Into<String>, id: impl Into<String>) -> Self {
        Self::NotFound {
            entity_type: entity_type.into(),
            id: id.into(),
        }
    }

    pub fn duplicate(entity_type: impl Into<String>, field: impl Into<String>, value: impl Into<String>) -> Self {
        Self::Duplicate {
            entity_type: entity_type.into(),
            field: field.into(),
            value: value.into(),
        }
    }

    pub fn validation(message: impl Into<String>) -> Self {
        Self::Validation { message: message.into() }
    }

    pub fn unauthorized(message: impl Into<String>) -> Self {
        Self::Unauthorized { message: message.into() }
    }

    pub fn forbidden(message: impl Into<String>) -> Self {
        Self::Forbidden { message: message.into() }
    }
}

pub type Result<T> = std::result::Result<T, PlatformError>;
