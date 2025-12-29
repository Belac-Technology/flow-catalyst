//! Message Group Processor
//!
//! Handles FIFO ordering within a message group.
//! Messages with the same message_group_id are processed sequentially.

use std::collections::VecDeque;
use std::sync::Arc;
use tokio::sync::{Mutex, oneshot};
use fc_common::Message;
use tracing::{debug, error, warn, info};
use async_trait::async_trait;

/// Message dispatch result
#[derive(Debug, Clone)]
pub enum DispatchResult {
    Success,
    Failure { error: String, retryable: bool },
    Blocked { reason: String },
}

/// Message dispatcher trait
#[async_trait]
pub trait MessageDispatcher: Send + Sync {
    async fn dispatch(&self, message: &Message) -> DispatchResult;
}

/// Configuration for message group processor
#[derive(Debug, Clone)]
pub struct MessageGroupProcessorConfig {
    /// Maximum queue depth before blocking
    pub max_queue_depth: usize,
    /// Whether to block on error (stops processing until resolved)
    pub block_on_error: bool,
    /// Maximum retry attempts before giving up
    pub max_retries: u32,
}

impl Default for MessageGroupProcessorConfig {
    fn default() -> Self {
        Self {
            max_queue_depth: 1000,
            block_on_error: true,
            max_retries: 3,
        }
    }
}

/// State of the message group processor
#[derive(Debug, Clone, PartialEq)]
pub enum ProcessorState {
    /// Normal processing
    Running,
    /// Blocked due to error (waiting for resolution)
    Blocked { message_id: String, error: String },
    /// Paused by operator
    Paused,
    /// Stopped
    Stopped,
}

/// Message with tracking info
#[derive(Debug, Clone)]
pub struct TrackedMessage {
    pub message: Message,
    pub attempt: u32,
    pub last_error: Option<String>,
}

impl TrackedMessage {
    pub fn new(message: Message) -> Self {
        Self {
            message,
            attempt: 0,
            last_error: None,
        }
    }

    pub fn increment_attempt(&mut self) {
        self.attempt += 1;
    }
}

/// Message group processor - ensures FIFO ordering within a group
pub struct MessageGroupProcessor {
    /// Group identifier
    group_id: String,
    /// Configuration
    config: MessageGroupProcessorConfig,
    /// Message queue
    queue: Arc<Mutex<VecDeque<TrackedMessage>>>,
    /// Current processor state
    state: Arc<Mutex<ProcessorState>>,
    /// Message dispatcher
    dispatcher: Arc<dyn MessageDispatcher>,
    /// Shutdown signal receiver
    shutdown_rx: Arc<Mutex<Option<oneshot::Receiver<()>>>>,
}

impl MessageGroupProcessor {
    pub fn new(
        group_id: String,
        config: MessageGroupProcessorConfig,
        dispatcher: Arc<dyn MessageDispatcher>,
    ) -> (Self, oneshot::Sender<()>) {
        let (shutdown_tx, shutdown_rx) = oneshot::channel();

        let processor = Self {
            group_id,
            config,
            queue: Arc::new(Mutex::new(VecDeque::new())),
            state: Arc::new(Mutex::new(ProcessorState::Running)),
            dispatcher,
            shutdown_rx: Arc::new(Mutex::new(Some(shutdown_rx))),
        };

        (processor, shutdown_tx)
    }

    /// Get the group ID
    pub fn group_id(&self) -> &str {
        &self.group_id
    }

    /// Enqueue a message for processing
    pub async fn enqueue(&self, message: Message) -> Result<(), String> {
        let mut queue = self.queue.lock().await;

        if queue.len() >= self.config.max_queue_depth {
            warn!(
                "Queue depth exceeded for group {}, current: {}",
                self.group_id, queue.len()
            );
            return Err("Queue depth exceeded".to_string());
        }

        queue.push_back(TrackedMessage::new(message));
        debug!(
            "Message enqueued for group {}, queue depth: {}",
            self.group_id,
            queue.len()
        );

        Ok(())
    }

    /// Get current queue depth
    pub async fn queue_depth(&self) -> usize {
        let queue = self.queue.lock().await;
        queue.len()
    }

