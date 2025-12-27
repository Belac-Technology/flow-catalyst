//! FlowCatalyst Production Router
//!
//! Consumes messages from SQS and routes them through the processing pipeline.
//! Provides REST API for monitoring, health, and message publishing.

use std::sync::Arc;
use std::net::SocketAddr;
use fc_router::{
    QueueManager, HttpMediator, LifecycleManager, LifecycleConfig,
    WarningService, WarningServiceConfig,
    HealthService, HealthServiceConfig,
};
use fc_queue::sqs::SqsQueueConsumer;
use fc_common::{RouterConfig, PoolConfig, QueueConfig};
use fc_api::create_router;
use anyhow::Result;
use tracing::{info, error};
use tracing_subscriber::EnvFilter;
use tokio::signal;
use tower_http::trace::TraceLayer;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive(tracing::Level::INFO.into()))
        .init();

    info!("Starting FlowCatalyst Message Router (Production)");

    // 1. Setup AWS Config
    let config = aws_config::load_defaults(aws_config::BehaviorVersion::latest()).await;
    let sqs_client = aws_sdk_sqs::Client::new(&config);

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
    queue_manager.add_consumer(consumer);

    // 6. Apply configuration
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
                uri: queue_url,
                connections: 1,
                visibility_timeout: visibility_timeout as u32,
            },
        ],
    };
    queue_manager.apply_config(router_config).await?;

    // 7. Start lifecycle manager with health monitoring
    let lifecycle = LifecycleManager::start(
        queue_manager.clone(),
        warning_service.clone(),
        health_service.clone(),
        LifecycleConfig::default(),
    );

    // 8. Setup HTTP API server
    let api_port: u16 = std::env::var("API_PORT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(8080);

    // Create a simple publisher that publishes to SQS
    let publisher = Arc::new(SqsPublisher::new(sqs_client, std::env::var("QUEUE_URL")
        .unwrap_or_else(|_| "http://localhost:4566/000000000000/dev-queue".to_string())));

    let app = create_router(
        publisher,
        queue_manager.clone(),
        warning_service.clone(),
        health_service.clone(),
    )
    .layer(TraceLayer::new_for_http());

    let addr = SocketAddr::from(([0, 0, 0, 0], api_port));
    info!(port = api_port, "Starting HTTP API server");

    let server_handle = tokio::spawn(async move {
        let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
        axum::serve(listener, app).await.unwrap();
    });

    // 9. Start QueueManager in background
    let manager_handle = {
        let manager = queue_manager.clone();
        tokio::spawn(async move {
            if let Err(e) = manager.start().await {
                error!("QueueManager error: {}", e);
            }
        })
    };

    info!("FlowCatalyst Router started. Press Ctrl+C to shutdown.");

    // Wait for shutdown signal
    shutdown_signal().await;
    info!("Shutdown signal received...");

    // Graceful shutdown
    lifecycle.shutdown().await;
    queue_manager.shutdown().await;

    server_handle.abort();
    let _ = tokio::time::timeout(
        std::time::Duration::from_secs(30),
        manager_handle,
    ).await;

    info!("FlowCatalyst Router shutdown complete");
    Ok(())
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
