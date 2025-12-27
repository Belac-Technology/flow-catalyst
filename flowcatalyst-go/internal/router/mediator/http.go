// Package mediator provides HTTP webhook mediation
package mediator

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/rs/zerolog/log"
	"github.com/sony/gobreaker"

	"go.flowcatalyst.tech/internal/common/metrics"
	"go.flowcatalyst.tech/internal/router/pool"
)

// HTTPMediator mediates messages via HTTP webhooks
type HTTPMediator struct {
	client         *http.Client
	circuitBreaker *gobreaker.CircuitBreaker
	maxRetries     int
	baseBackoff    time.Duration
}

// HTTPMediatorConfig configures the HTTP mediator
type HTTPMediatorConfig struct {
	// Timeout for HTTP requests
	Timeout time.Duration

	// MaxRetries for transient errors
	MaxRetries int

	// BaseBackoff for retry backoff (multiplied by attempt number)
	BaseBackoff time.Duration

	// CircuitBreaker settings
	CircuitBreakerEnabled     bool
	CircuitBreakerRequests    uint32        // Request volume threshold
	CircuitBreakerInterval    time.Duration // Stats window
	CircuitBreakerRatio       float64       // Failure ratio to trip
	CircuitBreakerTimeout     time.Duration // Time in open state before half-open
	CircuitBreakerMinRequests uint32        // Min requests before evaluating ratio
}

// DefaultHTTPMediatorConfig returns sensible defaults
func DefaultHTTPMediatorConfig() *HTTPMediatorConfig {
	return &HTTPMediatorConfig{
		Timeout:                   30 * time.Second,
		MaxRetries:                3,
		BaseBackoff:               time.Second,
		CircuitBreakerEnabled:     true,
		CircuitBreakerRequests:    10,
		CircuitBreakerInterval:    60 * time.Second,
		CircuitBreakerRatio:       0.5,
		CircuitBreakerTimeout:     5 * time.Second,
		CircuitBreakerMinRequests: 10,
	}
}

// NewHTTPMediator creates a new HTTP mediator
func NewHTTPMediator(cfg *HTTPMediatorConfig) *HTTPMediator {
	if cfg == nil {
		cfg = DefaultHTTPMediatorConfig()
	}

	// Create HTTP client with timeout
	client := &http.Client{
		Timeout: cfg.Timeout,
		Transport: &http.Transport{
			MaxIdleConns:        100,
			MaxIdleConnsPerHost: 10,
			IdleConnTimeout:     90 * time.Second,
			DialContext: (&net.Dialer{
				Timeout:   10 * time.Second,
				KeepAlive: 30 * time.Second,
			}).DialContext,
		},
	}

	mediator := &HTTPMediator{
		client:      client,
		maxRetries:  cfg.MaxRetries,
		baseBackoff: cfg.BaseBackoff,
	}

	// Create circuit breaker if enabled
	if cfg.CircuitBreakerEnabled {
		mediator.circuitBreaker = gobreaker.NewCircuitBreaker(gobreaker.Settings{
			Name:        "http-mediator",
			MaxRequests: cfg.CircuitBreakerRequests,
			Interval:    cfg.CircuitBreakerInterval,
			Timeout:     cfg.CircuitBreakerTimeout,
			ReadyToTrip: func(counts gobreaker.Counts) bool {
				if counts.Requests < cfg.CircuitBreakerMinRequests {
					return false
				}
				failureRatio := float64(counts.TotalFailures) / float64(counts.Requests)
				return failureRatio >= cfg.CircuitBreakerRatio
			},
			OnStateChange: func(name string, from gobreaker.State, to gobreaker.State) {
				log.Info().
					Str("name", name).
					Str("from", from.String()).
					Str("to", to.String()).
					Msg("Circuit breaker state changed")

				// Update circuit breaker metrics
				var stateValue float64
				switch to {
				case gobreaker.StateClosed:
					stateValue = float64(metrics.CircuitBreakerClosed)
				case gobreaker.StateOpen:
					stateValue = float64(metrics.CircuitBreakerOpen)
					metrics.MediatorCircuitBreakerTrips.WithLabelValues(name).Inc()
				case gobreaker.StateHalfOpen:
					stateValue = float64(metrics.CircuitBreakerHalfOpen)
				}
				metrics.MediatorCircuitBreakerState.WithLabelValues(name).Set(stateValue)
			},
		})
	}

	return mediator
}

