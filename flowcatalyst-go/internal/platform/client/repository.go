package client

import (
	"context"
	"errors"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"

	"go.flowcatalyst.tech/internal/common/tsid"
)

var (
	ErrNotFound            = errors.New("client not found")
	ErrDuplicateIdentifier = errors.New("identifier already exists")
	ErrDuplicateDomain     = errors.New("domain already exists")
)

// Repository provides access to client data
type Repository struct {
	clients       *mongo.Collection
	accessGrants  *mongo.Collection
	anchorDomains *mongo.Collection
	authConfigs   *mongo.Collection
	roleMappings  *mongo.Collection
}

// NewRepository creates a new client repository
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{
		clients:       db.Collection("auth_clients"),
		accessGrants:  db.Collection("client_access_grants"),
		anchorDomains: db.Collection("anchor_domains"),
		authConfigs:   db.Collection("client_auth_config"),
		roleMappings:  db.Collection("idp_role_mappings"),
	}
}

// === Client operations ===

// FindByID finds a client by ID
func (r *Repository) FindByID(ctx context.Context, id string) (*Client, error) {
	var client Client
	err := r.clients.FindOne(ctx, bson.M{"_id": id}).Decode(&client)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &client, nil
}

// FindByIdentifier finds a client by its unique identifier
func (r *Repository) FindByIdentifier(ctx context.Context, identifier string) (*Client, error) {
	var client Client
	err := r.clients.FindOne(ctx, bson.M{"identifier": identifier}).Decode(&client)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &client, nil
}

// FindAll returns all clients with optional pagination
func (r *Repository) FindAll(ctx context.Context, skip, limit int64) ([]*Client, error) {
	opts := options.Find().
		SetSkip(skip).
		SetLimit(limit).
		SetSort(bson.D{{Key: "name", Value: 1}})

	cursor, err := r.clients.Find(ctx, bson.M{}, opts)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var clients []*Client
	if err := cursor.All(ctx, &clients); err != nil {
		return nil, err
	}
	return clients, nil
}

// FindByStatus finds clients by status
func (r *Repository) FindByStatus(ctx context.Context, status ClientStatus) ([]*Client, error) {
	cursor, err := r.clients.Find(ctx, bson.M{"status": status})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var clients []*Client
	if err := cursor.All(ctx, &clients); err != nil {
		return nil, err
	}
	return clients, nil
}

// Search searches clients by name or identifier
func (r *Repository) Search(ctx context.Context, query string) ([]*Client, error) {
	filter := bson.M{
		"$or": []bson.M{
			{"name": bson.M{"$regex": query, "$options": "i"}},
			{"identifier": bson.M{"$regex": query, "$options": "i"}},
		},
	}

	cursor, err := r.clients.Find(ctx, filter)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var clients []*Client
	if err := cursor.All(ctx, &clients); err != nil {
		return nil, err
	}
	return clients, nil
}

// Insert creates a new client
func (r *Repository) Insert(ctx context.Context, client *Client) error {
	if client.ID == "" {
		client.ID = tsid.Generate()
	}
	now := time.Now()
	client.CreatedAt = now
	client.UpdatedAt = now
	if client.Status == "" {
		client.Status = ClientStatusActive
	}

	_, err := r.clients.InsertOne(ctx, client)
	if mongo.IsDuplicateKeyError(err) {
		return ErrDuplicateIdentifier
	}
	return err
}

