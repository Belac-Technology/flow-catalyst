pub mod repository;
pub mod buffer;
pub mod message_group_processor;
pub mod group_distributor;

#[cfg(feature = "sqlite")]
pub mod sqlite;
#[cfg(feature = "postgres")]
pub mod postgres;
#[cfg(feature = "mongo")]
pub mod mongo;

use std::sync::Arc;
use tokio::time::{sleep, Duration};
use crate::repository::OutboxRepository;
use fc_common::{OutboxStatus, Message, MediationType};
use anyhow::Result;
use tracing::{info, error, debug};
use async_trait::async_trait;

// Re-export key types
pub use buffer::{GlobalBuffer, GlobalBufferConfig};
pub use message_group_processor::{
    MessageGroupProcessor, MessageGroupProcessorConfig, MessageDispatcher,
    DispatchResult, ProcessorState, TrackedMessage,
};
pub use group_distributor::{GroupDistributor, GroupDistributorConfig, DistributorStats};

pub struct OutboxProcessor {
    repository: Arc<dyn OutboxRepository>,
    queue_publisher: Arc<dyn QueuePublisher>,
    poll_interval: Duration,
    batch_size: u32,
}

#[async_trait]
pub trait QueuePublisher: Send + Sync {
    async fn publish(&self, message: Message) -> Result<()>;
}

impl OutboxProcessor {
    pub fn new(
        repository: Arc<dyn OutboxRepository>,
        queue_publisher: Arc<dyn QueuePublisher>,
        poll_interval: Duration,
        batch_size: u32,
    ) -> Self {
        Self {
            repository,
            queue_publisher,
            poll_interval,
            batch_size,
        }
    }

    pub async fn start(&self) {
        info!("Starting Outbox Processor");
        loop {
            if let Err(e) = self.process_batch().await {
                error!("Error processing outbox batch: {}", e);
            }
            sleep(self.poll_interval).await;
        }
    }

    async fn process_batch(&self) -> Result<()> {
        let items = self.repository.fetch_pending(self.batch_size).await?;
        if items.is_empty() {
            return Ok(());
        }

        let ids: Vec<String> = items.iter().map(|i| i.id.clone()).collect();
        self.repository.mark_processing(ids).await?;

        for item in items {
            debug!("Processing outbox item [{}]", item.id);

            // Map OutboxItem to Message
            let message = Message {
                id: item.id.clone(),
                pool_code: item.pool_code.clone().unwrap_or_else(|| "DEFAULT".to_string()),
                auth_token: None,
                mediation_type: MediationType::HTTP,
                mediation_target: item.mediation_target.clone().unwrap_or_else(|| "http://localhost:8080".to_string()),
                message_group_id: item.message_group.clone(),
                payload: item.payload.clone(),
                created_at: item.created_at,
            };

            match self.queue_publisher.publish(message).await {
                Ok(_) => {
                    self.repository.update_status(&item.id, OutboxStatus::COMPLETED, None).await?;
                }
                Err(e) => {
                    error!("Failed to publish outbox item [{}]: {}", item.id, e);
                    self.repository.update_status(&item.id, OutboxStatus::FAILED, Some(e.to_string())).await?;
                }
            }
        }

        Ok(())
    }
}