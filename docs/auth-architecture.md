# Authentication & Authorization Architecture

## Overview

This document describes the authentication and authorization system to be built as a separate reusable module (`flowcatalyst-auth`) for FlowCatalyst and other applications.

The module will be a standard Quarkus library (not a full Quarkus extension) that provides MongoDB Panache entities, CDI services, JAX-RS resources, and security filters that applications can include as a dependency.

**Database:** MongoDB primary (FlowCatalyst uses MongoDB, so auth uses the same database). Relational database support available as a separate module if needed.

## Design Philosophy

### Simplicity Principles

1. **Roles are application-level, not tenant data** - Defined in code like enums, not dynamic tenant resources
2. **Only customers get tenants** - Partners and anchor users don't have a home tenant
3. **Anchor domains, not anchor tenants** - Configured email domains provide global access
4. **IDP roles are global** - Apply to all tenants a user can access, mapped via simple lookup
5. **Unknown IDP roles ignored** - Invalid role names logged as warnings, not errors
6. **Tenant-specific needs handled manually** - No complex per-tenant role mappings
7. **Auth does auth, business does business** - Authorization system only validates RBAC permissions; tenant isolation and business rules are enforced in application logic

### Core Tenets

- **Multi-tenant isolation**: Customer data segregated by tenant
- **Unified identity**: Users and services share the same authorization model
- **Flexible authentication**: Support internal credentials and OIDC (Keycloak)
- **Enforced SSO policy**: Authentication method determined by email domain (no user choice)
- **Service-to-service auth**: Support client credentials (OIDC) and internal tokens
- **Zero credential sprawl**: Minimize password/secret proliferation
- **Reusable module**: Can be included in any Quarkus application

## Identity Model

### Principals

All authenticated entities are "principals" - both human users and service accounts:

```
Principal (abstract concept)
├── User (authenticated via email/password or OIDC)
└── Service Account (authenticated via client credentials or internal token)
```

### Tenant Assignment

- **Customer users**: `principals.tenant_id` = their organization's tenant
- **Service accounts**: `principals.tenant_id` = the tenant that owns them
- **Partner users**: `principals.tenant_id` = NULL (no home tenant)
- **Anchor domain users**: `principals.tenant_id` = NULL (global access)

### Tenant Access Rules

| User Type | Home Tenant | Access Behavior |
|-----------|-------------|-----------------|
| Customer user | Customer A | Access only Customer A tenant |
| Anchor domain user (@company.com) | NULL | Access ALL tenants |
| Partner user | NULL | Access tenants via explicit grants |
| Service account | Customer A | Access only Customer A tenant |

## Database Schema (MongoDB)

FlowCatalyst uses MongoDB, so the auth module stores data in MongoDB collections within the same database.

### Core Collections

```javascript
// principals collection
// All authenticated entities (users + services) with embedded identity details
{
  _id: ObjectId,
  type: "USER",  // or "SERVICE"
  tenantId: ObjectId,  // nullable for partners/anchor users
  name: "John Doe",
  active: true,
  createdAt: ISODate("2024-01-01T00:00:00Z"),
  updatedAt: ISODate("2024-01-01T00:00:00Z"),

  // Embedded for USER type (null for SERVICE)
  userIdentity: {
    email: "john@example.com",
    emailDomain: "example.com",
    idpType: "OIDC",  // INTERNAL | OIDC
    externalIdpId: "keycloak-sub-123",  // nullable
    passwordHash: null,  // populated if idpType = INTERNAL
    lastLoginAt: ISODate("2024-01-15T12:00:00Z")
  },

  // Embedded for SERVICE type (null for USER)
  serviceAccount: {
    clientId: "service-scheduler-123",
    clientSecretHash: "$argon2id$...",
    lastUsedAt: ISODate("2024-01-15T14:30:00Z")
  }
}

// Indexes:
// - userIdentity.email (unique)
// - userIdentity.externalIdpId
// - serviceAccount.clientId (unique)
// - tenantId

// tenants collection
// Customer organizations
{
  _id: ObjectId,
  name: "Acme Corporation",
  slug: "acmecorp",
  active: true,
  createdAt: ISODate("2024-01-01T00:00:00Z"),
  updatedAt: ISODate("2024-01-01T00:00:00Z")
}

// Indexes:
// - slug (unique)

// anchor_domains collection
// Email domains with global access to all tenants
{
  _id: ObjectId,
  emailDomain: "mycompany.com",
  description: "Internal employees - platform admins",
  createdAt: ISODate("2024-01-01T00:00:00Z")
}

// Indexes:
// - emailDomain (unique)

// tenant_access_grants collection
// Explicit multi-tenant access (for partners primarily)
{
  _id: ObjectId,
  principalId: ObjectId,
  tenantId: ObjectId,
  grantedAt: ISODate("2024-01-10T00:00:00Z"),
  grantedBy: ObjectId,  // nullable
  expiresAt: null,  // nullable
  notes: "Partner logistics provider"
}

// Indexes:
// - principalId
// - tenantId
// - { principalId: 1, tenantId: 1 } (compound unique)

// tenant_auth_config collection
// Authentication configuration per email domain
{
  _id: ObjectId,
  emailDomain: "acmecorp.com",
  authProvider: "OIDC",  // INTERNAL | OIDC
  oidcIssuerUrl: "https://keycloak.acmecorp.com/realms/employees",
  oidcClientId: "flowcatalyst",
  oidcClientSecretEncrypted: "encrypted:AES256:...",
  createdAt: ISODate("2024-01-01T00:00:00Z"),
  updatedAt: ISODate("2024-01-01T00:00:00Z")
}

// Indexes:
// - emailDomain (unique)
```

### Authorization Collections

```javascript
// roles collection
// Application-defined roles with embedded permissions
{
  _id: ObjectId,
  name: "platform-admin",
  description: "Full system access",
  isSystem: true,  // System roles can't be deleted
  createdAt: ISODate("2024-01-01T00:00:00Z"),

  // Permissions embedded in role for fast loading
  permissions: [
    {
      resource: "tenant",
      action: "create",
      description: "Create new tenant organizations"
    },
    {
      resource: "tenant",
      action: "read",
      description: "List accessible tenants"
    },
    {
      resource: "dispatch-job",
      action: "create",
      description: "Create dispatch jobs"
    }
    // ... more permissions
  ]
}

// Indexes:
// - name (unique)

// principal_roles collection
// Role assignments to principals (NO SCOPING - roles are global)
{
  _id: ObjectId,
  principalId: ObjectId,
  roleId: ObjectId,
  source: "IDP",  // MANUAL | IDP | SYSTEM (how was this assigned?)
  grantedAt: ISODate("2024-01-01T00:00:00Z"),
  grantedBy: ObjectId  // nullable
}

// Indexes:
// - principalId
// - { principalId: 1, roleId: 1 } (compound unique)

// idp_role_mappings collection
// SECURITY: Explicit authorization of IDP roles
// Only IDP roles in this table are accepted during login
// Unknown/unmapped roles are logged as warnings and IGNORED
{
  _id: ObjectId,
  idpRoleName: "keycloak-platform-admin",  // Exact role name from IDP token
  internalRoleId: ObjectId,                // Maps to internal role (e.g., "platform-admin")
  createdAt: ISODate("2024-01-01T00:00:00Z"),
  updatedAt: ISODate("2024-01-01T00:00:00Z")
}

// Indexes:
// - idpRoleName (unique)
```

### Key MongoDB Design Notes

