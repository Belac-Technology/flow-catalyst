use async_trait::async_trait;
use fc_common::{OutboxItem, OutboxStatus};
use anyhow::Result;
use std::time::Duration;

#[async_trait]
pub trait OutboxRepository: Send + Sync {
    async fn fetch_pending(&self, limit: u32) -> Result<Vec<OutboxItem>>;
    async fn update_status(&self, id: &str, status: OutboxStatus, error: Option<String>) -> Result<()>;
    async fn mark_processing(&self, ids: Vec<String>) -> Result<()>;

    /// Recover items stuck in PROCESSING state for longer than the specified timeout.
    /// Returns the number of items recovered (reset to PENDING).
    async fn recover_stuck_items(&self, timeout: Duration) -> Result<u64>;
}
