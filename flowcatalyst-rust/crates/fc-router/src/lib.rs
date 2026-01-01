//! FlowCatalyst Message Router
//!
//! This crate provides the core message routing functionality with:
//! - QueueManager: Central orchestrator for message routing
//! - ProcessPool: Worker pools with concurrency control, rate limiting, and FIFO ordering
//! - HttpMediator: HTTP-based message delivery with circuit breaker and retry
//! - WarningService: In-memory warning storage with categories and severity
//! - HealthService: System health monitoring with rolling windows
//! - Lifecycle: Background tasks for visibility extension, health checks, etc.
//! - PoolMetricsCollector: Enhanced metrics with sliding windows and percentiles
//! - CircuitBreakerRegistry: Per-endpoint circuit breaker tracking for monitoring

pub mod error;
pub mod manager;
pub mod pool;
pub mod mediator;
pub mod lifecycle;
pub mod router_metrics;
pub mod warning;
pub mod health;
pub mod metrics;
pub mod circuit_breaker_registry;

pub use error::RouterError;
pub use manager::{QueueManager, InFlightMessageInfo};
pub use pool::{ProcessPool, PoolConfigUpdate};
pub use mediator::{Mediator, HttpMediator, CircuitState, HttpMediatorConfig, HttpVersion};
pub use lifecycle::{LifecycleManager, LifecycleConfig};
pub use warning::{WarningService, WarningServiceConfig};
pub use health::{HealthService, HealthServiceConfig};
pub use metrics::{PoolMetricsCollector, MetricsConfig};
pub use circuit_breaker_registry::{CircuitBreakerRegistry, CircuitBreakerConfig, CircuitBreakerStats, CircuitBreakerState};

// Re-export QueueMetrics for API
pub use fc_queue::QueueMetrics;

pub type Result<T> = std::result::Result<T, RouterError>;
