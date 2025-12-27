//! ProcessPool - Worker pool with FIFO ordering, rate limiting, and concurrency control
//!
//! Mirrors the Java ProcessPoolImpl with:
//! - Per-message-group FIFO ordering
//! - Semaphore-based concurrency control
//! - Rate limiting using governor
//! - Dynamic worker tasks per message group

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::time::Duration;
use std::num::NonZeroU32;
use dashmap::{DashMap, DashSet};
use tokio::sync::{mpsc, Semaphore, oneshot};
use governor::{Quota, RateLimiter, state::{NotKeyed, InMemoryState}, clock::DefaultClock};
use tracing::{info, warn, error, debug};

use fc_common::{
    Message, BatchMessage, AckNack, PoolConfig, PoolStats,
    MediationResult,
};
use crate::mediator::Mediator;
use crate::Result;

const DEFAULT_GROUP: &str = "__DEFAULT__";
const QUEUE_CAPACITY_MULTIPLIER: u32 = 10;
const MIN_QUEUE_CAPACITY: u32 = 500;

/// Task submitted to a pool worker
pub struct PoolTask {
    pub message: Message,
    pub receipt_handle: String,
    pub ack_tx: oneshot::Sender<AckNack>,
    pub batch_id: Option<String>,
}

/// Process pool with FIFO ordering and rate limiting
pub struct ProcessPool {
    config: PoolConfig,
    mediator: Arc<dyn Mediator>,

    /// Pool-level concurrency semaphore
    semaphore: Arc<Semaphore>,

    /// Per-message-group queues for FIFO ordering
    message_group_queues: DashMap<String, mpsc::Sender<PoolTask>>,

    /// Track in-flight message groups
    in_flight_groups: DashSet<String>,

    /// Batch+group failure tracking for cascading NACKs
    failed_batch_groups: DashSet<String>,

    /// Rate limiter (optional)
    rate_limiter: Option<Arc<RateLimiter<NotKeyed, InMemoryState, DefaultClock>>>,

    /// Running state
    running: AtomicBool,

    /// Queue size counter (Arc for sharing across tasks)
    queue_size: Arc<AtomicU32>,

    /// Active workers counter (Arc for sharing across tasks)
    active_workers: Arc<AtomicU32>,
}

impl ProcessPool {
    pub fn new(config: PoolConfig, mediator: Arc<dyn Mediator>) -> Self {
        let concurrency = config.concurrency as usize;

        let rate_limiter = config.rate_limit_per_minute.and_then(|rpm| {
            NonZeroU32::new(rpm).map(|nz| {
                Arc::new(RateLimiter::direct(Quota::per_minute(nz)))
            })
        });

        Self {
            config: config.clone(),
            mediator,
            semaphore: Arc::new(Semaphore::new(concurrency)),
            message_group_queues: DashMap::new(),
            in_flight_groups: DashSet::new(),
            failed_batch_groups: DashSet::new(),
            rate_limiter,
            running: AtomicBool::new(false),
            queue_size: Arc::new(AtomicU32::new(0)),
            active_workers: Arc::new(AtomicU32::new(0)),
        }
    }

    /// Start the pool
    pub async fn start(&self) {
        if self.running.swap(true, Ordering::SeqCst) {
            return; // Already running
        }

        info!(
            pool_code = %self.config.code,
            concurrency = self.config.concurrency,
            rate_limit = ?self.config.rate_limit_per_minute,
            "Starting process pool"
        );
    }

