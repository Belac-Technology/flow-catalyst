//! QueueManager - Central orchestrator for message routing
//!
//! Mirrors the Java QueueManager with:
//! - In-pipeline message tracking for deduplication
//! - Batch message routing with policies
//! - Pool management and lifecycle
//! - Consumer health monitoring

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use dashmap::DashMap;
use parking_lot::Mutex;
use tokio::sync::{oneshot, broadcast, RwLock};
use tracing::{info, warn, error, debug};

use fc_common::{
    QueuedMessage, BatchMessage, AckNack, InFlightMessage,
    PoolConfig, RouterConfig, PoolStats,
};
use fc_queue::{QueueConsumer, QueueMetrics};
use utoipa::ToSchema;

use crate::pool::ProcessPool;
use crate::mediator::Mediator;
use crate::error::RouterError;
use crate::Result;

/// Central orchestrator for message routing
pub struct QueueManager {
    /// In-pipeline message tracking for deduplication
    in_pipeline: DashMap<String, InFlightMessage>,

    /// App message ID to pipeline key mapping for deduplication
    app_message_to_pipeline_key: DashMap<String, String>,

    /// Process pools by code
    pools: DashMap<String, Arc<ProcessPool>>,

    /// Pools that are draining (removed from config, waiting for in-flight to complete)
    draining_pools: DashMap<String, Arc<ProcessPool>>,

    /// Queue consumers (RwLock for async-safe access)
    consumers: RwLock<HashMap<String, Arc<dyn QueueConsumer + Send + Sync>>>,

    /// Current pool configurations (for detecting changes)
    pool_configs: RwLock<HashMap<String, PoolConfig>>,

    /// Mediator for message delivery
    mediator: Arc<dyn Mediator + 'static>,

    /// Default pool code for messages without explicit pool
    default_pool_code: String,

    /// Running state
    running: AtomicBool,

    /// Shutdown signal sender
    shutdown_tx: broadcast::Sender<()>,

    /// Batch ID counter for grouping messages
    batch_counter: std::sync::atomic::AtomicU64,

    /// Track broker message IDs that were successfully processed but failed to delete
    /// (due to expired receipt handle). When these reappear, delete them immediately.
    /// Uses the broker's internal MessageId (not our application message ID) to correctly
    /// distinguish redeliveries from new instructions with the same application ID.
    pending_delete_broker_ids: Arc<Mutex<HashSet<String>>>,

    /// Maximum number of pools allowed
    max_pools: usize,

    /// Pool count warning threshold
    pool_warning_threshold: usize,
}

impl QueueManager {
    pub fn new(mediator: Arc<dyn Mediator + 'static>) -> Self {
        Self::with_limits(mediator, 2000, 1000)
    }

    pub fn with_limits(mediator: Arc<dyn Mediator + 'static>, max_pools: usize, pool_warning_threshold: usize) -> Self {
        let (shutdown_tx, _) = broadcast::channel(1);

        Self {
            in_pipeline: DashMap::new(),
            app_message_to_pipeline_key: DashMap::new(),
            pools: DashMap::new(),
            draining_pools: DashMap::new(),
            consumers: RwLock::new(HashMap::new()),
            pool_configs: RwLock::new(HashMap::new()),
            mediator,
            default_pool_code: "DEFAULT-POOL".to_string(),  // Java: DEFAULT_POOL_CODE
            running: AtomicBool::new(true),
            shutdown_tx,
            batch_counter: std::sync::atomic::AtomicU64::new(0),
            pending_delete_broker_ids: Arc::new(Mutex::new(HashSet::new())),
            max_pools,
            pool_warning_threshold,
        }
    }

    /// Add a queue consumer
    pub async fn add_consumer(&self, consumer: Arc<dyn QueueConsumer + Send + Sync>) {
        let id = consumer.identifier().to_string();
        self.consumers.write().await.insert(id, consumer);
    }

    /// Apply router configuration (initial setup)
    pub async fn apply_config(&self, config: RouterConfig) -> Result<()> {
        let mut pool_configs = self.pool_configs.write().await;
        for pool_config in config.processing_pools {
            let code = pool_config.code.clone();
            pool_configs.insert(code.clone(), pool_config.clone());
            self.get_or_create_pool(&code, Some(pool_config)).await?;
        }
        Ok(())
    }

