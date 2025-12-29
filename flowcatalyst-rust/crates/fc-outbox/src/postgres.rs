use async_trait::async_trait;
use fc_common::{OutboxItem, OutboxStatus};
use crate::repository::OutboxRepository;
use anyhow::Result;
use sqlx::{PgPool, Row};
use chrono::{Utc, DateTime};
use std::time::Duration;
use tracing::info;

pub struct PostgresOutboxRepository {
    pool: PgPool,
}

impl PostgresOutboxRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn init_schema(&self) -> Result<()> {
        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS outbox_items (
                id TEXT PRIMARY KEY,
                item_type TEXT NOT NULL,
                pool_code TEXT,
                mediation_target TEXT,
                message_group TEXT,
                payload TEXT NOT NULL,
                status TEXT NOT NULL,
                retry_count INTEGER DEFAULT 0,
                error_message TEXT,
                created_at BIGINT NOT NULL,
                processed_at BIGINT
            );
            CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox_items(status);
            "#
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }
}

#[async_trait]
impl OutboxRepository for PostgresOutboxRepository {
    async fn fetch_pending(&self, limit: u32) -> Result<Vec<OutboxItem>> {
        let rows = sqlx::query(
            "SELECT id, item_type, pool_code, mediation_target, message_group, payload, status, retry_count, created_at FROM outbox_items WHERE status = 'PENDING' ORDER BY created_at LIMIT $1"
        )
        .bind(limit as i64)
        .fetch_all(&self.pool)
        .await?;

        let mut items = Vec::new();
        for row in rows {
            let created_at_ts: i64 = row.get("created_at");
            let created_at = DateTime::from_timestamp_millis(created_at_ts)
                .ok_or_else(|| anyhow::anyhow!("Invalid timestamp"))?;

            items.push(OutboxItem {
                id: row.get("id"),
                item_type: row.get("item_type"),
                pool_code: row.get("pool_code"),
                mediation_target: row.get("mediation_target"),
                message_group: row.get("message_group"),
                payload: serde_json::from_str(row.get("payload"))?,
                status: OutboxStatus::PENDING,
                retry_count: row.get::<i64, _>("retry_count") as u32,
                created_at,
            });
        }
        Ok(items)
    }

    async fn mark_processing(&self, ids: Vec<String>) -> Result<()> {
        if ids.is_empty() { return Ok(()); }
        
        let now = Utc::now().timestamp_millis();
        // Postgres supports ANY($1) for array parameters, which is cleaner than manual string building
        let query = "UPDATE outbox_items SET status = 'PROCESSING', processed_at = $1 WHERE id = ANY($2)";
        
        sqlx::query(query)
            .bind(now)
            .bind(&ids)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    async fn update_status(&self, id: &str, status: OutboxStatus, error: Option<String>) -> Result<()> {
        let status_str = match status {
            OutboxStatus::PENDING => "PENDING",
            OutboxStatus::PROCESSING => "PROCESSING",
            OutboxStatus::COMPLETED => "COMPLETED",
            OutboxStatus::FAILED => "FAILED",
        };

        sqlx::query("UPDATE outbox_items SET status = $1, error_message = $2 WHERE id = $3")
            .bind(status_str)
            .bind(error)
            .bind(id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    async fn recover_stuck_items(&self, timeout: Duration) -> Result<u64> {
        let timeout_ms = timeout.as_millis() as i64;
        let cutoff = Utc::now().timestamp_millis() - timeout_ms;

        let result = sqlx::query(
            r#"
            UPDATE outbox_items
            SET status = 'PENDING', processed_at = NULL
            WHERE status = 'PROCESSING'
            AND processed_at < $1
            "#
        )
        .bind(cutoff)
        .execute(&self.pool)
        .await?;

        let recovered = result.rows_affected();
        if recovered > 0 {
            info!("Recovered {} stuck outbox items (PostgreSQL)", recovered);
        }
        Ok(recovered)
    }
}
