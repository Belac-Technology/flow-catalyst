# MongoDB Schema Migration Strategy

## Overview

This document outlines the production-ready strategy for managing MongoDB schema changes across the FlowCatalyst application. The approach is designed to handle large datasets (1M+ documents) with zero downtime and safe rollback capabilities.

## Core Principles

1. **Zero Downtime**: Application remains available during all migrations
2. **Lazy Migration**: Fast operations at deploy time, slow migrations happen asynchronously
3. **Version Aware**: Code handles multiple schema versions simultaneously
4. **Reversible**: Safe rollback points using expand/contract pattern
5. **Observable**: Metrics and logging for migration progress

## Architecture

### Three-Phase Migration Pattern

```
┌─────────────────────────────────────────────────────────────────┐
│ Phase 1: EXPAND (Safe Rollback Zone)                            │
├─────────────────────────────────────────────────────────────────┤
│ - Deploy code that handles BOTH old and new schema              │
│ - Create indexes (fast, milliseconds)                           │
│ - Add new fields as OPTIONAL                                    │
│ - Maintain backward compatibility                               │
│ ✅ Can rollback to previous version anytime                     │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ Phase 2: MIGRATE (Safe Rollback Zone)                           │
├─────────────────────────────────────────────────────────────────┤
│ - Background job migrates old records to new schema             │
│ - Batched (1000s at a time)                                     │
│ - Throttled (doesn't overload database)                         │
│ - Runs during off-peak hours                                    │
│ - "Write-through" migration: hot records auto-migrate on write  │
│ ✅ Can rollback to previous version (both schemas still work)   │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ Phase 3: CONTRACT (Point of No Return)                          │
├─────────────────────────────────────────────────────────────────┤
│ - Remove old schema support from code                           │
│ - Make new fields REQUIRED                                      │
│ - Add strict validation                                         │
│ - Drop old indexes                                              │
│ ⚠️  Cannot rollback past this point without data loss           │
└─────────────────────────────────────────────────────────────────┘
```

## Implementation Components

### 1. Schema Versioning

Every document tracks its schema version for safe multi-version handling.

**Add to all entities:**
```java
@MongoEntity(collection = "principals")
public class Principal extends PanacheMongoEntityBase {

    @BsonId
    public ObjectId id;

    /**
     * Schema version for this document.
     * Allows code to handle multiple schema versions simultaneously.
     *
     * Version history:
     * - null/1: Initial schema
     * - 2: Added ServiceAccount.code and ServiceAccount.description
     */
    public Integer schemaVersion = 2;  // Current version

    // ... rest of fields
}
```

**Benefits:**
- Query migration progress: `db.principals.countDocuments({schemaVersion: {$lt: 2}})`
- Handle mixed versions: Code knows which format to expect
- Targeted migrations: Only migrate documents below target version
- Debugging: See which records are old vs new

### 2. Mongock Setup

Mongock handles fast schema operations (indexes, collections) at deployment time.

**Dependency:**
```kotlin
// flowcatalyst-auth/build.gradle.kts
dependencies {
    implementation("io.mongock:mongock-quarkus-driver:5.4.0")
    implementation("io.mongock:mongock-bom:5.4.0")
}
```

**Configuration:**
```properties
# application.properties

# Mongock enabled for migration jobs only
mongock.enabled=${MIGRATION_MODE:false}

# Lock configuration (prevents concurrent migrations)
mongock.lock-acquired-for-minutes=3
mongock.lock-try-frequency-ms=1000
mongock.lock-quit-trying-after-ms=30000
mongock.throw-exception-if-cannot-obtain-lock=true

# Migration package
mongock.migration-scan-package=tech.flowcatalyst.*.migration

# Run synchronously during startup
mongock.start-mode=sync
```

**Environment-specific:**
```properties
# application-dev.properties
mongock.enabled=true  # Run on startup in dev

# application-prod.properties
mongock.enabled=false  # Disabled for app instances
# Separate migration job sets MIGRATION_MODE=true
```

**Distributed Lock:**
- Mongock uses MongoDB itself for distributed locking
- Creates `mongockLock` collection with lock document
- Only one instance runs migrations at a time
- Other instances wait or fail based on timeout config
- Lock automatically released after migration completes

