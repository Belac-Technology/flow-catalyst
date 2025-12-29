use async_trait::async_trait;
use fc_common::{OutboxItem, OutboxStatus};
use crate::repository::OutboxRepository;
use anyhow::Result;
use sqlx::{MySqlPool, Row};
use chrono::{Utc, DateTime};
use std::time::Duration;
use tracing::info;

pub struct MySqlOutboxRepository {
    pool: MySqlPool,
}

impl MySqlOutboxRepository {
    pub fn new(pool: MySqlPool) -> Self {
        Self { pool }
    }

    pub async fn init_schema(&self) -> Result<()> {
        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS outbox_items (
                id VARCHAR(13) PRIMARY KEY,
                item_type VARCHAR(50) NOT NULL,
                pool_code VARCHAR(100),
                mediation_target VARCHAR(500),
                message_group VARCHAR(255),
                payload TEXT NOT NULL,
                status VARCHAR(20) NOT NULL,
                retry_count INT DEFAULT 0,
                error_message TEXT,
                created_at BIGINT NOT NULL,
                processed_at BIGINT,
                INDEX idx_outbox_status (status),
                INDEX idx_outbox_status_group (status, message_group, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            "#
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    /// Build a query with the appropriate number of placeholders for IN clause
    fn build_in_clause(count: usize) -> String {
        let placeholders: Vec<&str> = (0..count).map(|_| "?").collect();
        placeholders.join(", ")
    }
}

#[async_trait]
impl OutboxRepository for MySqlOutboxRepository {
    async fn fetch_pending(&self, limit: u32) -> Result<Vec<OutboxItem>> {
        let rows = sqlx::query(
            "SELECT id, item_type, pool_code, mediation_target, message_group, payload, status, retry_count, created_at FROM outbox_items WHERE status = 'PENDING' ORDER BY message_group, created_at LIMIT ?"
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

        // MySQL doesn't support ANY(), so we build the IN clause manually
        let in_clause = Self::build_in_clause(ids.len());
        let query = format!(
            "UPDATE outbox_items SET status = 'PROCESSING', processed_at = ? WHERE id IN ({})",
            in_clause
        );

        // Build the query with dynamic bindings
        let mut q = sqlx::query(&query).bind(now);
        for id in &ids {
            q = q.bind(id);
        }

        q.execute(&self.pool).await?;
        Ok(())
    }

    async fn update_status(&self, id: &str, status: OutboxStatus, error: Option<String>) -> Result<()> {
        let status_str = match status {
            OutboxStatus::PENDING => "PENDING",
            OutboxStatus::PROCESSING => "PROCESSING",
            OutboxStatus::COMPLETED => "COMPLETED",
            OutboxStatus::FAILED => "FAILED",
        };

        sqlx::query("UPDATE outbox_items SET status = ?, error_message = ? WHERE id = ?")
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
            AND processed_at < ?
            "#
        )
        .bind(cutoff)
        .execute(&self.pool)
        .await?;

        let recovered = result.rows_affected();
        if recovered > 0 {
            info!("Recovered {} stuck outbox items (MySQL)", recovered);
        }
        Ok(recovered)
    }
}