// Update updates an existing client
func (r *Repository) Update(ctx context.Context, client *Client) error {
	client.UpdatedAt = time.Now()

	result, err := r.clients.ReplaceOne(ctx, bson.M{"_id": client.ID}, client)
	if err != nil {
		return err
	}
	if result.MatchedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// UpdateStatus updates a client's status
func (r *Repository) UpdateStatus(ctx context.Context, id string, status ClientStatus, reason string) error {
	now := time.Now()
	result, err := r.clients.UpdateOne(ctx,
		bson.M{"_id": id},
		bson.M{
			"$set": bson.M{
				"status":          status,
				"statusReason":    reason,
				"statusChangedAt": now,
				"updatedAt":       now,
			},
		},
	)
	if err != nil {
		return err
	}
	if result.MatchedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// AddNote adds a note to a client
func (r *Repository) AddNote(ctx context.Context, id string, note ClientNote) error {
	note.Timestamp = time.Now()
	result, err := r.clients.UpdateOne(ctx,
		bson.M{"_id": id},
		bson.M{
			"$push": bson.M{"notes": note},
			"$set":  bson.M{"updatedAt": time.Now()},
		},
	)
	if err != nil {
		return err
	}
	if result.MatchedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// Delete removes a client
func (r *Repository) Delete(ctx context.Context, id string) error {
	result, err := r.clients.DeleteOne(ctx, bson.M{"_id": id})
	if err != nil {
		return err
	}
	if result.DeletedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// === Access Grant operations ===

// FindAccessGrantsByPrincipal finds all access grants for a principal
func (r *Repository) FindAccessGrantsByPrincipal(ctx context.Context, principalID string) ([]*ClientAccessGrant, error) {
	cursor, err := r.accessGrants.Find(ctx, bson.M{"principalId": principalID})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var grants []*ClientAccessGrant
	if err := cursor.All(ctx, &grants); err != nil {
		return nil, err
	}
	return grants, nil
}

// FindAccessGrantsByClient finds all access grants for a client
func (r *Repository) FindAccessGrantsByClient(ctx context.Context, clientID string) ([]*ClientAccessGrant, error) {
	cursor, err := r.accessGrants.Find(ctx, bson.M{"clientId": clientID})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var grants []*ClientAccessGrant
	if err := cursor.All(ctx, &grants); err != nil {
		return nil, err
	}
	return grants, nil
}

// GrantAccess grants a principal access to a client
func (r *Repository) GrantAccess(ctx context.Context, grant *ClientAccessGrant) error {
	if grant.ID == "" {
		grant.ID = tsid.Generate()
	}
	grant.GrantedAt = time.Now()

	_, err := r.accessGrants.InsertOne(ctx, grant)
	return err
}

// RevokeAccess revokes a principal's access to a client
func (r *Repository) RevokeAccess(ctx context.Context, principalID, clientID string) error {
	_, err := r.accessGrants.DeleteOne(ctx, bson.M{
		"principalId": principalID,
		"clientId":    clientID,
	})
	return err
}

// HasAccess checks if a principal has access to a client
func (r *Repository) HasAccess(ctx context.Context, principalID, clientID string) (bool, error) {
	count, err := r.accessGrants.CountDocuments(ctx, bson.M{
		"principalId": principalID,
		"clientId":    clientID,
	})
	if err != nil {
		return false, err
	}
	return count > 0, nil
}

// === Anchor Domain operations ===

// FindAnchorDomains returns all anchor domains
func (r *Repository) FindAnchorDomains(ctx context.Context) ([]*AnchorDomain, error) {
	cursor, err := r.anchorDomains.Find(ctx, bson.M{})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var domains []*AnchorDomain
	if err := cursor.All(ctx, &domains); err != nil {
		return nil, err
	}
	return domains, nil
}

// IsAnchorDomain checks if a domain is an anchor domain
func (r *Repository) IsAnchorDomain(ctx context.Context, domain string) (bool, error) {
	count, err := r.anchorDomains.CountDocuments(ctx, bson.M{"domain": domain})
	if err != nil {
		return false, err
	}
	return count > 0, nil
}

// AddAnchorDomain adds an anchor domain
func (r *Repository) AddAnchorDomain(ctx context.Context, domain *AnchorDomain) error {
	if domain.ID == "" {
		domain.ID = tsid.Generate()
	}
	domain.CreatedAt = time.Now()

	_, err := r.anchorDomains.InsertOne(ctx, domain)
	if mongo.IsDuplicateKeyError(err) {
		return ErrDuplicateDomain
	}
	return err
}

// RemoveAnchorDomain removes an anchor domain
func (r *Repository) RemoveAnchorDomain(ctx context.Context, domain string) error {
	_, err := r.anchorDomains.DeleteOne(ctx, bson.M{"domain": domain})
	return err
}

// === Auth Config operations ===

// FindAuthConfigByDomain finds auth config for an email domain
func (r *Repository) FindAuthConfigByDomain(ctx context.Context, emailDomain string) (*ClientAuthConfig, error) {
	var config ClientAuthConfig
	err := r.authConfigs.FindOne(ctx, bson.M{"emailDomain": emailDomain}).Decode(&config)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &config, nil
}

// FindAllAuthConfigs returns all auth configs
func (r *Repository) FindAllAuthConfigs(ctx context.Context) ([]*ClientAuthConfig, error) {
	cursor, err := r.authConfigs.Find(ctx, bson.M{})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var configs []*ClientAuthConfig
	if err := cursor.All(ctx, &configs); err != nil {
		return nil, err
	}
	return configs, nil
}

// InsertAuthConfig creates a new auth config
func (r *Repository) InsertAuthConfig(ctx context.Context, config *ClientAuthConfig) error {
	if config.ID == "" {
		config.ID = tsid.Generate()
	}
	now := time.Now()
	config.CreatedAt = now
	config.UpdatedAt = now

	_, err := r.authConfigs.InsertOne(ctx, config)
	if mongo.IsDuplicateKeyError(err) {
		return ErrDuplicateDomain
	}
	return err
}

// UpdateAuthConfig updates an existing auth config
func (r *Repository) UpdateAuthConfig(ctx context.Context, config *ClientAuthConfig) error {
	config.UpdatedAt = time.Now()

	result, err := r.authConfigs.ReplaceOne(ctx, bson.M{"_id": config.ID}, config)
	if err != nil {
		return err
	}
	if result.MatchedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteAuthConfig removes an auth config
func (r *Repository) DeleteAuthConfig(ctx context.Context, id string) error {
	result, err := r.authConfigs.DeleteOne(ctx, bson.M{"_id": id})
	if err != nil {
		return err
	}
	if result.DeletedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// === IDP Role Mapping operations ===

// FindIdpRoleMappingsByDomain finds all role mappings for an email domain
func (r *Repository) FindIdpRoleMappingsByDomain(ctx context.Context, emailDomain string) ([]*IdpRoleMapping, error) {
	cursor, err := r.roleMappings.Find(ctx, bson.M{"emailDomain": emailDomain})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var mappings []*IdpRoleMapping
	if err := cursor.All(ctx, &mappings); err != nil {
		return nil, err
	}
	return mappings, nil
}

// FindAllIdpRoleMappings returns all role mappings
func (r *Repository) FindAllIdpRoleMappings(ctx context.Context) ([]*IdpRoleMapping, error) {
	cursor, err := r.roleMappings.Find(ctx, bson.M{})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var mappings []*IdpRoleMapping
	if err := cursor.All(ctx, &mappings); err != nil {
		return nil, err
	}
	return mappings, nil
}

// InsertIdpRoleMapping creates a new role mapping
func (r *Repository) InsertIdpRoleMapping(ctx context.Context, mapping *IdpRoleMapping) error {
	if mapping.ID == "" {
		mapping.ID = tsid.Generate()
	}
	now := time.Now()
	mapping.CreatedAt = now
	mapping.UpdatedAt = now

	_, err := r.roleMappings.InsertOne(ctx, mapping)
	return err
}

// DeleteIdpRoleMapping removes a role mapping
func (r *Repository) DeleteIdpRoleMapping(ctx context.Context, id string) error {
	result, err := r.roleMappings.DeleteOne(ctx, bson.M{"_id": id})
	if err != nil {
		return err
	}
	if result.DeletedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteIdpRoleMappingsByDomain removes all role mappings for a domain
func (r *Repository) DeleteIdpRoleMappingsByDomain(ctx context.Context, emailDomain string) error {
	_, err := r.roleMappings.DeleteMany(ctx, bson.M{"emailDomain": emailDomain})
	return err
}
