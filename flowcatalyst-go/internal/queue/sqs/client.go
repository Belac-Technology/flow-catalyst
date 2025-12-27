// Package sqs provides AWS SQS queue implementation
package sqs

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/rs/zerolog/log"

	"go.flowcatalyst.tech/internal/queue"
)

// SQSClientAPI defines the interface for SQS client operations (for testing)
type SQSClientAPI interface {
	ReceiveMessage(ctx context.Context, params *sqs.ReceiveMessageInput, optFns ...func(*sqs.Options)) (*sqs.ReceiveMessageOutput, error)
	DeleteMessage(ctx context.Context, params *sqs.DeleteMessageInput, optFns ...func(*sqs.Options)) (*sqs.DeleteMessageOutput, error)
	ChangeMessageVisibility(ctx context.Context, params *sqs.ChangeMessageVisibilityInput, optFns ...func(*sqs.Options)) (*sqs.ChangeMessageVisibilityOutput, error)
	SendMessage(ctx context.Context, params *sqs.SendMessageInput, optFns ...func(*sqs.Options)) (*sqs.SendMessageOutput, error)
	SendMessageBatch(ctx context.Context, params *sqs.SendMessageBatchInput, optFns ...func(*sqs.Options)) (*sqs.SendMessageBatchOutput, error)
	GetQueueAttributes(ctx context.Context, params *sqs.GetQueueAttributesInput, optFns ...func(*sqs.Options)) (*sqs.GetQueueAttributesOutput, error)
}

// Visibility timeout constants matching Java implementation
const (
	FastFailVisibilitySeconds  = 10  // For rate limits and pool full
	DefaultVisibilitySeconds   = 30  // For real processing failures
	MaxVisibilitySeconds       = 43200 // 12 hours - SQS maximum
)

// Client provides AWS SQS queue operations
type Client struct {
	sqs       SQSClientAPI
	config    *queue.SQSConfig
	consumers map[string]*Consumer
	mu        sync.RWMutex
}

// NewClient creates a new SQS client
func NewClient(ctx context.Context, cfg *queue.SQSConfig) (*Client, error) {
	// Load AWS configuration
	awsCfg, err := config.LoadDefaultConfig(ctx,
		config.WithRegion(cfg.Region),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}

	// Set defaults
	if cfg.WaitTimeSeconds == 0 {
		cfg.WaitTimeSeconds = 20 // Long polling (SQS max)
	}
	if cfg.VisibilityTimeout == 0 {
		cfg.VisibilityTimeout = 120 // 2 minutes default
	}
	if cfg.MaxNumberOfMessages == 0 {
		cfg.MaxNumberOfMessages = 10 // SQS max per batch
	}

	return &Client{
		sqs:       sqs.NewFromConfig(awsCfg),
		config:    cfg,
		consumers: make(map[string]*Consumer),
	}, nil
}

// ClientConfig holds extended SQS client configuration
type ClientConfig struct {
	QueueConfig *queue.SQSConfig
	// CustomEndpoint is used for LocalStack/testing
	CustomEndpoint string
	// AccessKeyID for custom credentials (optional, for testing)
	AccessKeyID string
	// SecretAccessKey for custom credentials (optional, for testing)
	SecretAccessKey string
}

