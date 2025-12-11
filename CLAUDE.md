# FlowCatalyst Project Context

## ID Handling - TSID as Crockford Base32 Strings

**IMPORTANT**: All entity IDs in this project use TSIDs (Time-Sorted IDs) stored and transmitted as Crockford Base32 strings.

### TSID Format
- **Library**: `tsid-creator` (com.github.f4b6a3.tsid)
- **String Format**: 13-character Crockford Base32 (e.g., `0HZXEQ5Y8JY5Z`)
- **Properties**:
  - Lexicographically sortable (newer IDs sort after older ones)
  - URL-safe and case-insensitive
  - Shorter than numeric strings (13 vs ~19 chars)
  - Safe from JavaScript number precision issues

### Rules:
1. **Entities**: Use `String id` for all `@BsonId` fields
2. **Repositories**: Extend `PanacheMongoRepositoryBase<Entity, String>`
3. **DTOs**: All ID fields must be `String` type
4. **Services/Commands**: All ID parameters must be `String` type
5. **Frontend**: IDs are always strings (no parsing needed)

### TsidGenerator Usage:
```java
import tech.flowcatalyst.platform.shared.TsidGenerator;

// Generate a new TSID string
String id = TsidGenerator.generate();  // e.g., "0HZXEQ5Y8JY5Z"

// Convert between formats (for migration/compatibility)
Long longId = TsidGenerator.toLong("0HZXEQ5Y8JY5Z");
String strId = TsidGenerator.toString(786259737685263979L);
```

### Entity Pattern:
```java
@MongoEntity(collection = "my_entities")
public class MyEntity extends PanacheMongoEntityBase {
    @BsonId
    public String id;  // TSID Crockford Base32

    public String relatedEntityId;  // Foreign key as String
}

// Repository
public class MyEntityRepository implements PanacheMongoRepositoryBase<MyEntity, String> {}
```

### Why Not Long?
JavaScript loses precision for integers > 2^53. A TSID like `786259737685263979` becomes `786259737685264000` when parsed as a JavaScript number, causing 404 errors.

## Multi-Tenant Architecture

### UserScope Enum
Users have explicit access scopes:
- `ANCHOR` - Platform admin users, access to all clients
- `PARTNER` - Partner users, access to multiple assigned clients
- `CLIENT` - Users bound to a single client

### Email Domain Configuration
- Anchor domains: Users get `ANCHOR` scope automatically
- Client-bound domains: Users get `CLIENT` scope, constrained to that client
- Unconfigured domains: Default to internal auth

## Authentication

### External Base URL
For OAuth/OIDC callbacks behind a proxy (e.g., Vite dev server), configure:
```properties
flowcatalyst.auth.external-base-url=http://localhost:4200
```

### Token Claims
Session tokens include a `clients` claim:
- `["*"]` for ANCHOR users (access all)
- `["123", "456"]` for specific client IDs

## Database Operations - CRITICAL RULES

### NEVER Drop Collections or Databases Without Permission
**IMPORTANT**: NEVER drop MongoDB collections or databases without explicit user permission. Dropping data is destructive and irreversible.

### Handling Data Type Mismatches
When encountering MongoDB decode errors (e.g., "expected 'DATE_TIME' BsonType but got 'STRING'"), the proper fix is to **migrate the data**, not drop it:

```javascript
// Example: Fix Instant fields stored as STRING instead of DATE_TIME
db.collection.find({ createdAt: { $type: "string" } }).forEach(function(doc) {
  db.collection.updateOne(
    { _id: doc._id },
    { $set: { createdAt: new Date(doc.createdAt) } }
  );
});
```

### Migration vs Dropping
- **Preferred**: Write a migration script to convert incorrect field types
- **Alternative**: Ask user if they want to drop the affected collection
- **Never**: Silently drop collections/databases as a "quick fix"
