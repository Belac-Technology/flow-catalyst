package audit

import (
	"context"
	"encoding/json"
	"time"

	"github.com/rs/zerolog/log"

	"go.flowcatalyst.tech/internal/common/tsid"
)

const (
	// SystemPrincipalCode is the service account code for system operations
	SystemPrincipalCode = "SYSTEM"
	// SystemPrincipalName is the display name for system operations
	SystemPrincipalName = "System"
)

// Service provides audit logging functionality
type Service struct {
	repo *Repository
}

// NewService creates a new audit service
func NewService(repo *Repository) *Service {
	return &Service{repo: repo}
}

// Log creates an audit log entry
func (s *Service) Log(ctx context.Context, entityType, entityID, operation, principalID string, operationData interface{}) {
	var operationJSON string
	if operationData != nil {
		data, err := json.Marshal(operationData)
		if err != nil {
			log.Warn().Err(err).Msg("Failed to serialize operation data for audit log")
		} else {
			operationJSON = string(data)
		}
	}

	auditLog := &AuditLog{
		ID:            tsid.Generate(),
		EntityType:    entityType,
		EntityID:      entityID,
		Operation:     operation,
		OperationJSON: operationJSON,
		PrincipalID:   principalID,
		PerformedAt:   time.Now(),
	}

	if err := s.repo.Insert(ctx, auditLog); err != nil {
		log.Error().Err(err).
			Str("entityType", entityType).
			Str("entityId", entityID).
			Str("operation", operation).
			Msg("Failed to insert audit log")
	}
}

// LogCreate logs a CREATE operation
func (s *Service) LogCreate(ctx context.Context, entityType, entityID, principalID string, entity interface{}) {
	s.Log(ctx, entityType, entityID, "CREATE", principalID, entity)
}

// LogUpdate logs an UPDATE operation
func (s *Service) LogUpdate(ctx context.Context, entityType, entityID, principalID string, changes interface{}) {
	s.Log(ctx, entityType, entityID, "UPDATE", principalID, changes)
}

// LogDelete logs a DELETE operation
func (s *Service) LogDelete(ctx context.Context, entityType, entityID, principalID string) {
	s.Log(ctx, entityType, entityID, "DELETE", principalID, nil)
}

// LogLogin logs a login event
func (s *Service) LogLogin(ctx context.Context, principalID, email string, success bool, details interface{}) {
	operation := "LOGIN"
	if !success {
		operation = "LOGIN_FAILED"
	}
	s.Log(ctx, "Principal", principalID, operation, principalID, details)
}

// LogLogout logs a logout event
func (s *Service) LogLogout(ctx context.Context, principalID string) {
	s.Log(ctx, "Principal", principalID, "LOGOUT", principalID, nil)
}

// LogSystem logs a system-initiated operation
func (s *Service) LogSystem(ctx context.Context, entityType, entityID, operation string, operationData interface{}) {
	s.Log(ctx, entityType, entityID, operation, SystemPrincipalCode, operationData)
}