### 3. Migration Structure

**Fast operations only** - must complete in seconds/minutes:

```java
package tech.flowcatalyst.auth.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Phase 1: EXPAND
 * Add ServiceAccount code and description fields.
 * SAFE ROLLBACK: Can rollback to v1 code anytime during/after this migration.
 */
@ChangeUnit(id = "002-add-service-account-code", order = "002", author = "system")
public class V002_AddServiceAccountCode {

    @Execution
    public void addCodeIndex(MongoDatabase db) {
        // Create sparse unique index (allows nulls during migration)
        db.getCollection("principals").createIndex(
            Indexes.ascending("serviceAccount.code"),
            new IndexOptions()
                .unique(true)
                .sparse(true)  // ← Key: allows null/missing values
                .name("idx_service_account_code")
        );

        // Add description field index for queries
        db.getCollection("principals").createIndex(
            Indexes.text("serviceAccount.description"),
            new IndexOptions().name("idx_service_account_description")
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase db) {
        // Compensating action if migration fails
        db.getCollection("principals").dropIndex("idx_service_account_code");
        db.getCollection("principals").dropIndex("idx_service_account_description");
    }
}

/**
 * Phase 3: CONTRACT (Future migration, after backfill complete)
 * Make code required and enforce validation.
 * ⚠️ POINT OF NO RETURN: Cannot rollback past this migration.
 */
@ChangeUnit(id = "003-require-service-account-code", order = "003", author = "system")
public class V003_RequireServiceAccountCode {

    @Execution
    public void makeCodeRequired(MongoDatabase db) {
        // Log warning about point of no return
        System.err.println("⚠️  POINT OF NO RETURN: Making serviceAccount.code required");
        System.err.println("    Cannot rollback past this migration without data loss");

        // Replace sparse index with strict unique index
        db.getCollection("principals").dropIndex("idx_service_account_code");
        db.getCollection("principals").createIndex(
            Indexes.ascending("serviceAccount.code"),
            new IndexOptions()
                .unique(true)
                .sparse(false)  // ← No longer allows nulls
                .name("idx_service_account_code_required")
        );

        // Add schema validation at database level
        db.runCommand(new Document("collMod", "principals")
            .append("validator", new Document("$jsonSchema", new Document()
                .append("bsonType", "object")
                .append("properties", new Document()
                    .append("serviceAccount", new Document()
                        .append("bsonType", "object")
                        .append("required", Arrays.asList("code", "clientId"))
                    )
                )
            ))
            .append("validationLevel", "strict")
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase db) {
        // This is a "forward rollback" - restore to sparse index
        db.getCollection("principals").dropIndex("idx_service_account_code_required");
        db.getCollection("principals").createIndex(
            Indexes.ascending("serviceAccount.code"),
            new IndexOptions().unique(true).sparse(true)
        );

        // Remove validation
        db.runCommand(new Document("collMod", "principals")
            .append("validator", new Document())
        );
    }
}
```

**What NOT to put in Mongock:**
```java
// ❌ DON'T DO THIS - Would block startup for hours
@Execution
public void backfillOneMillionRecords(MongoDatabase db) {
    db.getCollection("principals")
      .find()
      .forEach(doc -> {
          // This would take hours!
          migrateDocument(doc);
      });
}
```

### 4. Version-Aware Service Layer

Code that handles multiple schema versions simultaneously.

