# MongoDB Migration Plan

## Overview

Migrating from PostgreSQL to MongoDB using Panache MongoDB with Repository pattern.

## Migration Strategy

### Phase 1: Update Dependencies ✅

**Remove:**
```kotlin
// build.gradle.kts
implementation("io.quarkus:quarkus-hibernate-orm-panache")
implementation("io.quarkus:quarkus-jdbc-postgresql")
implementation("io.quarkus:quarkus-flyway")
```

**Add:**
```kotlin
implementation("io.quarkus:quarkus-mongodb-panache")
```

### Phase 2: Convert Entities to POJOs

**Before (JPA):**
```java
@Entity
@Table(name = "dispatch_job")
public class DispatchJob extends PanacheEntityBase {
    @Id
    public Long id;

    @Column(name = "external_id")
    public String externalId;

    @OneToMany(mappedBy = "dispatchJob", cascade = CascadeType.ALL)
    public List<DispatchJobMetadata> metadata = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credentials_id")
    public DispatchCredentials credentials;
}
```

**After (MongoDB POJO):**
```java
public class DispatchJob {
    public Long id;  // TSID as NumberLong
    public String externalId;

    // Embedded metadata (no separate collection)
    public List<DispatchJobMetadata> metadata = new ArrayList<>();

    // Reference by ID (no embedding)
    public Long credentialsId;

    public String source;
    public String type;
    public String groupId;
    public String targetUrl;
    public DispatchProtocol protocol;
    public Map<String, String> headers = new HashMap<>();
    public String payload;
    public String payloadContentType;
    public Integer maxRetries;
    public String retryStrategy;
    public Instant scheduledFor;
    public Instant expiresAt;
    public DispatchStatus status;
    public Integer attemptCount;
    public Instant lastAttemptAt;
    public Instant completedAt;
    public Long durationMillis;
    public String lastError;
    public String idempotencyKey;
    public Instant createdAt;
    public Instant updatedAt;

    // Embedded attempts (single document read)
    public List<DispatchAttempt> attempts = new ArrayList<>();
}
```

**DispatchJobMetadata (Embedded):**
```java
public class DispatchJobMetadata {
    public Long id;  // TSID
    public String key;
    public String value;

    public DispatchJobMetadata() {}

    public DispatchJobMetadata(String key, String value) {
        this.id = TsidGenerator.generate();
        this.key = key;
        this.value = value;
    }
}
```

**DispatchAttempt (Embedded):**
```java
public class DispatchAttempt {
    public Long id;  // TSID
    public Integer attemptNumber;
    public Instant attemptedAt;
    public Instant completedAt;
    public Long durationMillis;
    public DispatchAttemptStatus status;
    public Integer responseCode;
    public String responseBody;
    public String errorMessage;
    public String errorStackTrace;
    public Instant createdAt;
}
```

**DispatchCredentials (Separate Collection):**
```java
public class DispatchCredentials {
    public Long id;  // TSID as NumberLong
    public String bearerToken;
    public String signingSecret;
    public SignatureAlgorithm algorithm;
    public Instant createdAt;
    public Instant updatedAt;
}
```

### Phase 3: Create Panache Repositories