// NewClientWithConfig creates a new SQS client with extended configuration
// This supports custom endpoints for LocalStack integration testing
func NewClientWithConfig(ctx context.Context, cfg *ClientConfig) (*Client, error) {
	var awsCfg aws.Config
	var err error

	// Set defaults on queue config
	if cfg.QueueConfig.WaitTimeSeconds == 0 {
		cfg.QueueConfig.WaitTimeSeconds = 20
	}
	if cfg.QueueConfig.VisibilityTimeout == 0 {
		cfg.QueueConfig.VisibilityTimeout = 120
	}
	if cfg.QueueConfig.MaxNumberOfMessages == 0 {
		cfg.QueueConfig.MaxNumberOfMessages = 10
	}

	if cfg.CustomEndpoint != "" {
		// LocalStack/testing mode with custom endpoint
		awsCfg, err = config.LoadDefaultConfig(ctx,
			config.WithRegion(cfg.QueueConfig.Region),
			config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(
				cfg.AccessKeyID, cfg.SecretAccessKey, "",
			)),
		)
		if err != nil {
			return nil, fmt.Errorf("failed to load AWS config: %w", err)
		}

		// Create SQS client with custom endpoint
		sqsClient := sqs.NewFromConfig(awsCfg, func(o *sqs.Options) {
			o.BaseEndpoint = aws.String(cfg.CustomEndpoint)
		})

		return &Client{
			sqs:       sqsClient,
			config:    cfg.QueueConfig,
			consumers: make(map[string]*Consumer),
		}, nil
	}

	// Production mode - use standard AWS configuration
	awsCfg, err = config.LoadDefaultConfig(ctx,
		config.WithRegion(cfg.QueueConfig.Region),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to load AWS config: %w", err)
	}

	return &Client{
		sqs:       sqs.NewFromConfig(awsCfg),
		config:    cfg.QueueConfig,
		consumers: make(map[string]*Consumer),
	}, nil
}

// Publisher returns an SQS publisher for the configured queue
func (c *Client) Publisher() queue.Publisher {
	return &Publisher{
		client:   c.sqs,
		queueURL: c.config.QueueURL,
	}
}

// CreateConsumer creates a new consumer for the queue
// The name parameter is used for logging/identification (no filter concept in SQS like NATS)
// The filterSubject parameter is ignored for SQS (included for interface compatibility)
func (c *Client) CreateConsumer(ctx context.Context, name, filterSubject string) (*Consumer, error) {
	consumer := &Consumer{
		client:              c.sqs,
		queueURL:            c.config.QueueURL,
		name:                name,
		waitTimeSeconds:     c.config.WaitTimeSeconds,
		visibilityTimeout:   c.config.VisibilityTimeout,
		maxNumberOfMessages: c.config.MaxNumberOfMessages,
		pendingDeletes:      make(map[string]struct{}),
	}

	c.mu.Lock()
	c.consumers[name] = consumer
	c.mu.Unlock()

	log.Info().
		Str("name", name).
		Str("queueURL", c.config.QueueURL).
		Int32("maxMessages", c.config.MaxNumberOfMessages).
		Int32("waitTime", c.config.WaitTimeSeconds).
		Msg("SQS consumer created")

	return consumer, nil
}

// GetConsumer returns an existing consumer by name
func (c *Client) GetConsumer(name string) *Consumer {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.consumers[name]
}

// Connection returns the underlying SQS client for health checks
func (c *Client) Connection() SQSClientAPI {
	return c.sqs
}

// QueueURL returns the configured queue URL
func (c *Client) QueueURL() string {
	return c.config.QueueURL
}

// HealthCheck verifies that the SQS queue is accessible
// This can be used with health.SQSCheck
func (c *Client) HealthCheck(ctx context.Context) error {
	input := &sqs.GetQueueAttributesInput{
		QueueUrl: aws.String(c.config.QueueURL),
		AttributeNames: []types.QueueAttributeName{
			types.QueueAttributeNameApproximateNumberOfMessages,
		},
	}

	_, err := c.sqs.GetQueueAttributes(ctx, input)
	return err
}

// Close closes the client and all consumers
func (c *Client) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	for name, consumer := range c.consumers {
		if err := consumer.Close(); err != nil {
			log.Error().Err(err).Str("consumer", name).Msg("Error closing consumer")
		}
	}
	c.consumers = make(map[string]*Consumer)

	return nil
}

// Publisher publishes messages to SQS
type Publisher struct {
	client   SQSClientAPI
	queueURL string
}

// Publish sends a message to the queue
func (p *Publisher) Publish(ctx context.Context, subject string, data []byte) error {
	input := &sqs.SendMessageInput{
		QueueUrl:    aws.String(p.queueURL),
		MessageBody: aws.String(string(data)),
		MessageAttributes: map[string]types.MessageAttributeValue{
			"Subject": {
				DataType:    aws.String("String"),
				StringValue: aws.String(subject),
			},
		},
	}

	_, err := p.client.SendMessage(ctx, input)
	if err != nil {
		return fmt.Errorf("failed to send SQS message: %w", err)
	}
	return nil
}