    /// Hot reload configuration - applies changes without restart
    /// Mirrors Java's updatePoolConfiguration behavior:
    /// - Removed pools: drain asynchronously
    /// - Updated pools: update concurrency/rate limit in-place
    /// - New pools: create and start
    pub async fn reload_config(&self, config: RouterConfig) -> Result<bool> {
        if !self.running.load(Ordering::SeqCst) {
            warn!("Cannot reload config - QueueManager is shutting down");
            return Ok(false);
        }

        info!("Hot reloading configuration...");

        // Build map of new pool configs
        let new_pool_configs: HashMap<String, PoolConfig> = config.processing_pools
            .iter()
            .map(|p| (p.code.clone(), p.clone()))
            .collect();

        let mut pool_configs = self.pool_configs.write().await;
        let mut pools_updated = 0;
        let mut pools_created = 0;
        let mut pools_removed = 0;

        // Step 1: Handle existing pools - update or remove
        let existing_codes: Vec<String> = self.pools.iter().map(|e| e.key().clone()).collect();
        for pool_code in existing_codes {
            if let Some(new_config) = new_pool_configs.get(&pool_code) {
                // Pool exists in new config - check for changes
                if let Some(old_config) = pool_configs.get(&pool_code) {
                    let concurrency_changed = old_config.concurrency != new_config.concurrency;
                    let rate_limit_changed = old_config.rate_limit_per_minute != new_config.rate_limit_per_minute;

                    if concurrency_changed || rate_limit_changed {
                        if let Some(pool) = self.pools.get(&pool_code) {
                            // Update the pool in-place
                            if concurrency_changed {
                                info!(
                                    pool_code = %pool_code,
                                    old_concurrency = old_config.concurrency,
                                    new_concurrency = new_config.concurrency,
                                    "Updating pool concurrency"
                                );
                                pool.update_concurrency(new_config.concurrency).await;
                            }

                            if rate_limit_changed {
                                info!(
                                    pool_code = %pool_code,
                                    old_rate_limit = ?old_config.rate_limit_per_minute,
                                    new_rate_limit = ?new_config.rate_limit_per_minute,
                                    "Updating pool rate limit"
                                );
                                pool.update_rate_limit(new_config.rate_limit_per_minute);
                            }

                            pools_updated += 1;
                        }
                    }
                }
                // Update stored config
                pool_configs.insert(pool_code, new_config.clone());
            } else {
                // Pool removed from config - drain asynchronously
                if let Some((code, pool)) = self.pools.remove(&pool_code) {
                    info!(
                        pool_code = %code,
                        queue_size = pool.queue_size(),
                        active_workers = pool.active_workers(),
                        "Pool removed from config - draining asynchronously"
                    );
                    pool.drain().await;
                    self.draining_pools.insert(code.clone(), pool);
                    pool_configs.remove(&code);
                    pools_removed += 1;
                }
            }
        }

        // Step 2: Create new pools
        for pool_config in &config.processing_pools {
            if !self.pools.contains_key(&pool_config.code) {
                // Check pool count limits
                let current_count = self.pools.len();
                if current_count >= self.max_pools {
                    error!(
                        pool_code = %pool_config.code,
                        current_count = current_count,
                        max_pools = self.max_pools,
                        "Cannot create pool: maximum pool limit reached"
                    );
                    continue;
                }

                if current_count >= self.pool_warning_threshold {
                    warn!(
                        pool_code = %pool_config.code,
                        current_count = current_count,
                        max_pools = self.max_pools,
                        threshold = self.pool_warning_threshold,
                        "Pool count approaching limit"
                    );
                }

                // Create new pool
                self.get_or_create_pool(&pool_config.code, Some(pool_config.clone())).await?;
                pool_configs.insert(pool_config.code.clone(), pool_config.clone());
                pools_created += 1;
            }
        }

        info!(
            pools_updated = pools_updated,
            pools_created = pools_created,
            pools_removed = pools_removed,
            total_active = self.pools.len(),
            total_draining = self.draining_pools.len(),
            "Configuration reload complete"
        );

        Ok(true)
    }

    /// Cleanup draining pools that have finished
    /// Should be called periodically (e.g., every 10 seconds)
    pub async fn cleanup_draining_pools(&self) {
        let mut cleaned = Vec::new();

        for entry in self.draining_pools.iter() {
            let pool = entry.value();
            if pool.is_fully_drained() {
                info!(pool_code = %entry.key(), "Draining pool finished - cleaning up");
                pool.shutdown().await;
                cleaned.push(entry.key().clone());
            }
        }

        for code in cleaned {
            self.draining_pools.remove(&code);
        }
    }

