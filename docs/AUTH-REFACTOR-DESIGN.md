# Auth System Refactor - Code-First Permissions & Roles

## Core Concepts

### Permission Structure (4-part hierarchy)

```
{subdomain}:{context}:{aggregate}:{action}

Examples:
- logistics:dispatch:job:create
- logistics:dispatch:job:read
- logistics:dispatch:route:optimize
- platform:tenant:user:invite
- platform:billing:invoice:view
```

**Parts:**
- `subdomain` - Business domain (logistics, platform, analytics)
- `context` - Bounded context within domain (dispatch, warehouse, billing)
- `aggregate` - Entity/resource (job, route, user, invoice)
- `action` - Operation (create, read, update, delete, execute, approve)

### Role Structure (2-part hierarchy)

```
{subdomain}:{role-name}

Examples:
- logistics:operator
- logistics:admin
- platform:tenant-admin
- platform:support
```

---

## Core Models

### 1. Permission Definition (Record)

```java
package tech.flowcatalyst.platform.authorization;

/**
 * Permission definition with semantic parts.
 * Generates structured permission string: subdomain:context:aggregate:action
 */
public record PermissionDefinition(
    String subdomain,    // Business domain
    String context,      // Bounded context
    String aggregate,    // Resource/entity
    String action,       // Operation
    String description   // Human-readable description
) {

    /**
     * Generate permission string representation.
     * @return "subdomain:context:aggregate:action"
     */
    public String toPermissionString() {
        return String.format("%s:%s:%s:%s", subdomain, context, aggregate, action);
    }

    /**
     * Validate permission parts.
     */
    public PermissionDefinition {
        validatePart(subdomain, "subdomain");
        validatePart(context, "context");
        validatePart(aggregate, "aggregate");
        validatePart(action, "action");
    }

    private void validatePart(String part, String name) {
        if (part == null || part.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be null or blank");
        }
        if (!part.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(
                name + " must be lowercase alphanumeric with hyphens: " + part
            );
        }
    }

    /**
     * Check if this permission matches a pattern (for wildcard checking).
     * Examples:
     * - "logistics:*" matches "logistics:dispatch:job:create"
     * - "logistics:dispatch:*:read" matches "logistics:dispatch:job:read"
     */
    public boolean matches(String pattern) {
        String[] patternParts = pattern.split(":");
        String[] permParts = toPermissionString().split(":");

        if (patternParts.length > permParts.length) {
            return false;
        }

        for (int i = 0; i < patternParts.length; i++) {
            if (!patternParts[i].equals("*") && !patternParts[i].equals(permParts[i])) {
                return false;
            }
        }

        return true;
    }
}
```

### 2. Role Definition (Record)

```java
package tech.flowcatalyst.platform.authorization;

import java.util.Set;

/**
 * Role definition with semantic parts.
 * Generates structured role string: subdomain:role-name
 */
public record RoleDefinition(
    String subdomain,           // Business domain
    String roleName,            // Role name within domain
    Set<String> permissions,    // Permission strings this role grants
    String description          // Human-readable description
) {

    /**
     * Generate role string representation.
     * @return "subdomain:role-name"
     */
    public String toRoleString() {
        return String.format("%s:%s", subdomain, roleName);
    }

    /**
     * Validate role parts.
     */
    public RoleDefinition {
        validatePart(subdomain, "subdomain");
        validatePart(roleName, "roleName");

        if (permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("Role must have at least one permission");
        }
    }

    private void validatePart(String part, String name) {
        if (part == null || part.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be null or blank");
        }
        if (!part.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(
                name + " must be lowercase alphanumeric with hyphens: " + part
            );
        }
    }

    /**
     * Check if this role grants a specific permission.
     */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
```

### 3. Factory Interfaces