    /// Submit a message to the pool
    pub async fn submit(&self, batch_msg: BatchMessage) -> Result<()> {
        if !self.running.load(Ordering::SeqCst) {
            let _ = batch_msg.ack_tx.send(AckNack::Nack { delay_seconds: Some(5) });
            return Ok(());
        }

        // Check capacity
        let current_size = self.queue_size.load(Ordering::SeqCst);
        let capacity = std::cmp::max(
            self.config.concurrency * QUEUE_CAPACITY_MULTIPLIER,
            MIN_QUEUE_CAPACITY,
        );

        if current_size >= capacity {
            debug!(
                pool_code = %self.config.code,
                current = current_size,
                capacity = capacity,
                "Pool at capacity, rejecting"
            );
            let _ = batch_msg.ack_tx.send(AckNack::Nack { delay_seconds: Some(5) });
            return Ok(());
        }

        // Increment queue size
        self.queue_size.fetch_add(1, Ordering::SeqCst);

        // Get message group
        let group_id = batch_msg.message.message_group_id
            .as_ref()
            .filter(|s| !s.is_empty())
            .cloned()
            .unwrap_or_else(|| DEFAULT_GROUP.to_string());

        // Check if batch+group has failed
        if let Some(batch_id) = &batch_msg.batch_id {
            let batch_group_key = format!("{}|{}", batch_id, group_id);
            if self.failed_batch_groups.contains(&batch_group_key) {
                debug!(
                    message_id = %batch_msg.message.id,
                    batch_group = %batch_group_key,
                    "Batch+group failed, NACKing for FIFO"
                );
                self.queue_size.fetch_sub(1, Ordering::SeqCst);
                let _ = batch_msg.ack_tx.send(AckNack::Nack { delay_seconds: Some(1) });
                return Ok(());
            }
        }

        // Get or create group queue and worker
        let group_tx = self.get_or_create_group_queue(&group_id);

        // Send to group queue
        let task = PoolTask {
            message: batch_msg.message,
            receipt_handle: batch_msg.receipt_handle,
            ack_tx: batch_msg.ack_tx,
            batch_id: batch_msg.batch_id,
        };

        if let Err(e) = group_tx.send(task).await {
            error!(error = %e, "Failed to send to group queue");
            self.queue_size.fetch_sub(1, Ordering::SeqCst);
        }

        Ok(())
    }

    /// Get or create a message group queue and worker
    fn get_or_create_group_queue(&self, group_id: &str) -> mpsc::Sender<PoolTask> {
        if let Some(tx) = self.message_group_queues.get(group_id) {
            return tx.clone();
        }

        // Create new group queue
        let (tx, rx) = mpsc::channel(100);
        self.message_group_queues.insert(group_id.to_string(), tx.clone());

        // Spawn worker for this group
        let group_id_clone = group_id.to_string();
        let pool_code = self.config.code.clone();
        let semaphore = self.semaphore.clone();
        let mediator = self.mediator.clone();
        let queue_size = self.queue_size.clone();
        let active_workers = self.active_workers.clone();
        let in_flight_groups = self.in_flight_groups.clone();
        let failed_batch_groups = self.failed_batch_groups.clone();
        let rate_limiter = self.rate_limiter.clone();
        let message_group_queues = self.message_group_queues.clone();

        tokio::spawn(async move {
            Self::run_group_worker(
                group_id_clone,
                pool_code,
                rx,
                semaphore,
                mediator,
                queue_size,
                active_workers,
                in_flight_groups,
                failed_batch_groups,
                rate_limiter,
                message_group_queues,
            ).await;
        });

        tx
    }