1. **Embedded user/service identity**: `userIdentity` and `serviceAccount` embedded in `principals` for single-document lookup
2. **Embedded permissions in roles**: Roles contain their permissions array for fast loading (no join needed)
3. **ObjectId primary keys**: MongoDB-native ObjectId, but can use UUID string if interoperability needed
4. **principals.tenantId is nullable**: Partners and anchor users have no home tenant
5. **No role scoping**: Roles apply globally to all accessible tenants
6. **anchor_domains**: Email domains with god-mode access
7. **tenant_access_grants**: Explicit multi-tenant access for partners
8. **Simple RBAC**: Authorization system only checks if principal has permission; tenant isolation is handled in business logic
9. **IDP role authorization**: Only IDP roles explicitly listed in `idp_role_mappings` are accepted; unknown roles are logged and ignored (critical security control)
10. **Indexes required**: Create indexes on lookup fields (email, clientId, emailDomain, principalId, tenantId)
11. **Transactions**: Multi-document operations should use transactions (requires MongoDB replica set)

## Roles and Permissions

### Role Definition Approach

Roles are **application-level constants**, not tenant-specific data. They are defined in code and seeded via startup bootstrap.

**Example Bootstrap:**

```java
@ApplicationScoped
public class RoleBootstrap {

    @Inject
    RoleRepository roleRepo;

    @ReactiveTransactional
    public void seedRoles(@Observes StartupEvent event) {
        // Only seed if collection is empty
        if (roleRepo.count() > 0) {
            return;
        }

        // Platform admin - full access
        Role platformAdmin = new Role();
        platformAdmin.name = "platform-admin";
        platformAdmin.description = "Full system access";
        platformAdmin.isSystem = true;
        platformAdmin.permissions = List.of(
            new Permission("tenant", "create", "Create new tenant organizations"),
            new Permission("tenant", "read", "List accessible tenants"),
            new Permission("tenant", "update", "Update tenant settings"),
            new Permission("tenant", "delete", "Delete a tenant"),
            new Permission("dispatch-job", "create", "Create dispatch jobs"),
            new Permission("dispatch-job", "read", "View dispatch jobs"),
            new Permission("dispatch-job", "update", "Modify dispatch jobs"),
            new Permission("dispatch-job", "delete", "Delete dispatch jobs"),
            new Permission("dispatch-job", "execute", "Execute dispatch jobs"),
            new Permission("user", "create", "Create users"),
            new Permission("user", "read", "View users"),
            new Permission("user", "update", "Modify users"),
            new Permission("user", "delete", "Delete users")
        );
        roleRepo.persist(platformAdmin);

        // Tenant admin - manage jobs and users in tenant
        Role tenantAdmin = new Role();
        tenantAdmin.name = "tenant-admin";
        tenantAdmin.description = "Tenant administrator";
        tenantAdmin.isSystem = true;
        tenantAdmin.permissions = List.of(
            new Permission("dispatch-job", "create", "Create dispatch jobs"),
            new Permission("dispatch-job", "read", "View dispatch jobs"),
            new Permission("dispatch-job", "update", "Modify dispatch jobs"),
            new Permission("dispatch-job", "delete", "Delete dispatch jobs"),
            new Permission("dispatch-job", "execute", "Execute dispatch jobs"),
            new Permission("user", "read", "View users"),
            new Permission("user", "update", "Modify users")
        );
        roleRepo.persist(tenantAdmin);

        // Operator - execute jobs
        Role operator = new Role();
        operator.name = "operator";
        operator.description = "Job operator";
        operator.isSystem = true;
        operator.permissions = List.of(
            new Permission("dispatch-job", "read", "View dispatch jobs"),
            new Permission("dispatch-job", "execute", "Execute dispatch jobs")
        );
        roleRepo.persist(operator);

        // Viewer - read-only
        Role viewer = new Role();
        viewer.name = "viewer";
        viewer.description = "Read-only access";
        viewer.isSystem = true;
        viewer.permissions = List.of(
            new Permission("dispatch-job", "read", "View dispatch jobs")
        );
        roleRepo.persist(viewer);

        Log.info("Seeded default roles: platform-admin, tenant-admin, operator, viewer");
    }
}

// Entity classes
@MongoEntity(collection = "roles")
public class Role extends PanacheMongoEntityBase {
    @BsonId
    public ObjectId id;
    public String name;
    public String description;
    public boolean isSystem;
    public List<Permission> permissions;  // Embedded
    public Instant createdAt = Instant.now();
}

// Not a @MongoEntity - embedded in Role
public class Permission {
    public String resource;
    public String action;
    public String description;

    public Permission() {}

    public Permission(String resource, String action, String description) {
        this.resource = resource;
        this.action = action;
        this.description = description;
    }
}
```

### Example Entity and Repository Implementation

**Principal Entity (with embedded identity):**

```java
@MongoEntity(collection = "principals")
public class Principal extends PanacheMongoEntityBase {
    @BsonId
    public ObjectId id;

    public PrincipalType type;  // USER or SERVICE enum

    public ObjectId tenantId;  // nullable

    public String name;

    public boolean active = true;

    // Embedded for USER type
    public UserIdentity userIdentity;

    // Embedded for SERVICE type
    public ServiceAccount serviceAccount;

    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
}

// Embedded document (no @MongoEntity annotation)
public class UserIdentity {
    public String email;
    public String emailDomain;
    public IdpType idpType;  // INTERNAL or OIDC enum
    public String externalIdpId;  // nullable
    public String passwordHash;  // nullable
    public Instant lastLoginAt;

    // Constructor, getters/setters...
}

// Embedded document (no @MongoEntity annotation)
public class ServiceAccount {
    public String clientId;
    public String clientSecretHash;
    public Instant lastUsedAt;

    // Constructor, getters/setters...
}
```

**Repository:**

```java
@ApplicationScoped
public class PrincipalRepository implements PanacheMongoRepositoryBase<Principal, ObjectId> {

    public Optional<Principal> findByEmail(String email) {
        return find("userIdentity.email", email).firstResultOptional();
    }

    public Optional<Principal> findByExternalIdpId(String externalIdpId) {
        return find("userIdentity.externalIdpId", externalIdpId).firstResultOptional();
    }

    public Optional<Principal> findByClientId(String clientId) {
        return find("serviceAccount.clientId", clientId).firstResultOptional();
    }

    public List<Principal> findByTenantId(ObjectId tenantId) {
        return find("tenantId", tenantId).list();
    }

    public List<Principal> findByType(PrincipalType type) {
        return find("type", type).list();
    }
}
```

**Tenant Entity:**

```java
@MongoEntity(collection = "tenants")
public class Tenant extends PanacheMongoEntityBase {
    @BsonId
    public ObjectId id;

    public String name;
    public String slug;
    public boolean active = true;

    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
}

@ApplicationScoped
public class TenantRepository implements PanacheMongoRepositoryBase<Tenant, ObjectId> {

    public Optional<Tenant> findBySlug(String slug) {
        return find("slug", slug).firstResultOptional();
    }

    public List<Tenant> findByIds(Set<ObjectId> ids) {
        return find("_id in ?1", ids).list();
    }
}
```

### Authorization Approach

The authorization system provides **simple RBAC** - checking only whether a principal has a specific permission.

**Tenant isolation and context validation is the responsibility of business logic**, not the authorization system. This allows each endpoint to decide:
- Whether tenant context is required, optional, or not applicable
- How to filter data based on accessible tenants
- What validation rules apply

A future enhancement will add an **ABAC (Attribute-Based Access Control) layer** that can be called within business logic for fine-grained access control.

## Authentication Flows

### Human User Login Flow