    /// Get current state
    pub async fn state(&self) -> ProcessorState {
        let state = self.state.lock().await;
        state.clone()
    }

    /// Pause processing
    pub async fn pause(&self) {
        let mut state = self.state.lock().await;
        if *state == ProcessorState::Running {
            *state = ProcessorState::Paused;
            info!("Message group processor {} paused", self.group_id);
        }
    }

    /// Resume processing
    pub async fn resume(&self) {
        let mut state = self.state.lock().await;
        if *state == ProcessorState::Paused {
            *state = ProcessorState::Running;
            info!("Message group processor {} resumed", self.group_id);
        }
    }

    /// Unblock the processor (after resolving blocking error)
    pub async fn unblock(&self) {
        let mut state = self.state.lock().await;
        if matches!(*state, ProcessorState::Blocked { .. }) {
            *state = ProcessorState::Running;
            info!("Message group processor {} unblocked", self.group_id);
        }
    }

    /// Skip the blocking message (mark as failed and continue)
    pub async fn skip_blocking_message(&self) -> Option<TrackedMessage> {
        let state_val = self.state().await;
        if !matches!(state_val, ProcessorState::Blocked { .. }) {
            return None;
        }

        let mut queue = self.queue.lock().await;
        let skipped = queue.pop_front();

        let mut state = self.state.lock().await;
        *state = ProcessorState::Running;

        if let Some(ref msg) = skipped {
            info!(
                "Skipped blocking message {} in group {}",
                msg.message.id, self.group_id
            );
        }

        skipped
    }

    /// Process one message from the queue
    pub async fn process_one(&self) -> Option<DispatchResult> {
        // Check state
        let current_state = self.state().await;
        match current_state {
            ProcessorState::Stopped => return None,
            ProcessorState::Paused => return None,
            ProcessorState::Blocked { .. } => return None,
            ProcessorState::Running => {}
        }

        // Get next message
        let mut tracked = {
            let mut queue = self.queue.lock().await;
            queue.pop_front()?
        };

        tracked.increment_attempt();
        debug!(
            "Processing message {} in group {} (attempt {})",
            tracked.message.id, self.group_id, tracked.attempt
        );

        // Dispatch
        let result = self.dispatcher.dispatch(&tracked.message).await;

        match &result {
            DispatchResult::Success => {
                debug!(
                    "Message {} dispatched successfully in group {}",
                    tracked.message.id, self.group_id
                );
            }
            DispatchResult::Failure { error, retryable } => {
                let message_id = tracked.message.id.clone();
                tracked.last_error = Some(error.clone());

                if *retryable && tracked.attempt < self.config.max_retries {
                    // Re-queue for retry at front
                    let mut queue = self.queue.lock().await;
                    queue.push_front(tracked);
                    debug!(
                        "Message {} re-queued for retry in group {}",
                        message_id, self.group_id
                    );
                } else if self.config.block_on_error {
                    // Block the processor
                    let mut state = self.state.lock().await;
                    *state = ProcessorState::Blocked {
                        message_id: message_id.clone(),
                        error: error.clone(),
                    };
                    // Put message back at front
                    let mut queue = self.queue.lock().await;
                    queue.push_front(tracked);
                    error!(
                        "Message group processor {} blocked on message {}",
                        self.group_id, message_id
                    );
                } else {
                    error!(
                        "Message {} failed permanently in group {}: {}",
                        message_id, self.group_id, error
                    );
                }
            }
            DispatchResult::Blocked { reason } => {
                let mut state = self.state.lock().await;
                *state = ProcessorState::Blocked {
                    message_id: tracked.message.id.clone(),
                    error: reason.clone(),
                };
                // Put message back at front
                let mut queue = self.queue.lock().await;
                queue.push_front(tracked);
                warn!(
                    "Message group processor {} blocked: {}",
                    self.group_id, reason
                );
            }
        }

        Some(result)
    }