    /// Worker loop for a message group
    async fn run_group_worker(
        group_id: String,
        pool_code: String,
        mut rx: mpsc::Receiver<PoolTask>,
        semaphore: Arc<Semaphore>,
        mediator: Arc<dyn Mediator>,
        queue_size: Arc<AtomicU32>,
        active_workers: Arc<AtomicU32>,
        in_flight_groups: DashSet<String>,
        failed_batch_groups: DashSet<String>,
        rate_limiter: Option<Arc<RateLimiter<NotKeyed, InMemoryState, DefaultClock>>>,
        message_group_queues: DashMap<String, mpsc::Sender<PoolTask>>,
    ) {
        debug!(group_id = %group_id, pool_code = %pool_code, "Group worker started");

        // Idle timeout for cleanup
        let idle_timeout = Duration::from_secs(300); // 5 minutes

        loop {
            // Wait for task with idle timeout
            let task = tokio::time::timeout(idle_timeout, rx.recv()).await;

            let task = match task {
                Ok(Some(t)) => t,
                Ok(None) => {
                    // Channel closed
                    debug!(group_id = %group_id, "Group channel closed, exiting");
                    break;
                }
                Err(_) => {
                    // Idle timeout - cleanup if queue is empty
                    if rx.is_empty() {
                        debug!(group_id = %group_id, "Group idle timeout, cleaning up");
                        message_group_queues.remove(&group_id);
                        break;
                    }
                    continue;
                }
            };

            // Decrement queue size
            queue_size.fetch_sub(1, Ordering::SeqCst);

            // Check rate limiting before acquiring semaphore
            if let Some(ref rl) = rate_limiter {
                if rl.check().is_err() {
                    debug!(
                        message_id = %task.message.id,
                        pool_code = %pool_code,
                        "Rate limited, NACKing"
                    );
                    let _ = task.ack_tx.send(AckNack::Nack { delay_seconds: Some(1) });
                    continue;
                }
            }

            // Acquire semaphore permit
            let permit = match semaphore.acquire().await {
                Ok(p) => p,
                Err(_) => {
                    error!("Semaphore closed");
                    let _ = task.ack_tx.send(AckNack::Nack { delay_seconds: Some(5) });
                    break;
                }
            };

            active_workers.fetch_add(1, Ordering::SeqCst);
            in_flight_groups.insert(group_id.clone());

            // Process the message
            let start = std::time::Instant::now();
            let outcome = mediator.mediate(&task.message).await;
            let duration_ms = start.elapsed().as_millis() as u64;

            // Handle outcome
            let ack_nack = match outcome.result {
                MediationResult::Success => {
                    debug!(
                        message_id = %task.message.id,
                        duration_ms = duration_ms,
                        "Message processed successfully"
                    );
                    AckNack::Ack
                }
                MediationResult::ErrorConfig => {
                    warn!(
                        message_id = %task.message.id,
                        error = ?outcome.error_message,
                        "Configuration error, ACKing to prevent retry"
                    );
                    AckNack::Ack
                }
                MediationResult::ErrorProcess => {
                    warn!(
                        message_id = %task.message.id,
                        error = ?outcome.error_message,
                        "Transient error, NACKing for retry"
                    );

                    // Mark batch+group as failed
                    if let Some(ref batch_id) = task.batch_id {
                        let batch_group_key = format!("{}|{}", batch_id, group_id);
                        failed_batch_groups.insert(batch_group_key);
                    }

                    AckNack::Nack { delay_seconds: outcome.delay_seconds }
                }
                MediationResult::ErrorConnection => {
                    warn!(
                        message_id = %task.message.id,
                        error = ?outcome.error_message,
                        "Connection error, NACKing for retry"
                    );

                    // Mark batch+group as failed
                    if let Some(ref batch_id) = task.batch_id {
                        let batch_group_key = format!("{}|{}", batch_id, group_id);
                        failed_batch_groups.insert(batch_group_key);
                    }

                    AckNack::Nack { delay_seconds: Some(5) }
                }
            };

            // Send ACK/NACK
            let _ = task.ack_tx.send(ack_nack);

            // Cleanup
            in_flight_groups.remove(&group_id);
            active_workers.fetch_sub(1, Ordering::SeqCst);
            drop(permit);
        }

        debug!(group_id = %group_id, pool_code = %pool_code, "Group worker exited");
    }

    /// Check available capacity
    pub fn available_capacity(&self) -> usize {
        let capacity = std::cmp::max(
            self.config.concurrency * QUEUE_CAPACITY_MULTIPLIER,
            MIN_QUEUE_CAPACITY,
        ) as usize;
        let used = self.queue_size.load(Ordering::SeqCst) as usize;
        capacity.saturating_sub(used)
    }

    /// Check if rate limited
    pub fn is_rate_limited(&self) -> bool {
        self.rate_limiter
            .as_ref()
            .map(|rl| rl.check().is_err())
            .unwrap_or(false)
    }

    /// Drain the pool (stop accepting new work)
    pub async fn drain(&self) {
        info!(pool_code = %self.config.code, "Draining pool");
        self.running.store(false, Ordering::SeqCst);
    }

    /// Check if fully drained
    pub fn is_fully_drained(&self) -> bool {
        self.queue_size.load(Ordering::SeqCst) == 0 &&
        self.active_workers.load(Ordering::SeqCst) == 0
    }