    /// Get or create a pool by code
    async fn get_or_create_pool(&self, code: &str, config: Option<PoolConfig>) -> Result<Arc<ProcessPool>> {
        if let Some(pool) = self.pools.get(code) {
            return Ok(pool.clone());
        }

        let pool_config = config.unwrap_or_else(|| PoolConfig {
            code: code.to_string(),
            concurrency: 20,  // Java: DEFAULT_POOL_CONCURRENCY = 20
            rate_limit_per_minute: None,
        });

        let pool = ProcessPool::new(
            pool_config.clone(),
            self.mediator.clone(),
        );

        let pool_arc = Arc::new(pool);
        pool_arc.start().await;

        self.pools.insert(code.to_string(), pool_arc.clone());
        info!(pool_code = %code, concurrency = pool_config.concurrency, "Created process pool");

        Ok(pool_arc)
    }

    /// Route a batch of messages from a consumer poll
    pub async fn route_batch(&self, messages: Vec<QueuedMessage>, consumer: Arc<dyn QueueConsumer>) -> Result<()> {
        if !self.running.load(Ordering::SeqCst) {
            // NACK all messages on shutdown
            for msg in messages {
                let _ = consumer.nack(&msg.receipt_handle, None).await;
            }
            return Err(RouterError::ShutdownInProgress);
        }

        if messages.is_empty() {
            return Ok(());
        }

        let batch_id = self.batch_counter.fetch_add(1, Ordering::SeqCst).to_string();

        // Phase 0: Check for messages that need immediate deletion (previously processed but ACK failed)
        // First, identify which messages need deletion (while holding lock)
        let mut messages_to_delete = Vec::new();
        let mut messages_to_process = Vec::with_capacity(messages.len());
        {
            let mut pending_delete = self.pending_delete_broker_ids.lock();
            for msg in messages {
                let should_delete = msg.broker_message_id.as_ref()
                    .map(|broker_id| pending_delete.remove(broker_id))
                    .unwrap_or(false);

                if should_delete {
                    // This message was already processed successfully, mark for deletion
                    messages_to_delete.push(msg);
                } else {
                    messages_to_process.push(msg);
                }
            }
        }
        // Now perform the deletions outside the lock scope
        for msg in messages_to_delete {
            info!(
                broker_message_id = ?msg.broker_message_id,
                app_message_id = %msg.message.id,
                "Message was previously processed - deleting from queue now"
            );
            let _ = consumer.ack(&msg.receipt_handle).await;
        }

        if messages_to_process.is_empty() {
            return Ok(());
        }

        // Phase 1: Filter duplicates
        let (unique, duplicates, requeued) = self.filter_duplicates(&messages_to_process, &batch_id);

        // Handle duplicates - NACK them (let SQS retry later, original still processing)
        for (msg, _) in duplicates {
            debug!(message_id = %msg.message.id, "Duplicate message (redelivery), NACKing");
            let _ = consumer.nack(&msg.receipt_handle, None).await;
        }

        // Handle requeued - these were already completed, ACK them
        for (msg, _) in requeued {
            debug!(message_id = %msg.message.id, "Requeued duplicate, ACKing");
            let _ = consumer.ack(&msg.receipt_handle).await;
        }

        // Phase 2: Group by pool and route
        let by_pool = self.group_by_pool(unique);

        for (pool_code, pool_messages) in by_pool {
            let pool = match self.get_or_create_pool(&pool_code, None).await {
                Ok(p) => p,
                Err(e) => {
                    error!(pool_code = %pool_code, error = %e, "Failed to get/create pool");
                    // NACK all messages for this pool
                    for msg in pool_messages {
                        let _ = consumer.nack(&msg.receipt_handle, Some(5)).await;
                    }
                    continue;
                }
            };

            // Check pool capacity
            let available = pool.available_capacity();
            if available < pool_messages.len() {
                warn!(
                    pool_code = %pool_code,
                    available = available,
                    requested = pool_messages.len(),
                    "Pool at capacity, NACKing batch"
                );
                for msg in pool_messages {
                    let _ = consumer.nack(&msg.receipt_handle, Some(5)).await;
                }
                continue;
            }

            // Check rate limiting
            if pool.is_rate_limited() {
                warn!(pool_code = %pool_code, "Pool rate limited, NACKing batch");
                for msg in pool_messages {
                    let _ = consumer.nack(&msg.receipt_handle, Some(10)).await;
                }
                continue;
            }

            // Route messages to pool
            for msg in pool_messages {
                let (ack_tx, ack_rx) = oneshot::channel();
                let pipeline_key = format!("{}:{}", msg.queue_identifier, msg.message.id);
                let receipt_handle = msg.receipt_handle.clone();
                let broker_message_id = msg.broker_message_id.clone();
                let app_message_id = msg.message.id.clone();

                // Track in pipeline with receipt handle
                let in_flight = InFlightMessage::new(
                    &msg.message,
                    msg.broker_message_id.clone(),
                    msg.queue_identifier.clone(),
                    Some(batch_id.clone()),
                    msg.receipt_handle.clone(),
                );
                self.in_pipeline.insert(pipeline_key.clone(), in_flight);

                // Submit to pool - pool will send ACK/NACK through ack_tx
                let batch_msg = BatchMessage {
                    message: msg.message,
                    receipt_handle: msg.receipt_handle,
                    broker_message_id: msg.broker_message_id,
                    queue_identifier: msg.queue_identifier,
                    batch_id: Some(batch_id.clone()),
                    ack_tx,
                };

                let consumer_clone = consumer.clone();
                let pipeline_key_clone = pipeline_key.clone();
                let in_pipeline = self.in_pipeline.clone();
                let pending_delete = self.pending_delete_broker_ids.clone();

                // Spawn task to handle callback from pool
                // Uses latest receipt handle from in_pipeline in case of SQS redelivery
                tokio::spawn(async move {
                    // Get the latest receipt handle and broker message ID (may have been updated on redelivery)
                    let (current_handle, current_broker_id) = in_pipeline
                        .get(&pipeline_key_clone)
                        .map(|entry| (entry.receipt_handle.clone(), entry.broker_message_id.clone()))
                        .unwrap_or((receipt_handle, broker_message_id));

                    match ack_rx.await {
                        Ok(AckNack::Ack) => {
                            if let Err(e) = consumer_clone.ack(&current_handle).await {
                                // ACK failed - likely receipt handle expired
                                // Add broker message ID to pending delete so it gets deleted on next poll
                                if let Some(broker_id) = current_broker_id {
                                    warn!(
                                        broker_message_id = %broker_id,
                                        app_message_id = %app_message_id,
                                        error = %e,
                                        "ACK failed (receipt handle likely expired) - adding to pending delete"
                                    );
                                    pending_delete.lock().insert(broker_id);
                                } else {
                                    error!(
                                        app_message_id = %app_message_id,
                                        error = %e,
                                        "ACK failed and no broker message ID to track for pending delete"
                                    );
                                }
                            }
                        }
                        Ok(AckNack::Nack { delay_seconds }) => {
                            let _ = consumer_clone.nack(&current_handle, delay_seconds).await;
                        }
                        Ok(AckNack::ExtendVisibility { seconds }) => {
                            let _ = consumer_clone.extend_visibility(&current_handle, seconds).await;
                        }
                        Err(_) => {
                            // Channel dropped - NACK to be safe
                            let _ = consumer_clone.nack(&current_handle, None).await;
                        }
                    }

                    // Cleanup from pipeline
                    in_pipeline.remove(&pipeline_key_clone);
                });

                // Actually submit to pool
                if let Err(e) = pool.submit(batch_msg).await {
                    error!(error = %e, "Failed to submit to pool");
                }
            }
        }

        Ok(())
    }

