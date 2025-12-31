//! Common API types and utilities

use axum::Json;
use utoipa::{ToSchema, IntoParams};
use serde::{Deserialize, Serialize};
/// Standard API error response
#[derive(Debug, Serialize, ToSchema)]
pub struct ApiError {
    pub error: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub details: Option<serde_json::Value>,
}

/// Pagination parameters
#[derive(Debug, Deserialize, ToSchema, IntoParams)]
#[serde(rename_all = "camelCase")]
#[into_params(parameter_in = Query)]
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
#[aliases(
    PaginatedEvents = PaginatedResponse<crate::api::events::EventReadResponse>,
    PaginatedEventTypes = PaginatedResponse<crate::api::event_types::EventTypeResponse>,
    PaginatedDispatchJobs = PaginatedResponse<crate::api::dispatch_jobs::DispatchJobResponse>,
    PaginatedClients = PaginatedResponse<crate::api::clients::ClientResponse>,
    PaginatedPrincipals = PaginatedResponse<crate::api::principals::PrincipalResponse>,
    PaginatedRoles = PaginatedResponse<crate::api::roles::RoleResponse>,
    PaginatedSubscriptions = PaginatedResponse<crate::api::subscriptions::SubscriptionResponse>,
)]
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