// PublishWithGroup sends a message with a message group for FIFO queues
func (p *Publisher) PublishWithGroup(ctx context.Context, subject string, data []byte, messageGroup string) error {
	input := &sqs.SendMessageInput{
		QueueUrl:       aws.String(p.queueURL),
		MessageBody:    aws.String(string(data)),
		MessageGroupId: aws.String(messageGroup),
		MessageAttributes: map[string]types.MessageAttributeValue{
			"Subject": {
				DataType:    aws.String("String"),
				StringValue: aws.String(subject),
			},
		},
	}

	_, err := p.client.SendMessage(ctx, input)
	if err != nil {
		return fmt.Errorf("failed to send SQS message with group: %w", err)
	}
	return nil
}

// PublishWithDeduplication sends a message with deduplication ID for FIFO queues
func (p *Publisher) PublishWithDeduplication(ctx context.Context, subject string, data []byte, deduplicationID string) error {
	input := &sqs.SendMessageInput{
		QueueUrl:               aws.String(p.queueURL),
		MessageBody:            aws.String(string(data)),
		MessageDeduplicationId: aws.String(deduplicationID),
		MessageAttributes: map[string]types.MessageAttributeValue{
			"Subject": {
				DataType:    aws.String("String"),
				StringValue: aws.String(subject),
			},
		},
	}

	_, err := p.client.SendMessage(ctx, input)
	if err != nil {
		return fmt.Errorf("failed to send SQS message with deduplication: %w", err)
	}
	return nil
}

// PublishBatch sends multiple messages in a batch
func (p *Publisher) PublishBatch(ctx context.Context, messages []*queue.MessageBuilder) error {
	if len(messages) == 0 {
		return nil
	}

	// SQS allows max 10 messages per batch
	batchSize := 10
	for i := 0; i < len(messages); i += batchSize {
		end := i + batchSize
		if end > len(messages) {
			end = len(messages)
		}

		entries := make([]types.SendMessageBatchRequestEntry, 0, end-i)
		for j := i; j < end; j++ {
			msg := messages[j]
			entry := types.SendMessageBatchRequestEntry{
				Id:          aws.String(fmt.Sprintf("%d", j)),
				MessageBody: aws.String(string(msg.Data())),
				MessageAttributes: map[string]types.MessageAttributeValue{
					"Subject": {
						DataType:    aws.String("String"),
						StringValue: aws.String(msg.Subject()),
					},
				},
			}

			if msg.MessageGroup() != "" {
				entry.MessageGroupId = aws.String(msg.MessageGroup())
			}
			if msg.DeduplicationID() != "" {
				entry.MessageDeduplicationId = aws.String(msg.DeduplicationID())
			}

			entries = append(entries, entry)
		}

		input := &sqs.SendMessageBatchInput{
			QueueUrl: aws.String(p.queueURL),
			Entries:  entries,
		}

		result, err := p.client.SendMessageBatch(ctx, input)
		if err != nil {
			return fmt.Errorf("failed to send SQS batch: %w", err)
		}

		if len(result.Failed) > 0 {
			log.Error().
				Int("failed", len(result.Failed)).
				Int("successful", len(result.Successful)).
				Msg("Some messages failed to send")
			return fmt.Errorf("failed to send %d messages", len(result.Failed))
		}
	}

	return nil
}

// Close closes the publisher
func (p *Publisher) Close() error {
	return nil
}

// Consumer consumes messages from SQS
type Consumer struct {
	client              SQSClientAPI
	queueURL            string
	name                string
	waitTimeSeconds     int32
	visibilityTimeout   int32
	maxNumberOfMessages int32

	// Track SQS message IDs that were processed but delete failed (receipt handle expired)
	// When these reappear in the queue, delete them immediately
	pendingDeletes   map[string]struct{}
	pendingDeletesMu sync.RWMutex

	running bool
	mu      sync.Mutex
}