**DispatchJobRepository:**
```java
@ApplicationScoped
public class DispatchJobRepository
    implements PanacheMongoRepositoryBase<DispatchJob, Long> {

    @Inject
    MongoClient mongoClient;

    // ✅ CREATE - Use Panache persist
    public DispatchJob create(CreateDispatchJobRequest request) {
        DispatchJob job = new DispatchJob();
        job.id = TsidGenerator.generate();
        job.externalId = request.externalId();
        job.source = request.source();
        job.type = request.type();
        job.groupId = request.groupId();
        job.targetUrl = request.targetUrl();
        job.protocol = request.protocol() != null ?
            request.protocol() : DispatchProtocol.HTTP_WEBHOOK;
        job.headers = request.headers() != null ?
            request.headers() : new HashMap<>();
        job.payload = request.payload();
        job.payloadContentType = request.payloadContentType() != null ?
            request.payloadContentType() : "application/json";
        job.credentialsId = request.credentialsId();  // Store ID, not object
        job.maxRetries = request.maxRetries() != null ? request.maxRetries() : 3;
        job.retryStrategy = request.retryStrategy() != null ?
            request.retryStrategy() : "exponential";
        job.scheduledFor = request.scheduledFor();
        job.expiresAt = request.expiresAt();
        job.idempotencyKey = request.idempotencyKey();
        job.status = DispatchStatus.PENDING;
        job.attemptCount = 0;
        job.createdAt = Instant.now();
        job.updatedAt = Instant.now();
        job.attempts = new ArrayList<>();

        // Convert metadata Map to embedded list
        if (request.metadata() != null) {
            job.metadata = request.metadata().entrySet().stream()
                .map(e -> new DispatchJobMetadata(e.getKey(), e.getValue()))
                .toList();
        }

        persist(job);  // Panache handles serialization
        return job;
    }

    // ✅ READ - Use Panache findByIdOptional
    public Optional<DispatchJob> findById(Long id) {
        return findByIdOptional(id);
    }

    // ✅ UPDATE - Add attempt atomically with MongoClient
    public void addAttempt(Long jobId, DispatchAttempt attempt) {
        attempt.id = TsidGenerator.generate();
        attempt.createdAt = Instant.now();

        mongoCollection(DispatchJob.class).updateOne(
            eq("_id", jobId),
            combine(
                push("attempts", attempt),
                inc("attemptCount", 1),
                set("lastAttemptAt", attempt.attemptedAt),
                set("updatedAt", Instant.now())
            )
        );
    }

    // ✅ UPDATE - Update job status atomically
    public void updateStatus(Long jobId, DispatchStatus status,
                             Instant completedAt, Long durationMillis, String lastError) {
        Bson updates = combine(
            set("status", status),
            set("updatedAt", Instant.now())
        );

        if (completedAt != null) {
            updates = combine(updates, set("completedAt", completedAt));
        }
        if (durationMillis != null) {
            updates = combine(updates, set("durationMillis", durationMillis));
        }
        if (lastError != null) {
            updates = combine(updates, set("lastError", lastError));
        }

        mongoCollection(DispatchJob.class).updateOne(eq("_id", jobId), updates);
    }

    // ✅ QUERY - Find by metadata using Panache
    public List<DispatchJob> findByMetadata(String key, String value) {
        return find("{ metadata: { $elemMatch: { key: ?1, value: ?2 } } }",
                    key, value).list();
    }

    // ✅ QUERY - Complex filter using Panache
    public List<DispatchJob> findWithFilter(DispatchJobFilter filter) {
        List<Bson> filters = new ArrayList<>();

        if (filter.status() != null) {
            filters.add(eq("status", filter.status()));
        }
        if (filter.source() != null) {
            filters.add(eq("source", filter.source()));
        }
        if (filter.type() != null) {
            filters.add(eq("type", filter.type()));
        }
        if (filter.groupId() != null) {
            filters.add(eq("groupId", filter.groupId()));
        }
        if (filter.createdAfter() != null) {
            filters.add(gte("createdAt", filter.createdAfter()));
        }
        if (filter.createdBefore() != null) {
            filters.add(lte("createdAt", filter.createdBefore()));
        }

        Bson combinedFilter = filters.isEmpty() ? new Document() : and(filters);

        return mongoCollection(DispatchJob.class)
            .find(combinedFilter)
            .skip(filter.page() * filter.size())
            .limit(filter.size())
            .into(new ArrayList<>());
    }

    // ✅ COUNT - For pagination
    public long countWithFilter(DispatchJobFilter filter) {
        List<Bson> filters = new ArrayList<>();

        if (filter.status() != null) {
            filters.add(eq("status", filter.status()));
        }
        if (filter.source() != null) {
            filters.add(eq("source", filter.source()));
        }
        // ... same as findWithFilter

        Bson combinedFilter = filters.isEmpty() ? new Document() : and(filters);

        return mongoCollection(DispatchJob.class).countDocuments(combinedFilter);
    }
}
```

