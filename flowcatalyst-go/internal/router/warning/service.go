package warning

import (
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/rs/zerolog/log"
)

const (
	// MaxWarnings is the maximum number of warnings to store
	MaxWarnings = 1000
)

// Warning represents a system warning
type Warning struct {
	ID           string    `json:"id"`
	Category     string    `json:"category"`
	Severity     string    `json:"severity"`
	Message      string    `json:"message"`
	Timestamp    time.Time `json:"timestamp"`
	Source       string    `json:"source"`
	Acknowledged bool      `json:"acknowledged"`
}

// Service defines the warning service interface
type Service interface {
	// AddWarning adds a new warning
	AddWarning(category, severity, message, source string)

	// GetAllWarnings returns all warnings
	GetAllWarnings() []*Warning

	// GetWarningsBySeverity returns warnings filtered by severity
	GetWarningsBySeverity(severity string) []*Warning

	// GetUnacknowledgedWarnings returns all unacknowledged warnings
	GetUnacknowledgedWarnings() []*Warning

	// AcknowledgeWarning marks a warning as acknowledged
	AcknowledgeWarning(warningID string) bool

	// ClearAllWarnings removes all warnings
	ClearAllWarnings()

	// ClearOldWarnings removes warnings older than specified hours
	ClearOldWarnings(hoursOld int)
}

// InMemoryService is an in-memory implementation of the warning service
type InMemoryService struct {
	mu       sync.RWMutex
	warnings map[string]*Warning
}

// NewInMemoryService creates a new in-memory warning service
func NewInMemoryService() *InMemoryService {
	return &InMemoryService{
		warnings: make(map[string]*Warning),
	}
}

// AddWarning adds a new warning
func (s *InMemoryService) AddWarning(category, severity, message, source string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	// Limit warning storage
	if len(s.warnings) >= MaxWarnings {
		// Remove oldest warning
		var oldestID string
		var oldestTime time.Time
		for id, w := range s.warnings {
			if oldestID == "" || w.Timestamp.Before(oldestTime) {
				oldestID = id
				oldestTime = w.Timestamp
			}
		}
		if oldestID != "" {
			delete(s.warnings, oldestID)
		}
	}

	warningID := uuid.New().String()
	warning := &Warning{
		ID:           warningID,
		Category:     category,
		Severity:     severity,
		Message:      message,
		Timestamp:    time.Now(),
		Source:       source,
		Acknowledged: false,
	}

	s.warnings[warningID] = warning
	log.Info().
		Str("severity", severity).
		Str("category", category).
		Str("source", source).
		Str("message", message).
		Msg("Warning added")
}

// GetAllWarnings returns all warnings sorted by timestamp (newest first)
func (s *InMemoryService) GetAllWarnings() []*Warning {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]*Warning, 0, len(s.warnings))
	for _, w := range s.warnings {
		result = append(result, w)
	}

	// Sort by timestamp descending (newest first)
	sort.Slice(result, func(i, j int) bool {
		return result[i].Timestamp.After(result[j].Timestamp)
	})

	return result
}

// GetWarningsBySeverity returns warnings filtered by severity
func (s *InMemoryService) GetWarningsBySeverity(severity string) []*Warning {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var result []*Warning
	for _, w := range s.warnings {
		if strings.EqualFold(w.Severity, severity) {
			result = append(result, w)
		}
	}

	// Sort by timestamp descending
	sort.Slice(result, func(i, j int) bool {
		return result[i].Timestamp.After(result[j].Timestamp)
	})

	return result
}

// GetUnacknowledgedWarnings returns all unacknowledged warnings
func (s *InMemoryService) GetUnacknowledgedWarnings() []*Warning {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var result []*Warning
	for _, w := range s.warnings {
		if !w.Acknowledged {
			result = append(result, w)
		}
	}

	// Sort by timestamp descending
	sort.Slice(result, func(i, j int) bool {
		return result[i].Timestamp.After(result[j].Timestamp)
	})

	return result
}

// AcknowledgeWarning marks a warning as acknowledged
func (s *InMemoryService) AcknowledgeWarning(warningID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	existing, ok := s.warnings[warningID]
	if !ok {
		return false
	}

	// Create new warning with acknowledged flag
	acknowledged := &Warning{
		ID:           existing.ID,
		Category:     existing.Category,
		Severity:     existing.Severity,
		Message:      existing.Message,
		Timestamp:    existing.Timestamp,
		Source:       existing.Source,
		Acknowledged: true,
	}

	s.warnings[warningID] = acknowledged
	log.Info().Str("warningId", warningID).Msg("Warning acknowledged")
	return true
}

// ClearAllWarnings removes all warnings
func (s *InMemoryService) ClearAllWarnings() {
	s.mu.Lock()
	defer s.mu.Unlock()

	count := len(s.warnings)
	s.warnings = make(map[string]*Warning)
	log.Info().Int("count", count).Msg("Cleared all warnings")
}

// ClearOldWarnings removes warnings older than specified hours
func (s *InMemoryService) ClearOldWarnings(hoursOld int) {
	s.mu.Lock()
	defer s.mu.Unlock()

	threshold := time.Now().Add(-time.Duration(hoursOld) * time.Hour)
	var toRemove []string

	for id, w := range s.warnings {
		if w.Timestamp.Before(threshold) {
			toRemove = append(toRemove, id)
		}
	}

	for _, id := range toRemove {
		delete(s.warnings, id)
	}

	log.Info().Int("count", len(toRemove)).Int("hoursOld", hoursOld).Msg("Cleared old warnings")
}
