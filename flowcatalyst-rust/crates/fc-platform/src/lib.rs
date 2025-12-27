//! FlowCatalyst Platform
//!
//! Core platform providing:
//! - Event management (CloudEvents spec)
//! - Event type definitions with schema versioning
//! - Dispatch job lifecycle management
//! - Subscription-based event routing
//! - Multi-tenant identity and access control
//! - Service account management for webhooks

pub mod domain;
pub mod repository;
pub mod service;
pub mod api;
pub mod error;
pub mod tsid;

pub use domain::*;
pub use error::PlatformError;
pub use tsid::TsidGenerator;