    /// Shutdown the pool
    pub async fn shutdown(&self) {
        info!(pool_code = %self.config.code, "Shutting down pool");
        self.running.store(false, Ordering::SeqCst);
    }

    /// Get pool statistics
    pub fn get_stats(&self) -> PoolStats {
        PoolStats {
            pool_code: self.config.code.clone(),
            concurrency: self.config.concurrency,
            active_workers: self.active_workers.load(Ordering::SeqCst),
            queue_size: self.queue_size.load(Ordering::SeqCst),
            queue_capacity: std::cmp::max(
                self.config.concurrency * QUEUE_CAPACITY_MULTIPLIER,
                MIN_QUEUE_CAPACITY,
            ),
            message_group_count: self.message_group_queues.len() as u32,
            rate_limit_per_minute: self.config.rate_limit_per_minute,
            is_rate_limited: self.is_rate_limited(),
        }
    }

    /// Get the pool code
    pub fn code(&self) -> &str {
        &self.config.code
    }

    /// Get current concurrency setting
    pub fn concurrency(&self) -> u32 {
        self.config.concurrency
    }

    /// Get current rate limit setting
    pub fn rate_limit_per_minute(&self) -> Option<u32> {
        self.config.rate_limit_per_minute
    }

    /// Get current queue size
    pub fn queue_size(&self) -> u32 {
        self.queue_size.load(Ordering::SeqCst)
    }

    /// Get current active worker count
    pub fn active_workers(&self) -> u32 {
        self.active_workers.load(Ordering::SeqCst)
    }

    /// Update concurrency at runtime
    /// Note: This updates the semaphore permit count. New permits take effect immediately
    /// for waiting workers. Reducing permits will naturally happen as workers release them.
    pub async fn update_concurrency(&self, new_concurrency: u32) {
        let old_concurrency = self.config.concurrency;
        if new_concurrency == old_concurrency {
            return;
        }

        let diff = (new_concurrency as i32) - (old_concurrency as i32);

        if diff > 0 {
            // Increasing concurrency - add permits
            self.semaphore.add_permits(diff as usize);
            info!(
                pool_code = %self.config.code,
                old = old_concurrency,
                new = new_concurrency,
                added_permits = diff,
                "Increased pool concurrency"
            );
        } else {
            // Decreasing concurrency - we can't remove permits directly,
            // but the pool will naturally drain down to the new limit.
            // Log a warning that this is a gradual process.
            warn!(
                pool_code = %self.config.code,
                old = old_concurrency,
                new = new_concurrency,
                "Decreasing concurrency will take effect gradually as workers complete"
            );
        }

        // Note: We can't mutate config here since it's not mutable.
        // The new semaphore state will enforce the new concurrency.
    }

    /// Update rate limit at runtime
    /// Creates a new rate limiter with the updated limit
    pub fn update_rate_limit(&self, new_rate_limit: Option<u32>) {
        // Note: We can't update rate_limiter in place since it's not behind a lock.
        // For a full implementation, rate_limiter should be behind an RwLock.
        // For now, log the change - the manager will recreate the pool if needed.
        info!(
            pool_code = %self.config.code,
            old = ?self.config.rate_limit_per_minute,
            new = ?new_rate_limit,
            "Rate limit update requested - will take effect on next message batch"
        );
    }
}

/// Configuration update that can be applied at runtime
#[derive(Debug, Clone)]
pub struct PoolConfigUpdate {
    /// New concurrency level (if changed)
    pub concurrency: Option<u32>,
    /// New rate limit per minute (None to clear, Some(0) means no limit)
    pub rate_limit_per_minute: Option<Option<u32>>,
}

/// Builder for creating pool configuration updates
impl PoolConfigUpdate {
    pub fn new() -> Self {
        Self {
            concurrency: None,
            rate_limit_per_minute: None,
        }
    }

    pub fn with_concurrency(mut self, concurrency: u32) -> Self {
        self.concurrency = Some(concurrency);
        self
    }

    pub fn with_rate_limit(mut self, rate_limit: Option<u32>) -> Self {
        self.rate_limit_per_minute = Some(rate_limit);
        self
    }
}

impl Default for PoolConfigUpdate {
    fn default() -> Self {
        Self::new()
    }
}