```java
package tech.flowcatalyst.platform.authorization;

import java.util.Set;

/**
 * Marker interface for permission factory classes.
 * Implement this to have your permissions discovered at build/startup.
 */
public interface PermissionFactory {
    /**
     * Return all permission definitions provided by this factory.
     */
    Set<PermissionDefinition> getPermissions();
}

/**
 * Marker interface for role factory classes.
 * Implement this to have your roles discovered at build/startup.
 */
public interface RoleFactory {
    /**
     * Return all role definitions provided by this factory.
     */
    Set<RoleDefinition> getRoles();
}
```

---

## Example Implementation

### Logistics Domain Permissions

```java
package tech.flowcatalyst.logistics.authorization;

import tech.flowcatalyst.platform.authorization.PermissionDefinition;
import tech.flowcatalyst.platform.authorization.PermissionFactory;
import java.util.Set;

/**
 * All permissions for the Logistics subdomain.
 */
public class LogisticsPermissions implements PermissionFactory {

    // Dispatch context - Job aggregate
    public static final PermissionDefinition DISPATCH_JOB_CREATE = new PermissionDefinition(
        "logistics", "dispatch", "job", "create",
        "Create new dispatch jobs"
    );

    public static final PermissionDefinition DISPATCH_JOB_READ = new PermissionDefinition(
        "logistics", "dispatch", "job", "read",
        "View dispatch jobs"
    );

    public static final PermissionDefinition DISPATCH_JOB_UPDATE = new PermissionDefinition(
        "logistics", "dispatch", "job", "update",
        "Update existing dispatch jobs"
    );

    public static final PermissionDefinition DISPATCH_JOB_DELETE = new PermissionDefinition(
        "logistics", "dispatch", "job", "delete",
        "Delete dispatch jobs"
    );

    public static final PermissionDefinition DISPATCH_JOB_ASSIGN = new PermissionDefinition(
        "logistics", "dispatch", "job", "assign",
        "Assign jobs to drivers"
    );

    // Dispatch context - Route aggregate
    public static final PermissionDefinition DISPATCH_ROUTE_OPTIMIZE = new PermissionDefinition(
        "logistics", "dispatch", "route", "optimize",
        "Optimize delivery routes"
    );

    public static final PermissionDefinition DISPATCH_ROUTE_VIEW = new PermissionDefinition(
        "logistics", "dispatch", "route", "read",
        "View delivery routes"
    );

    // Warehouse context
    public static final PermissionDefinition WAREHOUSE_INVENTORY_READ = new PermissionDefinition(
        "logistics", "warehouse", "inventory", "read",
        "View warehouse inventory"
    );

    public static final PermissionDefinition WAREHOUSE_INVENTORY_UPDATE = new PermissionDefinition(
        "logistics", "warehouse", "inventory", "update",
        "Update inventory levels"
    );

    @Override
    public Set<PermissionDefinition> getPermissions() {
        return Set.of(
            DISPATCH_JOB_CREATE,
            DISPATCH_JOB_READ,
            DISPATCH_JOB_UPDATE,
            DISPATCH_JOB_DELETE,
            DISPATCH_JOB_ASSIGN,
            DISPATCH_ROUTE_OPTIMIZE,
            DISPATCH_ROUTE_VIEW,
            WAREHOUSE_INVENTORY_READ,
            WAREHOUSE_INVENTORY_UPDATE
        );
    }
}
```

### Logistics Domain Roles

