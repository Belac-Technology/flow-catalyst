//! FlowCatalyst Outbox Processor
//!
//! Reads messages from application database outbox tables and publishes to SQS queues.
//! Supports multiple database backends: SQLite, PostgreSQL, MongoDB.
//!
//! ## Environment Variables
//!
//! | Variable | Default | Description |
//! |----------|---------|-------------|
//! | `FC_OUTBOX_DB_TYPE` | `postgres` | Database type: `sqlite`, `postgres`, `mongo` |
//! | `FC_OUTBOX_DB_URL` | - | Database connection URL (required) |
//! | `FC_OUTBOX_MONGO_DB` | `flowcatalyst` | MongoDB database name |
//! | `FC_OUTBOX_MONGO_COLLECTION` | `outbox` | MongoDB collection name |
//! | `FC_OUTBOX_POLL_INTERVAL_MS` | `1000` | Poll interval in milliseconds |
//! | `FC_OUTBOX_BATCH_SIZE` | `100` | Max messages per batch |
//! | `FC_QUEUE_URL` | - | SQS queue URL (required) |
//! | `FC_METRICS_PORT` | `9090` | Metrics/health port |
//! | `RUST_LOG` | `info` | Log level |

use std::sync::Arc;
use std::time::Duration;
use std::net::SocketAddr;
use anyhow::Result;
use tracing::info;
use tracing_subscriber::EnvFilter;
use tokio::signal;
use tokio::sync::broadcast;
use async_trait::async_trait;

use fc_outbox::{OutboxProcessor, repository::OutboxRepository};
use fc_common::Message;

use sqlx::sqlite::SqlitePoolOptions;
use sqlx::postgres::PgPoolOptions;

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn env_or_parse<T: std::str::FromStr>(key: &str, default: T) -> T {
    std::env::var(key)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

fn env_required(key: &str) -> Result<String> {
    std::env::var(key).map_err(|_| anyhow::anyhow!("{} environment variable is required", key))
}

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::from_default_env()
                .add_directive(tracing::Level::INFO.into())
        )
        .init();

    info!("Starting FlowCatalyst Outbox Processor");

    // Configuration
    let db_type = env_or("FC_OUTBOX_DB_TYPE", "postgres");
    let poll_interval_ms: u64 = env_or_parse("FC_OUTBOX_POLL_INTERVAL_MS", 1000);
    let batch_size: u32 = env_or_parse("FC_OUTBOX_BATCH_SIZE", 100);
    let metrics_port: u16 = env_or_parse("FC_METRICS_PORT", 9090);
    let queue_url = env_required("FC_QUEUE_URL")?;

    // Setup shutdown signal
    let (shutdown_tx, _) = broadcast::channel::<()>(1);

    // Initialize outbox repository
    let outbox_repo = create_outbox_repository(&db_type).await?;
    info!("Outbox repository initialized ({})", db_type);

    // Initialize SQS publisher
    let config = aws_config::load_defaults(aws_config::BehaviorVersion::latest()).await;
    let sqs_client = aws_sdk_sqs::Client::new(&config);
    let publisher = Arc::new(SqsPublisher::new(sqs_client, queue_url.clone()));
    info!("SQS publisher initialized: {}", queue_url);

    // Create outbox processor
    let processor = OutboxProcessor::new(
        outbox_repo,
        publisher,
        Duration::from_millis(poll_interval_ms),
        batch_size,
    );

    // Start processor
    let processor_handle = {
        let mut shutdown_rx = shutdown_tx.subscribe();
        tokio::spawn(async move {
            tokio::select! {
                _ = processor.start() => {}
                _ = shutdown_rx.recv() => {
                    info!("Outbox processor shutting down");
                }
            }
        })
    };

    // Start metrics server
    let metrics_addr = SocketAddr::from(([0, 0, 0, 0], metrics_port));
    info!("Metrics server listening on http://{}/metrics", metrics_addr);

    let metrics_app = axum::Router::new()
        .route("/metrics", axum::routing::get(metrics_handler))
        .route("/health", axum::routing::get(health_handler))
        .route("/ready", axum::routing::get(ready_handler));

    let metrics_listener = tokio::net::TcpListener::bind(metrics_addr).await?;
    let metrics_handle = {
        let mut shutdown_rx = shutdown_tx.subscribe();
        tokio::spawn(async move {
            axum::serve(metrics_listener, metrics_app)
                .with_graceful_shutdown(async move {
                    let _ = shutdown_rx.recv().await;
                })
                .await
                .ok();
        })
    };

    info!("FlowCatalyst Outbox Processor started");
    info!("Press Ctrl+C to shutdown");

    // Wait for shutdown
    shutdown_signal().await;
    info!("Shutdown signal received...");

    let _ = shutdown_tx.send(());

    let _ = tokio::time::timeout(Duration::from_secs(30), async {
        let _ = processor_handle.await;
        let _ = metrics_handle.await;
    }).await;

    info!("FlowCatalyst Outbox Processor shutdown complete");
    Ok(())
}