**CredentialsRepository:**
```java
@ApplicationScoped
public class CredentialsRepository
    implements PanacheMongoRepositoryBase<DispatchCredentials, Long> {

    public DispatchCredentials create(CreateCredentialsRequest request) {
        DispatchCredentials credentials = new DispatchCredentials();
        credentials.id = TsidGenerator.generate();
        credentials.bearerToken = request.bearerToken();
        credentials.signingSecret = request.signingSecret();
        credentials.algorithm = request.algorithm() != null ?
            request.algorithm() : SignatureAlgorithm.HMAC_SHA256;
        credentials.createdAt = Instant.now();
        credentials.updatedAt = Instant.now();

        persist(credentials);
        return credentials;
    }

    public Optional<DispatchCredentials> findById(Long id) {
        return findByIdOptional(id);
    }

    public boolean deleteById(Long id) {
        return deleteById(id);  // Panache method
    }
}
```

### Phase 4: Update Services

**DispatchJobService Changes:**
```java
@ApplicationScoped
public class DispatchJobService {

    @Inject
    DispatchJobRepository dispatchJobRepository;  // Now uses MongoDB repo

    @Inject
    CredentialsRepository credentialsRepository;  // Now uses MongoDB repo

    @Inject
    WebhookDispatcher webhookDispatcher;

    @Inject
    SqsClient sqsClient;

    @Inject
    ObjectMapper objectMapper;

    @Transactional  // Panache MongoDB supports transactions
    public DispatchJob createDispatchJob(CreateDispatchJobRequest request) {
        // Validate credentials exist
        DispatchCredentials credentials = credentialsRepository.findById(request.credentialsId())
            .orElseThrow(() -> new IllegalArgumentException(
                "Credentials not found: " + request.credentialsId()));

        // Create via repository (handles TSID, metadata conversion)
        DispatchJob job = dispatchJobRepository.create(request);

        LOG.infof("Created dispatch job [%s] of type [%s] from source [%s]",
                  job.id, job.type, job.source);

        // Send to SQS queue
        sendToQueue(job, request.queueUrl());

        return job;
    }

    @Transactional
    public DispatchJobProcessResult processDispatchJob(Long dispatchJobId) {
        // Load job (single document read - includes metadata and attempts)
        DispatchJob job = dispatchJobRepository.findById(dispatchJobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + dispatchJobId));

        LOG.infof("Processing dispatch job [%s], attempt %d/%d",
                  job.id, job.attemptCount + 1, job.maxRetries);

        // Update status to IN_PROGRESS
        dispatchJobRepository.updateStatus(job.id, DispatchStatus.IN_PROGRESS,
                                          null, null, null);

        // Load credentials (separate collection)
        DispatchCredentials credentials = credentialsRepository.findById(job.credentialsId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Credentials not found: " + job.credentialsId));

        // Dispatch webhook
        DispatchAttempt attempt = webhookDispatcher.sendWebhook(job, credentials);

        // Add attempt atomically
        dispatchJobRepository.addAttempt(job.id, attempt);

        // Update job based on attempt result
        if (attempt.status == DispatchAttemptStatus.SUCCESS) {
            Instant completedAt = Instant.now();
            Long duration = Duration.between(job.createdAt, completedAt).toMillis();

            dispatchJobRepository.updateStatus(
                job.id, DispatchStatus.COMPLETED, completedAt, duration, null);

            LOG.infof("Dispatch job [%s] completed successfully", job.id);
            return new DispatchJobProcessResult(true, "Success", 200);

        } else {
            // Failure - check if we should retry
            int newAttemptCount = job.attemptCount + 1;

            if (newAttemptCount >= job.maxRetries) {
                // Max attempts exhausted
                Instant completedAt = Instant.now();
                Long duration = Duration.between(job.createdAt, completedAt).toMillis();

                dispatchJobRepository.updateStatus(
                    job.id, DispatchStatus.ERROR, completedAt, duration,
                    attempt.errorMessage);

                LOG.warnf("Dispatch job [%s] failed after %d attempts",
                         job.id, newAttemptCount);
                return new DispatchJobProcessResult(false, "Max attempts exhausted", 200);

            } else {
                // More attempts available
                dispatchJobRepository.updateStatus(
                    job.id, DispatchStatus.FAILED, null, null, attempt.errorMessage);

                LOG.warnf("Dispatch job [%s] failed, attempt %d/%d, will retry",
                         job.id, newAttemptCount, job.maxRetries);
                return new DispatchJobProcessResult(false, "Failed, will retry", 400);
            }
        }
    }

    public Optional<DispatchJob> findById(Long id) {
        return dispatchJobRepository.findById(id);
    }

    public List<DispatchJob> findWithFilter(DispatchJobFilter filter) {
        return dispatchJobRepository.findWithFilter(filter);
    }

    public long countWithFilter(DispatchJobFilter filter) {
        return dispatchJobRepository.countWithFilter(filter);
    }

    // No longer need separate findAttemptsByJobId - attempts embedded in job
}
```