    /// Filter duplicates from a batch
    /// Also updates receipt handles for in-flight duplicates (SQS redelivery handling)
    fn filter_duplicates(
        &self,
        messages: &[QueuedMessage],
        _batch_id: &str,
    ) -> (Vec<QueuedMessage>, Vec<(QueuedMessage, String)>, Vec<(QueuedMessage, String)>) {
        let mut unique = Vec::new();
        let mut duplicates = Vec::new();
        let mut requeued = Vec::new();

        for msg in messages {
            let pipeline_key = format!("{}:{}", msg.queue_identifier, msg.message.id);

            // Check if already in pipeline
            if let Some(mut entry) = self.in_pipeline.get_mut(&pipeline_key) {
                // Update receipt handle if it changed (SQS redelivery)
                if entry.receipt_handle != msg.receipt_handle {
                    debug!(
                        message_id = %msg.message.id,
                        old_handle = %entry.receipt_handle,
                        new_handle = %msg.receipt_handle,
                        "Updating receipt handle for redelivered message"
                    );
                    entry.receipt_handle = msg.receipt_handle.clone();
                }
                duplicates.push((msg.clone(), pipeline_key));
                continue;
            }

            // Check app message ID deduplication
            if let Some(existing_key) = self.app_message_to_pipeline_key.get(&msg.message.id) {
                if let Some(mut entry) = self.in_pipeline.get_mut(existing_key.value()) {
                    // Update receipt handle for cross-queue redelivery
                    if entry.receipt_handle != msg.receipt_handle {
                        debug!(
                            message_id = %msg.message.id,
                            "Updating receipt handle for cross-queue redelivered message"
                        );
                        entry.receipt_handle = msg.receipt_handle.clone();
                    }
                    duplicates.push((msg.clone(), pipeline_key));
                    continue;
                } else {
                    // Was processed before, now requeued
                    requeued.push((msg.clone(), pipeline_key));
                    continue;
                }
            }

            unique.push(msg.clone());
        }

        (unique, duplicates, requeued)
    }