```java
package tech.flowcatalyst.logistics.authorization;

import tech.flowcatalyst.platform.authorization.RoleDefinition;
import tech.flowcatalyst.platform.authorization.RoleFactory;
import java.util.Set;

import static tech.flowcatalyst.logistics.authorization.LogisticsPermissions.*;

/**
 * All roles for the Logistics subdomain.
 */
public class LogisticsRoles implements RoleFactory {

    public static final RoleDefinition OPERATOR = new RoleDefinition(
        "logistics", "operator",
        Set.of(
            DISPATCH_JOB_CREATE.toPermissionString(),
            DISPATCH_JOB_READ.toPermissionString(),
            DISPATCH_JOB_UPDATE.toPermissionString(),
            DISPATCH_JOB_ASSIGN.toPermissionString(),
            DISPATCH_ROUTE_VIEW.toPermissionString()
        ),
        "Logistics operator - can manage jobs and routes"
    );

    public static final RoleDefinition DISPATCHER = new RoleDefinition(
        "logistics", "dispatcher",
        Set.of(
            DISPATCH_JOB_CREATE.toPermissionString(),
            DISPATCH_JOB_READ.toPermissionString(),
            DISPATCH_JOB_UPDATE.toPermissionString(),
            DISPATCH_JOB_ASSIGN.toPermissionString(),
            DISPATCH_ROUTE_OPTIMIZE.toPermissionString(),
            DISPATCH_ROUTE_VIEW.toPermissionString()
        ),
        "Senior dispatcher - operator + route optimization"
    );

    public static final RoleDefinition WAREHOUSE_MANAGER = new RoleDefinition(
        "logistics", "warehouse-manager",
        Set.of(
            WAREHOUSE_INVENTORY_READ.toPermissionString(),
            WAREHOUSE_INVENTORY_UPDATE.toPermissionString()
        ),
        "Warehouse manager - inventory management only"
    );

    public static final RoleDefinition ADMIN = new RoleDefinition(
        "logistics", "admin",
        Set.of(
            // All permissions
            DISPATCH_JOB_CREATE.toPermissionString(),
            DISPATCH_JOB_READ.toPermissionString(),
            DISPATCH_JOB_UPDATE.toPermissionString(),
            DISPATCH_JOB_DELETE.toPermissionString(),
            DISPATCH_JOB_ASSIGN.toPermissionString(),
            DISPATCH_ROUTE_OPTIMIZE.toPermissionString(),
            DISPATCH_ROUTE_VIEW.toPermissionString(),
            WAREHOUSE_INVENTORY_READ.toPermissionString(),
            WAREHOUSE_INVENTORY_UPDATE.toPermissionString()
        ),
        "Logistics administrator - full access to logistics domain"
    );

    @Override
    public Set<RoleDefinition> getRoles() {
        return Set.of(OPERATOR, DISPATCHER, WAREHOUSE_MANAGER, ADMIN);
    }
}
```

### Platform Domain Permissions

```java
package tech.flowcatalyst.platform.authorization;

import java.util.Set;

/**
 * Platform-level permissions (tenant management, billing, etc.)
 */
public class PlatformPermissions implements PermissionFactory {

    // Tenant context
    public static final PermissionDefinition TENANT_USER_INVITE = new PermissionDefinition(
        "platform", "tenant", "user", "invite",
        "Invite users to tenant"
    );

    public static final PermissionDefinition TENANT_USER_REMOVE = new PermissionDefinition(
        "platform", "tenant", "user", "remove",
        "Remove users from tenant"
    );

    public static final PermissionDefinition TENANT_SETTINGS_UPDATE = new PermissionDefinition(
        "platform", "tenant", "settings", "update",
        "Update tenant settings"
    );

    // Billing context
    public static final PermissionDefinition BILLING_INVOICE_VIEW = new PermissionDefinition(
        "platform", "billing", "invoice", "read",
        "View billing invoices"
    );

    public static final PermissionDefinition BILLING_PAYMENT_MANAGE = new PermissionDefinition(
        "platform", "billing", "payment-method", "update",
        "Manage payment methods"
    );

    @Override
    public Set<PermissionDefinition> getPermissions() {
        return Set.of(
            TENANT_USER_INVITE,
            TENANT_USER_REMOVE,
            TENANT_SETTINGS_UPDATE,
            BILLING_INVOICE_VIEW,
            BILLING_PAYMENT_MANAGE
        );
    }
}
```

### Platform Domain Roles