    /// Run the processor loop
    pub async fn run(&self) {
        info!("Starting message group processor for {}", self.group_id);

        let mut shutdown_rx = {
            let mut rx = self.shutdown_rx.lock().await;
            rx.take()
        };

        loop {
            // Check for shutdown
            if let Some(ref mut rx) = shutdown_rx {
                if rx.try_recv().is_ok() {
                    let mut state = self.state.lock().await;
                    *state = ProcessorState::Stopped;
                    break;
                }
            }

            // Process one message
            match self.process_one().await {
                Some(_) => {
                    // Continue immediately if we processed something
                }
                None => {
                    // Nothing to process, wait a bit
                    tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
                }
            }
        }

        info!("Message group processor {} stopped", self.group_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Utc;
    use fc_common::MediationType;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct MockDispatcher {
        success_count: AtomicUsize,
        fail_until: AtomicUsize,
    }

    impl MockDispatcher {
        fn new(fail_until: usize) -> Self {
            Self {
                success_count: AtomicUsize::new(0),
                fail_until: AtomicUsize::new(fail_until),
            }
        }
    }

    #[async_trait]
    impl MessageDispatcher for MockDispatcher {
        async fn dispatch(&self, _message: &Message) -> DispatchResult {
            let current = self.success_count.fetch_add(1, Ordering::SeqCst);
            if current < self.fail_until.load(Ordering::SeqCst) {
                DispatchResult::Failure {
                    error: "Mock failure".to_string(),
                    retryable: true,
                }
            } else {
                DispatchResult::Success
            }
        }
    }

    fn create_test_message(id: &str) -> Message {
        Message {
            id: id.to_string(),
            pool_code: "default".to_string(),
            auth_token: None,
            mediation_type: MediationType::HTTP,
            mediation_target: "http://localhost".to_string(),
            message_group_id: Some("group-1".to_string()),
            payload: serde_json::json!({}),
            created_at: Utc::now(),
        }
    }

    #[tokio::test]
    async fn test_enqueue_and_process() {
        let dispatcher = Arc::new(MockDispatcher::new(0));
        let (processor, _shutdown) = MessageGroupProcessor::new(
            "test-group".to_string(),
            MessageGroupProcessorConfig::default(),
            dispatcher,
        );

        // Enqueue messages
        processor.enqueue(create_test_message("msg-1")).await.unwrap();
        processor.enqueue(create_test_message("msg-2")).await.unwrap();

        assert_eq!(processor.queue_depth().await, 2);

        // Process
        let result1 = processor.process_one().await;
        assert!(matches!(result1, Some(DispatchResult::Success)));
        assert_eq!(processor.queue_depth().await, 1);

        let result2 = processor.process_one().await;
        assert!(matches!(result2, Some(DispatchResult::Success)));
        assert_eq!(processor.queue_depth().await, 0);
    }

    #[tokio::test]
    async fn test_block_on_error() {
        let dispatcher = Arc::new(MockDispatcher::new(10)); // Fail 10 times
        let config = MessageGroupProcessorConfig {
            max_retries: 2,
            block_on_error: true,
            ..Default::default()
        };
        let (processor, _shutdown) = MessageGroupProcessor::new(
            "test-group".to_string(),
            config,
            dispatcher,
        );

        processor.enqueue(create_test_message("msg-1")).await.unwrap();

        // Process should fail and retry
        for _ in 0..2 {
            let _ = processor.process_one().await;
        }

        // Should now be blocked
        let state = processor.state().await;
        assert!(matches!(state, ProcessorState::Blocked { .. }));

        // Unblock
        processor.unblock().await;
        assert_eq!(processor.state().await, ProcessorState::Running);
    }

    #[tokio::test]
    async fn test_pause_resume() {
        let dispatcher = Arc::new(MockDispatcher::new(0));
        let (processor, _shutdown) = MessageGroupProcessor::new(
            "test-group".to_string(),
            MessageGroupProcessorConfig::default(),
            dispatcher,
        );

        processor.enqueue(create_test_message("msg-1")).await.unwrap();

        // Pause
        processor.pause().await;
        assert_eq!(processor.state().await, ProcessorState::Paused);

        // Process should return None when paused
        let result = processor.process_one().await;
        assert!(result.is_none());
        assert_eq!(processor.queue_depth().await, 1); // Message still in queue

        // Resume
        processor.resume().await;
        assert_eq!(processor.state().await, ProcessorState::Running);

        // Now process should work
        let result = processor.process_one().await;
        assert!(matches!(result, Some(DispatchResult::Success)));
    }
}