    /// Group messages by pool code
    fn group_by_pool(&self, messages: Vec<QueuedMessage>) -> std::collections::HashMap<String, Vec<QueuedMessage>> {
        let mut by_pool: std::collections::HashMap<String, Vec<QueuedMessage>> = std::collections::HashMap::new();

        for msg in messages {
            let pool_code = if msg.message.pool_code.is_empty() {
                self.default_pool_code.clone()
            } else {
                msg.message.pool_code.clone()
            };

            by_pool.entry(pool_code).or_default().push(msg);
        }

        by_pool
    }

    /// Start the queue manager and all consumers
    pub async fn start(self: Arc<Self>) -> Result<()> {
        let consumers = self.consumers.read().await;
        info!(consumers = consumers.len(), "Starting QueueManager");

        let mut handles = Vec::new();

        // Clone consumers for spawning tasks
        let consumers_vec: Vec<_> = consumers.values().cloned().collect();
        drop(consumers); // Release the read lock

        for consumer in consumers_vec {
            let manager = self.clone();
            let mut shutdown_rx = self.shutdown_tx.subscribe();

            let handle = tokio::spawn(async move {
                loop {
                    tokio::select! {
                        _ = shutdown_rx.recv() => {
                            info!(consumer = %consumer.identifier(), "Consumer shutting down");
                            break;
                        }
                        result = consumer.poll(10) => {
                            match result {
                                Ok(messages) if !messages.is_empty() => {
                                    if let Err(e) = manager.route_batch(messages, consumer.clone()).await {
                                        error!(error = %e, "Error routing batch");
                                    }
                                }
                                Ok(_) => {
                                    // No messages, brief pause
                                    tokio::time::sleep(Duration::from_millis(100)).await;
                                }
                                Err(e) => {
                                    error!(error = %e, consumer = %consumer.identifier(), "Error polling");
                                    tokio::time::sleep(Duration::from_secs(1)).await;
                                }
                            }
                        }
                    }
                }
            });

            handles.push(handle);
        }

        // Wait for all consumer tasks
        for handle in handles {
            let _ = handle.await;
        }

        Ok(())
    }

    /// Graceful shutdown
    pub async fn shutdown(&self) {
        info!("QueueManager shutting down...");
        self.running.store(false, Ordering::SeqCst);

        // Signal all consumer loops to stop
        let _ = self.shutdown_tx.send(());

        // Stop all consumers
        {
            let consumers = self.consumers.read().await;
            for consumer in consumers.values() {
                consumer.stop().await;
            }
        }

        // Drain all pools
        for entry in self.pools.iter() {
            entry.value().drain().await;
        }

        // Wait for pools to drain with timeout
        let drain_timeout = Duration::from_secs(60);
        let start = Instant::now();

        while !self.all_pools_drained() && start.elapsed() < drain_timeout {
            tokio::time::sleep(Duration::from_millis(500)).await;
        }

        // Log any remaining in-flight messages (they'll be NACKed when tasks are dropped)
        let remaining = self.in_pipeline.len();
        if remaining > 0 {
            warn!(remaining = remaining, "Remaining in-flight messages will be NACKed");
            self.in_pipeline.clear();
        }

        // Shutdown pools
        for entry in self.pools.iter() {
            entry.value().shutdown().await;
        }

        info!("QueueManager shutdown complete");
    }