```java
package tech.flowcatalyst.auth.service;

import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.auth.model.Principal;
import tech.flowcatalyst.auth.model.ServiceAccount;

/**
 * Service layer that handles multiple schema versions.
 * During migration period, both v1 and v2 documents exist.
 */
@ApplicationScoped
public class PrincipalService {

    private static final int CURRENT_SCHEMA_VERSION = 2;

    /**
     * Read path: Handle both v1 and v2 schemas.
     */
    public String getServiceAccountCode(Principal principal) {
        if (principal.serviceAccount == null) {
            return null;
        }

        // Check schema version
        int version = principal.schemaVersion != null ? principal.schemaVersion : 1;

        if (version < 2) {
            // v1 schema: code field doesn't exist, use clientId as fallback
            return principal.serviceAccount.clientId;
        }

        // v2 schema: code field exists
        return principal.serviceAccount.code;
    }

    /**
     * Write path: Auto-migrate to current version on write.
     * This is "lazy migration" or "write-through migration".
     */
    public void save(Principal principal) {
        // Migrate to current version if needed
        if (principal.schemaVersion == null || principal.schemaVersion < CURRENT_SCHEMA_VERSION) {
            migrateToCurrentVersion(principal);
        }

        // Set current version
        principal.schemaVersion = CURRENT_SCHEMA_VERSION;
        principal.updatedAt = Instant.now();

        // Save to database
        principal.persist();
    }

    /**
     * Migrate a single principal to current schema version.
     */
    private void migrateToCurrentVersion(Principal principal) {
        if (principal.serviceAccount != null) {
            migrateServiceAccountToV2(principal.serviceAccount);
        }
    }

    /**
     * Migrate ServiceAccount from v1 to v2.
     * v1 → v2: Add code and description fields.
     */
    private void migrateServiceAccountToV2(ServiceAccount serviceAccount) {
        // Generate code if missing
        if (serviceAccount.code == null || serviceAccount.code.isEmpty()) {
            serviceAccount.code = generateCodeFromClientId(serviceAccount.clientId);
        }

        // Initialize description if missing
        if (serviceAccount.description == null) {
            serviceAccount.description = "Migrated from v1 schema";
        }
    }

    /**
     * Business logic to generate code from existing clientId.
     */
    private String generateCodeFromClientId(String clientId) {
        // Option 1: Use clientId as-is if it meets format requirements
        if (clientId != null && isValidCode(clientId)) {
            return clientId;
        }

        // Option 2: Transform clientId to valid code format
        if (clientId != null) {
            return clientId.replaceAll("[^a-zA-Z0-9_-]", "_").toUpperCase();
        }

        // Option 3: Generate new code if clientId is invalid
        return "SA_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private boolean isValidCode(String code) {
        // Define your code format requirements
        return code.matches("[A-Z0-9_-]{3,50}");
    }
}
```

**Key Pattern:**
- **Read operations**: Handle all schema versions
- **Write operations**: Auto-migrate to current version
- **Hot data migrates first**: Frequently accessed records upgrade automatically
- **Cold data migrates in background**: Infrequent records handled by batch job

### 5. Background Migration Job

Asynchronously migrates old records in batches.

