//! Common API types and utilities

use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::{Deserialize, Serialize};
use crate::error::PlatformError;

/// Standard API error response
#[derive(Debug, Serialize)]
pub struct ApiError {
    pub error: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub details: Option<serde_json::Value>,
}

impl IntoResponse for PlatformError {
    fn into_response(self) -> Response {
        let (status, error_type) = match &self {
            PlatformError::NotFound { .. } => (StatusCode::NOT_FOUND, "NOT_FOUND"),
            PlatformError::Duplicate { .. } => (StatusCode::CONFLICT, "DUPLICATE"),
            PlatformError::Validation { .. } => (StatusCode::BAD_REQUEST, "VALIDATION_ERROR"),
            PlatformError::Unauthorized { .. } => (StatusCode::UNAUTHORIZED, "UNAUTHORIZED"),
            PlatformError::Forbidden { .. } => (StatusCode::FORBIDDEN, "FORBIDDEN"),
            PlatformError::InvalidCredentials => (StatusCode::UNAUTHORIZED, "INVALID_CREDENTIALS"),
            PlatformError::TokenExpired => (StatusCode::UNAUTHORIZED, "TOKEN_EXPIRED"),
            PlatformError::InvalidToken { .. } => (StatusCode::UNAUTHORIZED, "INVALID_TOKEN"),
            PlatformError::SchemaValidation { .. } => (StatusCode::BAD_REQUEST, "SCHEMA_ERROR"),
            PlatformError::EventTypeNotFound { .. } => (StatusCode::NOT_FOUND, "EVENT_TYPE_NOT_FOUND"),
            PlatformError::SubscriptionNotFound { .. } => (StatusCode::NOT_FOUND, "SUBSCRIPTION_NOT_FOUND"),
            PlatformError::ClientNotFound { .. } => (StatusCode::NOT_FOUND, "CLIENT_NOT_FOUND"),
            PlatformError::PrincipalNotFound { .. } => (StatusCode::NOT_FOUND, "PRINCIPAL_NOT_FOUND"),
            PlatformError::ServiceAccountNotFound { .. } => (StatusCode::NOT_FOUND, "SERVICE_ACCOUNT_NOT_FOUND"),
            _ => (StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"),
        };

        let body = ApiError {
            error: error_type.to_string(),
            message: self.to_string(),
            details: None,
        };

        (status, Json(body)).into_response()
    }
}

/// API Result type
pub type ApiResult<T> = Result<Json<T>, PlatformError>;

/// Pagination parameters
#[derive(Debug, Deserialize)]
pub struct PaginationParams {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_limit")]
    pub limit: u32,
}

fn default_page() -> u32 { 1 }
fn default_limit() -> u32 { 20 }

impl PaginationParams {
    pub fn offset(&self) -> u32 {
        (self.page.saturating_sub(1)) * self.limit
    }
}

/// Paginated response wrapper
#[derive(Debug, Serialize)]
pub struct PaginatedResponse<T> {
    pub data: Vec<T>,
    pub page: u32,
    pub limit: u32,
    pub total: u64,
    pub total_pages: u32,
}

impl<T> PaginatedResponse<T> {
    pub fn new(data: Vec<T>, page: u32, limit: u32, total: u64) -> Self {
        let total_pages = ((total as f64) / (limit as f64)).ceil() as u32;
        Self {
            data,
            page,
            limit,
            total,
            total_pages,
        }
    }
}

/// Success response with optional message
#[derive(Debug, Serialize)]
pub struct SuccessResponse {
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

impl SuccessResponse {
    pub fn ok() -> Self {
        Self {
            success: true,
            message: None,
        }
    }

    pub fn with_message(message: impl Into<String>) -> Self {
        Self {
            success: true,
            message: Some(message.into()),
        }
    }
}

/// Created response with ID
#[derive(Debug, Serialize)]
pub struct CreatedResponse {
    pub id: String,
}

impl CreatedResponse {
    pub fn new(id: impl Into<String>) -> Self {
        Self { id: id.into() }
    }
}