    fn all_pools_drained(&self) -> bool {
        self.pools.iter().all(|entry| entry.value().is_fully_drained())
    }

    /// Get statistics for all pools
    pub fn get_pool_stats(&self) -> Vec<PoolStats> {
        self.pools.iter().map(|entry| entry.value().get_stats()).collect()
    }

    /// Extend visibility for long-running messages
    /// Called periodically by LifecycleManager to prevent visibility timeout
    /// for messages that are still being processed.
    pub async fn extend_visibility_for_long_running(&self) {
        // Extend visibility when message has been processing for 50+ seconds
        // This matches SQS visibility timeout minus a safety buffer
        let threshold_seconds = 50;
        let extension_seconds = 120; // Extend by 120 seconds (matches Java)

        // Collect messages that need visibility extension
        let mut extensions = Vec::new();
        for entry in self.in_pipeline.iter() {
            let value = entry.value();
            if value.elapsed_seconds() >= threshold_seconds {
                extensions.push((
                    value.queue_identifier.clone(),
                    value.receipt_handle.clone(),
                    value.message_id.clone(),
                    value.elapsed_seconds(),
                ));
            }
        }

        if extensions.is_empty() {
            return;
        }

        // Get consumers and extend visibility
        let consumers = self.consumers.read().await;
        for (queue_id, receipt_handle, message_id, elapsed) in extensions {
            if let Some(consumer) = consumers.get(&queue_id) {
                match consumer.extend_visibility(&receipt_handle, extension_seconds).await {
                    Ok(()) => {
                        debug!(
                            message_id = %message_id,
                            queue = %queue_id,
                            elapsed = elapsed,
                            extension = extension_seconds,
                            "Extended visibility for long-running message"
                        );
                    }
                    Err(e) => {
                        warn!(
                            message_id = %message_id,
                            queue = %queue_id,
                            error = %e,
                            "Failed to extend visibility for long-running message"
                        );
                    }
                }
            }
        }
    }

    /// Check for potential memory leaks (large in-pipeline maps)
    pub fn check_memory_health(&self) -> bool {
        let in_pipeline_size = self.in_pipeline.len();
        let threshold = 10000;

        if in_pipeline_size > threshold {
            warn!(
                in_pipeline_size = in_pipeline_size,
                threshold = threshold,
                "Potential memory leak detected - in_pipeline map is large"
            );
            return false;
        }

        true
    }

    /// Update pool configuration at runtime (hot-reload)
    /// Note: Concurrency changes take effect on next message batch
    /// Rate limit changes take effect immediately
    pub async fn update_pool_config(&self, pool_code: &str, config: PoolConfig) -> Result<()> {
        // Check if pool exists and get current settings
        // IMPORTANT: Drop the Ref guard before calling insert() to avoid deadlock
        let pool_exists = if let Some(existing_pool) = self.pools.get(pool_code) {
            let current_concurrency = existing_pool.concurrency();
            let new_concurrency = config.concurrency;

            if current_concurrency != new_concurrency {
                info!(
                    pool_code = %pool_code,
                    old_concurrency = current_concurrency,
                    new_concurrency = new_concurrency,
                    "Pool concurrency update requested - will take effect after pool restart"
                );
            }

            let current_rate_limit = existing_pool.rate_limit_per_minute();
            let new_rate_limit = config.rate_limit_per_minute;

            if current_rate_limit != new_rate_limit {
                info!(
                    pool_code = %pool_code,
                    old_rate_limit = ?current_rate_limit,
                    new_rate_limit = ?new_rate_limit,
                    "Pool rate limit update requested - creating new pool"
                );
            }
            true
        } else {
            false
        };
        // Ref guard is now dropped

        if pool_exists {
            // For now, we recreate the pool with new config
            // In production, you might want to drain first
            let new_pool = ProcessPool::new(config.clone(), self.mediator.clone());
            let pool_arc = Arc::new(new_pool);
            pool_arc.start().await;

            // Replace the old pool
            self.pools.insert(pool_code.to_string(), pool_arc);

            info!(
                pool_code = %pool_code,
                concurrency = config.concurrency,
                rate_limit = ?config.rate_limit_per_minute,
                "Pool configuration updated"
            );

            Ok(())
        } else {
            // Pool doesn't exist, create it
            self.get_or_create_pool(pool_code, Some(config)).await?;
            Ok(())
        }
    }