```java
package tech.flowcatalyst.auth.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Background service that migrates old schema documents to new schema.
 * Runs during off-peak hours, processes in batches, throttled to avoid DB overload.
 */
@ApplicationScoped
public class SchemaMigrationService {

    private static final int TARGET_SCHEMA_VERSION = 2;

    @Inject
    MongoDatabase database;

    @ConfigProperty(name = "migration.batch-size", defaultValue = "1000")
    int batchSize;

    @ConfigProperty(name = "migration.throttle-delay-ms", defaultValue = "100")
    int throttleDelayMs;

    @ConfigProperty(name = "migration.background.enabled", defaultValue = "false")
    boolean migrationEnabled;

    @ConfigProperty(name = "migration.max-per-run", defaultValue = "100000")
    int maxPerRun;

    /**
     * Scheduled to run during off-peak hours.
     * Cron: Daily at 2 AM
     */
    @Scheduled(cron = "0 0 2 * * ?", identity = "schema-migration")
    public void scheduledMigration() {
        if (!migrationEnabled) {
            Log.debug("Background migration disabled");
            return;
        }

        Log.info("Starting scheduled schema migration");
        long migrated = migrateOldRecords();
        Log.info("Scheduled migration complete. Migrated " + migrated + " records");
    }

    /**
     * Can also be triggered manually via REST endpoint or CLI.
     */
    public long migrateOldRecords() {
        long totalMigrated = 0;
        MongoCollection<Document> principals = database.getCollection("principals");

        // Count total records needing migration
        long remaining = principals.countDocuments(getOldSchemaFilter());
        Log.info("Found " + remaining + " records needing migration to v" + TARGET_SCHEMA_VERSION);

        if (remaining == 0) {
            Log.info("All records already migrated!");
            return 0;
        }

        while (totalMigrated < maxPerRun) {
            // Fetch batch of old schema documents
            List<Document> batch = principals
                .find(getOldSchemaFilter())
                .limit(batchSize)
                .into(new ArrayList<>());

            if (batch.isEmpty()) {
                Log.info("Migration complete! Total migrated: " + totalMigrated);
                break;
            }

            // Migrate batch
            int batchMigrated = 0;
            for (Document doc : batch) {
                try {
                    if (migrateDocument(principals, doc)) {
                        batchMigrated++;
                        totalMigrated++;
                    }
                } catch (Exception e) {
                    Log.error("Failed to migrate principal: " + doc.get("_id"), e);
                    // Continue with next document (don't fail entire batch)
                }
            }

            remaining -= batchMigrated;
            Log.info("Migrated batch: " + batchMigrated + "/" + batchSize +
                    ", Total: " + totalMigrated + ", Remaining: ~" + remaining);

            // Throttle to avoid overwhelming database
            if (throttleDelayMs > 0) {
                try {
                    Thread.sleep(throttleDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.warn("Migration interrupted");
                    break;
                }
            }
        }

        if (totalMigrated >= maxPerRun) {
            Log.info("Reached max records per run (" + maxPerRun + "). " +
                    "Will continue in next scheduled run.");
        }

        return totalMigrated;
    }

    /**
     * Filter to find documents with old schema version.
     */
    private Bson getOldSchemaFilter() {
        return or(
            exists("schemaVersion", false),  // schemaVersion field doesn't exist (v1)
            lt("schemaVersion", TARGET_SCHEMA_VERSION)  // schemaVersion < 2
        );
    }

    /**
     * Migrate a single document to target schema version.
     * Returns true if migration was performed, false if skipped.
     */
    private boolean migrateDocument(MongoCollection<Document> collection, Document doc) {
        Object id = doc.get("_id");
        Document serviceAccount = doc.get("serviceAccount", Document.class);

        if (serviceAccount == null) {
            // No service account, just update version
            collection.updateOne(
                eq("_id", id),
                set("schemaVersion", TARGET_SCHEMA_VERSION)
            );
            return true;
        }

        // Build update operations
        List<Bson> updates = new ArrayList<>();
        updates.add(set("schemaVersion", TARGET_SCHEMA_VERSION));
        updates.add(set("updatedAt", Instant.now()));

        // Add code if missing
        if (!serviceAccount.containsKey("code") || serviceAccount.getString("code") == null) {
            String clientId = serviceAccount.getString("clientId");
            String code = generateCode(clientId);
            updates.add(set("serviceAccount.code", code));
        }

        // Add description if missing
        if (!serviceAccount.containsKey("description") || serviceAccount.getString("description") == null) {
            updates.add(set("serviceAccount.description", "Migrated from v1 schema"));
        }

        // Perform update
        collection.updateOne(
            eq("_id", id),
            combine(updates)
        );

        return true;
    }

    /**
     * Generate code from clientId (same logic as service layer).
     */
    private String generateCode(String clientId) {
        if (clientId != null && clientId.matches("[A-Z0-9_-]{3,50}")) {
            return clientId;
        }
        if (clientId != null) {
            return clientId.replaceAll("[^a-zA-Z0-9_-]", "_").toUpperCase();
        }
        return "SA_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
```

**Configuration:**
```properties
# application-dev.properties
migration.background.enabled=false

# application-prod.properties
migration.background.enabled=true
migration.batch-size=1000
migration.throttle-delay-ms=100
migration.max-per-run=100000  # Stop after 100K per run (prevents runaway)
```

### 6. Migration Metrics

Observable migration progress for monitoring.