// Process processes a message through HTTP mediation
func (m *HTTPMediator) Process(msg *pool.MessagePointer) *pool.MediationOutcome {
	if msg == nil {
		return &pool.MediationOutcome{
			Result: pool.MediationResultErrorConfig,
			Error:  errors.New("nil message"),
		}
	}

	targetURL := msg.MediationTarget
	if targetURL == "" {
		return &pool.MediationOutcome{
			Result: pool.MediationResultErrorConfig,
			Error:  errors.New("no target URL"),
		}
	}

	// Execute with circuit breaker if enabled
	if m.circuitBreaker != nil {
		result, err := m.circuitBreaker.Execute(func() (interface{}, error) {
			return m.executeWithRetry(msg)
		})

		if err != nil {
			// Circuit breaker open
			if errors.Is(err, gobreaker.ErrOpenState) || errors.Is(err, gobreaker.ErrTooManyRequests) {
				log.Warn().
					Str("messageId", msg.ID).
					Str("target", targetURL).
					Msg("Circuit breaker open")
				return &pool.MediationOutcome{
					Result: pool.MediationResultErrorConnection,
					Error:  err,
				}
			}
		}

		if outcome, ok := result.(*pool.MediationOutcome); ok {
			return outcome
		}
	}

	// No circuit breaker, execute directly
	outcome, _ := m.executeWithRetry(msg)
	return outcome
}

// executeWithRetry executes the HTTP request with retry logic
func (m *HTTPMediator) executeWithRetry(msg *pool.MessagePointer) (*pool.MediationOutcome, error) {
	var lastOutcome *pool.MediationOutcome

	for attempt := 1; attempt <= m.maxRetries; attempt++ {
		outcome := m.executeOnce(msg, attempt)
		lastOutcome = outcome

		// Check if we should retry
		if outcome.Result == pool.MediationResultSuccess {
			return outcome, nil
		}

		if outcome.Result == pool.MediationResultErrorConfig {
			// Config errors (4xx) should not be retried
			return outcome, nil
		}

		// Check if retryable
		if !m.isRetryable(outcome) {
			return outcome, nil
		}

		// Wait before retry (backoff = attempt * baseBackoff)
		if attempt < m.maxRetries {
			backoff := time.Duration(attempt) * m.baseBackoff
			log.Info().
				Str("messageId", msg.ID).
				Int("attempt", attempt).
				Dur("backoff", backoff).
				Msg("Retrying after backoff")
			time.Sleep(backoff)
		}
	}

	// Return last outcome after all retries exhausted
	return lastOutcome, lastOutcome.Error
}

// executeOnce executes a single HTTP request
// This matches Java's HttpMediator behavior:
// - POST to mediationTarget with {"messageId": "<id>"}
// - Authorization: Bearer <authToken>
func (m *HTTPMediator) executeOnce(msg *pool.MessagePointer, attempt int) *pool.MediationOutcome {
	targetURL := msg.MediationTarget

	// Determine timeout
	timeout := 30 * time.Second
	if msg.TimeoutSeconds > 0 {
		timeout = time.Duration(msg.TimeoutSeconds) * time.Second
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	// Create payload matching Java: {"messageId":"<id>"}
	payload := fmt.Sprintf(`{"messageId":"%s"}`, msg.ID)

	// Create request
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, targetURL, strings.NewReader(payload))
	if err != nil {
		return &pool.MediationOutcome{
			Result: pool.MediationResultErrorConfig,
			Error:  fmt.Errorf("failed to create request: %w", err),
		}
	}

	// Set headers - matching Java's HttpMediator
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	// Set Bearer auth token (matching Java)
	if msg.AuthToken != "" {
		req.Header.Set("Authorization", "Bearer "+msg.AuthToken)
	}

	// Add any additional custom headers
	for k, v := range msg.Headers {
		req.Header.Set(k, v)
	}

	// Execute request
	log.Debug().
		Str("messageId", msg.ID).
		Str("target", targetURL).
		Int("attempt", attempt).
		Msg("Executing HTTP request")

	startTime := time.Now()
	resp, err := m.client.Do(req)
	duration := time.Since(startTime)

	// Track HTTP duration
	metrics.MediatorHTTPDuration.WithLabelValues(targetURL).Observe(duration.Seconds())

	if err != nil {
		metrics.MediatorHTTPRequests.WithLabelValues("error", "POST").Inc()
		return m.handleError(msg, err)
	}
	defer resp.Body.Close()

	// Track HTTP request count by status
	metrics.MediatorHTTPRequests.WithLabelValues(strconv.Itoa(resp.StatusCode), "POST").Inc()

	// Read response body
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64*1024)) // Limit to 64KB

	log.Debug().
		Str("messageId", msg.ID).
		Int("statusCode", resp.StatusCode).
		Int("bodyLen", len(body)).
		Dur("duration", duration).
		Msg("HTTP response received")

	// Handle response
	return m.handleResponse(msg, resp.StatusCode, body)
}

