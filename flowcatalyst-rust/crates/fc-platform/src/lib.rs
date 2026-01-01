//! FlowCatalyst Platform
//!
//! Core platform providing:
//! - Event management (CloudEvents spec)
//! - Event type definitions with schema versioning
//! - Dispatch job lifecycle management
//! - Subscription-based event routing
//! - Multi-tenant identity and access control
//! - Service account management for webhooks
//! - Use Case pattern with guaranteed audit logging

pub mod domain;
pub mod repository;
pub mod service;
pub mod api;
pub mod error;
pub mod tsid;
pub mod usecase;
pub mod operations;
pub mod seed;
pub mod idp;

pub use domain::*;
pub use error::PlatformError;
pub use tsid::TsidGenerator;

// Re-export use case infrastructure
pub use usecase::{
    UseCaseResult, UseCaseError, DomainEvent, ExecutionContext,
    TracingContext, UnitOfWork, MongoUnitOfWork,
};