```java
package tech.flowcatalyst.auth.metrics;

import com.mongodb.client.MongoDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import static com.mongodb.client.model.Filters.*;

/**
 * Exposes metrics about schema migration progress.
 * Scraped by Prometheus/Grafana for dashboards and alerts.
 */
@ApplicationScoped
public class MigrationMetrics {

    @Inject
    MongoDatabase database;

    @Inject
    MeterRegistry registry;

    /**
     * Update metrics every minute.
     */
    @Scheduled(every = "1m")
    public void updateMetrics() {
        updatePrincipalMetrics();
        // Add more collections as needed
    }

    private void updatePrincipalMetrics() {
        long total = database.getCollection("principals").estimatedDocumentCount();

        long v1 = database.getCollection("principals").countDocuments(
            or(exists("schemaVersion", false), lt("schemaVersion", 2))
        );

        long v2 = database.getCollection("principals").countDocuments(
            eq("schemaVersion", 2)
        );

        // Gauges for absolute counts
        registry.gauge("mongodb.schema.version",
            Tags.of("collection", "principals", "version", "1"), v1);
        registry.gauge("mongodb.schema.version",
            Tags.of("collection", "principals", "version", "2"), v2);

        // Migration progress percentage
        double percentMigrated = total > 0 ? (v2 * 100.0 / total) : 100.0;
        registry.gauge("mongodb.schema.migration_percent",
            Tags.of("collection", "principals", "target_version", "2"),
            percentMigrated);

        // Alert if migration stalled
        if (v1 > 0 && percentMigrated < 100.0) {
            registry.counter("mongodb.schema.migration_in_progress",
                Tags.of("collection", "principals")).increment(0);
        }
    }
}
```

**Grafana Dashboard Queries:**
```promql
# Migration progress
mongodb_schema_migration_percent{collection="principals"}

# Records remaining
mongodb_schema_version{collection="principals",version="1"}

# Alert: Migration not progressing
rate(mongodb_schema_version{version="1"}[1h]) == 0 AND mongodb_schema_version{version="1"} > 0
```

### 7. Rollback Strategy

**Safe Rollback Zones:**

```
Phase 1: EXPAND ✅ Safe to rollback anytime
  - Old code: Ignores new fields (MongoDB drops unknown fields on write)
  - New code: Handles both v1 and v2
  - Data loss risk: Medium (old code doesn't preserve new fields on write)

Phase 2: MIGRATE ✅ Safe to rollback anytime
  - Same as Phase 1
  - Some records migrated, some not
  - Both old and new code work

Phase 3: CONTRACT ⚠️ CANNOT ROLLBACK
  - Old code: Cannot handle required new fields
  - Rolling back = production outage
  - Must roll forward with fix
```

**Rollback Procedure:**

**During Phase 1 or 2 (Safe):**
```bash
# 1. Rollback application code
kubectl rollout undo deployment/flowcatalyst-auth

# 2. Verify old code is running
kubectl get pods -l app=flowcatalyst-auth

# 3. (Optional) Remove indexes if causing issues
mongosh --eval 'db.principals.dropIndex("idx_service_account_code")'

# 4. Monitor error rates
# Old code ignores new fields, should work fine
```

**During Phase 3 (Point of No Return):**
```bash
# Cannot rollback - must roll forward

# Option 1: Hot fix (patch and redeploy)
# Fix the bug in new code and deploy

# Option 2: Feature flag (disable new behavior)
kubectl set env deployment/flowcatalyst-auth FEATURE_USE_CODE=false

# Option 3: Compensating migration (undo schema change)
# Deploy a new Mongock migration that reverses the change
# Then rollback code
```

**Rollback Decision Tree:**
```
Is migration in Phase 3 (CONTRACT)?
├─ No → Safe to rollback
│   ├─ Rollback application code
│   └─ Monitor for data loss on writes
├─ Yes → CANNOT rollback
    ├─ Is it a code bug?
    │   └─ Fix bug and redeploy (roll forward)
    └─ Is it a schema issue?
        └─ Deploy compensating migration + rollback code
```

## Deployment Workflow

### Development Environment
```bash
# Auto-run migrations on startup
MONGOCK_ENABLED=true ./gradlew quarkusDev

# Migrations run every startup (safe, small dataset)
```

### Production Environment

**Step 1: Deploy Migration Job**
```yaml
# k8s/migration-job.yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: flowcatalyst-auth-migration-v002
spec:
  template:
    spec:
      containers:
      - name: migrator
        image: flowcatalyst-auth:v1.2.0
        env:
        - name: MONGOCK_ENABLED
          value: "true"
        - name: QUARKUS_PROFILE
          value: "migration"
      restartPolicy: OnFailure
  backoffLimit: 3
```