**CredentialsService Changes:**
```java
@ApplicationScoped
public class CredentialsService {

    private static final Logger LOG = Logger.getLogger(CredentialsService.class);
    private static final String CACHE_NAME = "dispatch-credentials";

    @Inject
    CredentialsRepository credentialsRepository;

    @Transactional
    public DispatchCredentials create(CreateCredentialsRequest request) {
        DispatchCredentials credentials = credentialsRepository.create(request);
        LOG.infof("Created credentials [%s]", credentials.id);
        return credentials;
    }

    @CacheResult(cacheName = CACHE_NAME)
    public Optional<DispatchCredentials> findById(Long id) {
        LOG.debugf("Loading credentials [%s] from database (cache miss)", id);
        return credentialsRepository.findById(id);
    }

    @Transactional
    @CacheInvalidate(cacheName = CACHE_NAME)
    public boolean delete(Long id) {
        boolean deleted = credentialsRepository.deleteById(id);
        if (deleted) {
            LOG.infof("Deleted credentials [%s]", id);
        }
        return deleted;
    }

    @CacheInvalidate(cacheName = CACHE_NAME)
    public void invalidateCache(Long id) {
        LOG.debugf("Invalidated cache for credentials [%s]", id);
    }
}
```

**WebhookDispatcher Changes:**
```java
// Need to accept credentials as parameter (no longer loaded from job.credentials)
public DispatchAttempt sendWebhook(DispatchJob job, DispatchCredentials credentials) {
    Instant attemptStart = Instant.now();

    try {
        LOG.debugf("Sending webhook for dispatch job [%s] to [%s]",
                  (Object) job.id, job.targetUrl);

        // Sign the webhook
        WebhookSigner.SignedWebhookRequest signed = webhookSigner.sign(
            job.payload, credentials);

        // ... rest of implementation unchanged
    } catch (Exception e) {
        LOG.errorf(e, "Error sending webhook for dispatch job [%s]", (Object) job.id);
        return buildAttempt(job, attemptStart, null, e);
    }
}
```

### Phase 5: Update Configuration

**application.properties Changes:**

**Remove:**
```properties
# Database Configuration
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${DB_USERNAME:postgres}
quarkus.datasource.password=${DB_PASSWORD:postgres}
quarkus.datasource.jdbc.url=${DB_JDBC_URL:jdbc:postgresql://localhost:5432/flowcatalyst}

# Hibernate Configuration
quarkus.hibernate-orm.database.generation=none
quarkus.hibernate-orm.log.sql=false
%dev.quarkus.hibernate-orm.log.sql=true
%dev.quarkus.hibernate-orm.log.bind-parameters=true

# Flyway Configuration
quarkus.flyway.migrate-at-start=true
quarkus.flyway.baseline-on-migrate=true
quarkus.flyway.baseline-version=0
quarkus.flyway.locations=classpath:db/migration
```

**Add:**
```properties
# MongoDB Configuration
quarkus.mongodb.connection-string=${MONGODB_URI:mongodb://localhost:27017}
quarkus.mongodb.database=flowcatalyst

# Dev profile - local MongoDB
%dev.quarkus.mongodb.connection-string=mongodb://localhost:27017
%dev.quarkus.mongodb.database=flowcatalyst

# Prod profile - use environment variable
%prod.quarkus.mongodb.connection-string=${MONGODB_URI}
%prod.quarkus.mongodb.database=${MONGODB_DATABASE:flowcatalyst}
```