```java
package tech.flowcatalyst.platform.authorization;

import java.util.Set;
import static tech.flowcatalyst.platform.authorization.PlatformPermissions.*;

public class PlatformRoles implements RoleFactory {

    public static final RoleDefinition TENANT_ADMIN = new RoleDefinition(
        "platform", "tenant-admin",
        Set.of(
            TENANT_USER_INVITE.toPermissionString(),
            TENANT_USER_REMOVE.toPermissionString(),
            TENANT_SETTINGS_UPDATE.toPermissionString(),
            BILLING_INVOICE_VIEW.toPermissionString(),
            BILLING_PAYMENT_MANAGE.toPermissionString()
        ),
        "Tenant administrator - full access to tenant settings"
    );

    public static final RoleDefinition BILLING_VIEWER = new RoleDefinition(
        "platform", "billing-viewer",
        Set.of(
            BILLING_INVOICE_VIEW.toPermissionString()
        ),
        "Billing viewer - read-only access to invoices"
    );

    @Override
    public Set<RoleDefinition> getRoles() {
        return Set.of(TENANT_ADMIN, BILLING_VIEWER);
    }
}
```

---

## Permission Registry (In-Memory)

```java
package tech.flowcatalyst.platform.authorization;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * In-memory registry of all permissions and roles.
 * Scanned and loaded at startup from PermissionFactory and RoleFactory implementations.
 */
@ApplicationScoped
public class PermissionRegistry {

    // Map: "logistics:dispatch:job:create" → PermissionDefinition
    private Map<String, PermissionDefinition> permissions = new HashMap<>();

    // Map: "logistics:operator" → RoleDefinition
    private Map<String, RoleDefinition> roles = new HashMap<>();

    // Map: "logistics" → Set<PermissionDefinition>
    private Map<String, Set<PermissionDefinition>> permissionsBySubdomain = new HashMap<>();

    // Map: "logistics" → Set<RoleDefinition>
    private Map<String, Set<RoleDefinition>> rolesBySubdomain = new HashMap<>();

    @PostConstruct
    public void loadDefinitions() {
        // TODO: Scan classpath for PermissionFactory and RoleFactory implementations
        // For now, manually register
        registerPermissionFactory(new LogisticsPermissions());
        registerPermissionFactory(new PlatformPermissions());

        registerRoleFactory(new LogisticsRoles());
        registerRoleFactory(new PlatformRoles());

        validateRoles(); // Ensure all role permissions exist
    }

    private void registerPermissionFactory(PermissionFactory factory) {
        factory.getPermissions().forEach(perm -> {
            String key = perm.toPermissionString();
            if (permissions.containsKey(key)) {
                throw new IllegalStateException("Duplicate permission: " + key);
            }
            permissions.put(key, perm);

            permissionsBySubdomain
                .computeIfAbsent(perm.subdomain(), k -> new HashSet<>())
                .add(perm);
        });
    }

    private void registerRoleFactory(RoleFactory factory) {
        factory.getRoles().forEach(role -> {
            String key = role.toRoleString();
            if (roles.containsKey(key)) {
                throw new IllegalStateException("Duplicate role: " + key);
            }
            roles.put(key, role);

            rolesBySubdomain
                .computeIfAbsent(role.subdomain(), k -> new HashSet<>())
                .add(role);
        });
    }

    private void validateRoles() {
        // Ensure all permissions referenced by roles actually exist
        roles.values().forEach(role -> {
            role.permissions().forEach(permStr -> {
                if (!permissions.containsKey(permStr)) {
                    throw new IllegalStateException(
                        "Role " + role.toRoleString() +
                        " references non-existent permission: " + permStr
                    );
                }
            });
        });
    }

    // Query methods
    public Optional<PermissionDefinition> getPermission(String permissionString) {
        return Optional.ofNullable(permissions.get(permissionString));
    }

    public Optional<RoleDefinition> getRole(String roleString) {
        return Optional.ofNullable(roles.get(roleString));
    }

    public Set<PermissionDefinition> getPermissionsBySubdomain(String subdomain) {
        return permissionsBySubdomain.getOrDefault(subdomain, Set.of());
    }

    public Set<RoleDefinition> getRolesBySubdomain(String subdomain) {
        return rolesBySubdomain.getOrDefault(subdomain, Set.of());
    }

    public Set<String> getAllPermissionStrings() {
        return permissions.keySet();
    }

    public Set<String> getAllRoleStrings() {
        return roles.keySet();
    }

    /**
     * Get all permissions granted by a role.
     */
    public Set<String> getPermissionsForRole(String roleString) {
        return Optional.ofNullable(roles.get(roleString))
            .map(RoleDefinition::permissions)
            .orElse(Set.of());
    }
}
```

