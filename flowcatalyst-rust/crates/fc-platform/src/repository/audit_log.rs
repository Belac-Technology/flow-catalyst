//! Audit Log Repository

use mongodb::{Collection, Database, bson::doc, options::FindOptions};
use futures::TryStreamExt;
use chrono::{DateTime, Utc};
use crate::domain::{AuditLog, AuditAction};
use crate::error::Result;

pub struct AuditLogRepository {
    collection: Collection<AuditLog>,
}

impl AuditLogRepository {
    pub fn new(db: &Database) -> Self {
        Self {
            collection: db.collection("audit_logs"),
        }
    }

    pub async fn insert(&self, log: &AuditLog) -> Result<()> {
        self.collection.insert_one(log).await?;
        Ok(())
    }

    pub async fn insert_many(&self, logs: &[AuditLog]) -> Result<usize> {
        if logs.is_empty() {
            return Ok(0);
        }
        let result = self.collection.insert_many(logs).await?;
        Ok(result.inserted_ids.len())
    }

    pub async fn find_by_id(&self, id: &str) -> Result<Option<AuditLog>> {
        Ok(self.collection.find_one(doc! { "_id": id }).await?)
    }

    pub async fn find_by_entity(
        &self,
        entity_type: &str,
        entity_id: &str,
        limit: i64,
    ) -> Result<Vec<AuditLog>> {
        let options = FindOptions::builder()
            .sort(doc! { "createdAt": -1 })
            .limit(limit)
            .build();

        let cursor = self.collection
            .find(doc! { "entityType": entity_type, "entityId": entity_id })
            .with_options(options)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_principal(
        &self,
        principal_id: &str,
        limit: i64,
    ) -> Result<Vec<AuditLog>> {
        let options = FindOptions::builder()
            .sort(doc! { "createdAt": -1 })
            .limit(limit)
            .build();

        let cursor = self.collection
            .find(doc! { "principalId": principal_id })
            .with_options(options)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_client(
        &self,
        client_id: &str,
        limit: i64,
    ) -> Result<Vec<AuditLog>> {
        let options = FindOptions::builder()
            .sort(doc! { "createdAt": -1 })
            .limit(limit)
            .build();

        let cursor = self.collection
            .find(doc! { "clientId": client_id })
            .with_options(options)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_action(
        &self,
        action: AuditAction,
        limit: i64,
    ) -> Result<Vec<AuditLog>> {
        let action_str = serde_json::to_string(&action)
            .unwrap_or_default()
            .trim_matches('"')
            .to_string();

        let options = FindOptions::builder()
            .sort(doc! { "createdAt": -1 })
            .limit(limit)
            .build();

        let cursor = self.collection
            .find(doc! { "action": action_str })
            .with_options(options)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_by_date_range(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
        limit: i64,
    ) -> Result<Vec<AuditLog>> {
        let start_bson = mongodb::bson::DateTime::from_chrono(start);
        let end_bson = mongodb::bson::DateTime::from_chrono(end);

        let options = FindOptions::builder()
            .sort(doc! { "createdAt": -1 })
            .limit(limit)
            .build();

        let cursor = self.collection
            .find(doc! {
                "createdAt": {
                    "$gte": start_bson,
                    "$lte": end_bson
                }
            })
            .with_options(options)
            .await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn find_recent(&self, limit: i64) -> Result<Vec<AuditLog>> {
        let options = FindOptions::builder()
            .sort(doc! { "createdAt": -1 })
            .limit(limit)
            .build();

        let cursor = self.collection.find(doc! {}).with_options(options).await?;
        Ok(cursor.try_collect().await?)
    }

    /// Search audit logs with filters
    pub async fn search(
        &self,
        entity_type: Option<&str>,
        entity_id: Option<&str>,
        action: Option<AuditAction>,
        principal_id: Option<&str>,
        client_id: Option<&str>,
        start_date: Option<DateTime<Utc>>,
        end_date: Option<DateTime<Utc>>,
        skip: u64,
        limit: i64,
    ) -> Result<Vec<AuditLog>> {
        let mut filter = doc! {};

        if let Some(et) = entity_type {
            filter.insert("entityType", et);
        }
        if let Some(eid) = entity_id {
            filter.insert("entityId", eid);
        }
        if let Some(a) = action {
            let action_str = serde_json::to_string(&a)
                .unwrap_or_default()
                .trim_matches('"')
                .to_string();
            filter.insert("action", action_str);
        }
        if let Some(pid) = principal_id {
            filter.insert("principalId", pid);
        }
        if let Some(cid) = client_id {
            filter.insert("clientId", cid);
        }

        if start_date.is_some() || end_date.is_some() {
            let mut date_filter = doc! {};
            if let Some(start) = start_date {
                date_filter.insert("$gte", mongodb::bson::DateTime::from_chrono(start));
            }
            if let Some(end) = end_date {
                date_filter.insert("$lte", mongodb::bson::DateTime::from_chrono(end));
            }
            filter.insert("createdAt", date_filter);
        }

        let options = FindOptions::builder()
            .sort(doc! { "createdAt": -1 })
            .skip(skip)
            .limit(limit)
            .build();

        let cursor = self.collection.find(filter).with_options(options).await?;
        Ok(cursor.try_collect().await?)
    }

    pub async fn count(&self) -> Result<u64> {
        Ok(self.collection.count_documents(doc! {}).await?)
    }

    /// Delete logs older than a given date (for retention policy)
    pub async fn delete_older_than(&self, date: DateTime<Utc>) -> Result<u64> {
        let date_bson = mongodb::bson::DateTime::from_chrono(date);
        let result = self.collection
            .delete_many(doc! { "createdAt": { "$lt": date_bson } })
            .await?;
        Ok(result.deleted_count)
    }
}