// handleError handles HTTP errors
func (m *HTTPMediator) handleError(msg *pool.MessagePointer, err error) *pool.MediationOutcome {
	// Check for specific error types
	if errors.Is(err, context.DeadlineExceeded) {
		log.Warn().
			Str("messageId", msg.ID).
			Err(err).
			Msg("Request timeout")
		return &pool.MediationOutcome{
			Result: pool.MediationResultErrorConnection,
			Error:  err,
		}
	}

	if errors.Is(err, context.Canceled) {
		return &pool.MediationOutcome{
			Result: pool.MediationResultErrorProcess,
			Error:  err,
		}
	}

	// Check for network errors
	var netErr net.Error
	if errors.As(err, &netErr) {
		log.Warn().
			Str("messageId", msg.ID).
			Err(err).
			Bool("timeout", netErr.Timeout()).
			Msg("Network error")
		return &pool.MediationOutcome{
			Result: pool.MediationResultErrorConnection,
			Error:  err,
		}
	}

	// Check for connection refused, etc.
	if strings.Contains(err.Error(), "connection refused") ||
		strings.Contains(err.Error(), "no such host") ||
		strings.Contains(err.Error(), "dial tcp") {
		return &pool.MediationOutcome{
			Result: pool.MediationResultErrorConnection,
			Error:  err,
		}
	}

	// Default to process error
	return &pool.MediationOutcome{
		Result: pool.MediationResultErrorProcess,
		Error:  err,
	}
}

// handleResponse handles the HTTP response
func (m *HTTPMediator) handleResponse(msg *pool.MessagePointer, statusCode int, body []byte) *pool.MediationOutcome {
	// 2xx responses
	if statusCode >= 200 && statusCode < 300 {
		// Check for ack field in response
		ack := m.parseAckFromResponse(body)

		if ack != nil && !*ack {
			// ack=false means "not ready, try again later"
			delay := m.parseDelayFromResponse(body)
			log.Info().
				Str("messageId", msg.ID).
				Int("statusCode", statusCode).
				Msg("Response ack=false, will retry")
			return &pool.MediationOutcome{
				Result:      pool.MediationResultErrorProcess,
				StatusCode:  statusCode,
				ResponseAck: ack,
				Delay:       delay,
			}
		}

		return &pool.MediationOutcome{
			Result:     pool.MediationResultSuccess,
			StatusCode: statusCode,
		}
	}

	// 4xx client errors - configuration issue, don't retry
	if statusCode >= 400 && statusCode < 500 {
		// Special case: 429 Too Many Requests - treat as transient
		if statusCode == 429 {
			delay := m.parseRetryAfter(body)
			return &pool.MediationOutcome{
				Result:     pool.MediationResultErrorProcess,
				StatusCode: statusCode,
				Delay:      delay,
			}
		}

		log.Warn().
			Str("messageId", msg.ID).
			Int("statusCode", statusCode).
			Msg("Client error - will not retry")
		return &pool.MediationOutcome{
			Result:     pool.MediationResultErrorConfig,
			StatusCode: statusCode,
		}
	}

	// 5xx server errors - transient, retry
	if statusCode >= 500 {
		log.Warn().
			Str("messageId", msg.ID).
			Int("statusCode", statusCode).
			Msg("Server error - will retry")
		return &pool.MediationOutcome{
			Result:     pool.MediationResultErrorProcess,
			StatusCode: statusCode,
		}
	}

	// Other status codes - treat as process error
	return &pool.MediationOutcome{
		Result:     pool.MediationResultErrorProcess,
		StatusCode: statusCode,
	}
}

// parseAckFromResponse parses the ack field from a JSON response
func (m *HTTPMediator) parseAckFromResponse(body []byte) *bool {
	if len(body) == 0 {
		return nil
	}

	var response struct {
		Ack *bool `json:"ack"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return nil
	}

	return response.Ack
}

// parseDelayFromResponse parses the delaySeconds field from a JSON response
// This matches Java's MediationResponse.delaySeconds field
func (m *HTTPMediator) parseDelayFromResponse(body []byte) *time.Duration {
	if len(body) == 0 {
		return nil
	}

	var response struct {
		DelaySeconds *int `json:"delaySeconds"` // Delay in seconds (Java format)
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return nil
	}

	if response.DelaySeconds != nil && *response.DelaySeconds > 0 {
		d := time.Duration(*response.DelaySeconds) * time.Second
		return &d
	}

	return nil
}

// parseRetryAfter parses Retry-After from response (for 429)
func (m *HTTPMediator) parseRetryAfter(body []byte) *time.Duration {
	// Try to parse from body first
	if delay := m.parseDelayFromResponse(body); delay != nil {
		return delay
	}

	// Default delay for rate limiting
	d := 5 * time.Second
	return &d
}

// isRetryable determines if an outcome should be retried
func (m *HTTPMediator) isRetryable(outcome *pool.MediationOutcome) bool {
	switch outcome.Result {
	case pool.MediationResultErrorConnection:
		return true
	case pool.MediationResultErrorProcess:
		// Process errors are retryable except for certain cases
		return true
	default:
		return false
	}
}
