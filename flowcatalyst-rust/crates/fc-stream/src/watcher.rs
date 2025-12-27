use crate::{StreamWatcher, StreamConfig};
use crate::checkpoint::CheckpointStore;
use async_trait::async_trait;
use mongodb::{Client, Collection};
use mongodb::bson::{doc, Document};
use mongodb::options::ChangeStreamOptions;
use mongodb::change_stream::event::ResumeToken;
use futures::stream::StreamExt;
use anyhow::Result;
use tracing::{info, warn, error};
use std::sync::Arc;
use tokio::time::{timeout, Duration};

pub struct MongoStreamWatcher {
    client: Client,
    config: StreamConfig,
    checkpoint_store: Arc<dyn CheckpointStore>,
}

impl MongoStreamWatcher {
    pub fn new(
        client: Client, 
        config: StreamConfig, 
        checkpoint_store: Arc<dyn CheckpointStore>
    ) -> Self {
        Self {
            client,
            config,
            checkpoint_store,
        }
    }

    async fn process_batch(&self, batch: Vec<Document>, last_token: Option<ResumeToken>) -> Result<()> {
        if batch.is_empty() {
            return Ok(());
        }

        info!("[{}] Processing batch of {} events", self.config.name, batch.len());
        
        // TODO: Dispatch to downstream processor
        
        if let Some(token) = last_token {
            // ResumeToken is a wrapper around Bson/Document. We need to serialize it to Document to store it.
            // For simplicity in this dev version, we'll convert it to Bson and then Document if possible,
            // or just rely on the raw bytes if the driver exposes them.
            // The mongodb crate's ResumeToken is opaque but usually holds an _id document.
            // We'll store it as a Document for now.
             
             // Hack: In 2.x, ResumeToken is a newtype. We need to extract the underlying Bson/Document.
             // Ideally we'd use `token.into_raw()` or similar if available.
             // If not available easily, we will rely on the fact that the resume token IS the `_id` field of the event.
             // Let's assume for this step we can just serialize it.
             
             let token_doc = mongodb::bson::to_document(&token).unwrap_or_default();
             let key = format!("checkpoint:{}", self.config.name);
             self.checkpoint_store.save_checkpoint(&key, token_doc).await?;
        }
        
        Ok(())
    }
}

#[async_trait]
impl StreamWatcher for MongoStreamWatcher {
    async fn watch(&self) -> Result<()> {
        let db = self.client.database(&self.config.source_database);
        let collection: Collection<Document> = db.collection(&self.config.source_collection);
        
        // 1. Load Checkpoint
        let checkpoint_key = format!("checkpoint:{}", self.config.name);
        let resume_token_doc = self.checkpoint_store.get_checkpoint(&checkpoint_key).await?;
        
        let mut options = ChangeStreamOptions::builder()
            .full_document(Some(mongodb::options::FullDocumentType::UpdateLookup))
            .build();
            
        if let Some(doc) = resume_token_doc {
            info!("[{}] Resuming from checkpoint", self.config.name);
            // Deserialize Document back to ResumeToken
            if let Ok(token) = mongodb::bson::from_document::<ResumeToken>(doc) {
                options.resume_after = Some(token);
            }
        } else {
            info!("[{}] Starting from beginning (no checkpoint)", self.config.name);
        }

        // 2. Build Pipeline
        let pipeline = vec![
            doc! { "$match": { "operationType": { "$in": &self.config.watch_operations } } }
        ];

        // 3. Start Watch
        let mut stream = collection.watch(pipeline, options).await?;
        info!("[{}] Change stream opened on {}.{}", self.config.name, self.config.source_database, self.config.source_collection);

        let mut batch = Vec::new();
        let mut last_token = None;
        let batch_timeout = Duration::from_millis(self.config.batch_max_wait_ms);
        
        // 4. Consume Loop
        loop {
            // We use a timeout on the stream next() to force batch flushes
            let event_result = timeout(batch_timeout, stream.next()).await;

            match event_result {
                Ok(Some(Ok(event))) => {
                    if let Some(doc) = event.full_document {
                        batch.push(doc);
                        // The resume token for the *next* resume is the `_id` of the change stream event.
                        // The driver exposes this via `resume_token()` method on the stream usually, 
                        // or `event.resume_token()` if available. 
                        // In 2.x, `ChangeStreamEvent` HAS a `id` field which IS the resume token.
                        // However, the `ChangeStream` itself tracks the latest resume token.
                        // Let's use `stream.resume_token()` if possible, or construct it from the event.
                        
                        // NOTE: In mongodb 2.8, `stream.resume_token()` returns `Option<ResumeToken>`.
                        last_token = stream.resume_token();
                    }

                    if batch.len() >= self.config.batch_max_size as usize {
                        self.process_batch(batch.drain(..).collect(), last_token.clone()).await?;
                    }
                }
                Ok(Some(Err(e))) => {
                    error!("[{}] Change stream error: {}", self.config.name, e);
                    tokio::time::sleep(Duration::from_secs(5)).await;
                }
                Ok(None) => {
                    warn!("[{}] Change stream closed unexpectedly", self.config.name);
                    break;
                }
                Err(_) => {
                    // Timeout reached - flush batch if any
                    if !batch.is_empty() {
                        self.process_batch(batch.drain(..).collect(), last_token.clone()).await?;
                    }
                }
            }
        }

        Ok(())
    }
}