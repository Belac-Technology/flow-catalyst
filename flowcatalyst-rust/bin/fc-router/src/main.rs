//! FlowCatalyst Production Router
//!
//! Consumes messages from SQS and routes them through the processing pipeline.
//! Provides REST API for monitoring, health, and message publishing.
//!
//! ## Production Features
//!
//! - **Dynamic Configuration Sync**: Periodically fetches configuration from a central
//!   service and hot-reloads without restart. Enable with `FLOWCATALYST_CONFIG_SYNC_ENABLED=true`.
//!
//! - **Active/Standby HA**: Uses Redis-based leader election for high availability.
//!   Only the leader processes messages. Enable with `FLOWCATALYST_STANDBY_ENABLED=true`.

use std::sync::Arc;
use std::time::Duration;
use fc_router::{
    QueueManager, HttpMediator, LifecycleManager, LifecycleConfig,
    WarningService, WarningServiceConfig,
    HealthService, HealthServiceConfig,
    CircuitBreakerRegistry,
    // New production features
    ConfigSyncService, ConfigSyncConfig,
    StandbyProcessor, StandbyRouterConfig,
};
use fc_queue::sqs::SqsQueueConsumer;
use fc_common::{RouterConfig, PoolConfig, QueueConfig};
use fc_api::create_router;
use anyhow::Result;
use tracing::{info, warn, error};
use tracing_subscriber::EnvFilter;
use tokio::{signal, net::TcpListener};
use tower_http::cors::{CorsLayer, Any};
use tower_http::trace::TraceLayer;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive(tracing::Level::INFO.into()))
        .init();

    info!("Starting FlowCatalyst Message Router (Production)");

    // 1. Setup AWS Config
    let aws_config = aws_config::load_defaults(aws_config::BehaviorVersion::latest()).await;
    let sqs_client = aws_sdk_sqs::Client::new(&aws_config);

    // 2. Initialize Warning and Health Services
    let warning_service = Arc::new(WarningService::new(WarningServiceConfig::default()));
    let health_service = Arc::new(HealthService::new(
        HealthServiceConfig::default(),
        warning_service.clone(),
    ));

    // 3. Initialize Mediator (production mode: HTTP/2, 15 minute timeout)
    let mediator = Arc::new(HttpMediator::production());

    // 4. Create QueueManager
    let queue_manager = Arc::new(QueueManager::new(mediator.clone()));

    // 5. Setup SQS Consumer
    let queue_url = std::env::var("QUEUE_URL")
        .unwrap_or_else(|_| "http://localhost:4566/000000000000/dev-queue".to_string());
    let visibility_timeout = std::env::var("VISIBILITY_TIMEOUT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(30);

    let consumer = Arc::new(SqsQueueConsumer::from_queue_url(
        sqs_client.clone(),
        queue_url.clone(),
        visibility_timeout,
    ).await);
    queue_manager.add_consumer(consumer).await;

    // 6. Initialize Standby Processor (Active/Passive HA)
    let standby_config = load_standby_config();
    let standby = if standby_config.enabled {
        info!(
            redis_url = %standby_config.redis_url,
            lock_key = %standby_config.lock_key,
            "Initializing standby mode (Active/Passive HA)"
        );
        match StandbyProcessor::new(standby_config).await {
            Ok(processor) => {
                if let Err(e) = processor.start().await {
                    error!(error = %e, "Failed to start standby processor");
                    return Err(anyhow::anyhow!("Standby processor failed to start: {}", e));
                }
                Some(Arc::new(processor))
            }
            Err(e) => {
                error!(error = %e, "Failed to create standby processor");
                return Err(anyhow::anyhow!("Standby processor creation failed: {}", e));
            }
        }
    } else {
        info!("Standby mode disabled - this instance will always be active");
        None
    };

    // 7. Wait for leadership if in standby mode
    if let Some(ref standby_proc) = standby {
        if !standby_proc.is_leader() {
            info!("Waiting to become leader before starting message processing...");
            standby_proc.wait_for_leadership().await;
            info!("Acquired leadership - starting message processing");
        }
    }

    // 8. Initialize Config Sync Service
    let config_sync_config = load_config_sync_config();
    let config_sync = if config_sync_config.enabled {
        info!(
            url = %config_sync_config.config_url,
            interval = ?config_sync_config.sync_interval,
            "Initializing configuration sync"
        );
        let sync_service = Arc::new(ConfigSyncService::new(
            config_sync_config,
            queue_manager.clone(),
            warning_service.clone(),
        ));

        // Perform initial sync
        if let Err(e) = sync_service.initial_sync().await {
            error!(error = %e, "Initial configuration sync failed");
            return Err(anyhow::anyhow!("Initial config sync failed: {}", e));
        }

        Some(sync_service)
    } else {
        // Apply default configuration if config sync is disabled
        info!("Config sync disabled - using environment-based configuration");
        let pool_concurrency = std::env::var("POOL_CONCURRENCY")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(10);

        let router_config = RouterConfig {
            processing_pools: vec![
                PoolConfig {
                    code: "DEFAULT".to_string(),
                    concurrency: pool_concurrency,
                    rate_limit_per_minute: None,
                },
            ],
            queues: vec![
                QueueConfig {
                    name: "sqs-queue".to_string(),
                    uri: queue_url.clone(),
                    connections: 1,
                    visibility_timeout: visibility_timeout as u32,
                },
            ],
        };
        queue_manager.apply_config(router_config).await?;
        None
    };

    // 9. Start lifecycle manager with all features
    let lifecycle = LifecycleManager::start_with_features(
        queue_manager.clone(),
        warning_service.clone(),
        health_service.clone(),
        LifecycleConfig::default(),
        config_sync,
        standby.clone(),
    );

    // 10. Setup HTTP API server
    let api_port: u16 = std::env::var("API_PORT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(8080);

    // Create a simple publisher that publishes to SQS
    let publisher = Arc::new(SqsPublisher::new(sqs_client, queue_url));

    // Create circuit breaker registry for endpoint tracking
    let circuit_breaker_registry = Arc::new(CircuitBreakerRegistry::default());

    let app = create_router(
        publisher,
        queue_manager.clone(),
        warning_service.clone(),
        health_service.clone(),
        circuit_breaker_registry,
    )
    .layer(TraceLayer::new_for_http())
    .layer(CorsLayer::new().allow_origin(Any).allow_methods(Any).allow_headers(Any));

    let addr = format!("0.0.0.0:{}", api_port);
    info!(port = api_port, "Starting HTTP API server");

    let listener = TcpListener::bind(&addr).await?;
    let server_task = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });

    // 11. Start QueueManager in background (respecting standby status)
    let manager_handle = {
        let manager = queue_manager.clone();
        let standby_for_loop = standby.clone();

        tokio::spawn(async move {
            // If we have standby, wait for leadership before processing
            if let Some(ref standby_proc) = standby_for_loop {
                loop {
                    if standby_proc.should_process() {
                        info!("Leader status confirmed - starting message consumption");
                        if let Err(e) = manager.clone().start().await {
                            error!("QueueManager error: {}", e);
                        }
                        // If start() returns, check if we lost leadership
                        if !standby_proc.should_process() {
                            warn!("Lost leadership during processing - pausing");
                            standby_proc.wait_for_leadership().await;
                            info!("Re-acquired leadership - resuming");
                        }
                    } else {
                        // Not leader, wait
                        tokio::time::sleep(Duration::from_secs(1)).await;
                    }
                }
            } else {
                // No standby mode - just run
                if let Err(e) = manager.clone().start().await {
                    error!("QueueManager error: {}", e);
                }
            }
        })
    };

    // Log startup summary
    log_startup_summary(&lifecycle);

    info!("FlowCatalyst Router started. Press Ctrl+C to shutdown.");

    // Wait for shutdown signal
    shutdown_signal().await;
    info!("Shutdown signal received...");

    // Graceful shutdown
    lifecycle.shutdown().await;
    queue_manager.shutdown().await;

    server_task.abort();
    let _ = tokio::time::timeout(
        std::time::Duration::from_secs(30),
        manager_handle,
    ).await;

    info!("FlowCatalyst Router shutdown complete");
    Ok(())
}