```
┌─────────────────────────────────────────────────────────────┐
│ 1. User enters email address                                │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. Backend looks up email_domain in tenant_auth_config      │
│    - Extract domain from email                              │
│    - Query: SELECT * FROM tenant_auth_config                │
│             WHERE email_domain = ?                          │
└─────────────────────────────────────────────────────────────┘
                            ↓
                ┌───────────┴───────────┐
                │                       │
        ┌───────▼───────┐      ┌───────▼──────┐
        │ INTERNAL Auth │      │  OIDC Auth   │
        └───────┬───────┘      └───────┬──────┘
                │                      │
                │                      │
┌───────────────▼──────┐    ┌──────────▼─────────────────────┐
│ Show password form   │    │ Redirect to Keycloak           │
│ Validate credentials │    │ (Authorization Code Flow)      │
│ Create session       │    └──────────┬─────────────────────┘
└───────────┬──────────┘               │
            │                          │
            │              ┌───────────▼───────────────────────┐
            │              │ Keycloak callback with code       │
            │              │ Exchange code for tokens          │
            │              │ Validate ID token signature       │
            │              └───────────┬───────────────────────┘
            │                          │
            │              ┌───────────▼───────────────────────┐
            │              │ Lookup user by external_idp_id    │
            │              │ If not found: create new user     │
            │              │ Update last_login_at              │
            │              └───────────┬───────────────────────┘
            │                          │
            │              ┌───────────▼───────────────────────┐
            │              │ Extract roles from token claims   │
            │              │ Map IDP roles → Internal roles    │
            │              │ Sync to principal_roles           │
            │              │ (source = IDP)                    │
            │              └───────────┬───────────────────────┘
            │                          │
            └──────────────┬───────────┘
                           │
            ┌──────────────▼──────────────────────────────────┐
            │ Create session with:                            │
            │ - principal_id                                  │
            │ - active_tenant_id (selected by user if multi)  │
            │ - accessible_tenant_ids                         │
            │ - Set session cookie (HttpOnly, Secure)         │
            └─────────────────────────────────────────────────┘
```

### OIDC Role Synchronization

**SECURITY: IDP roles must be explicitly authorized before being accepted.**

When a user logs in via OIDC, we authorize and synchronize their roles:

```java
@Transactional
public void syncIdpRoles(ObjectId principalId, List<String> idpRoleNames) {
    Set<ObjectId> authorizedRoleIds = new HashSet<>();

    // SECURITY: Only accept IDP roles that are explicitly authorized in idp_role_mappings
    for (String idpRoleName : idpRoleNames) {
        IdpRoleMapping mapping = idpRoleMappingRepo.findByIdpRoleName(idpRoleName);

        if (mapping != null) {
            // This IDP role is authorized - map to internal role
            authorizedRoleIds.add(mapping.getInternalRoleId());
            log.debug("Accepted IDP role '{}' for principal {}", idpRoleName, principalId);
        } else {
            // SECURITY: Reject unauthorized IDP role
            // This prevents malicious/misconfigured IDPs from granting unauthorized access
            log.warn("REJECTED unauthorized IDP role '{}' for principal {}. " +
                     "Not in idp_role_mappings table.", idpRoleName, principalId);
        }
    }

    // Get current IDP-sourced roles
    List<PrincipalRole> currentIdpRoles = principalRoleRepo
        .findByPrincipalAndSource(principalId, "IDP");

    Set<ObjectId> currentRoleIds = currentIdpRoles.stream()
        .map(pr -> pr.getRoleId())
        .collect(Collectors.toSet());

    // Add new authorized roles
    for (ObjectId roleId : authorizedRoleIds) {
        if (!currentRoleIds.contains(roleId)) {
            PrincipalRole pr = new PrincipalRole();
            pr.principalId = principalId;
            pr.roleId = roleId;
            pr.source = "IDP";
            pr.grantedAt = Instant.now();
            principalRoleRepo.persist(pr);
        }
    }

    // Remove revoked roles (no longer in IDP token)
    for (PrincipalRole pr : currentIdpRoles) {
        if (!authorizedRoleIds.contains(pr.getRoleId())) {
            principalRoleRepo.delete(pr);
        }
    }
}
```

**Key Security Points:**
- **Explicit authorization required**: Only IDP roles listed in `idp_role_mappings` are accepted
- **Defense against misconfiguration**: Unknown/unauthorized IDP roles are rejected and logged
- **No trust by default**: Partner/customer IDPs cannot inject arbitrary roles
- **Admin control**: Platform admins control which IDP roles map to internal permissions
- **Audit trail**: All rejected roles are logged for security monitoring
- **Source tracking**: IDP roles marked with `source = IDP` to distinguish from manual assignments
- **Dynamic sync**: Roles synchronized on each login (supports role additions/removals in IDP)

### Service-to-Service Authentication

```
┌─────────────────────────────────────────────────────────────┐
│ Option A: Client Credentials (Keycloak)                     │
│                                                              │
│ 1. Service → Keycloak token endpoint                        │
│    POST /realms/{realm}/protocol/openid-connect/token       │
│    grant_type=client_credentials                            │
│    client_id=...&client_secret=...                          │
│                                                              │
│ 2. Keycloak → Service                                       │
│    { "access_token": "<JWT>", ... }                         │
│                                                              │
│ 3. Service → FlowCatalyst API                               │
│    Authorization: Bearer <JWT>                              │
│                                                              │
│ 4. API validates JWT:                                       │
│    - Verify signature with Keycloak JWKS                    │
│    - Check expiry, issuer, audience                         │
│    - Extract client_id from 'azp' or 'client_id' claim      │
│                                                              │
│ 5. Map to internal service account:                         │
│    - Lookup service_accounts by client_id                   │
│    - Load principal and roles                               │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Option B: Internal Tokens (Our Own JWTs)                    │
│                                                              │
│ 1. Service → FlowCatalyst API                               │
│    Authorization: Bearer <internal-JWT>                     │
│                                                              │
│ 2. API validates JWT:                                       │
│    - Verify signature with our private key                  │
│    - Check expiry, issuer (flowcatalyst)                    │
│    - Extract principal_id from claims                       │
│                                                              │
│ 3. Load principal and roles from database                   │
└─────────────────────────────────────────────────────────────┘
```

### Logout

```
POST /auth/logout
  ↓
Clear session cookie (set Max-Age=0)
  ↓
Return 200 OK

Note:
- Keycloak session may still be active
- This is acceptable - session is local to our app
- User will need to re-authenticate on next login
- No redirect to Keycloak logout endpoint required
```

## Authorization Model

### Tenant Access Resolution

```java
@ApplicationScoped
public class TenantAccessService {

    public Set<UUID> getAccessibleTenants(Principal principal, UserIdentity userIdentity) {
        Set<UUID> tenantIds = new HashSet<>();

        // 1. Check if anchor domain user (global access)
        if (userIdentity != null) {
            String domain = userIdentity.getEmailDomain();
            if (anchorDomainRepo.existsByDomain(domain)) {
                // Return ALL tenant IDs
                return tenantRepo.findAll().stream()
                    .map(Tenant::getId)
                    .collect(Collectors.toSet());
            }
        }

        // 2. Add home tenant if exists
        if (principal.getTenantId() != null) {
            tenantIds.add(principal.getTenantId());
        }

        // 3. Add explicitly granted tenants
        List<TenantAccessGrant> grants = tenantAccessGrantRepo
            .findByPrincipalId(principal.getId());

        grants.stream()
            .filter(g -> g.getExpiresAt() == null || g.getExpiresAt().isAfter(Instant.now()))
            .map(TenantAccessGrant::getTenantId)
            .forEach(tenantIds::add);

        return tenantIds;
    }
}
```

### Permission Checks

The authorization service provides a simple permission check:

```java
@ApplicationScoped
public class AuthorizationService {

    @Inject
    SecurityContext securityContext;

    @Inject
    PermissionRepository permissionRepo;

    @Inject
    PrincipalRoleRepository principalRoleRepo;

    /**
     * Require that the current principal has a specific permission.
     *
     * This only validates RBAC permissions. Tenant isolation and
     * business rules must be enforced in application logic.
     *
     * @throws ForbiddenException if permission denied
     */
    public void requirePermission(String resource, String action) {
        Principal principal = securityContext.getPrincipal();

        // 1. Find the permission
        Permission permission = permissionRepo
            .findByResourceAndAction(resource, action)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown permission: " + resource + ":" + action));

        // 2. Check if user's roles grant this permission
        Set<Role> userRoles = getRoles(principal.getId());
        boolean hasPermission = userRoles.stream()
            .flatMap(role -> role.getPermissions().stream())
            .anyMatch(p -> p.getId().equals(permission.getId()));

        if (!hasPermission) {
            throw new ForbiddenException(
                "Missing permission: " + resource + ":" + action);
        }
    }

    private Set<Role> getRoles(UUID principalId) {
        return principalRoleRepo.findByPrincipalId(principalId).stream()
            .map(PrincipalRole::getRole)
            .collect(Collectors.toSet());
    }
}
```

### Endpoint Usage Examples

These examples show how business logic handles tenant isolation after permission checks:

```java
@Path("/api/tenants")
@Authenticated
public class TenantAdminResource {

    @Inject
    AuthorizationService authzService;

    @Inject
    SecurityContext securityContext;

    @Inject
    TenantService tenantService;

    /**
     * Create a new tenant (global operation)
     */
    @POST
    public Response createTenant(TenantRequest request) {
        // Check permission
        authzService.requirePermission("tenant", "create");

        // No tenant context needed - creating a new tenant
        Tenant tenant = tenantService.createTenant(request);
        return Response.status(201).entity(tenant).build();
    }

    /**
     * Update a tenant (business logic validates tenant access)
     */
    @PUT
    @Path("/{tenantId}")
    public Response updateTenant(
        @PathParam("tenantId") UUID tenantId,
        TenantUpdateRequest request
    ) {
        // Check permission
        authzService.requirePermission("tenant", "update");

        // Business logic: Validate tenant access
        if (!securityContext.getAccessibleTenantIds().contains(tenantId)) {
            throw new ForbiddenException("No access to tenant: " + tenantId);
        }

        Tenant tenant = tenantService.updateTenant(tenantId, request);
        return Response.ok(tenant).build();
    }

    /**
     * List all accessible tenants
     */
    @GET
    public Response listTenants() {
        // Check permission
        authzService.requirePermission("tenant", "read");

        // Business logic: Filter to accessible tenants
        Set<UUID> accessibleTenantIds = securityContext.getAccessibleTenantIds();
        List<Tenant> tenants = tenantService.findByIds(accessibleTenantIds);
        return Response.ok(tenants).build();
    }
}

@Path("/api/dispatch-jobs")
@Authenticated
public class DispatchJobResource {

    @Inject
    AuthorizationService authzService;

    @Inject
    SecurityContext securityContext;

    @Inject
    JobService jobService;

    /**
     * Create a dispatch job in a specific tenant
     */
    @POST
    public Response createJob(
        @QueryParam("tenantId") UUID tenantId,
        JobRequest request
    ) {
        // Check permission
        authzService.requirePermission("dispatch-job", "create");

        // Business logic: Require tenant and validate access
        if (tenantId == null) {
            throw new BadRequestException("tenantId is required");
        }

        if (!securityContext.getAccessibleTenantIds().contains(tenantId)) {
            throw new ForbiddenException("No access to tenant: " + tenantId);
        }

        Job job = jobService.createJob(tenantId, request);
        return Response.status(201).entity(job).build();
    }

    /**
     * List dispatch jobs - optionally filtered to a tenant
     */
    @GET
    public Response listJobs(@QueryParam("tenantId") UUID tenantId) {
        // Check permission
        authzService.requirePermission("dispatch-job", "read");

        // Business logic: Determine tenant filter
        Set<UUID> tenantFilter;
        if (tenantId != null) {
            // Specific tenant requested - validate access
            if (!securityContext.getAccessibleTenantIds().contains(tenantId)) {
                throw new ForbiddenException("No access to tenant: " + tenantId);
            }
            tenantFilter = Set.of(tenantId);
        } else {
            // No tenant specified - filter to all accessible tenants
            tenantFilter = securityContext.getAccessibleTenantIds();
        }

        List<Job> jobs = jobService.findJobs(tenantFilter);
        return Response.ok(jobs).build();
    }

    /**
     * List jobs in error state - across all accessible tenants
     */
    @GET
    @Path("/errors")
    public Response listJobsInError(@QueryParam("tenantId") UUID tenantId) {
        // Check permission
        authzService.requirePermission("dispatch-job", "read");

        // Business logic: Optional tenant filter
        Set<UUID> tenantFilter;
        if (tenantId != null) {
            // Validate access to specific tenant
            if (!securityContext.getAccessibleTenantIds().contains(tenantId)) {
                throw new ForbiddenException("No access to tenant: " + tenantId);
            }
            tenantFilter = Set.of(tenantId);
        } else {
            // Show errors across all accessible tenants
            tenantFilter = securityContext.getAccessibleTenantIds();
        }

        List<Job> errorJobs = jobService.findJobsInError(tenantFilter);
        return Response.ok(errorJobs).build();
    }
}
```

### Alternative: Annotation-Based Authorization

For simpler cases, use Quarkus `@RolesAllowed`:

```java
@Path("/api/dispatch-jobs")
@Authenticated
public class DispatchJobResource {

    @Inject
    SecurityContext securityContext;

    @GET
    @RolesAllowed({"operator", "tenant-admin", "platform-admin"})
    public Response listJobs(@QueryParam("tenantId") UUID tenantId) {
        // Role check handled by @RolesAllowed annotation
        // Business logic still responsible for tenant validation
        if (tenantId != null && !securityContext.getAccessibleTenantIds().contains(tenantId)) {
            throw new ForbiddenException("No access to tenant");
        }
        // ...
    }
}
```

## Security Context

### Thread-Local Context Bean

```java
@ApplicationScoped
public class SecurityContext {

    private static final ThreadLocal<Principal> PRINCIPAL = new ThreadLocal<>();
    private static final ThreadLocal<UUID> ACTIVE_TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<Set<UUID>> ACCESSIBLE_TENANTS = new ThreadLocal<>();

    public Principal getPrincipal() {
        Principal p = PRINCIPAL.get();
        if (p == null) {
            throw new UnauthorizedException("No authenticated principal");
        }
        return p;
    }

    public void setPrincipal(Principal principal) {
        PRINCIPAL.set(principal);
    }

    public UUID getActiveTenantId() {
        return ACTIVE_TENANT_ID.get();
    }

    public void setActiveTenantId(UUID tenantId) {
        // Validate tenant is accessible
        Set<UUID> accessible = ACCESSIBLE_TENANTS.get();
        if (accessible != null && !accessible.contains(tenantId)) {
            throw new ForbiddenException("No access to tenant: " + tenantId);
        }
        ACTIVE_TENANT_ID.set(tenantId);
    }

    public Set<UUID> getAccessibleTenantIds() {
        return ACCESSIBLE_TENANTS.get();
    }

    public void setAccessibleTenantIds(Set<UUID> tenantIds) {
        ACCESSIBLE_TENANTS.set(tenantIds);
    }

    public void clear() {
        PRINCIPAL.remove();
        ACTIVE_TENANT_ID.remove();
        ACCESSIBLE_TENANTS.remove();
    }
}
```

### Security Filter