    /// Get list of all pool codes
    pub fn pool_codes(&self) -> Vec<String> {
        self.pools.iter().map(|entry| entry.key().clone()).collect()
    }

    /// Get list of all consumer identifiers
    pub async fn consumer_ids(&self) -> Vec<String> {
        self.consumers.read().await.keys().cloned().collect()
    }

    /// Restart a specific consumer by ID
    /// Returns true if consumer was found and restart was initiated
    pub async fn restart_consumer(&self, consumer_id: &str) -> bool {
        let consumers = self.consumers.read().await;
        if let Some(consumer) = consumers.get(consumer_id) {
            info!(consumer_id = %consumer_id, "Restarting consumer");

            // Stop the consumer first
            consumer.stop().await;

            // The consumer loop will detect the stop and exit
            // A new poll loop will need to be started externally
            // This is a signal that the consumer needs attention
            true
        } else {
            warn!(consumer_id = %consumer_id, "Consumer not found for restart");
            false
        }
    }

    /// Check if a consumer is healthy
    pub async fn is_consumer_healthy(&self, consumer_id: &str) -> bool {
        let consumers = self.consumers.read().await;
        consumers.get(consumer_id)
            .map(|c| c.is_healthy())
            .unwrap_or(false)
    }

    /// Get queue metrics from all consumers
    pub async fn get_queue_metrics(&self) -> Vec<QueueMetrics> {
        let consumers = self.consumers.read().await;
        let mut metrics = Vec::with_capacity(consumers.len());

        for (id, consumer) in consumers.iter() {
            match consumer.get_metrics().await {
                Ok(Some(m)) => metrics.push(m),
                Ok(None) => {
                    debug!(consumer_id = %id, "Consumer does not support metrics");
                }
                Err(e) => {
                    warn!(consumer_id = %id, error = %e, "Failed to get queue metrics");
                }
            }
        }

        metrics
    }

    /// Get in-flight messages (currently being processed)
    /// Returns messages sorted by elapsed time (oldest first)
    pub fn get_in_flight_messages(&self, limit: usize, message_id_filter: Option<&str>) -> Vec<InFlightMessageInfo> {
        let mut messages: Vec<InFlightMessageInfo> = self.in_pipeline
            .iter()
            .filter(|entry| {
                if let Some(filter) = message_id_filter {
                    entry.value().message_id.contains(filter)
                } else {
                    true
                }
            })
            .map(|entry| {
                let msg = entry.value();
                InFlightMessageInfo {
                    message_id: msg.message_id.clone(),
                    broker_message_id: msg.broker_message_id.clone(),
                    queue_id: msg.queue_identifier.clone(),
                    pool_code: msg.pool_code.clone(),
                    elapsed_time_ms: msg.started_at.elapsed().as_millis() as u64,
                    added_to_in_pipeline_at: chrono::Utc::now() - chrono::Duration::milliseconds(msg.started_at.elapsed().as_millis() as i64),
                }
            })
            .collect();

        // Sort by elapsed time descending (oldest first)
        messages.sort_by(|a, b| b.elapsed_time_ms.cmp(&a.elapsed_time_ms));

        // Apply limit
        messages.truncate(limit);
        messages
    }

    /// Get count of in-flight messages
    pub fn in_flight_count(&self) -> usize {
        self.in_pipeline.len()
    }
}

/// Information about an in-flight message for API response
#[derive(Debug, Clone, serde::Serialize, ToSchema)]
pub struct InFlightMessageInfo {
    #[serde(rename = "messageId")]
    pub message_id: String,
    #[serde(rename = "brokerMessageId")]
    pub broker_message_id: Option<String>,
    #[serde(rename = "queueId")]
    pub queue_id: String,
    #[serde(rename = "poolCode")]
    pub pool_code: String,
    #[serde(rename = "elapsedTimeMs")]
    pub elapsed_time_ms: u64,
    #[serde(rename = "addedToInPipelineAt")]
    pub added_to_in_pipeline_at: chrono::DateTime<chrono::Utc>,
}