/// Load standby configuration from environment variables
fn load_standby_config() -> StandbyRouterConfig {
    let enabled = std::env::var("FLOWCATALYST_STANDBY_ENABLED")
        .map(|v| v.parse().unwrap_or(false))
        .unwrap_or(false);

    let redis_url = std::env::var("FLOWCATALYST_STANDBY_REDIS_URL")
        .or_else(|_| std::env::var("FLOWCATALYST_REDIS_URL"))
        .unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string());

    let lock_key = std::env::var("FLOWCATALYST_STANDBY_LOCK_KEY")
        .unwrap_or_else(|_| "fc:router:leader".to_string());

    let lock_ttl = std::env::var("FLOWCATALYST_STANDBY_LOCK_TTL")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(30);

    let heartbeat_interval = std::env::var("FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(10);

    let instance_id = std::env::var("FLOWCATALYST_INSTANCE_ID")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_default();

    StandbyRouterConfig {
        enabled,
        redis_url,
        lock_key,
        lock_ttl_seconds: lock_ttl,
        heartbeat_interval_seconds: heartbeat_interval,
        instance_id,
    }
}

/// Load config sync configuration from environment variables
fn load_config_sync_config() -> ConfigSyncConfig {
    let enabled = std::env::var("FLOWCATALYST_CONFIG_SYNC_ENABLED")
        .map(|v| v.parse().unwrap_or(false))
        .unwrap_or(false);

    let config_url = std::env::var("FLOWCATALYST_CONFIG_SYNC_URL")
        .unwrap_or_default();

    let interval_secs = std::env::var("FLOWCATALYST_CONFIG_SYNC_INTERVAL")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(300); // 5 minutes default

    let max_retries = std::env::var("FLOWCATALYST_CONFIG_SYNC_MAX_RETRIES")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(12);

    let retry_delay_secs = std::env::var("FLOWCATALYST_CONFIG_SYNC_RETRY_DELAY")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(5);

    let fail_on_initial_error = std::env::var("FLOWCATALYST_CONFIG_SYNC_FAIL_ON_ERROR")
        .map(|v| v.parse().unwrap_or(true))
        .unwrap_or(true);

    ConfigSyncConfig {
        enabled: enabled && !config_url.is_empty(),
        config_url,
        sync_interval: Duration::from_secs(interval_secs),
        max_retry_attempts: max_retries,
        retry_delay: Duration::from_secs(retry_delay_secs),
        request_timeout: Duration::from_secs(30),
        fail_on_initial_sync_error: fail_on_initial_error,
    }
}

