# Database Strategy

## Current Implementation

Flow Catalyst uses **PostgreSQL with Hibernate ORM Panache** following Domain-Driven Design principles.

### Why Hibernate ORM Panache with PostgreSQL

**Decision:** Use Hibernate ORM Panache Repository pattern with PostgreSQL JSONB for complex data structures.

**Rationale:**
1. **DDD-aligned Repository pattern** - Separates persistence from domain entities
2. **Less boilerplate** - Panache simplifies JPA
3. **Pure domain entities** - Entities are POJOs with JPA annotations
4. **JSONB for flexibility** - PostgreSQL JSONB provides document-like storage when needed
5. **ACID guarantees** - Full transactional support
6. **Rich querying** - SQL + JSONB operators provide powerful query capabilities

**Pattern:**
```java
// Domain entity with JPA
@Entity
@Table(name = "dispatch_jobs")
public class DispatchJob extends PanacheEntityBase {
    @Id
    public Long id;  // TSID

    public String source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public List<DispatchAttempt> attempts;
    // ... domain logic methods
}

// Repository - Hibernate ORM Panache
@ApplicationScoped
public class DispatchJobRepository
    implements PanacheRepositoryBase<DispatchJob, Long> {

    @Inject EntityManager em;

    // Panache for simple CRUD
    public List<DispatchJob> findBySource(String source) {
        return find("source", source).list();
    }

    // Transactional updates
    @Transactional
    public void addAttempt(Long jobId, DispatchAttempt attempt) {
        DispatchJob job = findById(jobId);
        job.attempts.add(attempt);
        persist(job);
    }
}
```

## Database Schema

### Table Structure
- **Primary Keys:** TSID (Time-Sorted IDs) as `BIGINT` - NOT ObjectId or UUID
- **External IDs:** String field (up to 32 chars) for client-provided UUID/ULID identifiers
- **Schema Migrations:** Flyway manages all schema changes
- **Persistence:** Quarkus Hibernate ORM Panache with Repository pattern
- **Foreign Keys:** Used sparingly - only for data integrity where critical, NO CASCADE operations

### TSID Implementation
```java
// Centralized ID generation
public class TsidGenerator {
    public static Long generate() {
        return TsidCreator.getTsid().toLong();
    }
}

// Usage in entities with @PrePersist
@Entity
@Table(name = "dispatch_jobs")
public class DispatchJob extends PanacheEntityBase {
    @Id
    public Long id;  // TSID as BIGINT

    @Column(name = "external_id", length = 100)
    public String externalId;  // Client-provided ID

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = TsidGenerator.generate();
        }
    }
}
```

**Why TSID?**
- Time-sortable (creation order preserved)
- 64-bit efficiency (vs 128-bit UUID)
- Sequential-ish (better for indexing than random UUIDs)
- Monotonic (no collisions in distributed systems)

**Why External ID?**
- Clients may use their own UUID/ULID
- Indexed for lookups by client identifiers
- Decouples internal IDs from external contracts

## Architectural Philosophy

### No Foreign Key Constraints

**Decision:** Foreign keys are NOT used in this application.

**Rationale:**
- FKs couple services at the database level (anti-pattern in microservices)
- Application-level referential integrity is more flexible
- Enables independent service evolution
- Simplifies horizontal scaling and sharding
- Supports eventual consistency patterns

**Implementation:**
- Entities reference other entities by ID (Long)
- Application code validates relationships
- No CASCADE operations at database level
- Orphaned records are handled via cleanup jobs if needed

### Aggregate-Oriented Design

**Philosophy:** Data is organized around aggregates, not normalized relations.

**Example - Dispatch Job Aggregate:**
```
DispatchJob (Aggregate Root)
├── metadata (collection of key-value pairs)
├── attempts (collection of delivery attempts)
└── credentials (reference by ID, not FK)
```

**In PostgreSQL:**
```sql
CREATE TABLE dispatch_jobs (
  id BIGINT PRIMARY KEY,  -- TSID
  external_id VARCHAR(100),

  -- Metadata as JSONB
  metadata JSONB,

  -- Attempts as JSONB array
  attempts JSONB,

  credentials_id BIGINT,
  ...
);

-- GIN index for efficient JSONB queries
CREATE INDEX idx_dispatch_job_metadata_gin
  ON dispatch_jobs USING gin (metadata);
```

