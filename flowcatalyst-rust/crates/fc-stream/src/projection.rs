//! Read Projections
//!
//! Creates denormalized read projections for events and dispatch jobs.

use std::sync::Arc;
use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use async_trait::async_trait;
use tracing::debug;

/// Event read projection
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventReadProjection {
    #[serde(rename = "_id")]
    pub id: String,
    pub event_type_code: String,
    pub event_type_name: String,
    pub application: String,
    pub subdomain: String,
    pub subject: String,
    pub action: String,
    pub client_id: Option<String>,
    pub client_name: Option<String>,
    pub source_id: Option<String>,
    pub source_type: Option<String>,
    pub correlation_id: Option<String>,
    pub data_summary: Option<String>,
    pub dispatch_job_count: u32,
    pub created_at: DateTime<Utc>,
}

/// Dispatch job read projection
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DispatchJobReadProjection {
    #[serde(rename = "_id")]
    pub id: String,
    pub event_id: String,
    pub event_type_code: String,
    pub event_type_name: String,
    pub subscription_id: String,
    pub subscription_name: String,
    pub client_id: Option<String>,
    pub client_name: Option<String>,
    pub target: String,
    pub status: String,
    pub attempt_count: u32,
    pub max_retries: u32,
    pub last_error: Option<String>,
    pub last_attempt_at: Option<DateTime<Utc>>,
    pub next_retry_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub correlation_id: Option<String>,
    pub dispatch_pool_id: Option<String>,
    pub dispatch_pool_name: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// Lookup service for resolving names
#[async_trait]
pub trait ProjectionLookup: Send + Sync {
    async fn get_event_type_name(&self, code: &str) -> Option<String>;
    async fn get_client_name(&self, id: &str) -> Option<String>;
    async fn get_subscription_name(&self, id: &str) -> Option<String>;
    async fn get_dispatch_pool_name(&self, id: &str) -> Option<String>;
}

/// Storage for projections
#[async_trait]
pub trait ProjectionStore: Send + Sync {
    async fn save_event_projection(&self, projection: &EventReadProjection) -> Result<(), String>;
    async fn save_dispatch_job_projection(&self, projection: &DispatchJobReadProjection) -> Result<(), String>;
    async fn update_dispatch_job_projection(&self, projection: &DispatchJobReadProjection) -> Result<(), String>;
    async fn increment_event_dispatch_count(&self, event_id: &str) -> Result<(), String>;
}

/// Event data for projection creation
#[derive(Debug, Clone)]
pub struct EventData {
    pub id: String,
    pub event_type_code: String,
    pub client_id: Option<String>,
    pub source_id: Option<String>,
    pub source_type: Option<String>,
    pub correlation_id: Option<String>,
    pub data: serde_json::Value,
    pub created_at: DateTime<Utc>,
}

/// Dispatch job data for projection creation
#[derive(Debug, Clone)]
pub struct DispatchJobData {
    pub id: String,
    pub event_id: String,
    pub event_type_code: String,
    pub subscription_id: String,
    pub client_id: Option<String>,
    pub target: String,
    pub status: String,
    pub attempt_count: u32,
    pub max_retries: u32,
    pub last_error: Option<String>,
    pub last_attempt_at: Option<DateTime<Utc>>,
    pub next_retry_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub correlation_id: Option<String>,
    pub dispatch_pool_id: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// Projection builder
pub struct ProjectionBuilder {
    lookup: Arc<dyn ProjectionLookup>,
    store: Arc<dyn ProjectionStore>,
}

impl ProjectionBuilder {
    pub fn new(lookup: Arc<dyn ProjectionLookup>, store: Arc<dyn ProjectionStore>) -> Self {
        Self { lookup, store }
    }

    /// Create projection for a new event
    pub async fn create_event_projection(&self, event: &EventData) -> Result<EventReadProjection, String> {
        // Parse event type code (app:subdomain:subject:action)
        let parts: Vec<&str> = event.event_type_code.split(':').collect();
        let (application, subdomain, subject, action) = if parts.len() == 4 {
            (
                parts[0].to_string(),
                parts[1].to_string(),
                parts[2].to_string(),
                parts[3].to_string(),
            )
        } else {
            (
                "unknown".to_string(),
                "unknown".to_string(),
                "unknown".to_string(),
                "unknown".to_string(),
            )
        };

        let event_type_name = self
            .lookup
            .get_event_type_name(&event.event_type_code)
            .await
            .unwrap_or_else(|| event.event_type_code.clone());

        let client_name = match &event.client_id {
            Some(id) => self.lookup.get_client_name(id).await,
            None => None,
        };

        // Create data summary (first 200 chars of JSON)
        let data_summary = {
            let json = serde_json::to_string(&event.data).unwrap_or_default();
            if json.len() > 200 {
                Some(format!("{}...", &json[..200]))
            } else if json != "{}" && json != "null" {
                Some(json)
            } else {
                None
            }
        };

        let projection = EventReadProjection {
            id: event.id.clone(),
            event_type_code: event.event_type_code.clone(),
            event_type_name,
            application,
            subdomain,
            subject,
            action,
            client_id: event.client_id.clone(),
            client_name,
            source_id: event.source_id.clone(),
            source_type: event.source_type.clone(),
            correlation_id: event.correlation_id.clone(),
            data_summary,
            dispatch_job_count: 0,
            created_at: event.created_at,
        };

        self.store.save_event_projection(&projection).await?;
        debug!("Created event projection: {}", event.id);

        Ok(projection)
    }

    /// Create projection for a new dispatch job
    pub async fn create_dispatch_job_projection(
        &self,
        job: &DispatchJobData,
    ) -> Result<DispatchJobReadProjection, String> {
        let event_type_name = self
            .lookup
            .get_event_type_name(&job.event_type_code)
            .await
            .unwrap_or_else(|| job.event_type_code.clone());

        let subscription_name = self
            .lookup
            .get_subscription_name(&job.subscription_id)
            .await
            .unwrap_or_else(|| job.subscription_id.clone());

        let client_name = match &job.client_id {
            Some(id) => self.lookup.get_client_name(id).await,
            None => None,
        };

        let dispatch_pool_name = match &job.dispatch_pool_id {
            Some(id) => self.lookup.get_dispatch_pool_name(id).await,
            None => None,
        };

        let projection = DispatchJobReadProjection {
            id: job.id.clone(),
            event_id: job.event_id.clone(),
            event_type_code: job.event_type_code.clone(),
            event_type_name,
            subscription_id: job.subscription_id.clone(),
            subscription_name,
            client_id: job.client_id.clone(),
            client_name,
            target: job.target.clone(),
            status: job.status.clone(),
            attempt_count: job.attempt_count,
            max_retries: job.max_retries,
            last_error: job.last_error.clone(),
            last_attempt_at: job.last_attempt_at,
            next_retry_at: job.next_retry_at,
            completed_at: job.completed_at,
            correlation_id: job.correlation_id.clone(),
            dispatch_pool_id: job.dispatch_pool_id.clone(),
            dispatch_pool_name,
            created_at: job.created_at,
            updated_at: job.updated_at,
        };

        self.store.save_dispatch_job_projection(&projection).await?;

        // Increment event's dispatch job count
        self.store.increment_event_dispatch_count(&job.event_id).await?;

        debug!("Created dispatch job projection: {}", job.id);

        Ok(projection)
    }

    /// Update projection for an existing dispatch job
    pub async fn update_dispatch_job_projection(
        &self,
        job: &DispatchJobData,
    ) -> Result<DispatchJobReadProjection, String> {
        let event_type_name = self
            .lookup
            .get_event_type_name(&job.event_type_code)
            .await
            .unwrap_or_else(|| job.event_type_code.clone());

        let subscription_name = self
            .lookup
            .get_subscription_name(&job.subscription_id)
            .await
            .unwrap_or_else(|| job.subscription_id.clone());

        let client_name = match &job.client_id {
            Some(id) => self.lookup.get_client_name(id).await,
            None => None,
        };

        let dispatch_pool_name = match &job.dispatch_pool_id {
            Some(id) => self.lookup.get_dispatch_pool_name(id).await,
            None => None,
        };

        let projection = DispatchJobReadProjection {
            id: job.id.clone(),
            event_id: job.event_id.clone(),
            event_type_code: job.event_type_code.clone(),
            event_type_name,
            subscription_id: job.subscription_id.clone(),
            subscription_name,
            client_id: job.client_id.clone(),
            client_name,
            target: job.target.clone(),
            status: job.status.clone(),
            attempt_count: job.attempt_count,
            max_retries: job.max_retries,
            last_error: job.last_error.clone(),
            last_attempt_at: job.last_attempt_at,
            next_retry_at: job.next_retry_at,
            completed_at: job.completed_at,
            correlation_id: job.correlation_id.clone(),
            dispatch_pool_id: job.dispatch_pool_id.clone(),
            dispatch_pool_name,
            created_at: job.created_at,
            updated_at: job.updated_at,
        };

        self.store.update_dispatch_job_projection(&projection).await?;
        debug!("Updated dispatch job projection: {}", job.id);

        Ok(projection)
    }
}

/// MongoDB implementation of projection store
pub struct MongoProjectionStore {
    events_read: mongodb::Collection<mongodb::bson::Document>,
    dispatch_jobs_read: mongodb::Collection<mongodb::bson::Document>,
}

impl MongoProjectionStore {
    pub fn new(db: &mongodb::Database) -> Self {
        Self {
            events_read: db.collection("events_read"),
            dispatch_jobs_read: db.collection("dispatch_jobs_read"),
        }
    }

    /// Ensure all required indexes exist on the projection collections.
    /// Should be called on startup.
    pub async fn ensure_indexes(&self) -> Result<(), String> {
        use mongodb::bson::doc;
        use mongodb::options::IndexOptions;
        use mongodb::IndexModel;
        use tracing::info;

        // Events read collection indexes
        let event_indexes = vec![
            // Query by client and time (UI list view)
            IndexModel::builder()
                .keys(doc! { "clientId": 1, "createdAt": -1 })
                .options(IndexOptions::builder().name("idx_client_time".to_string()).build())
                .build(),
            // Query by event type
            IndexModel::builder()
                .keys(doc! { "eventTypeCode": 1, "createdAt": -1 })
                .options(IndexOptions::builder().name("idx_event_type".to_string()).build())
                .build(),
            // Query by correlation ID (tracing)
            IndexModel::builder()
                .keys(doc! { "correlationId": 1 })
                .options(IndexOptions::builder().name("idx_correlation".to_string()).sparse(true).build())
                .build(),
            // Query by source (entity-specific views)
            IndexModel::builder()
                .keys(doc! { "sourceType": 1, "sourceId": 1, "createdAt": -1 })
                .options(IndexOptions::builder().name("idx_source".to_string()).sparse(true).build())
                .build(),
            // Full-text search on data summary
            IndexModel::builder()
                .keys(doc! { "dataSummary": "text" })
                .options(IndexOptions::builder().name("idx_data_search".to_string()).build())
                .build(),
        ];

        self.events_read
            .create_indexes(event_indexes, None)
            .await
            .map_err(|e| format!("Failed to create event indexes: {}", e))?;

        info!("Created indexes on events_read collection");

        // Dispatch jobs read collection indexes
        let job_indexes = vec![
            // Query by client and time (UI list view)
            IndexModel::builder()
                .keys(doc! { "clientId": 1, "createdAt": -1 })
                .options(IndexOptions::builder().name("idx_client_time".to_string()).build())
                .build(),
            // Query by status (filter by pending, failed, etc.)
            IndexModel::builder()
                .keys(doc! { "status": 1, "createdAt": -1 })
                .options(IndexOptions::builder().name("idx_status".to_string()).build())
                .build(),
            // Query by event ID (show dispatch jobs for an event)
            IndexModel::builder()
                .keys(doc! { "eventId": 1 })
                .options(IndexOptions::builder().name("idx_event".to_string()).build())
                .build(),
            // Query by subscription (analytics)
            IndexModel::builder()
                .keys(doc! { "subscriptionId": 1, "createdAt": -1 })
                .options(IndexOptions::builder().name("idx_subscription".to_string()).build())
                .build(),
            // Query by correlation ID (tracing)
            IndexModel::builder()
                .keys(doc! { "correlationId": 1 })
                .options(IndexOptions::builder().name("idx_correlation".to_string()).sparse(true).build())
                .build(),
            // Query pending retries (scheduler)
            IndexModel::builder()
                .keys(doc! { "status": 1, "nextRetryAt": 1 })
                .options(IndexOptions::builder().name("idx_pending_retry".to_string()).build())
                .build(),
            // Query by dispatch pool (analytics)
            IndexModel::builder()
                .keys(doc! { "dispatchPoolId": 1, "createdAt": -1 })
                .options(IndexOptions::builder().name("idx_pool".to_string()).sparse(true).build())
                .build(),
        ];

        self.dispatch_jobs_read
            .create_indexes(job_indexes, None)
            .await
            .map_err(|e| format!("Failed to create dispatch job indexes: {}", e))?;

        info!("Created indexes on dispatch_jobs_read collection");

        Ok(())
    }
}

#[async_trait]
impl ProjectionStore for MongoProjectionStore {
    async fn save_event_projection(&self, projection: &EventReadProjection) -> Result<(), String> {
        let doc = mongodb::bson::to_document(projection)
            .map_err(|e| format!("Serialization error: {}", e))?;

        self.events_read
            .insert_one(doc, None)
            .await
            .map_err(|e| format!("MongoDB insert error: {}", e))?;

        Ok(())
    }

    async fn save_dispatch_job_projection(
        &self,
        projection: &DispatchJobReadProjection,
    ) -> Result<(), String> {
        let doc = mongodb::bson::to_document(projection)
            .map_err(|e| format!("Serialization error: {}", e))?;

        self.dispatch_jobs_read
            .insert_one(doc, None)
            .await
            .map_err(|e| format!("MongoDB insert error: {}", e))?;

        Ok(())
    }

    async fn update_dispatch_job_projection(
        &self,
        projection: &DispatchJobReadProjection,
    ) -> Result<(), String> {
        use mongodb::bson::doc;

        let doc = mongodb::bson::to_document(projection)
            .map_err(|e| format!("Serialization error: {}", e))?;

        self.dispatch_jobs_read
            .replace_one(doc! { "_id": &projection.id }, doc, None)
            .await
            .map_err(|e| format!("MongoDB replace error: {}", e))?;

        Ok(())
    }

    async fn increment_event_dispatch_count(&self, event_id: &str) -> Result<(), String> {
        use mongodb::bson::doc;

        self.events_read
            .update_one(
                doc! { "_id": event_id },
                doc! { "$inc": { "dispatchJobCount": 1 } },
                None,
            )
            .await
            .map_err(|e| format!("MongoDB update error: {}", e))?;

        Ok(())
    }
}

/// In-memory implementation for testing
#[derive(Clone)]
pub struct InMemoryProjectionStore {
    events: Arc<tokio::sync::RwLock<std::collections::HashMap<String, EventReadProjection>>>,
    jobs: Arc<tokio::sync::RwLock<std::collections::HashMap<String, DispatchJobReadProjection>>>,
}

impl InMemoryProjectionStore {
    pub fn new() -> Self {
        Self {
            events: Arc::new(tokio::sync::RwLock::new(std::collections::HashMap::new())),
            jobs: Arc::new(tokio::sync::RwLock::new(std::collections::HashMap::new())),
        }
    }

    pub async fn get_event(&self, id: &str) -> Option<EventReadProjection> {
        self.events.read().await.get(id).cloned()
    }

    pub async fn get_job(&self, id: &str) -> Option<DispatchJobReadProjection> {
        self.jobs.read().await.get(id).cloned()
    }
}

impl Default for InMemoryProjectionStore {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl ProjectionStore for InMemoryProjectionStore {
    async fn save_event_projection(&self, projection: &EventReadProjection) -> Result<(), String> {
        self.events
            .write()
            .await
            .insert(projection.id.clone(), projection.clone());
        Ok(())
    }

    async fn save_dispatch_job_projection(
        &self,
        projection: &DispatchJobReadProjection,
    ) -> Result<(), String> {
        self.jobs
            .write()
            .await
            .insert(projection.id.clone(), projection.clone());
        Ok(())
    }

    async fn update_dispatch_job_projection(
        &self,
        projection: &DispatchJobReadProjection,
    ) -> Result<(), String> {
        self.jobs
            .write()
            .await
            .insert(projection.id.clone(), projection.clone());
        Ok(())
    }

    async fn increment_event_dispatch_count(&self, event_id: &str) -> Result<(), String> {
        if let Some(event) = self.events.write().await.get_mut(event_id) {
            event.dispatch_job_count += 1;
        }
        Ok(())
    }
}

/// In-memory lookup for testing
pub struct InMemoryLookup {
    event_types: std::collections::HashMap<String, String>,
    clients: std::collections::HashMap<String, String>,
    subscriptions: std::collections::HashMap<String, String>,
    pools: std::collections::HashMap<String, String>,
}

impl InMemoryLookup {
    pub fn new() -> Self {
        Self {
            event_types: std::collections::HashMap::new(),
            clients: std::collections::HashMap::new(),
            subscriptions: std::collections::HashMap::new(),
            pools: std::collections::HashMap::new(),
        }
    }

    pub fn add_event_type(&mut self, code: &str, name: &str) {
        self.event_types.insert(code.to_string(), name.to_string());
    }

    pub fn add_client(&mut self, id: &str, name: &str) {
        self.clients.insert(id.to_string(), name.to_string());
    }

    pub fn add_subscription(&mut self, id: &str, name: &str) {
        self.subscriptions.insert(id.to_string(), name.to_string());
    }

    pub fn add_pool(&mut self, id: &str, name: &str) {
        self.pools.insert(id.to_string(), name.to_string());
    }
}

impl Default for InMemoryLookup {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl ProjectionLookup for InMemoryLookup {
    async fn get_event_type_name(&self, code: &str) -> Option<String> {
        self.event_types.get(code).cloned()
    }

    async fn get_client_name(&self, id: &str) -> Option<String> {
        self.clients.get(id).cloned()
    }

    async fn get_subscription_name(&self, id: &str) -> Option<String> {
        self.subscriptions.get(id).cloned()
    }

    async fn get_dispatch_pool_name(&self, id: &str) -> Option<String> {
        self.pools.get(id).cloned()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_create_event_projection() {
        let mut lookup = InMemoryLookup::new();
        lookup.add_event_type("orders:fulfillment:shipment:shipped", "Shipment Shipped");
        lookup.add_client("client-1", "Acme Corp");

        let store = InMemoryProjectionStore::new();
        let builder = ProjectionBuilder::new(
            Arc::new(lookup),
            Arc::new(store.clone()),
        );

        let event = EventData {
            id: "evt-1".to_string(),
            event_type_code: "orders:fulfillment:shipment:shipped".to_string(),
            client_id: Some("client-1".to_string()),
            source_id: Some("order-123".to_string()),
            source_type: Some("Order".to_string()),
            correlation_id: Some("corr-1".to_string()),
            data: serde_json::json!({"tracking": "ABC123"}),
            created_at: Utc::now(),
        };

        let projection = builder.create_event_projection(&event).await.unwrap();

        assert_eq!(projection.id, "evt-1");
        assert_eq!(projection.event_type_name, "Shipment Shipped");
        assert_eq!(projection.client_name, Some("Acme Corp".to_string()));
        assert_eq!(projection.application, "orders");
        assert_eq!(projection.subdomain, "fulfillment");
        assert_eq!(projection.subject, "shipment");
        assert_eq!(projection.action, "shipped");
    }
}