```bash
# Run migration job
kubectl apply -f k8s/migration-job.yaml

# Wait for completion
kubectl wait --for=condition=complete --timeout=600s job/flowcatalyst-auth-migration-v002

# Check logs
kubectl logs job/flowcatalyst-auth-migration-v002

# Verify indexes created
mongosh --eval 'db.principals.getIndexes()'
```

**Step 2: Deploy Application**
```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: flowcatalyst-auth
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: app
        image: flowcatalyst-auth:v1.2.0
        env:
        - name: MONGOCK_ENABLED
          value: "false"  # Don't run migrations on app instances
        - name: MIGRATION_BACKGROUND_ENABLED
          value: "true"   # Enable background migration
        livenessProbe:
          httpGet:
            path: /q/health/live
            port: 8080
          initialDelaySeconds: 60  # Allow time for initialization
        readinessProbe:
          httpGet:
            path: /q/health/ready
            port: 8080
          initialDelaySeconds: 30
```

```bash
# Deploy application
kubectl apply -f k8s/deployment.yaml

# Monitor rollout
kubectl rollout status deployment/flowcatalyst-auth

# Check migration progress
kubectl exec -it deployment/flowcatalyst-auth -- \
  curl localhost:8080/q/metrics | grep mongodb_schema_migration_percent
```

**Step 3: Monitor Background Migration**
```bash
# Watch migration metrics
watch -n 5 'kubectl exec deployment/flowcatalyst-auth -- \
  curl -s localhost:8080/q/metrics | grep mongodb_schema'

# Check application logs for migration progress
kubectl logs -f deployment/flowcatalyst-auth | grep -i migration

# Query MongoDB directly
mongosh --eval 'db.principals.countDocuments({schemaVersion: {$lt: 2}})'
```

**Step 4: Deploy CONTRACT Phase (After 100% Migrated)**
```bash
# Verify migration complete
REMAINING=$(mongosh --quiet --eval \
  'db.principals.countDocuments({$or: [{schemaVersion: {$exists: false}}, {schemaVersion: {$lt: 2}}]})')

if [ "$REMAINING" -eq 0 ]; then
  echo "✅ Safe to deploy CONTRACT phase"

  # Deploy v1.3.0 with required fields
  kubectl set image deployment/flowcatalyst-auth \
    app=flowcatalyst-auth:v1.3.0
else
  echo "⚠️  $REMAINING records still on old schema. Wait for migration to complete."
fi
```

## Example: ServiceAccount Code Migration

**Timeline:**

**Week 1: Phase 1 (EXPAND)**
```
Deploy: v1.1.0
- ServiceAccount.code added as optional field
- Sparse unique index created
- Code handles both with/without code field
- Background migration starts (off-peak hours)
Rollback: ✅ Safe anytime
```

**Week 2-3: Phase 2 (MIGRATE)**
```
Status: v1.1.0 running
- Background job processes 100K records/day
- Hot records auto-migrate on write
- 85% → 95% → 100% migrated
Rollback: ✅ Safe anytime
```

**Week 4: Phase 3 (CONTRACT)**
```
Prerequisites:
- 100% records migrated ✅
- No v1 schemas remain ✅
- Monitoring stable ✅

Deploy: v1.2.0
- ServiceAccount.code now required
- Strict unique index (no nulls)
- MongoDB validation added
- Remove v1 compatibility code
Rollback: ⚠️ CANNOT rollback without compensating migration
```

## Testing Strategy

### Pre-Production Validation

**1. Test on Production Snapshot**
```bash
# Restore prod data to staging
mongodump --uri="mongodb://prod" --gzip --archive=prod-snapshot.gz
mongorestore --uri="mongodb://staging" --gzip --archive=prod-snapshot.gz --drop

# Run migration on staging
kubectl apply -f k8s/migration-job.yaml --context=staging

# Verify results
mongosh staging --eval '
  db.principals.aggregate([
    {$group: {_id: "$schemaVersion", count: {$sum: 1}}}
  ])
'
```

**2. Load Testing During Migration**
```bash
# Start background migration
curl -X POST staging-api/admin/migration/start

# Run load test simultaneously
k6 run loadtest.js --vus 100 --duration 10m

# Verify:
# - No errors in application logs
# - Response times stable
# - Migration progressing
```