**Benefits of JSONB:**
- Single row read for complete aggregate
- Efficient queries using PostgreSQL JSONB operators (`@>`, `->`, `->>`)
- Maintains aggregate boundaries while providing SQL query power
- No separate junction tables needed for collections

## Future: MongoDB Migration Path

### Why MongoDB Is the Future Default

**For the Platform:**
1. **Aggregate model is natural** - Document structure matches domain aggregates
2. **No FK temptation** - MongoDB doesn't have FKs, enforces good boundaries
3. **Single-write efficiency** - Create job with metadata in ONE operation
4. **Atomic updates** - Update job + push attempt in single document operation
5. **Horizontal scaling** - Sharding by tenant/source is straightforward
6. **Schema flexibility** - LOB applications have varying needs per tenant

**Performance Benefits:**
```
PostgreSQL:
  - Create job: 1 INSERT (job) + N INSERTs (metadata) = N+1 round trips
  - Add attempt: 1 UPDATE (job) + 1 INSERT (attempt) = 2 round trips

MongoDB:
  - Create job: 1 insertOne with embedded metadata = 1 round trip
  - Add attempt: 1 updateOne with $push = 1 round trip
```

For high-throughput webhook dispatch (10k+ jobs/sec), this is significant.

### When to Migrate

**Current State (PostgreSQL):**
- ✅ Works well for development and initial deployments
- ✅ Simpler local development (PostgreSQL via Docker)
- ✅ Existing code works with Panache
- ✅ Flyway migrations are version controlled

**Migrate to MongoDB when:**
- Scale exceeds 10M jobs/day
- Multi-region deployment is needed
- Multiple LOB applications are built on the platform
- External companies adopt and prefer MongoDB

### Migration Strategy

**Phase 1: Preparation (Current)**
- Keep repository boundary clean (see guidelines below)
- Use domain objects in service layer (not database types)
- Document aggregate structure clearly

**Phase 2: Dual-Write (During Migration)**
- Implement MongoDB repository
- Write to both PostgreSQL and MongoDB
- Read from PostgreSQL (source of truth)
- Validate MongoDB data consistency

**Phase 3: Cutover**
- Switch reads to MongoDB
- Verify performance and correctness
- Stop writing to PostgreSQL
- Keep PostgreSQL backup for rollback window

**Phase 4: Cleanup**
- Archive PostgreSQL data
- Remove PostgreSQL dependencies
- Update documentation

## Supporting Alternative Databases

### Design for External Adoption

**Scenario:** External company wants to use Flow Catalyst but prefers PostgreSQL over MongoDB (or vice versa).

**Current Approach:** Concrete implementation, NOT premature abstraction.

### Repository Structure (Current)

```java
@ApplicationScoped
public class DispatchJobRepository {  // Concrete class, not interface

    // PostgreSQL/Panache implementation
    @Inject
    EntityManager em;

    // ✅ Public methods use domain types ONLY
    public DispatchJob create(CreateDispatchJobRequest request) {
        DispatchJob job = new DispatchJob();
        job.id = TsidGenerator.generate();
        job.externalId = request.externalId();
        // ... build aggregate

        if (request.metadata() != null) {
            request.metadata().forEach((key, value) -> {
                DispatchJobMetadata meta = new DispatchJobMetadata();
                meta.id = TsidGenerator.generate();
                meta.key = key;
                meta.value = value;
                job.metadata.add(meta);
            });
        }

        job.persist();
        return job;
    }

    public Optional<DispatchJob> findById(Long id) {
        return DispatchJob.findByIdOptional(id);
    }

    public void addAttempt(Long jobId, DispatchAttempt attempt) {
        DispatchJob job = findById(jobId).orElseThrow();
        attempt.id = TsidGenerator.generate();
        attempt.dispatchJob = job;
        attempt.persist();

        job.attemptCount++;
        job.lastAttemptAt = attempt.attemptedAt;
        job.persist();
    }
}
```

**Service Layer (Database-Agnostic):**
```java
@ApplicationScoped
public class DispatchJobService {

    @Inject
    DispatchJobRepository repository;  // Just concrete class

    // ✅ No database imports in this file
    // ✅ Only domain objects (DispatchJob, DispatchAttempt)

    public DispatchJob createDispatchJob(CreateDispatchJobRequest request) {
        // Validate credentials exist
        DispatchCredentials creds = credentialsService.findById(request.credentialsId())
            .orElseThrow(() -> new IllegalArgumentException("Credentials not found"));

        // Create via repository (database-agnostic)
        return repository.create(request);
    }
}
```