```java
@Provider
@Priority(Priorities.AUTHENTICATION + 1)
public class SecurityContextFilter implements ContainerRequestFilter {

    @Inject
    SecurityIdentity identity;

    @Inject
    SecurityContext securityContext;

    @Inject
    PrincipalRepository principalRepo;

    @Inject
    TenantAccessService tenantAccessService;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (identity.isAnonymous()) {
            return;
        }

        // Load principal from identity
        String principalId = identity.getPrincipal().getName();
        Principal principal = principalRepo.findById(UUID.fromString(principalId));

        // Set in thread-local context
        securityContext.setPrincipal(principal);

        // Calculate accessible tenants
        UserIdentity userIdentity = null;
        if (principal.getType() == PrincipalType.USER) {
            userIdentity = userIdentityRepo.findByPrincipalId(principal.getId());
        }
        Set<UUID> accessibleTenants = tenantAccessService
            .getAccessibleTenants(principal, userIdentity);
        securityContext.setAccessibleTenantIds(accessibleTenants);

        // Set default active tenant (home tenant or first accessible)
        if (principal.getTenantId() != null) {
            securityContext.setActiveTenantId(principal.getTenantId());
        } else if (!accessibleTenants.isEmpty()) {
            // For multi-tenant users, tenant should be specified per-request
            // Don't set a default
        }
    }
}

@Provider
@Priority(Priorities.AUTHENTICATION + 100)
public class SecurityContextCleanupFilter implements ContainerResponseFilter {

    @Inject
    SecurityContext securityContext;

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        securityContext.clear();
    }
}
```

## Configuration

### Application Properties

```properties
# MongoDB Connection (shared with FlowCatalyst)
quarkus.mongodb.connection-string=mongodb://localhost:27017
quarkus.mongodb.database=flowcatalyst

# OIDC Integration (optional - leave blank to disable)
quarkus.oidc.enabled=false
# quarkus.oidc.auth-server-url=https://keycloak.example.com/realms/myrealm
# quarkus.oidc.client-id=flowcatalyst
# quarkus.oidc.credentials.secret=secret

# Internal Authentication
flowcatalyst.auth.internal.enabled=true
flowcatalyst.auth.internal.password-min-length=12
flowcatalyst.auth.internal.password-require-uppercase=true
flowcatalyst.auth.internal.password-require-numbers=true
flowcatalyst.auth.internal.password-require-special=true

# Session Configuration
flowcatalyst.auth.session.timeout=30m
flowcatalyst.auth.session.cookie-name=FLOWCATALYST_SESSION
flowcatalyst.auth.session.secure=true
flowcatalyst.auth.session.http-only=true
flowcatalyst.auth.session.same-site=strict

# OIDC Role Sync
flowcatalyst.auth.oidc.role-claim-path=realm_access.roles
flowcatalyst.auth.oidc.sync-roles-on-login=true

# Service Tokens (for internal JWT issuance)
flowcatalyst.auth.service.token-issuer=flowcatalyst
flowcatalyst.auth.service.token-expiry=365d
flowcatalyst.auth.service.signing-key-path=/etc/flowcatalyst/jwt-private-key.pem
```

### Runtime Configuration

**Anchor Domains** (via admin API or MongoDB):

```javascript
// Via MongoDB shell or admin API
db.anchor_domains.insertOne({
  emailDomain: "mycompany.com",
  description: "Internal employees - global access",
  createdAt: new Date()
});
```

**Tenant Auth Config** (via admin API or MongoDB):

```javascript
// Customer A uses internal auth
db.tenant_auth_config.insertOne({
  emailDomain: "customera.com",
  authProvider: "INTERNAL",
  createdAt: new Date(),
  updatedAt: new Date()
});

// Customer B uses OIDC
db.tenant_auth_config.insertOne({
  emailDomain: "customerb.com",
  authProvider: "OIDC",
  oidcIssuerUrl: "https://keycloak.customerb.com/realms/employees",
  oidcClientId: "flowcatalyst",
  oidcClientSecretEncrypted: "encrypted:AES256:...",
  createdAt: new Date(),
  updatedAt: new Date()
});
```

**IDP Role Mappings**:

```javascript
// Find platform-admin role
const platformAdminRole = db.roles.findOne({ name: "platform-admin" });
const tenantAdminRole = db.roles.findOne({ name: "tenant-admin" });
const operatorRole = db.roles.findOne({ name: "operator" });
const viewerRole = db.roles.findOne({ name: "viewer" });

// Create IDP role mappings
db.idp_role_mappings.insertMany([
  {
    idpRoleName: "keycloak-platform-admin",
    internalRoleId: platformAdminRole._id,
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    idpRoleName: "keycloak-admin",
    internalRoleId: tenantAdminRole._id,
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    idpRoleName: "keycloak-operator",
    internalRoleId: operatorRole._id,
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    idpRoleName: "keycloak-viewer",
    internalRoleId: viewerRole._id,
    createdAt: new Date(),
    updatedAt: new Date()
  }
]);
```

### Index Creation

Indexes should be created programmatically on application startup:

```java
@ApplicationScoped
public class IndexSetup {

    @Inject
    MongoClient mongoClient;

    void onStart(@Observes StartupEvent ev) {
        MongoDatabase db = mongoClient.getDatabase("flowcatalyst");

        // Principals collection indexes
        MongoCollection<Document> principals = db.getCollection("principals");
        principals.createIndex(Indexes.ascending("userIdentity.email"));
        principals.createIndex(Indexes.ascending("userIdentity.externalIdpId"));
        principals.createIndex(Indexes.ascending("serviceAccount.clientId"));
        principals.createIndex(Indexes.ascending("tenantId"));

        // Tenants collection indexes
        MongoCollection<Document> tenants = db.getCollection("tenants");
        tenants.createIndex(Indexes.ascending("slug"));

        // Anchor domains collection indexes
        MongoCollection<Document> anchorDomains = db.getCollection("anchor_domains");
        anchorDomains.createIndex(Indexes.ascending("emailDomain"));

        // Tenant access grants collection indexes
        MongoCollection<Document> grants = db.getCollection("tenant_access_grants");
        grants.createIndex(Indexes.ascending("principalId"));
        grants.createIndex(Indexes.ascending("tenantId"));
        grants.createIndex(Indexes.compoundIndex(
            Indexes.ascending("principalId"),
            Indexes.ascending("tenantId")
        ));

        // Tenant auth config collection indexes
        MongoCollection<Document> authConfig = db.getCollection("tenant_auth_config");
        authConfig.createIndex(Indexes.ascending("emailDomain"));

        // Roles collection indexes
        MongoCollection<Document> roles = db.getCollection("roles");
        roles.createIndex(Indexes.ascending("name"));

        // Principal roles collection indexes
        MongoCollection<Document> principalRoles = db.getCollection("principal_roles");
        principalRoles.createIndex(Indexes.ascending("principalId"));
        principalRoles.createIndex(Indexes.compoundIndex(
            Indexes.ascending("principalId"),
            Indexes.ascending("roleId")
        ));

        // IDP role mappings collection indexes
        MongoCollection<Document> idpMappings = db.getCollection("idp_role_mappings");
        idpMappings.createIndex(Indexes.ascending("idpRoleName"));

        Log.info("MongoDB indexes created for auth collections");
    }
}
```

## Module Structure

### Repository Layout