/// Log startup summary
fn log_startup_summary(lifecycle: &LifecycleManager) {
    info!("=== FlowCatalyst Router Startup Summary ===");

    if lifecycle.is_leader() {
        info!("  Mode: ACTIVE (processing messages)");
    } else {
        info!("  Mode: STANDBY (waiting for leadership)");
    }

    if lifecycle.standby().is_some() {
        info!("  HA: Enabled (Active/Standby with Redis leader election)");
    } else {
        info!("  HA: Disabled (single instance mode)");
    }

    if lifecycle.config_sync().is_some() {
        info!("  Config Sync: Enabled (dynamic configuration updates)");
    } else {
        info!("  Config Sync: Disabled (static configuration)");
    }

    info!("==========================================");
}

async fn shutdown_signal() {
    let ctrl_c = async {
        signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        signal::unix::signal(signal::unix::SignalKind::terminate())
            .expect("failed to install signal handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}

// Simple SQS publisher implementation
use async_trait::async_trait;
use fc_queue::{QueuePublisher, QueueError};
use fc_common::Message;

struct SqsPublisher {
    client: aws_sdk_sqs::Client,
    queue_url: String,
}

impl SqsPublisher {
    fn new(client: aws_sdk_sqs::Client, queue_url: String) -> Self {
        Self { client, queue_url }
    }
}

#[async_trait]
impl QueuePublisher for SqsPublisher {
    fn identifier(&self) -> &str {
        &self.queue_url
    }

    async fn publish(&self, message: Message) -> fc_queue::Result<String> {
        let message_id = message.id.clone();
        let body = serde_json::to_string(&message)?;

        self.client.send_message()
            .queue_url(&self.queue_url)
            .message_body(body)
            .send()
            .await
            .map_err(|e| QueueError::Sqs(e.to_string()))?;

        Ok(message_id)
    }

    async fn publish_batch(&self, messages: Vec<Message>) -> fc_queue::Result<Vec<String>> {
        let mut ids = Vec::with_capacity(messages.len());
        for message in messages {
            let id = self.publish(message).await?;
            ids.push(id);
        }
        Ok(ids)
    }
}