// Consume starts consuming messages and calls the handler for each
func (c *Consumer) Consume(ctx context.Context, handler func(queue.Message) error) error {
	c.mu.Lock()
	c.running = true
	c.mu.Unlock()

	log.Info().
		Str("consumer", c.name).
		Str("queueURL", c.queueURL).
		Msg("Starting SQS consumer")

	for {
		select {
		case <-ctx.Done():
			log.Info().Str("consumer", c.name).Msg("SQS consumer context cancelled, stopping")
			c.mu.Lock()
			c.running = false
			c.mu.Unlock()
			return ctx.Err()
		default:
			c.mu.Lock()
			running := c.running
			c.mu.Unlock()
			if !running {
				log.Info().Str("consumer", c.name).Msg("SQS consumer stopped")
				return nil
			}

			batchSize, err := c.pollMessages(ctx, handler)
			if err != nil {
				if ctx.Err() != nil {
					return ctx.Err()
				}
				log.Error().Err(err).Str("consumer", c.name).Msg("Error polling SQS messages")
				time.Sleep(time.Second) // Back off on error
				continue
			}

			// Adaptive delay based on batch size
			// Empty batch: 1s (queue likely empty)
			// Partial batch: 50ms (allow accumulation)
			// Full batch: no delay (keep consuming at full speed)
			if batchSize == 0 {
				time.Sleep(time.Second)
			} else if batchSize < int(c.maxNumberOfMessages) {
				time.Sleep(50 * time.Millisecond)
			}
		}
	}
}

// pollMessages receives and processes a batch of messages
func (c *Consumer) pollMessages(ctx context.Context, handler func(queue.Message) error) (int, error) {
	input := &sqs.ReceiveMessageInput{
		QueueUrl:              aws.String(c.queueURL),
		MaxNumberOfMessages:   c.maxNumberOfMessages,
		WaitTimeSeconds:       c.waitTimeSeconds,
		VisibilityTimeout:     c.visibilityTimeout,
		MessageAttributeNames: []string{"All"},
		AttributeNames:        []types.QueueAttributeName{"All"},
	}

	result, err := c.client.ReceiveMessage(ctx, input)
	if err != nil {
		return 0, fmt.Errorf("failed to receive messages: %w", err)
	}

	processedCount := 0
	for _, msg := range result.Messages {
		sqsMessageID := aws.ToString(msg.MessageId)

		// Check if this message was already processed but delete failed
		c.pendingDeletesMu.RLock()
		_, isPendingDelete := c.pendingDeletes[sqsMessageID]
		c.pendingDeletesMu.RUnlock()

		if isPendingDelete {
			// This message was already processed - delete it now
			log.Info().
				Str("sqsMessageId", sqsMessageID).
				Msg("SQS message was previously processed - deleting now")

			if err := c.deleteMessage(ctx, msg.ReceiptHandle); err != nil {
				log.Warn().Err(err).
					Str("sqsMessageId", sqsMessageID).
					Msg("Failed to delete previously processed message")
			} else {
				c.pendingDeletesMu.Lock()
				delete(c.pendingDeletes, sqsMessageID)
				c.pendingDeletesMu.Unlock()
			}
			continue
		}

		// Process the message
		wrapped := &SQSMessage{
			msg:               &msg,
			client:            c.client,
			queueURL:          c.queueURL,
			sqsMessageID:      sqsMessageID,
			receiptHandle:     aws.ToString(msg.ReceiptHandle),
			visibilityTimeout: c.visibilityTimeout,
			consumer:          c,
		}

		if err := handler(wrapped); err != nil {
			log.Error().
				Err(err).
				Str("messageId", sqsMessageID).
				Str("consumer", c.name).
				Msg("Message handler error")
		}

		processedCount++
	}

	return processedCount, nil
}