---

## Database Changes

### Before (Database-Driven)

```sql
CREATE TABLE auth_role (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255),
    description TEXT,
    permissions JSONB  -- Embedded
);

CREATE TABLE auth_principal_role (
    id BIGINT PRIMARY KEY,
    principal_id BIGINT REFERENCES auth_principal(id),
    role_id BIGINT REFERENCES auth_role(id)  -- FK to role table
);
```

### After (Code-Driven)

```sql
-- No role table! Roles defined in code.
-- No permission table! Permissions defined in code.

CREATE TABLE auth_principal_role (
    id BIGINT PRIMARY KEY,
    principal_id BIGINT REFERENCES auth_principal(id),
    role_name VARCHAR(255) NOT NULL,  -- "logistics:operator"
    assignment_source VARCHAR(50),     -- "MANUAL", "IDP_SYNC"
    assigned_at TIMESTAMP,

    UNIQUE(principal_id, role_name)
);

CREATE INDEX idx_principal_role_lookup ON auth_principal_role(principal_id, role_name);
```

---

## Benefits of This Design

### 1. Semantic Parts
✅ Each part has meaning (subdomain, context, aggregate, action)
✅ Can query by subdomain: "Show me all logistics permissions"
✅ Can do wildcard matching: `"logistics:*:*:read"` for all read permissions

### 2. Type Safety
✅ Validation at construction time
✅ IDE autocomplete for permission parts
✅ Compile-time errors if permission doesn't exist

### 3. Version Control
✅ Permissions and roles in code (git tracked)
✅ PR reviews for permission changes
✅ Audit trail via git history

### 4. Reusability
✅ Same permission in multiple roles
✅ No duplication in database

### 5. Documentation
✅ Self-documenting: parts have meaning
✅ Description field for each permission/role
✅ Can generate API docs automatically

### 6. Testability
✅ Test permission matching logic
✅ Test role definitions
✅ Test registry loading

---

## IDP Sync Strategy

### Internal IDP (Our Keycloak/Entra)
```java
// Sync roles TO IDP on startup
keycloak.createRole("logistics:operator");
keycloak.assignPermissions("logistics:operator", [
    "logistics:dispatch:job:create",
    "logistics:dispatch:job:read",
    // ...
]);

// Users get roles from OIDC token
// No mapping table needed (1:1)
```

### External IDP (Customer's Keycloak)
```java
// Keep IdpRoleMapping table (security whitelist)
IdpRoleMapping {
    idpRoleName: "acme-dispatcher",      // Their role name
    internalRoleName: "logistics:operator" // Our role string
}

// During OIDC login:
// 1. Get roles from customer IDP: ["acme-dispatcher"]
// 2. Look up mapping: "acme-dispatcher" → "logistics:operator"
// 3. Assign "logistics:operator" to user
```

---

## Next Steps

1. Create core records: `PermissionDefinition`, `RoleDefinition`
2. Create factory interfaces
3. Create `PermissionRegistry`
4. Implement example permissions/roles for logistics domain
5. Update `PrincipalRole` to use string role names
6. Update `AuthorizationService` to check string permissions
7. Implement IDP sync for internal IDP
8. Migrate existing data

Should I proceed with implementation?
