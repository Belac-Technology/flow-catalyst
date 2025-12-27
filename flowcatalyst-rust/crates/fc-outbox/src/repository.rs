use async_trait::async_trait;
use fc_common::{OutboxItem, OutboxStatus};
use anyhow::Result;

#[async_trait]
pub trait OutboxRepository: Send + Sync {
    async fn fetch_pending(&self, limit: u32) -> Result<Vec<OutboxItem>>;
    async fn update_status(&self, id: &str, status: OutboxStatus, error: Option<String>) -> Result<()>;
    async fn mark_processing(&self, ids: Vec<String>) -> Result<()>;
}
