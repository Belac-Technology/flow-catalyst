use async_trait::async_trait;
use fc_common::{OutboxItem, OutboxStatus};
use crate::repository::OutboxRepository;
use anyhow::Result;
use mongodb::{Client, Collection};
use mongodb::bson::{doc, Document};
use mongodb::options::FindOptions;
use chrono::{Utc, DateTime};
use futures::stream::TryStreamExt;
use std::time::Duration;
use tracing::info;

pub struct MongoOutboxRepository {
    collection: Collection<Document>,
}

impl MongoOutboxRepository {
    pub fn new(client: Client, db_name: &str, collection_name: &str) -> Self {
        let db = client.database(db_name);
        let collection = db.collection(collection_name);
        Self { collection }
    }
}

#[async_trait]
impl OutboxRepository for MongoOutboxRepository {
    async fn fetch_pending(&self, limit: u32) -> Result<Vec<OutboxItem>> {
        let filter = doc! { "status": "PENDING" };
        let find_options = FindOptions::builder()
            .sort(doc! { "created_at": 1 })
            .limit(limit as i64)
            .build();

        let mut cursor = self.collection.find(filter, find_options).await?;
        let mut items = Vec::new();

        while let Some(doc) = cursor.try_next().await? {
            // MongoDB BSON stores 64-bit integers for timestamps
            let created_at_ts = doc.get_i64("created_at")?;
            let created_at = DateTime::from_timestamp_millis(created_at_ts)
                .ok_or_else(|| anyhow::anyhow!("Invalid timestamp"))?;

            let payload_str = doc.get_str("payload")?;
            let payload: serde_json::Value = serde_json::from_str(payload_str)?;

            let message_group = match doc.get_str("message_group") {
                Ok(s) => Some(s.to_string()),
                Err(_) => None,
            };

            let pool_code = match doc.get_str("pool_code") {
                Ok(s) => Some(s.to_string()),
                Err(_) => None,
            };

            let mediation_target = match doc.get_str("mediation_target") {
                Ok(s) => Some(s.to_string()),
                Err(_) => None,
            };

            items.push(OutboxItem {
                id: doc.get_str("id")?.to_string(),
                item_type: doc.get_str("item_type")?.to_string(),
                pool_code,
                mediation_target,
                message_group,
                payload,
                status: OutboxStatus::PENDING,
                retry_count: doc.get_i32("retry_count").unwrap_or(0) as u32,
                created_at,
            });
        }
        Ok(items)
    }

    async fn mark_processing(&self, ids: Vec<String>) -> Result<()> {
        if ids.is_empty() { return Ok(()); }
        
        let now = Utc::now().timestamp_millis();
        let filter = doc! { "id": { "$in": ids } };
        let update = doc! { 
            "$set": { 
                "status": "PROCESSING",
                "processed_at": now
            } 
        };

        self.collection.update_many(filter, update, None).await?;
        Ok(())
    }

    async fn update_status(&self, id: &str, status: OutboxStatus, error: Option<String>) -> Result<()> {
        let status_str = match status {
            OutboxStatus::PENDING => "PENDING",
            OutboxStatus::PROCESSING => "PROCESSING",
            OutboxStatus::COMPLETED => "COMPLETED",
            OutboxStatus::FAILED => "FAILED",
        };

        let filter = doc! { "id": id };
        let mut set_doc = doc! { "status": status_str };

        if let Some(err) = error {
            set_doc.insert("error_message", err);
        }

        let update = doc! { "$set": set_doc };
        self.collection.update_one(filter, update, None).await?;
        Ok(())
    }

    async fn recover_stuck_items(&self, timeout: Duration) -> Result<u64> {
        let timeout_ms = timeout.as_millis() as i64;
        let cutoff = Utc::now().timestamp_millis() - timeout_ms;

        let filter = doc! {
            "status": "PROCESSING",
            "processed_at": { "$lt": cutoff }
        };

        let update = doc! {
            "$set": { "status": "PENDING" },
            "$unset": { "processed_at": "" }
        };

        let result = self.collection.update_many(filter, update, None).await?;
        let recovered = result.modified_count;

        if recovered > 0 {
            info!("Recovered {} stuck outbox items (MongoDB)", recovered);
        }
        Ok(recovered)
    }
}