// deleteMessage deletes a message from the queue
func (c *Consumer) deleteMessage(ctx context.Context, receiptHandle *string) error {
	if receiptHandle == nil {
		return nil
	}

	input := &sqs.DeleteMessageInput{
		QueueUrl:      aws.String(c.queueURL),
		ReceiptHandle: receiptHandle,
	}

	_, err := c.client.DeleteMessage(ctx, input)
	return err
}

// markForDeletion adds a message ID to the pending delete set
func (c *Consumer) markForDeletion(sqsMessageID string) {
	c.pendingDeletesMu.Lock()
	c.pendingDeletes[sqsMessageID] = struct{}{}
	c.pendingDeletesMu.Unlock()
	log.Info().
		Str("sqsMessageId", sqsMessageID).
		Msg("SQS message marked for deletion on next poll")
}

// Stop stops the consumer
func (c *Consumer) Stop() {
	c.mu.Lock()
	c.running = false
	c.mu.Unlock()
}

// Close closes the consumer
func (c *Consumer) Close() error {
	c.Stop()
	log.Info().Str("consumer", c.name).Msg("SQS consumer closed")
	return nil
}

// SQSMessage wraps an SQS message with visibility control
type SQSMessage struct {
	msg               *types.Message
	client            SQSClientAPI
	queueURL          string
	sqsMessageID      string
	receiptHandle     string
	visibilityTimeout int32
	consumer          *Consumer
}

// ID returns the SQS message ID
func (m *SQSMessage) ID() string {
	return m.sqsMessageID
}

// Data returns the message payload
func (m *SQSMessage) Data() []byte {
	if m.msg.Body != nil {
		return []byte(*m.msg.Body)
	}
	return nil
}

// Subject returns the message subject from attributes
func (m *SQSMessage) Subject() string {
	if attr, ok := m.msg.MessageAttributes["Subject"]; ok {
		if attr.StringValue != nil {
			return *attr.StringValue
		}
	}
	return ""
}

// MessageGroup returns the message group ID
func (m *SQSMessage) MessageGroup() string {
	if m.msg.Attributes != nil {
		if group, ok := m.msg.Attributes["MessageGroupId"]; ok {
			return group
		}
	}
	return ""
}

// Ack acknowledges successful processing by deleting the message
func (m *SQSMessage) Ack() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	input := &sqs.DeleteMessageInput{
		QueueUrl:      aws.String(m.queueURL),
		ReceiptHandle: aws.String(m.receiptHandle),
	}

	_, err := m.client.DeleteMessage(ctx, input)
	if err != nil {
		// Check if receipt handle expired
		if isReceiptHandleExpiredError(err) {
			// Mark for deletion on next poll
			m.consumer.markForDeletion(m.sqsMessageID)
			log.Info().
				Str("sqsMessageId", m.sqsMessageID).
				Msg("Receipt handle expired - marked for deletion on next poll")
			return nil
		}
		return fmt.Errorf("failed to delete SQS message: %w", err)
	}

	log.Debug().Str("sqsMessageId", m.sqsMessageID).Msg("SQS message deleted successfully")
	return nil
}

// Nak signals processing failure - for SQS this is a no-op
// The message will become visible again after visibility timeout expires
func (m *SQSMessage) Nak() error {
	log.Debug().
		Str("sqsMessageId", m.sqsMessageID).
		Msg("SQS NACK - message will become visible after visibility timeout")
	// No-op for SQS - message visibility timeout handles retry
	return nil
}

// NakWithDelay signals failure with a custom visibility delay before redelivery
func (m *SQSMessage) NakWithDelay(delay time.Duration) error {
	seconds := int32(delay.Seconds())
	if seconds < 0 {
		seconds = 0
	}
	if seconds > MaxVisibilitySeconds {
		seconds = MaxVisibilitySeconds
	}
	return m.changeVisibility(seconds)
}

// InProgress extends the processing deadline
func (m *SQSMessage) InProgress() error {
	return m.changeVisibility(m.visibilityTimeout)
}

// SetFastFailVisibility sets visibility to 10 seconds for rate limit retries
func (m *SQSMessage) SetFastFailVisibility() error {
	return m.changeVisibility(FastFailVisibilitySeconds)
}

