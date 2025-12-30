//! Common API types and utilities

use salvo::prelude::*;
use salvo::oapi::{ToSchema, EndpointOutRegister, Components, Operation};
use serde::{Deserialize, Serialize};
use crate::error::PlatformError;

/// Standard API error response
#[derive(Debug, Serialize, ToSchema)]
pub struct ApiError {
    pub error: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub details: Option<serde_json::Value>,
}

#[async_trait]
impl Writer for PlatformError {
    async fn write(mut self, _req: &mut Request, _depot: &mut Depot, res: &mut Response) {
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

        res.status_code(status);
        res.render(Json(body));
    }
}

impl EndpointOutRegister for PlatformError {
    fn register(components: &mut Components, operation: &mut Operation) {
        // Register ApiError schema for error responses
        operation.responses.insert(
            "400".to_string(),
            salvo::oapi::Response::new("Bad Request")
                .add_content("application/json", ApiError::to_schema(components)),
        );
        operation.responses.insert(
            "401".to_string(),
            salvo::oapi::Response::new("Unauthorized")
                .add_content("application/json", ApiError::to_schema(components)),
        );
        operation.responses.insert(
            "403".to_string(),
            salvo::oapi::Response::new("Forbidden")
                .add_content("application/json", ApiError::to_schema(components)),
        );
        operation.responses.insert(
            "404".to_string(),
            salvo::oapi::Response::new("Not Found")
                .add_content("application/json", ApiError::to_schema(components)),
        );
        operation.responses.insert(
            "500".to_string(),
            salvo::oapi::Response::new("Internal Server Error")
                .add_content("application/json", ApiError::to_schema(components)),
        );
    }
}

/// Pagination parameters
#[derive(Debug, Deserialize, ToSchema, salvo::oapi::ToParameters)]
#[serde(rename_all = "camelCase")]
#[salvo(parameters(default_parameter_in = Query))]
pub struct PaginationParams {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_limit")]
    pub limit: u32,
}

fn default_page() -> u32 { 1 }
fn default_limit() -> u32 { 20 }

impl Default for PaginationParams {
    fn default() -> Self {
        Self {
            page: default_page(),
            limit: default_limit(),
        }
    }
}

impl PaginationParams {
    pub fn offset(&self) -> u32 {
        (self.page.saturating_sub(1)) * self.limit
    }
}

/// Paginated response wrapper
#[derive(Debug, Serialize, ToSchema)]
pub struct PaginatedResponse<T: ToSchema> {
    pub data: Vec<T>,
    pub page: u32,
    pub limit: u32,
    pub total: u64,
    pub total_pages: u32,
}

impl<T: ToSchema> PaginatedResponse<T> {
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
#[derive(Debug, Serialize, ToSchema)]
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
#[derive(Debug, Serialize, ToSchema)]
pub struct CreatedResponse {
    pub id: String,
}

impl CreatedResponse {
    pub fn new(id: impl Into<String>) -> Self {
        Self { id: id.into() }
    }
}