### Extracting Interface for Alternative Implementations

**When needed** (external company requests PostgreSQL when we've moved to MongoDB, or vice versa):

**Step 1: Extract Interface (5 minutes)**
```java
public interface DispatchJobRepository {
    DispatchJob create(CreateDispatchJobRequest request);
    Optional<DispatchJob> findById(Long id);
    void addAttempt(Long jobId, DispatchAttempt attempt);
    List<DispatchJob> findByMetadata(Map<String, String> filters);
    List<DispatchJob> findWithFilter(DispatchJobFilter filter);
    long countWithFilter(DispatchJobFilter filter);
    // ... methods used by service layer
}
```

**Step 2: Rename Implementation**
```java
@ApplicationScoped
public class DispatchJobPostgresRepository implements DispatchJobRepository {
    // Current PostgreSQL/Panache code
}
```

**Step 3: Implement MongoDB Version**
```java
@ApplicationScoped
public class DispatchJobMongoRepository implements DispatchJobRepository {

    @Inject
    MongoClient mongoClient;

    private MongoCollection<Document> collection;

    @PostConstruct
    void init() {
        this.collection = mongoClient
            .getDatabase("flowcatalyst")
            .getCollection("dispatch_jobs");
    }

    @Override
    public DispatchJob create(CreateDispatchJobRequest request) {
        // Build document with embedded metadata
        Document doc = new Document()
            .append("_id", TsidGenerator.generate())
            .append("externalId", request.externalId())
            .append("source", request.source())
            .append("metadata", request.metadata().entrySet().stream()
                .map(e -> new Document("key", e.getKey()).append("value", e.getValue()))
                .toList())
            .append("attempts", new ArrayList<>())
            .append("status", "PENDING");

        collection.insertOne(doc);  // Single write operation
        return toDispatchJob(doc);
    }

    @Override
    public void addAttempt(Long jobId, DispatchAttempt attempt) {
        // Atomic update - job + attempt in one operation
        collection.updateOne(
            eq("_id", jobId),
            combine(
                push("attempts", toDocument(attempt)),
                inc("attemptCount", 1),
                set("lastAttemptAt", attempt.attemptedAt)
            )
        );
    }
}
```

**Step 4: Configuration Selection**
```java
// Use CDI qualifier or config property
@ApplicationScoped
public class RepositoryProducer {

    @ConfigProperty(name = "dispatch.database.type", defaultValue = "postgresql")
    String databaseType;

    @Produces
    @ApplicationScoped
    public DispatchJobRepository createRepository(
            DispatchJobPostgresRepository postgresRepo,
            DispatchJobMongoRepository mongoRepo) {

        return "mongodb".equalsIgnoreCase(databaseType)
            ? mongoRepo
            : postgresRepo;
    }
}
```

### Why Not Abstract Now?

**Reasons to wait:**
1. ✅ **YAGNI** - Don't build what you don't need yet
2. ✅ **Interface emerges from usage** - Real methods emerge as service layer develops
3. ✅ **Avoids premature decisions** - Don't guess at abstraction before understanding domain
4. ✅ **Simpler codebase** - Less ceremony, easier to understand
5. ✅ **External companies can fork** - They can extract interface when needed

**Extraction is trivial IF:**
- ✅ Repository methods use domain objects (DispatchJob, not Document/Entity)
- ✅ Service layer doesn't import database-specific classes
- ✅ Repository is the only layer that knows about database

**Extraction takes ~10 minutes when needed** - IntelliJ "Extract Interface" refactoring.

## Repository Layer Guidelines

### ✅ DO: Keep Repository Boundary Clean

**Good:**
```java
// Repository method signature
public DispatchJob create(CreateDispatchJobRequest request) { ... }
public Optional<DispatchJob> findById(Long id) { ... }
public List<DispatchJob> findByMetadata(Map<String, String> filters) { ... }

// Service layer
@Inject
DispatchJobRepository repository;

DispatchJob job = repository.create(request);  // ✅ Domain objects
```

**Bad:**
```java
// 🚩 Database types leaked into repository interface
public Document create(CreateDispatchJobRequest request) { ... }
public List<Bson> buildFilters(Map<String, String> filters) { ... }

// 🚩 Service layer knows about database
collection.find(eq("_id", id)).first();  // MongoDB in service layer
```

### ✅ DO: Use Domain Objects

**Good:**
```java
@ApplicationScoped
public class DispatchJobService {
    @Inject
    DispatchJobRepository repository;

    // ✅ No database imports
    // ✅ Works with DispatchJob, DispatchAttempt (domain objects)
}
```

**Bad:**
```java
@ApplicationScoped
public class DispatchJobService {
    @Inject
    MongoClient mongoClient;  // 🚩 Database client in service layer

    public void processJob(Long id) {
        collection.updateOne(...);  // 🚩 Database operations in service
    }
}
```

### ✅ DO: Keep Database Logic in Repository

**Good:**
```java
// Repository
public List<DispatchJob> findByMetadata(Map<String, String> filters) {
    // PostgreSQL: JOIN query
    // MongoDB: $elemMatch query
    // Service layer doesn't care HOW
}

// Service
List<DispatchJob> jobs = repository.findByMetadata(Map.of("tenant", "acme"));
```

**Bad:**
```java
// Service
List<DispatchJob> jobs = em.createQuery(
    "SELECT j FROM DispatchJob j JOIN j.metadata m WHERE m.key = :key",
    DispatchJob.class
).getResultList();  // 🚩 Database query in service layer
```

### ✅ DO: Document Aggregate Structure

```java
/**
 * DispatchJob is the aggregate root.
 *
 * Aggregate includes:
 * - Job metadata (searchable key-value pairs)
 * - Delivery attempts (full attempt history)
 * - Credentials reference (by ID, not embedded)
 *
 * PostgreSQL: Separate tables linked by dispatch_job_id
 * MongoDB: Single document with embedded arrays
 */
@Entity
@Table(name = "dispatch_job")
public class DispatchJob extends PanacheEntityBase { ... }
```

## Platform Recommendation

### For New LOB Applications

**Default Database:** MongoDB (when available)

**Why:**
- Aggregate-oriented design is natural
- No FK constraints enforces good microservice boundaries
- Single-operation writes for complex aggregates
- Schema flexibility per tenant
- Horizontal scaling story is clear

**Use PostgreSQL when:**
- Heavy analytical/reporting requirements (complex JOINs, aggregations)
- Geospatial features needed (PostGIS)
- Team has deep PostgreSQL expertise and no MongoDB experience
- Compliance requires specific SQL audit capabilities

### For Flow Catalyst Itself

**Current:** PostgreSQL with TSID + Flyway
**Future:** Migrate to MongoDB when:
- Multiple LOB apps are built on platform
- Scale exceeds 10M jobs/day
- External adoption increases

**Timeline:** Opportunistic, not urgent. Current implementation works well.

## References

- [Dispatch Jobs Documentation](dispatch-jobs.md) - Complete dispatch job system details
- [Architecture Documentation](architecture.md) - Overall Flow Catalyst architecture
- [TSID Creator Library](https://github.com/f4b6a3/tsid-creator) - TSID implementation
- [Quarkus Panache](https://quarkus.io/guides/hibernate-orm-panache) - ORM with virtual threads
- [Quarkus MongoDB](https://quarkus.io/guides/mongodb) - MongoDB integration guide

## Decision Log

### 2024: PostgreSQL with TSID + Flyway
- **Decision:** Use PostgreSQL for initial implementation
- **Rationale:** Simpler local development, team familiarity, existing tooling
- **Trade-offs:** More round trips for aggregates vs MongoDB, but acceptable at current scale

### 2024: No Foreign Keys
- **Decision:** Do not use FK constraints
- **Rationale:** Align with microservice philosophy, enable independent scaling
- **Trade-offs:** Application-level integrity vs database-level enforcement

### 2024: TSID over UUID
- **Decision:** Use TSID as BIGINT (8 bytes) instead of UUID (16 bytes)
- **Rationale:** Time-sorted, more efficient, better indexing
- **Trade-offs:** External clients may prefer UUID - solved with externalId field

### 2024: Concrete Repository (No Interface Yet)
- **Decision:** Implement concrete repository class, extract interface later if needed
- **Rationale:** YAGNI, interface emerges from real usage, trivial to extract
- **Trade-offs:** External adopters need to fork and extract - acceptable cost
