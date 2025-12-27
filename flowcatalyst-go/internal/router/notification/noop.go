package notification

import "github.com/rs/zerolog/log"

// NoOpService is a placeholder notification service that logs notifications instead of sending them.
// In the future, this can be replaced with implementations for email, Slack, PagerDuty, etc.
type NoOpService struct{}

// NewNoOpService creates a new no-op notification service
func NewNoOpService() *NoOpService {
	return &NoOpService{}
}

// NotifyWarning logs the warning
func (s *NoOpService) NotifyWarning(warning *Warning) {
	log.Info().
		Str("severity", warning.Severity).
		Str("category", warning.Category).
		Str("message", warning.Message).
		Str("source", warning.Source).
		Msg("NOTIFICATION [WARNING]")
}

// NotifyCriticalError logs the critical error
func (s *NoOpService) NotifyCriticalError(message, source string) {
	log.Error().
		Str("message", message).
		Str("source", source).
		Msg("NOTIFICATION [CRITICAL]")
}

// NotifySystemEvent logs the system event
func (s *NoOpService) NotifySystemEvent(eventType, message string) {
	log.Info().
		Str("eventType", eventType).
		Str("message", message).
		Msg("NOTIFICATION [EVENT]")
}

// IsEnabled returns false as this is a placeholder implementation
func (s *NoOpService) IsEnabled() bool {
	return false
}
