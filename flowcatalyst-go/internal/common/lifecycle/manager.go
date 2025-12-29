// Package lifecycle provides graceful shutdown orchestration
package lifecycle

import (
	"context"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/rs/zerolog/log"
)

// ShutdownPhase defines the order of shutdown phases
type ShutdownPhase int

const (
	// PhaseHTTP stops accepting new HTTP requests and drains in-flight
	PhaseHTTP ShutdownPhase = iota
	// PhaseQueue stops queue consumers and drains in-flight messages
	PhaseQueue
	// PhaseWorkers stops background workers and waits for completion
	PhaseWorkers
	// PhaseLeader releases leader election locks
	PhaseLeader
	// PhaseDatabase closes database connections
	PhaseDatabase
	// PhaseFinal performs any final cleanup
	PhaseFinal
)

// ShutdownHook is a function called during shutdown
type ShutdownHook struct {
	Name     string
	Phase    ShutdownPhase
	Timeout  time.Duration
	Shutdown func(ctx context.Context) error
}

// Manager orchestrates graceful shutdown
type Manager struct {
	mu              sync.Mutex
	hooks           []ShutdownHook
	shutdownTimeout time.Duration
	done            chan struct{}
	once            sync.Once
}

// NewManager creates a new lifecycle manager
func NewManager() *Manager {
	return &Manager{
		hooks:           make([]ShutdownHook, 0),
		shutdownTimeout: 30 * time.Second,
		done:            make(chan struct{}),
	}
}

// SetShutdownTimeout sets the overall shutdown timeout
func (m *Manager) SetShutdownTimeout(timeout time.Duration) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.shutdownTimeout = timeout
}

// RegisterHook adds a shutdown hook
func (m *Manager) RegisterHook(hook ShutdownHook) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if hook.Timeout == 0 {
		hook.Timeout = 10 * time.Second
	}
	m.hooks = append(m.hooks, hook)
}

// RegisterHTTPShutdown registers an HTTP server shutdown hook
func (m *Manager) RegisterHTTPShutdown(name string, shutdown func(ctx context.Context) error) {
	m.RegisterHook(ShutdownHook{
		Name:     name,
		Phase:    PhaseHTTP,
		Timeout:  15 * time.Second,
		Shutdown: shutdown,
	})
}

// RegisterQueueShutdown registers a queue consumer shutdown hook
func (m *Manager) RegisterQueueShutdown(name string, shutdown func(ctx context.Context) error) {
	m.RegisterHook(ShutdownHook{
		Name:     name,
		Phase:    PhaseQueue,
		Timeout:  30 * time.Second,
		Shutdown: shutdown,
	})
}

// RegisterWorkerShutdown registers a worker/processor shutdown hook
func (m *Manager) RegisterWorkerShutdown(name string, shutdown func(ctx context.Context) error) {
	m.RegisterHook(ShutdownHook{
		Name:     name,
		Phase:    PhaseWorkers,
		Timeout:  30 * time.Second,
		Shutdown: shutdown,
	})
}

// RegisterLeaderShutdown registers a leader election shutdown hook
func (m *Manager) RegisterLeaderShutdown(name string, shutdown func(ctx context.Context) error) {
	m.RegisterHook(ShutdownHook{
		Name:     name,
		Phase:    PhaseLeader,
		Timeout:  5 * time.Second,
		Shutdown: shutdown,
	})
}

// RegisterDatabaseShutdown registers a database shutdown hook
func (m *Manager) RegisterDatabaseShutdown(name string, shutdown func(ctx context.Context) error) {
	m.RegisterHook(ShutdownHook{
		Name:     name,
		Phase:    PhaseDatabase,
		Timeout:  10 * time.Second,
		Shutdown: shutdown,
	})
}

// WaitForSignal blocks until SIGINT or SIGTERM is received
func (m *Manager) WaitForSignal() {
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-quit:
		log.Info().Str("signal", sig.String()).Msg("Shutdown signal received")
	case <-m.done:
		log.Info().Msg("Shutdown triggered programmatically")
	}
}

// Shutdown triggers graceful shutdown
func (m *Manager) Shutdown() {
	m.once.Do(func() {
		close(m.done)
	})
}

// Execute runs the shutdown sequence
func (m *Manager) Execute() error {
	m.mu.Lock()
	hooks := make([]ShutdownHook, len(m.hooks))
	copy(hooks, m.hooks)
	timeout := m.shutdownTimeout
	m.mu.Unlock()

	log.Info().Int("hooks", len(hooks)).Dur("timeout", timeout).Msg("Starting graceful shutdown")

	// Create overall context with timeout
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	// Group hooks by phase
	phaseHooks := make(map[ShutdownPhase][]ShutdownHook)
	for _, hook := range hooks {
		phaseHooks[hook.Phase] = append(phaseHooks[hook.Phase], hook)
	}

	// Execute phases in order
	phases := []ShutdownPhase{PhaseHTTP, PhaseQueue, PhaseWorkers, PhaseLeader, PhaseDatabase, PhaseFinal}

	for _, phase := range phases {
		if len(phaseHooks[phase]) == 0 {
			continue
		}

		log.Info().Int("phase", int(phase)).Int("hooks", len(phaseHooks[phase])).Msg("Executing shutdown phase")

		// Execute hooks in parallel within each phase
		var wg sync.WaitGroup
		for _, hook := range phaseHooks[phase] {
			wg.Add(1)
			go func(h ShutdownHook) {
				defer wg.Done()
				m.executeHook(ctx, h)
			}(hook)
		}
		wg.Wait()

		// Check if context was cancelled
		if ctx.Err() != nil {
			log.Warn().Msg("Shutdown timeout reached, forcing exit")
			return ctx.Err()
		}
	}

	log.Info().Msg("Graceful shutdown completed")
	return nil
}

// executeHook runs a single shutdown hook with its own timeout
func (m *Manager) executeHook(parentCtx context.Context, hook ShutdownHook) {
	ctx, cancel := context.WithTimeout(parentCtx, hook.Timeout)
	defer cancel()

	log.Debug().Str("hook", hook.Name).Dur("timeout", hook.Timeout).Msg("Executing shutdown hook")

	errCh := make(chan error, 1)
	go func() {
		errCh <- hook.Shutdown(ctx)
	}()

	select {
	case err := <-errCh:
		if err != nil {
			log.Error().Err(err).Str("hook", hook.Name).Msg("Shutdown hook failed")
		} else {
			log.Debug().Str("hook", hook.Name).Msg("Shutdown hook completed")
		}
	case <-ctx.Done():
		log.Warn().Str("hook", hook.Name).Msg("Shutdown hook timed out")
	}
}

// Run combines WaitForSignal and Execute for convenience
func (m *Manager) Run() error {
	m.WaitForSignal()
	return m.Execute()
}