### Phase 6: Remove Flyway Migrations

Delete:
- `src/main/resources/db/migration/V1__initial_schema.sql`
- Any other migration files

MongoDB doesn't need schema migrations - collections and indexes are created on first use.

### Phase 7: Update DTOs

**DispatchAttemptResponse - Remove dispatchJobId:**
```java
public record DispatchAttemptResponse(
    @JsonProperty("id") Long id,
    // Remove: @JsonProperty("dispatchJobId") Long dispatchJobId,  // No longer separate
    @JsonProperty("attemptNumber") Integer attemptNumber,
    @JsonProperty("attemptedAt") Instant attemptedAt,
    @JsonProperty("completedAt") Instant completedAt,
    @JsonProperty("durationMillis") Long durationMillis,
    @JsonProperty("status") DispatchAttemptStatus status,
    @JsonProperty("responseCode") Integer responseCode,
    @JsonProperty("responseBody") String responseBody,
    @JsonProperty("errorMessage") String errorMessage,
    @JsonProperty("createdAt") Instant createdAt
) {
    public static DispatchAttemptResponse from(DispatchAttempt attempt) {
        return new DispatchAttemptResponse(
            attempt.id,
            // Remove: attempt.dispatchJob.id,
            attempt.attemptNumber,
            attempt.attemptedAt,
            attempt.completedAt,
            attempt.durationMillis,
            attempt.status,
            attempt.responseCode,
            attempt.responseBody,
            attempt.errorMessage,
            attempt.createdAt
        );
    }
}
```

**DispatchJobResponse - Update credentialsId:**
```java
public record DispatchJobResponse(
    @JsonProperty("id") Long id,
    @JsonProperty("externalId") String externalId,
    @JsonProperty("source") String source,
    @JsonProperty("type") String type,
    @JsonProperty("groupId") String groupId,
    @JsonProperty("metadata") Map<String, String> metadata,
    @JsonProperty("targetUrl") String targetUrl,
    @JsonProperty("protocol") DispatchProtocol protocol,
    @JsonProperty("headers") Map<String, String> headers,
    @JsonProperty("payloadContentType") String payloadContentType,
    @JsonProperty("credentialsId") Long credentialsId,
    @JsonProperty("status") DispatchStatus status,
    @JsonProperty("maxRetries") Integer maxRetries,
    @JsonProperty("retryStrategy") String retryStrategy,
    @JsonProperty("scheduledFor") Instant scheduledFor,
    @JsonProperty("expiresAt") Instant expiresAt,
    @JsonProperty("attemptCount") Integer attemptCount,
    @JsonProperty("lastAttemptAt") Instant lastAttemptAt,
    @JsonProperty("completedAt") Instant completedAt,
    @JsonProperty("durationMillis") Long durationMillis,
    @JsonProperty("lastError") String lastError,
    @JsonProperty("idempotencyKey") String idempotencyKey,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("updatedAt") Instant updatedAt
) {
    public static DispatchJobResponse from(DispatchJob job) {
        // Convert metadata list to Map
        Map<String, String> metadataMap = new HashMap<>();
        if (job.metadata != null) {
            job.metadata.forEach(m -> metadataMap.put(m.key, m.value));
        }

        return new DispatchJobResponse(
            job.id,
            job.externalId,
            job.source,
            job.type,
            job.groupId,
            metadataMap,
            job.targetUrl,
            job.protocol,
            job.headers,
            job.payloadContentType,
            job.credentialsId,  // Just the ID, not credentials.id
            job.status,
            job.maxRetries,
            job.retryStrategy,
            job.scheduledFor,
            job.expiresAt,
            job.attemptCount,
            job.lastAttemptAt,
            job.completedAt,
            job.durationMillis,
            job.lastError,
            job.idempotencyKey,
            job.createdAt,
            job.updatedAt
        );
    }
}
```

### Phase 8: Update Endpoints