async fn create_outbox_repository(db_type: &str) -> Result<Arc<dyn OutboxRepository>> {
    match db_type {
        "sqlite" => {
            let url = env_required("FC_OUTBOX_DB_URL")?;
            let pool = SqlitePoolOptions::new()
                .max_connections(5)
                .connect(&url)
                .await?;
            let repo = fc_outbox::sqlite::SqliteOutboxRepository::new(pool);
            repo.init_schema().await?;
            info!("Using SQLite outbox: {}", url);
            Ok(Arc::new(repo))
        }
        "postgres" => {
            let url = env_required("FC_OUTBOX_DB_URL")?;
            let pool = PgPoolOptions::new()
                .max_connections(10)
                .connect(&url)
                .await?;
            let repo = fc_outbox::postgres::PostgresOutboxRepository::new(pool);
            repo.init_schema().await?;
            info!("Using PostgreSQL outbox");
            Ok(Arc::new(repo))
        }
        "mongo" => {
            let url = env_required("FC_OUTBOX_DB_URL")?;
            let db_name = env_or("FC_OUTBOX_MONGO_DB", "flowcatalyst");
            let collection = env_or("FC_OUTBOX_MONGO_COLLECTION", "outbox");
            let client = mongodb::Client::with_uri_str(&url).await?;
            let repo = fc_outbox::mongo::MongoOutboxRepository::new(client, &db_name, &collection);
            info!("Using MongoDB outbox: {}/{}", db_name, collection);
            Ok(Arc::new(repo))
        }
        other => {
            Err(anyhow::anyhow!("Unknown database type: {}. Use sqlite, postgres, or mongo", other))
        }
    }
}

// SQS Publisher
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
impl fc_outbox::QueuePublisher for SqsPublisher {
    async fn publish(&self, message: Message) -> Result<()> {
        let body = serde_json::to_string(&message)?;

        self.client.send_message()
            .queue_url(&self.queue_url)
            .message_body(body)
            .message_group_id(message.message_group_id.as_deref().unwrap_or("default"))
            .message_deduplication_id(&message.id)
            .send()
            .await
            .map_err(|e| anyhow::anyhow!("SQS send error: {}", e))?;

        Ok(())
    }
}

async fn metrics_handler() -> String {
    "# HELP fc_outbox_up Outbox processor is up\n# TYPE fc_outbox_up gauge\nfc_outbox_up 1\n".to_string()
}

async fn health_handler() -> axum::Json<serde_json::Value> {
    axum::Json(serde_json::json!({
        "status": "UP",
        "version": env!("CARGO_PKG_VERSION")
    }))
}

async fn ready_handler() -> axum::Json<serde_json::Value> {
    axum::Json(serde_json::json!({
        "status": "READY"
    }))
}

async fn shutdown_signal() {
    let ctrl_c = async {
        signal::ctrl_c().await.expect("failed to install Ctrl+C handler");
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