**3. Rollback Test**
```bash
# Deploy new version
kubectl apply -f deployment-v1.1.yaml

# Wait for rollout
kubectl rollout status deployment/flowcatalyst-auth

# Trigger rollback
kubectl rollout undo deployment/flowcatalyst-auth

# Verify:
# - Old version handles new schema documents
# - No 500 errors
# - Graceful degradation
```

### Unit Tests

```java
@QuarkusTest
public class SchemaMigrationTest {

    @Inject
    PrincipalService principalService;

    @Inject
    SchemaMigrationService migrationService;

    @Test
    public void testReadV1Schema() {
        // Given: v1 principal (no code field)
        Principal v1Principal = new Principal();
        v1Principal.schemaVersion = 1;
        v1Principal.serviceAccount = new ServiceAccount();
        v1Principal.serviceAccount.clientId = "client-123";
        v1Principal.persist();

        // When: Read with v2 code
        String code = principalService.getServiceAccountCode(v1Principal);

        // Then: Falls back to clientId
        assertEquals("client-123", code);
    }

    @Test
    public void testWriteMigratesSchema() {
        // Given: v1 principal
        Principal v1Principal = new Principal();
        v1Principal.schemaVersion = 1;
        v1Principal.serviceAccount = new ServiceAccount();
        v1Principal.serviceAccount.clientId = "client-456";
        v1Principal.persist();

        // When: Update and save
        v1Principal.name = "Updated Name";
        principalService.save(v1Principal);

        // Then: Auto-migrated to v2
        Principal updated = Principal.findById(v1Principal.id);
        assertEquals(2, updated.schemaVersion);
        assertNotNull(updated.serviceAccount.code);
    }

    @Test
    public void testBackgroundMigration() {
        // Given: 1000 v1 principals
        for (int i = 0; i < 1000; i++) {
            Principal p = new Principal();
            p.schemaVersion = 1;
            p.serviceAccount = new ServiceAccount();
            p.serviceAccount.clientId = "client-" + i;
            p.persist();
        }

        // When: Run migration
        long migrated = migrationService.migrateOldRecords();

        // Then: All migrated
        assertEquals(1000, migrated);
        assertEquals(0, Principal.count("schemaVersion < 2"));
    }
}
```

## Monitoring & Alerts

### Key Metrics

```properties
# Prometheus metrics to monitor

# Migration progress (should reach 100%)
mongodb_schema_migration_percent{collection="principals"} = 0-100

# Records remaining (should reach 0)
mongodb_schema_version{collection="principals",version="1"} = N

# Migration rate (records/second)
rate(mongodb_schema_version{version="2"}[5m])

# Error rate during migration (should stay low)
http_server_requests_seconds_count{status=~"5..",uri=~"/api/.*"}
```

### Alerts

```yaml
# Grafana/Prometheus alerts

- alert: SchemaMigrationStalled
  expr: |
    mongodb_schema_version{version="1"} > 0
    and
    rate(mongodb_schema_version{version="2"}[1h]) == 0
  for: 2h
  annotations:
    summary: "Schema migration not progressing"
    description: "{{ $value }} records still on old schema, but no progress in 1 hour"

- alert: SchemaMigrationErrorRate
  expr: |
    rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.01
  for: 10m
  annotations:
    summary: "High error rate during migration"
    description: "Error rate {{ $value }}/s during schema migration"

- alert: BackgroundMigrationDisabled
  expr: |
    mongodb_schema_version{version="1"} > 1000
    and
    up{job="flowcatalyst-auth"} == 1
  for: 24h
  annotations:
    summary: "Background migration not running"
    description: "{{ $value }} records still unmigrated after 24 hours"
```

### Dashboards

**Grafana Dashboard - Migration Progress:**
```json
{
  "panels": [
    {
      "title": "Migration Progress",
      "targets": [
        {
          "expr": "mongodb_schema_migration_percent{collection='principals'}"
        }
      ],
      "type": "gauge",
      "thresholds": [0, 50, 90, 100]
    },
    {
      "title": "Records by Schema Version",
      "targets": [
        {
          "expr": "mongodb_schema_version{collection='principals'}",
          "legendFormat": "v{{ version }}"
        }
      ],
      "type": "graph"
    },
    {
      "title": "Migration Rate",
      "targets": [
        {
          "expr": "rate(mongodb_schema_version{collection='principals',version='2'}[5m])"
        }
      ],
      "type": "graph"
    }
  ]
}
```