**DispatchJobResource - Remove findAttemptsByJobId endpoint:**
```java
@GET
@Path("/{id}/attempts")
@Produces(MediaType.APPLICATION_JSON)
@Operation(summary = "Get all attempts for a dispatch job")
public Response getDispatchJobAttempts(@PathParam("id") Long id) {
    // Attempts are now embedded in job - just return job.attempts
    return dispatchJobService.findById(id)
        .map(job -> {
            List<DispatchAttemptResponse> responses = job.attempts.stream()
                .map(DispatchAttemptResponse::from)
                .toList();
            return Response.ok(responses).build();
        })
        .orElse(Response.status(404)
            .entity(new ErrorResponse("Dispatch job not found"))
            .build());
}
```

## Testing Strategy

### Local Development Setup

**Start MongoDB:**
```bash
docker run -d \
  --name mongodb \
  -p 27017:27017 \
  -e MONGO_INITDB_DATABASE=flowcatalyst \
  mongo:7
```

**Verify connection:**
```bash
mongosh mongodb://localhost:27017/flowcatalyst
```

### Create Indexes

**Manual index creation (optional - Panache can create automatically):**
```javascript
use flowcatalyst;

// DispatchJob indexes
db.DispatchJob.createIndex({ "externalId": 1 });
db.DispatchJob.createIndex({ "source": 1 });
db.DispatchJob.createIndex({ "type": 1 });
db.DispatchJob.createIndex({ "status": 1 });
db.DispatchJob.createIndex({ "scheduledFor": 1 });
db.DispatchJob.createIndex({ "createdAt": -1 });
db.DispatchJob.createIndex({ "metadata.key": 1, "metadata.value": 1 });

// DispatchCredentials indexes
db.DispatchCredentials.createIndex({ "createdAt": -1 });
```

## Benefits After Migration

### Performance Improvements

**Before (PostgreSQL):**
- Create job with 5 metadata entries: 6 INSERT statements (1 job + 5 metadata)
- Add attempt: 2 statements (1 UPDATE job + 1 INSERT attempt)
- Get job with attempts: 3 queries (1 job + 1 metadata JOIN + 1 attempts query)

**After (MongoDB):**
- Create job with 5 metadata entries: 1 insertOne (everything embedded)
- Add attempt: 1 updateOne with $push (atomic)
- Get job with attempts: 1 findOne (everything in one document)

### Code Simplification

**Before:**
```java
// Create with metadata
DispatchJob job = new DispatchJob();
job.persist();  // INSERT job

for (Map.Entry<String, String> entry : metadata.entrySet()) {
    DispatchJobMetadata meta = new DispatchJobMetadata();
    meta.dispatchJob = job;
    meta.persist();  // INSERT metadata (N times)
}
```

**After:**
```java
// Create with metadata
DispatchJob job = new DispatchJob();
job.metadata = metadata.entrySet().stream()
    .map(e -> new DispatchJobMetadata(e.getKey(), e.getValue()))
    .toList();
persist(job);  // Single insertOne with embedded metadata
```

### Aggregate Efficiency

MongoDB's document model matches the DispatchJob aggregate perfectly:
- Job + metadata + attempts = single document
- Atomic updates (no multi-table transactions)
- Single read for complete aggregate

## Rollback Plan

If issues arise during migration:

1. Keep PostgreSQL dependency in build.gradle.kts (commented out)
2. Keep Flyway migrations in source control (don't delete)
3. Tag git commit before migration: `git tag before-mongodb-migration`
4. Rollback: `git revert` MongoDB commits, uncomment PostgreSQL deps, rebuild

## Timeline

**Estimated effort:** 4-6 hours

- Phase 1 (Dependencies): 10 minutes
- Phase 2 (Entities): 30 minutes
- Phase 3 (Repositories): 60 minutes
- Phase 4 (Services): 45 minutes
- Phase 5 (Configuration): 10 minutes
- Phase 6 (Remove Flyway): 5 minutes
- Phase 7 (DTOs): 20 minutes
- Phase 8 (Endpoints): 15 minutes
- Testing: 60-90 minutes
- Documentation: 30 minutes