// ResetVisibilityToDefault resets visibility to 30 seconds for real failures
func (m *SQSMessage) ResetVisibilityToDefault() error {
	return m.changeVisibility(DefaultVisibilitySeconds)
}

// SetVisibilityDelay sets a custom visibility delay (1-43200 seconds)
func (m *SQSMessage) SetVisibilityDelay(seconds int32) error {
	if seconds < 0 {
		seconds = 0
	}
	if seconds > MaxVisibilitySeconds {
		seconds = MaxVisibilitySeconds
	}
	return m.changeVisibility(seconds)
}

// ExtendVisibility extends the visibility timeout
func (m *SQSMessage) ExtendVisibility(seconds int32) error {
	return m.changeVisibility(seconds)
}

// changeVisibility changes the message visibility timeout
func (m *SQSMessage) changeVisibility(timeout int32) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	input := &sqs.ChangeMessageVisibilityInput{
		QueueUrl:          aws.String(m.queueURL),
		ReceiptHandle:     aws.String(m.receiptHandle),
		VisibilityTimeout: timeout,
	}

	_, err := m.client.ChangeMessageVisibility(ctx, input)
	if err != nil {
		if isReceiptHandleExpiredError(err) {
			log.Debug().
				Str("sqsMessageId", m.sqsMessageID).
				Msg("Receipt handle expired - cannot change visibility")
			return nil // Not a fatal error
		}
		return fmt.Errorf("failed to change message visibility: %w", err)
	}

	log.Debug().
		Str("sqsMessageId", m.sqsMessageID).
		Int32("timeout", timeout).
		Msg("Changed message visibility")
	return nil
}

// UpdateReceiptHandle updates the receipt handle (called on redelivery)
func (m *SQSMessage) UpdateReceiptHandle(newReceiptHandle string) {
	log.Info().
		Str("sqsMessageId", m.sqsMessageID).
		Msg("Updating receipt handle due to redelivery")
	m.receiptHandle = newReceiptHandle
}

// GetReceiptHandle returns the current receipt handle
func (m *SQSMessage) GetReceiptHandle() string {
	return m.receiptHandle
}

// Metadata returns message metadata
func (m *SQSMessage) Metadata() map[string]string {
	result := make(map[string]string)
	for k, v := range m.msg.MessageAttributes {
		if v.StringValue != nil {
			result[k] = *v.StringValue
		}
	}
	return result
}

// isReceiptHandleExpiredError checks if the error is due to expired receipt handle
func isReceiptHandleExpiredError(err error) bool {
	if err == nil {
		return false
	}
	errStr := err.Error()
	return contains(errStr, "receipt handle has expired") ||
		contains(errStr, "ReceiptHandleIsInvalid") ||
		contains(errStr, "The receipt handle has expired")
}

// contains checks if s contains substr (case-insensitive not needed for these checks)
func contains(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || len(s) > 0 && containsString(s, substr))
}

func containsString(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}

// DispatchMessage represents a dispatch job message for the queue
type DispatchMessage struct {
	JobID          string            `json:"jobId"`
	DispatchPoolID string            `json:"dispatchPoolId"`
	MessageGroup   string            `json:"messageGroup"`
	BatchID        string            `json:"batchId"`
	Sequence       int               `json:"sequence"`
	TargetURL      string            `json:"targetUrl"`
	Headers        map[string]string `json:"headers,omitempty"`
	Payload        string            `json:"payload"`
	ContentType    string            `json:"contentType"`
	TimeoutSeconds int               `json:"timeoutSeconds"`
	MaxRetries     int               `json:"maxRetries"`
	AttemptNumber  int               `json:"attemptNumber"`
}

// Encode encodes the dispatch message to JSON
func (m *DispatchMessage) Encode() ([]byte, error) {
	return json.Marshal(m)
}

// DecodeDispatchMessage decodes a dispatch message from JSON
func DecodeDispatchMessage(data []byte) (*DispatchMessage, error) {
	var msg DispatchMessage
	if err := json.Unmarshal(data, &msg); err != nil {
		return nil, err
	}
	return &msg, nil
}