```
flowcatalyst-auth/
├── src/main/java/tech/flowcatalyst/auth/
│   ├── entity/
│   │   ├── Principal.java              # @MongoEntity, embedded UserIdentity & ServiceAccount
│   │   ├── UserIdentity.java           # Embedded in Principal
│   │   ├── ServiceAccount.java         # Embedded in Principal
│   │   ├── Tenant.java                 # @MongoEntity
│   │   ├── AnchorDomain.java           # @MongoEntity
│   │   ├── TenantAccessGrant.java      # @MongoEntity
│   │   ├── TenantAuthConfig.java       # @MongoEntity
│   │   ├── Role.java                   # @MongoEntity, embedded Permission list
│   │   ├── Permission.java             # Embedded in Role
│   │   ├── PrincipalRole.java          # @MongoEntity
│   │   └── IdpRoleMapping.java         # @MongoEntity
│   │
│   ├── repository/
│   │   ├── PrincipalRepository.java           # Panache MongoDB repository
│   │   ├── TenantRepository.java              # Panache MongoDB repository
│   │   ├── AnchorDomainRepository.java        # Panache MongoDB repository
│   │   ├── TenantAccessGrantRepository.java   # Panache MongoDB repository
│   │   ├── TenantAuthConfigRepository.java    # Panache MongoDB repository
│   │   ├── RoleRepository.java                # Panache MongoDB repository
│   │   ├── PrincipalRoleRepository.java       # Panache MongoDB repository
│   │   └── IdpRoleMappingRepository.java      # Panache MongoDB repository
│   │
│   ├── service/
│   │   ├── AuthenticationService.java      # Login, logout, token validation
│   │   ├── AuthorizationService.java       # Permission checks
│   │   ├── TenantAccessService.java        # Resolve accessible tenants
│   │   ├── UserService.java                # User CRUD
│   │   ├── TenantService.java              # Tenant management
│   │   ├── RoleService.java                # Role/permission management
│   │   ├── ServiceAccountService.java      # Service account management
│   │   ├── OidcSyncService.java            # OIDC user and role sync
│   │   ├── TokenService.java               # Internal JWT issuance/validation
│   │   └── RoleBootstrap.java              # Seed roles/permissions on startup
│   │
│   ├── security/
│   │   ├── SecurityContext.java            # Thread-local context
│   │   ├── SecurityContextFilter.java      # Request filter
│   │   ├── SecurityContextCleanupFilter.java # Response filter
│   │   ├── InternalIdentityProvider.java   # Internal auth
│   │   ├── OidcIdentityAugmentor.java      # OIDC user sync
│   │   └── ServiceTokenValidator.java      # Validate internal/client tokens
│   │
│   ├── resource/
│   │   ├── AuthResource.java               # POST /auth/login, /auth/logout, /auth/switch-tenant
│   │   ├── UserAdminResource.java          # /admin/users
│   │   ├── TenantAdminResource.java        # /admin/tenants
│   │   ├── RoleAdminResource.java          # /admin/roles
│   │   ├── ServiceAccountAdminResource.java # /admin/service-accounts
│   │   ├── AnchorDomainAdminResource.java  # /admin/anchor-domains
│   │   └── IdpRoleMappingAdminResource.java # /admin/idp-role-mappings
│   │
│   ├── config/
│   │   └── AuthConfig.java                 # @ConfigMapping for properties
│   │
│   ├── bootstrap/
│   │   ├── RoleBootstrap.java              # Seed default roles on startup
│   │   └── IndexSetup.java                 # Create MongoDB indexes on startup
│   │
│   └── exception/
│       ├── UnauthorizedException.java
│       ├── ForbiddenException.java
│       └── AuthenticationException.java
│
├── src/main/resources/
│   └── application.properties              # MongoDB connection config
│
├── src/test/java/tech/flowcatalyst/auth/
│   ├── AuthenticationFlowTest.java
│   ├── AuthorizationTest.java
│   ├── TenantAccessTest.java
│   └── OidcSyncTest.java
│
├── build.gradle.kts
└── README.md
```

### Package Organization Principles