## Troubleshooting

### Migration Job Fails

**Symptom:** Migration job exits with error
```
Error: Failed to acquire lock after 30000ms
```

**Cause:** Another migration job still running or lock stuck

**Solution:**
```bash
# Check for running migrations
kubectl get jobs | grep migration

# Check lock in MongoDB
mongosh --eval 'db.mongockLock.find()'

# If lock stuck (older than 5 minutes), remove it
mongosh --eval 'db.mongockLock.deleteMany({})'

# Retry migration
kubectl delete job flowcatalyst-auth-migration-v002
kubectl apply -f k8s/migration-job.yaml
```

### Background Migration Not Progressing

**Symptom:** Metrics show same unmigrated count for hours

**Debugging:**
```bash
# Check if background migration enabled
kubectl exec deployment/flowcatalyst-auth -- \
  env | grep MIGRATION_BACKGROUND_ENABLED

# Check application logs
kubectl logs deployment/flowcatalyst-auth | grep -i migration

# Check for errors in migration logic
kubectl logs deployment/flowcatalyst-auth | grep ERROR
```

**Common causes:**
- `migration.background.enabled=false` in config
- Scheduler not running (check Quarkus scheduler enabled)
- Database connection issues
- Logic error in migration code (check logs)

### Application Errors After Migration

**Symptom:** 500 errors after deploying new version

**Debugging:**
```bash
# Check if documents have required fields
mongosh --eval '
  db.principals.findOne({
    "serviceAccount.code": {$exists: false}
  })
'

# Check schema version distribution
mongosh --eval '
  db.principals.aggregate([
    {$group: {_id: "$schemaVersion", count: {$sum: 1}}}
  ])
'

# Check application logs for null pointer exceptions
kubectl logs deployment/flowcatalyst-auth | grep NullPointerException
```

**Solution:**
- If v1 records remain: Code should handle gracefully (check `PrincipalService.getServiceAccountCode`)
- If v2 records missing data: Migration logic bug (check `SchemaMigrationService.migrateDocument`)
- If index issues: Check index creation succeeded (check `db.principals.getIndexes()`)

### Rollback Fails

**Symptom:** Old version crashes after rollback

**Debugging:**
```bash
# Check what version documents are on
mongosh --eval 'db.principals.findOne({schemaVersion: 2})'

# Check if old code trying to read new schema
kubectl logs deployment/flowcatalyst-auth | grep -i "unknown field"
```

**Solution:**
- MongoDB silently drops unknown fields - old code should work
- If crashes: New code added **required** fields (not supposed to happen in EXPAND phase)
- Must roll forward or run compensating migration to remove new fields

## Checklist for New Migrations

Before writing a new migration, verify:

- [ ] Fast operations only (< 1 minute)
- [ ] Indexes created as sparse/optional first
- [ ] Schema version field added to entity
- [ ] Service layer handles all versions
- [ ] Write path auto-migrates documents
- [ ] Background migration job created
- [ ] Metrics added for observability
- [ ] Unit tests for v1 → v2 compatibility
- [ ] Tested on production snapshot
- [ ] Rollback procedure documented
- [ ] CONTRACT phase only after 100% migrated
- [ ] Monitoring/alerts configured

## References

- **Expand/Contract Pattern**: https://martinfowler.com/bliki/ParallelChange.html
- **Mongock Documentation**: https://docs.mongock.io/
- **MongoDB Schema Design**: https://www.mongodb.com/docs/manual/core/data-modeling-introduction/
- **Zero Downtime Deployments**: https://cloud.google.com/architecture/application-deployment-and-testing-strategies

## Version History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-10-06 | System | Initial strategy document |
| 2.0 | TBD | | After first production migration, update with lessons learned |

---

**Last Updated:** 2025-10-06
**Status:** Draft - Ready for Implementation
