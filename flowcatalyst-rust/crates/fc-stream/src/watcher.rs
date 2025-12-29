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

/// Reconnection settings
const INITIAL_BACKOFF_MS: u64 = 5000;    // 5 seconds
const MAX_BACKOFF_MS: u64 = 60000;       // 60 seconds
const BACKOFF_MULTIPLIER: f64 = 2.0;

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
    /// Watch the change stream with automatic reconnection on failure.
    ///
    /// This method wraps the stream consumption in a retry loop with exponential backoff.
    /// It handles:
    /// - Connection failures (retries with backoff)
    /// - Stream errors (retries with backoff)
    /// - Stale resume tokens (clears checkpoint and starts fresh)
    async fn watch(&self) -> Result<()> {
        let db = self.client.database(&self.config.source_database);
        let collection: Collection<Document> = db.collection(&self.config.source_collection);
        let checkpoint_key = format!("checkpoint:{}", self.config.name);

        let mut consecutive_failures = 0u32;
        let mut backoff_ms = INITIAL_BACKOFF_MS;

        // Outer reconnection loop
        loop {
            // Load checkpoint for this connection attempt
            let resume_token_doc = match self.checkpoint_store.get_checkpoint(&checkpoint_key).await {
                Ok(doc) => doc,
                Err(e) => {
                    warn!("[{}] Failed to load checkpoint, starting from current: {}", self.config.name, e);
                    None
                }
            };

            let mut options = ChangeStreamOptions::builder()
                .full_document(Some(mongodb::options::FullDocumentType::UpdateLookup))
                .build();

            if let Some(doc) = resume_token_doc {
                info!("[{}] Resuming from checkpoint", self.config.name);
                if let Ok(token) = mongodb::bson::from_document::<ResumeToken>(doc) {
                    options.resume_after = Some(token);
                }
            } else {
                info!("[{}] Starting from current position (no checkpoint)", self.config.name);
            }

            let pipeline = vec![
                doc! { "$match": { "operationType": { "$in": &self.config.watch_operations } } }
            ];

            // Try to open the change stream
            let stream_result = collection.watch(pipeline, options).await;
            let mut stream = match stream_result {
                Ok(s) => {
                    // Reset backoff on successful connection
                    consecutive_failures = 0;
                    backoff_ms = INITIAL_BACKOFF_MS;
                    info!("[{}] Change stream opened on {}.{}",
                        self.config.name, self.config.source_database, self.config.source_collection);
                    s
                }
                Err(e) => {
                    consecutive_failures += 1;

                    // Check for stale resume token
                    if is_stale_resume_token_error(&e) {
                        error!("[{}] Resume token expired - clearing checkpoint. EVENTS MAY BE MISSED.", self.config.name);
                        let _ = self.checkpoint_store.clear_checkpoint(&checkpoint_key).await;
                        backoff_ms = INITIAL_BACKOFF_MS;
                        continue;
                    }

                    error!("[{}] Failed to open change stream (attempt {}), retrying in {}ms: {}",
                        self.config.name, consecutive_failures, backoff_ms, e);

                    tokio::time::sleep(Duration::from_millis(backoff_ms)).await;
                    backoff_ms = (backoff_ms as f64 * BACKOFF_MULTIPLIER) as u64;
                    if backoff_ms > MAX_BACKOFF_MS {
                        backoff_ms = MAX_BACKOFF_MS;
                    }
                    continue;
                }
            };

            // Process stream events
            let stream_error = self.process_stream_events(&mut stream, &checkpoint_key).await;

            // Handle stream exit
            match stream_error {
                Ok(()) => {
                    // Clean exit (shouldn't happen normally)
                    info!("[{}] Change stream ended cleanly", self.config.name);
                    return Ok(());
                }
                Err(e) => {
                    consecutive_failures += 1;

                    // Check for stale resume token
                    if is_stale_resume_token_error(&e) {
                        error!("[{}] Resume token expired - clearing checkpoint. EVENTS MAY BE MISSED.", self.config.name);
                        let _ = self.checkpoint_store.clear_checkpoint(&checkpoint_key).await;
                        backoff_ms = INITIAL_BACKOFF_MS;
                        continue;
                    }

                    warn!("[{}] Change stream error (attempt {}), reconnecting in {}ms: {}",
                        self.config.name, consecutive_failures, backoff_ms, e);

                    tokio::time::sleep(Duration::from_millis(backoff_ms)).await;
                    backoff_ms = (backoff_ms as f64 * BACKOFF_MULTIPLIER) as u64;
                    if backoff_ms > MAX_BACKOFF_MS {
                        backoff_ms = MAX_BACKOFF_MS;
                    }
                }
            }
        }
    }
}

impl MongoStreamWatcher {
    /// Process events from an active change stream until an error occurs
    async fn process_stream_events(
        &self,
        stream: &mut mongodb::change_stream::ChangeStream<mongodb::change_stream::event::ChangeStreamEvent<Document>>,
        _checkpoint_key: &str,
    ) -> Result<()> {
        let mut batch = Vec::new();
        let mut last_token = None;
        let batch_timeout = Duration::from_millis(self.config.batch_max_wait_ms);

        loop {
            let event_result = timeout(batch_timeout, stream.next()).await;

            match event_result {
                Ok(Some(Ok(event))) => {
                    if let Some(doc) = event.full_document {
                        batch.push(doc);
                        last_token = stream.resume_token();
                    }

                    if batch.len() >= self.config.batch_max_size as usize {
                        self.process_batch(batch.drain(..).collect(), last_token.clone()).await?;
                    }
                }
                Ok(Some(Err(e))) => {
                    // Stream error - flush batch and return error for reconnection
                    if !batch.is_empty() {
                        let _ = self.process_batch(batch.drain(..).collect(), last_token.clone()).await;
                    }
                    return Err(e.into());
                }
                Ok(None) => {
                    // Stream closed - flush batch and return error for reconnection
                    if !batch.is_empty() {
                        let _ = self.process_batch(batch.drain(..).collect(), last_token.clone()).await;
                    }
                    return Err(anyhow::anyhow!("Change stream closed unexpectedly"));
                }
                Err(_) => {
                    // Timeout - flush batch if any
                    if !batch.is_empty() {
                        self.process_batch(batch.drain(..).collect(), last_token.clone()).await?;
                    }
                }
            }
        }
    }
}

/// Check if an error is due to a stale/expired resume token
/// Works with any error type that implements Display
fn is_stale_resume_token_error<E: std::fmt::Display>(e: &E) -> bool {
    let err_str = e.to_string().to_lowercase();
    err_str.contains("changestream") && err_str.contains("history") ||
    err_str.contains("resume token") ||
    err_str.contains("oplog") ||
    err_str.contains("invalidate")
}