1. **entity/** - MongoDB Panache entities (@MongoEntity) and embedded documents, minimal logic
2. **repository/** - MongoDB Panache repositories (PanacheMongoRepositoryBase), query methods
3. **service/** - Business logic, transactional methods (@ReactiveTransactional for MongoDB)
4. **security/** - Quarkus security integration (IdentityProvider, filters, etc.)
5. **resource/** - JAX-RS REST endpoints
6. **config/** - Configuration mappings (@ConfigMapping)
7. **bootstrap/** - Startup initialization (seed roles, create indexes)
8. **exception/** - Custom exception types

## Integration Guide

### For Applications Using This Module

**Step 1: Add Dependency**

```gradle
// build.gradle.kts
dependencies {
    implementation("tech.flowcatalyst:flowcatalyst-auth:1.0.0")
}
```

**Step 2: Configure MongoDB**

```properties
# application.properties
# Uses same MongoDB connection as FlowCatalyst
quarkus.mongodb.connection-string=mongodb://localhost:27017
quarkus.mongodb.database=flowcatalyst
```

**Step 3: Configure Auth**

```properties
# OIDC (optional)
quarkus.oidc.enabled=false

# Internal auth
flowcatalyst.auth.internal.enabled=true
flowcatalyst.auth.session.timeout=30m
```

**Step 4: Bootstrap Initial Data**

On first deployment, create:
1. An anchor domain for your company email
2. A tenant auth config for that domain
3. An initial admin user

The auth module will automatically create indexes and seed default roles on startup.

**Bootstrap via MongoDB shell or admin API:**

```javascript
// 1. Add anchor domain
db.anchor_domains.insertOne({
  emailDomain: "mycompany.com",
  description: "Internal employees",
  createdAt: new Date()
});

// 2. Configure auth for that domain
db.tenant_auth_config.insertOne({
  emailDomain: "mycompany.com",
  authProvider: "INTERNAL",
  createdAt: new Date(),
  updatedAt: new Date()
});

// 3. Create initial admin user
const adminPrincipalId = new ObjectId();
db.principals.insertOne({
  _id: adminPrincipalId,
  type: "USER",
  tenantId: null,
  name: "Admin User",
  active: true,
  createdAt: new Date(),
  updatedAt: new Date(),
  userIdentity: {
    email: "admin@mycompany.com",
    emailDomain: "mycompany.com",
    idpType: "INTERNAL",
    externalIdpId: null,
    passwordHash: "$argon2id$v=19$m=65536,t=3,p=4$...",  // Hash of initial password
    lastLoginAt: null
  },
  serviceAccount: null
});

// 4. Assign platform-admin role
const platformAdminRole = db.roles.findOne({ name: "platform-admin" });
db.principal_roles.insertOne({
  principalId: adminPrincipalId,
  roleId: platformAdminRole._id,
  source: "SYSTEM",
  grantedAt: new Date(),
  grantedBy: null
});
```

**Step 5: Use in Your Code**

```java
@Path("/api/my-resource")
@Authenticated
public class MyResource {

    @Inject
    SecurityContext securityContext;

    @Inject
    AuthorizationService authzService;

    @GET
    public Response getData(@QueryParam("tenantId") UUID tenantId) {
        // Check permission
        authzService.requirePermission("my-resource", "read");

        // Business logic: Validate tenant access
        if (tenantId == null) {
            throw new BadRequestException("tenantId is required");
        }

        if (!securityContext.getAccessibleTenantIds().contains(tenantId)) {
            throw new ForbiddenException("No access to tenant: " + tenantId);
        }

        // Your business logic with validated tenant
        // ...
    }
}
```

## Admin Operations

### Grant Partner Access to Customer Tenant

```java
// Via admin API: POST /admin/tenant-access-grants
{
  "principalId": "partner-user-uuid",
  "tenantId": "customer-a-uuid",
  "notes": "Partner logistics provider"
}

// Behind the scenes:
@Transactional
public TenantAccessGrant grantTenantAccess(UUID principalId, UUID tenantId, String notes) {
    Principal principal = principalRepo.findById(principalId);
    Tenant tenant = tenantRepo.findById(tenantId);

    // Validate principal is not already in this tenant
    if (principal.getTenantId() != null && principal.getTenantId().equals(tenantId)) {
        throw new BadRequestException("User already belongs to this tenant");
    }

    TenantAccessGrant grant = new TenantAccessGrant();
    grant.setPrincipalId(principalId);
    grant.setTenantId(tenantId);
    grant.setGrantedAt(Instant.now());
    grant.setGrantedBy(securityContext.getPrincipal().getId());
    grant.setNotes(notes);

    grantRepo.persist(grant);
    return grant;
}
```

### Assign Role to User

```java
// Via admin API: POST /admin/users/{userId}/roles
{
  "roleId": "operator-role-uuid"
}

@Transactional
public PrincipalRole assignRole(UUID principalId, UUID roleId) {
    // Check if already assigned
    Optional<PrincipalRole> existing = principalRoleRepo
        .findByPrincipalAndRole(principalId, roleId);

    if (existing.isPresent()) {
        return existing.get();
    }

    PrincipalRole pr = new PrincipalRole();
    pr.setPrincipalId(principalId);
    pr.setRoleId(roleId);
    pr.setSource("MANUAL");
    pr.setGrantedAt(Instant.now());
    pr.setGrantedBy(securityContext.getPrincipal().getId());

    principalRoleRepo.persist(pr);
    return pr;
}
```

### Authorize IDP Role Mapping

**SECURITY: This is how you authorize specific IDP roles to be accepted.**

Only platform admins should have permission to create IDP role mappings. This is a critical security control that determines which external roles are trusted.

```java
// Via admin API: POST /admin/idp-role-mappings
// SECURITY: Only platform admins can authorize IDP roles
{
  "idpRoleName": "keycloak-logistics-admin",  // Exact role name from partner/customer IDP
  "internalRoleName": "tenant-admin"          // Internal role to grant
}

@Transactional
@RolesAllowed("platform-admin")  // SECURITY: Only platform admins
public IdpRoleMapping authorizeIdpRole(String idpRoleName, String internalRoleName) {
    // Verify the internal role exists
    Role internalRole = roleRepo.findByName(internalRoleName)
        .orElseThrow(() -> new NotFoundException("Role not found: " + internalRoleName));

    // Check if mapping already exists
    Optional<IdpRoleMapping> existing = mappingRepo.findByIdpRoleName(idpRoleName);
    if (existing.isPresent()) {
        throw new ConflictException("IDP role already authorized: " + idpRoleName);
    }

    // Create the authorization mapping
    IdpRoleMapping mapping = new IdpRoleMapping();
    mapping.idpRoleName = idpRoleName;
    mapping.internalRoleId = internalRole.id;
    mapping.createdAt = Instant.now();
    mapping.updatedAt = Instant.now();

    mappingRepo.persist(mapping);

    log.info("SECURITY: Authorized IDP role '{}' to map to internal role '{}' by admin {}",
             idpRoleName, internalRoleName, securityContext.getPrincipal().getName());

    return mapping;
}
```

**Authorization Workflow:**

1. **Partner/Customer requests role mapping**: "We want users with role `logistics-admin` in our Keycloak to have access"
2. **Platform admin reviews request**: Ensures the requested internal role is appropriate
3. **Admin creates IDP role mapping**: Via admin API or UI
4. **Mapping takes effect immediately**: Next login will sync the authorized role
5. **Audit logged**: All IDP role authorizations are logged for security review

**Example Scenario:**

```
Customer: "Acme Corp" (acmecorp.com domain)
IDP: Their own Keycloak instance
Request: Users with "acme-admin" role should have tenant-admin access

Platform Admin Action:
POST /admin/idp-role-mappings
{
  "idpRoleName": "acme-admin",
  "internalRoleName": "tenant-admin"
}

Result:
- When user@acmecorp.com logs in with "acme-admin" role → gets "tenant-admin" permissions
- When user@acmecorp.com logs in with "acme-viewer" role → role REJECTED (not authorized)
```

## Security Considerations

### Authentication Security

1. **Password Storage**: Use Argon2id algorithm with appropriate parameters
   - Memory: 64 MB minimum
   - Iterations: 3-4
   - Parallelism: 4

2. **Token Validation**:
   - Always validate JWT signatures
   - Check expiry (`exp` claim)
   - Verify issuer (`iss` claim)
   - Validate audience (`aud` claim) if present

3. **Session Security**:
   - HttpOnly cookies (not accessible to JavaScript)
   - Secure flag (HTTPS only)
   - SameSite=Strict (CSRF protection)
   - Short timeout (30 minutes default)

4. **Rate Limiting**: Protect login endpoints
   - Max 5 failed attempts per email per 15 minutes
   - Exponential backoff on subsequent attempts
   - Log suspicious activity

### Authorization Security

1. **IDP Role Authorization** (CRITICAL):
   - **Never trust IDP roles by default**: All IDP roles must be explicitly authorized in `idp_role_mappings`
   - **Platform admin only**: Only platform administrators can authorize IDP role mappings
   - **Reject unknown roles**: Any IDP role not in the mapping table is rejected and logged as a warning
   - **Defense in depth**: Prevents malicious or misconfigured IDPs from granting unauthorized access
   - **Regular review**: Periodically audit `idp_role_mappings` to ensure only appropriate roles are authorized
   - **Per-domain consideration**: Different customer/partner domains may send the same role name with different meanings
   - **Audit all changes**: Log all IDP role mapping creations, updates, and deletions
   - **Example attack prevented**: Partner IDP compromised and grants all users "super-admin" role → rejected because not in mapping table

2. **Tenant Isolation**:
   - ALWAYS filter queries by tenant_id
   - Validate tenant access before operations
   - Never trust client-provided tenant IDs without validation

3. **Principle of Least Privilege**:
   - Start users with minimal roles
   - Grant additional permissions as needed
   - Regularly audit role assignments

3. **Audit Logging**:
   - Log all authentication attempts (success and failure)
   - Log role assignments and removals
   - Log tenant access grants
   - Log permission denials

4. **Secret Storage**:
   - Encrypt OIDC client secrets in database
   - Store JWT signing keys outside database (filesystem, vault)
   - Rotate keys periodically

### Data Protection

1. **Sensitive Fields**:
   - Never log passwords or tokens
   - Redact sensitive data in logs
   - Use encrypted connections (TLS) for all communication

2. **GDPR Compliance**:
   - Support user data export
   - Support user deletion (soft delete principals)
   - Maintain audit trail for compliance

## Performance Considerations

### Caching Strategy

```java
@ApplicationScoped
public class RoleCacheService {

    @CacheResult(cacheName = "principal-roles")
    public Set<Role> getRoles(UUID principalId) {
        return principalRoleRepo.findByPrincipalId(principalId).stream()
            .map(PrincipalRole::getRole)
            .collect(Collectors.toSet());
    }

    @CacheInvalidate(cacheName = "principal-roles")
    public void invalidateRoles(UUID principalId) {
        // Cache cleared
    }
}
```

**Cache Invalidation Triggers**:
- When roles are granted/revoked
- On OIDC login (IDP role sync)
- On user update

### Database Indexes

```sql
-- High-cardinality lookups
CREATE INDEX idx_user_identities_email ON user_identities(email);
CREATE INDEX idx_user_identities_external_idp_id ON user_identities(external_idp_id);
CREATE INDEX idx_service_accounts_client_id ON service_accounts(client_id);

-- Foreign key lookups
CREATE INDEX idx_principals_tenant_id ON principals(tenant_id);
CREATE INDEX idx_tenant_access_grants_principal_id ON tenant_access_grants(principal_id);
CREATE INDEX idx_tenant_access_grants_tenant_id ON tenant_access_grants(tenant_id);
CREATE INDEX idx_principal_roles_principal_id ON principal_roles(principal_id);
CREATE INDEX idx_role_permissions_role_id ON role_permissions(role_id);

-- Domain lookups
CREATE INDEX idx_tenant_auth_config_email_domain ON tenant_auth_config(email_domain);
CREATE INDEX idx_anchor_domains_email_domain ON anchor_domains(email_domain);
```

## Testing Strategy

### Unit Tests

```java
@QuarkusTest
class TenantAccessServiceTest {

    @Inject
    TenantAccessService tenantAccessService;

    @Test
    void anchorDomainUserAccessesAllTenants() {
        // Setup: Create anchor domain, tenants, user
        // ...

        Set<UUID> accessible = tenantAccessService.getAccessibleTenants(principal, userIdentity);

        assertEquals(allTenantIds, accessible);
    }

    @Test
    void partnerUserAccessesGrantedTenantsOnly() {
        // Setup: Create partner user, grant access to 2 tenants
        // ...

        Set<UUID> accessible = tenantAccessService.getAccessibleTenants(principal, userIdentity);

        assertEquals(2, accessible.size());
        assertTrue(accessible.contains(tenantA.getId()));
        assertTrue(accessible.contains(tenantB.getId()));
    }
}
```

### Integration Tests

```java
@QuarkusTest
class OidcAuthenticationFlowTest {

    @Test
    void oidcLoginCreatesUserAndSyncsRoles() {
        // Mock Keycloak callback with token containing roles
        // Verify user created in database
        // Verify roles mapped and assigned
        // Verify session created
    }

    @Test
    void unknownIdpRoleIsIgnored() {
        // Mock Keycloak token with unknown role
        // Verify user created successfully
        // Verify unknown role not assigned
        // Verify warning logged
    }
}
```

## Future Enhancements

1. **Additional Authentication Methods**:
   - SAML 2.0
   - Magic links (passwordless email)
   - WebAuthn / FIDO2

2. **Multi-Factor Authentication**:
   - TOTP (Google Authenticator, etc.)
   - SMS codes
   - Push notifications

3. **Attribute-Based Access Control (ABAC)**:
   - Policy-based authorization layer that can be called from business logic
   - Resource-level permissions (e.g., "can edit job #123")
   - Dynamic permission evaluation based on attributes (user properties, resource state, environment)
   - Policy engine with rules like "users can only edit their own draft jobs"
   - Complements RBAC for fine-grained control

4. **Audit Trail**:
   - Comprehensive audit log table
   - Queryable audit API
   - Compliance reports

5. **Admin UI**:
   - Web interface for user/tenant/role management
   - Visual role assignment matrix
   - Audit log viewer

6. **API Keys**:
   - Alternative to service accounts for simpler integrations
   - Scoped API keys (limited permissions)
   - Revocable, rotatable

7. **Keycloak Role Push**:
   - Bidirectional role sync
   - Push app-defined roles to Keycloak
   - Use in other applications

## Database Support

### Current: MongoDB

This module uses **MongoDB as the primary database** because FlowCatalyst uses MongoDB. This provides:

**Advantages:**
- **Single database deployment**: Auth data in same database as application data
- **No operational complexity**: No separate database to manage
- **Fast lookups**: Embedded documents (userIdentity in Principal) = single query
- **Flexible schema**: Easy to add fields without migrations
- **Horizontal scaling**: Same scaling strategy as FlowCatalyst

**Design Decisions:**
- **Embedded identity data**: `UserIdentity` and `ServiceAccount` embedded in `Principal` for performance
- **Embedded permissions**: Permission list embedded in `Role` for fast loading
- **ObjectId primary keys**: MongoDB-native, but can use UUID strings if needed
- **No schema migrations**: Bootstrap and index creation on startup
- **Transactions required**: Multi-document operations need MongoDB replica set (not standalone)

**Requirements:**
- MongoDB 4.0+ (for transactions)
- Replica set if using transactions
- Same MongoDB instance as FlowCatalyst

### Future: Relational Database Support

A relational database variant is available as a separate module for applications that:
- Must use relational databases for compliance/policy reasons
- Already have relational database infrastructure
- Need strong foreign key constraints

**Separate Module:** `flowcatalyst-auth-relational`

The relational implementation would use:
- Hibernate ORM with Panache
- Flyway for schema migrations
- Normalized schema (user_identities, service_accounts as separate tables)
- Foreign key constraints for referential integrity
- JPA transactions

**Supported Databases:**
- PostgreSQL (recommended)
- MySQL / MariaDB
- Oracle
- SQL Server

For most use cases, **MongoDB (current implementation) is recommended** as it aligns with FlowCatalyst's architecture and provides simpler deployment.

## Repository

Module will be developed at: `https://github.com/flowcatalyst/flowcatalyst-auth`

This document will be updated as requirements evolve and implementation progresses.

## Appendix: Common Scenarios

### Scenario 1: Customer User Login

```
User: customer@acmecorp.com
Domain: acmecorp.com
Auth Config: INTERNAL
Tenant: AcmeCorp (tenant_id = acme-uuid)

Flow:
1. User enters email
2. System finds tenant_auth_config for acmecorp.com → INTERNAL
3. Show password form
4. Validate password
5. Create session with:
   - principal_id = user's UUID
   - active_tenant_id = acme-uuid
   - accessible_tenants = [acme-uuid]
6. User can only access their tenant
```

### Scenario 2: Anchor Domain Admin Login

```
User: admin@mycompany.com
Domain: mycompany.com
Anchor Domains: mycompany.com
Auth Config: OIDC

Flow:
1. User enters email
2. System finds mycompany.com in anchor_domains → Global access
3. System finds tenant_auth_config for mycompany.com → OIDC
4. Redirect to Keycloak
5. OIDC callback, validate token
6. Extract roles from token: ["keycloak-platform-admin"]
7. Map to internal role: platform-admin
8. Create session with:
   - principal_id = admin's UUID
   - active_tenant_id = NULL (will be set per-request)
   - accessible_tenants = ALL tenant UUIDs
9. Admin can access all tenants
```

### Scenario 3: Partner User with Multi-Tenant Access

```
User: partner@logistics.com
Domain: logistics.com
Tenant Access Grants: [Customer A, Customer B, Customer C]
Auth Config: OIDC

Flow:
1. User enters email
2. System finds tenant_auth_config for logistics.com → OIDC
3. Redirect to Keycloak
4. OIDC callback, validate token
5. Extract roles: ["keycloak-operator"]
6. Map to internal role: operator
7. Lookup tenant_access_grants for user → 3 tenants
8. Create session with:
   - principal_id = partner's UUID
   - active_tenant_id = NULL (user will choose)
   - accessible_tenants = [customer-a-uuid, customer-b-uuid, customer-c-uuid]
9. User can switch between granted tenants via /auth/switch-tenant
10. User has "operator" role in all 3 tenants
```

### Scenario 4: Service-to-Service Call

```
Service: dispatch-scheduler
Tenant: Customer A
Client ID: flowcatalyst-scheduler

Flow:
1. Service calls Keycloak token endpoint with client credentials
2. Receives JWT access token
3. Service calls FlowCatalyst API:
   POST /api/dispatch-jobs?tenantId=customer-a-uuid
   Authorization: Bearer <keycloak-jwt>
4. API validates JWT signature with Keycloak
5. Extracts client_id from token
6. Looks up service_accounts by client_id
7. Loads principal (tenant_id = customer-a-uuid)
8. Loads roles (e.g., "operator")
9. Sets security context:
   - principal_id = service's UUID
   - accessible_tenants = [customer-a-uuid]
10. Checks permission: dispatch-job:create
11. Business logic validates tenantId matches service's accessible tenants
12. Executes request
```

---

**End of Document**
