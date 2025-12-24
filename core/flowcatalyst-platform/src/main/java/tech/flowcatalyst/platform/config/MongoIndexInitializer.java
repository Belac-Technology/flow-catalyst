package tech.flowcatalyst.platform.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import java.util.concurrent.TimeUnit;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Initializes MongoDB indexes on application startup.
 * This replaces the PostgreSQL indexes that were defined in JPA @Index annotations.
 */
@ApplicationScoped
public class MongoIndexInitializer {

    private static final Logger LOG = Logger.getLogger(MongoIndexInitializer.class);

    /** TTL for high-volume transactional data: 30 days */
    private static final long TTL_30_DAYS_SECONDS = 30L * 24 * 60 * 60;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@Observes StartupEvent ev) {
        LOG.info("Initializing MongoDB indexes...");
        MongoDatabase db = mongoClient.getDatabase(databaseName);

        createPrincipalIndexes(db);
        createClientIndexes(db);
        createApplicationIndexes(db);
        createAuthRoleIndexes(db);
        createAuthPermissionIndexes(db);
        createOAuthIndexes(db);
        createEventIndexes(db);
        createDispatchJobIndexes(db);
        createEventTypeIndexes(db);
        createAuditLogIndexes(db);
        createMiscIndexes(db);

        LOG.info("MongoDB indexes initialized successfully");
    }

    private void createPrincipalIndexes(MongoDatabase db) {
        MongoCollection<Document> principals = db.getCollection("auth_principals");
        principals.createIndex(Indexes.ascending("clientId"), opt());
        principals.createIndex(Indexes.ascending("type"), opt());
        principals.createIndex(Indexes.ascending("userIdentity.email"),
            opt().unique(true).sparse(true));
        principals.createIndex(Indexes.ascending("serviceAccount.clientId"),
            opt().unique(true).sparse(true));
    }

    private void createClientIndexes(MongoDatabase db) {
        MongoCollection<Document> clients = db.getCollection("auth_clients");
        clients.createIndex(Indexes.ascending("identifier"), opt().unique(true));
        clients.createIndex(Indexes.ascending("status"), opt());

        MongoCollection<Document> accessGrants = db.getCollection("client_access_grants");
        accessGrants.createIndex(Indexes.ascending("principalId"), opt());
        accessGrants.createIndex(Indexes.ascending("clientId"), opt());
        accessGrants.createIndex(
            Indexes.compoundIndex(Indexes.ascending("principalId"), Indexes.ascending("clientId")),
            opt().unique(true));

        MongoCollection<Document> authConfig = db.getCollection("client_auth_config");
        authConfig.createIndex(Indexes.ascending("emailDomain"), opt().unique(true));
    }

    private void createApplicationIndexes(MongoDatabase db) {
        MongoCollection<Document> applications = db.getCollection("auth_applications");
        applications.createIndex(Indexes.ascending("code"), opt().unique(true));
        applications.createIndex(Indexes.ascending("active"), opt());

        MongoCollection<Document> appConfig = db.getCollection("application_client_config");
        appConfig.createIndex(Indexes.ascending("applicationId"), opt());
        appConfig.createIndex(Indexes.ascending("clientId"), opt());
        appConfig.createIndex(
            Indexes.compoundIndex(Indexes.ascending("applicationId"), Indexes.ascending("clientId")),
            opt().unique(true));
    }

    private void createAuthRoleIndexes(MongoDatabase db) {
        MongoCollection<Document> roles = db.getCollection("auth_roles");
        roles.createIndex(Indexes.ascending("name"), opt().unique(true));
        roles.createIndex(Indexes.ascending("applicationId"), opt());
        roles.createIndex(Indexes.ascending("source"), opt());

        MongoCollection<Document> principalRoles = db.getCollection("principal_roles");
        principalRoles.createIndex(Indexes.ascending("principalId"), opt());
        principalRoles.createIndex(Indexes.ascending("roleName"), opt());
        principalRoles.createIndex(
            Indexes.compoundIndex(Indexes.ascending("principalId"), Indexes.ascending("roleName")),
            opt().unique(true));

        MongoCollection<Document> idpMappings = db.getCollection("idp_role_mappings");
        idpMappings.createIndex(Indexes.ascending("idpRoleName"), opt().unique(true));
        idpMappings.createIndex(Indexes.ascending("internalRoleName"), opt());
    }

    private void createAuthPermissionIndexes(MongoDatabase db) {
        MongoCollection<Document> permissions = db.getCollection("auth_permissions");
        permissions.createIndex(Indexes.ascending("name"), opt().unique(true));
        permissions.createIndex(Indexes.ascending("applicationId"), opt());
    }

    private void createOAuthIndexes(MongoDatabase db) {
        MongoCollection<Document> oauthClients = db.getCollection("oauth_clients");
        oauthClients.createIndex(Indexes.ascending("clientId"), opt().unique(true));
        oauthClients.createIndex(Indexes.ascending("ownerClientId"), opt());

        MongoCollection<Document> authCodes = db.getCollection("authorization_codes");
        authCodes.createIndex(Indexes.ascending("code"), opt().unique(true));
        authCodes.createIndex(Indexes.ascending("expiresAt"), opt());

        MongoCollection<Document> refreshTokens = db.getCollection("refresh_tokens");
        refreshTokens.createIndex(Indexes.ascending("tokenHash"), opt().unique(true));
        refreshTokens.createIndex(Indexes.ascending("principalId"), opt());
        refreshTokens.createIndex(Indexes.ascending("tokenFamily"), opt());
        refreshTokens.createIndex(Indexes.ascending("expiresAt"), opt());
    }

    private void createEventIndexes(MongoDatabase db) {
        // Events collection is write-optimized (transactional).
        // Query indexes are on events_read projection instead.
        MongoCollection<Document> events = db.getCollection("events");
        // Idempotency - essential for deduplication
        events.createIndex(Indexes.ascending("deduplicationId"), opt().unique(true).sparse(true));
        // TTL index - auto-delete events after 30 days based on 'time' field
        events.createIndex(
            Indexes.ascending("time"),
            opt().expireAfter(TTL_30_DAYS_SECONDS, TimeUnit.SECONDS));
        LOG.info("Created minimal indexes on events (write-optimized, queries use events_read)");
    }

    private void createDispatchJobIndexes(MongoDatabase db) {
        // Dispatch jobs collection is write-optimized (transactional).
        // Query indexes are on dispatch_jobs_read projection instead.
        MongoCollection<Document> jobs = db.getCollection("dispatch_jobs");
        // Idempotency - essential for deduplication
        jobs.createIndex(Indexes.ascending("idempotencyKey"), opt().unique(true).sparse(true));
        // Scheduler - find pending jobs to dispatch (global or client-scoped)
        // clientId at end allows filtering by client while preserving scheduledFor sort efficiency
        jobs.createIndex(
            Indexes.compoundIndex(
                Indexes.ascending("status"),
                Indexes.ascending("scheduledFor"),
                Indexes.ascending("clientId")),
            opt());
        // FIFO ordering within client context (messageGroup implies client scope)
        jobs.createIndex(
            Indexes.compoundIndex(
                Indexes.ascending("clientId"),
                Indexes.ascending("messageGroup"),
                Indexes.ascending("status")),
            opt().sparse(true));
        // TTL index - auto-delete dispatch jobs after 30 days based on 'createdAt' field
        jobs.createIndex(
            Indexes.ascending("createdAt"),
            opt().expireAfter(TTL_30_DAYS_SECONDS, TimeUnit.SECONDS));
        LOG.info("Created minimal indexes on dispatch_jobs (write-optimized, queries use dispatch_jobs_read)");
    }

    private void createEventTypeIndexes(MongoDatabase db) {
        MongoCollection<Document> eventTypes = db.getCollection("event_types");
        eventTypes.createIndex(Indexes.ascending("code"), opt().unique(true));
        eventTypes.createIndex(Indexes.ascending("status"), opt());
    }

    private void createAuditLogIndexes(MongoDatabase db) {
        MongoCollection<Document> auditLogs = db.getCollection("audit_logs");
        auditLogs.createIndex(
            Indexes.compoundIndex(Indexes.ascending("entityType"), Indexes.ascending("entityId")),
            opt());
        auditLogs.createIndex(Indexes.ascending("principalId"), opt());
        auditLogs.createIndex(Indexes.descending("performedAt"), opt());
    }

    private void createMiscIndexes(MongoDatabase db) {
        MongoCollection<Document> anchorDomains = db.getCollection("anchor_domains");
        anchorDomains.createIndex(Indexes.ascending("domain"), opt().unique(true));
    }

    private IndexOptions opt() {
        return new IndexOptions().background(true);
    }
}
