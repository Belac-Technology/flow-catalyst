# Permission and Role System

## Overview

FlowCatalyst uses a **code-first, type-safe** permission and role system. Permissions and roles are defined as Java classes (or TypeScript classes/PHP classes in their respective SDKs) rather than database records. This provides compile-time safety, IDE autocomplete, and self-documenting code.

## Architecture Principles

### 1. Code-First Definitions
- Permissions and roles are **defined in code** as classes implementing standard interfaces
- The database only stores **string identifiers** linking users to roles
- No foreign key constraints between users and permission/role definitions
- Permission/role definitions are discovered at application startup via annotation scanning

### 2. Structured Naming Convention

**Roles**: `module:role`
- Examples: `dispatch:manager`, `platform:admin`, `billing:viewer`

**Permissions**: `module:domain:permission`
- Examples: `dispatch:job:create`, `billing:invoice:read`, `platform:user:delete`

### 3. Type Safety
- Use class references instead of magic strings: `@RequiresPermission(DispatchJobCreatePermission.class)`
- Compile-time validation prevents typos and references to non-existent permissions
- IDE autocomplete shows all available permissions

### 4. Multi-Language Support
The same pattern works across all SDK languages:
- **Java**: Interfaces + Classes with annotations (Quarkus/Jandex scanning)
- **TypeScript**: Decorators on classes
- **PHP**: Attributes on classes

---

## Java Implementation (Quarkus)

### Core Interfaces

```java
package tech.flowcatalyst.platform.security;

/**
 * Defines a permission in the system.
 * Permissions follow the format: module:domain:permission
 * Example: dispatch:job:create
 */
public interface PermissionDefinition {
    /**
     * Unique permission code (module:domain:permission)
     */
    String getCode();

    /**
     * Human-readable display name
     */
    String getDisplayName();

    /**
     * Detailed description of what this permission grants
     */
    String getDescription();

    /**
     * Module this permission belongs to (extracted from code)
     */
    default String getModule() {
        String[] parts = getCode().split(":");
        return parts.length > 0 ? parts[0] : "";
    }

    /**
     * Domain this permission applies to (extracted from code)
     */
    default String getDomain() {
        String[] parts = getCode().split(":");
        return parts.length > 1 ? parts[1] : "";
    }

    /**
     * Action/operation this permission grants (extracted from code)
     */
    default String getAction() {
        String[] parts = getCode().split(":");
        return parts.length > 2 ? parts[2] : "";
    }
}

/**
 * Defines a role in the system.
 * Roles follow the format: module:role
 * Example: dispatch:manager
 */
public interface RoleDefinition {
    /**
     * Unique role code (module:role)
     */
    String getCode();

    /**
     * Human-readable display name
     */
    String getDisplayName();

    /**
     * Detailed description of this role's purpose
     */
    String getDescription();

    /**
     * Module this role belongs to (extracted from code)
     */
    default String getModule() {
        String[] parts = getCode().split(":");
        return parts.length > 0 ? parts[0] : "";
    }

    /**
     * List of permission codes this role grants
     */
    List<String> getPermissionCodes();
}
```

### Annotations

```java
package tech.flowcatalyst.platform.annotation;

/**
 * Marks a class as defining a permission.
 * The class must implement PermissionDefinition.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DefinePermission {
}

/**
 * Marks a class as defining a role.
 * The class must implement RoleDefinition.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DefineRole {
}

/**
 * Requires a specific permission to execute a method or access a resource.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    /**
     * The permission class required
     */
    Class<? extends PermissionDefinition> value();
}

/**
 * Requires ANY of the specified permissions (OR logic).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAnyPermission {
    /**
     * Any of these permission classes is sufficient
     */
    Class<? extends PermissionDefinition>[] value();
}

/**
 * Requires ALL of the specified permissions (AND logic).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAllPermissions {
    /**
     * All of these permission classes are required
     */
    Class<? extends PermissionDefinition>[] value();
}

/**
 * Requires a specific role.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {
    /**
     * The role class required
     */
    Class<? extends RoleDefinition> value();
}

/**
 * Requires ANY of the specified roles (OR logic).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAnyRole {
    /**
     * Any of these role classes is sufficient
     */
    Class<? extends RoleDefinition>[] value();
}
```

### Defining Permissions

Create a permission class for each permission in your module:

```java
package tech.flowcatalyst.dispatch.security;

import tech.flowcatalyst.platform.annotation.DefinePermission;
import tech.flowcatalyst.platform.security.PermissionDefinition;

@DefinePermission
public class DispatchJobCreatePermission implements PermissionDefinition {

    public static final String CODE = "dispatch:job:create";

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getDisplayName() {
        return "Create Dispatch Jobs";
    }

    @Override
    public String getDescription() {
        return "Allows creation of new dispatch jobs in the system";
    }
}
```

**Best Practice**: Define a constants class for your module:

```java
package tech.flowcatalyst.dispatch.security;

public class DispatchPermissions {

    @DefinePermission
    public static class JobCreate implements PermissionDefinition {
        public static final String CODE = "dispatch:job:create";
        // ... implementation
    }

    @DefinePermission
    public static class JobRead implements PermissionDefinition {
        public static final String CODE = "dispatch:job:read";
        // ... implementation
    }

    @DefinePermission
    public static class JobUpdate implements PermissionDefinition {
        public static final String CODE = "dispatch:job:update";
        // ... implementation
    }

    @DefinePermission
    public static class JobDelete implements PermissionDefinition {
        public static final String CODE = "dispatch:job:delete";
        // ... implementation
    }
}
```

### Defining Roles

Create a role class that bundles permissions:

```java
package tech.flowcatalyst.dispatch.security;

import tech.flowcatalyst.platform.annotation.DefineRole;
import tech.flowcatalyst.platform.security.RoleDefinition;
import java.util.List;

@DefineRole
public class DispatchManagerRole implements RoleDefinition {

    public static final String CODE = "dispatch:manager";

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getDisplayName() {
        return "Dispatch Manager";
    }

    @Override
    public String getDescription() {
        return "Full access to dispatch operations including job management and driver assignments";
    }

    @Override
    public List<String> getPermissionCodes() {
        return List.of(
            DispatchPermissions.JobCreate.CODE,
            DispatchPermissions.JobRead.CODE,
            DispatchPermissions.JobUpdate.CODE,
            DispatchPermissions.JobDelete.CODE,
            DispatchPermissions.DriverAssign.CODE,
            DispatchPermissions.RouteOptimize.CODE
        );
    }
}

@DefineRole
public class DispatchViewerRole implements RoleDefinition {

    public static final String CODE = "dispatch:viewer";

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getDisplayName() {
        return "Dispatch Viewer";
    }

    @Override
    public String getDescription() {
        return "Read-only access to dispatch operations";
    }

    @Override
    public List<String> getPermissionCodes() {
        return List.of(
            DispatchPermissions.JobRead.CODE
        );
    }
}
```

### Using Permissions in Code

#### On REST Endpoints

```java
package tech.flowcatalyst.dispatch.resource;

import jakarta.ws.rs.*;
import tech.flowcatalyst.platform.annotation.*;
import tech.flowcatalyst.dispatch.security.*;

@Path("/api/dispatch/jobs")
@Produces("application/json")
@Consumes("application/json")
public class DispatchJobResource {

    @POST
    @RequiresPermission(DispatchPermissions.JobCreate.class)
    public Response createJob(JobRequest request) {
        // Only users with dispatch:job:create can call this
        return Response.ok(jobService.create(request)).build();
    }

    @GET
    @Path("/{id}")
    @RequiresAnyPermission({
        DispatchPermissions.JobRead.class,
        PlatformPermissions.AdminFullAccess.class
    })
    public Response getJob(@PathParam("id") Long id) {
        // Users need dispatch:job:read OR platform:admin:full
        return Response.ok(jobService.get(id)).build();
    }

    @DELETE
    @Path("/{id}")
    @RequiresRole(DispatchManagerRole.class)
    public Response deleteJob(@PathParam("id") Long id) {
        // Only dispatch:manager role can delete
        return Response.noContent().build();
    }
}
```

#### In Service Layer

```java
package tech.flowcatalyst.dispatch.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.flowcatalyst.platform.service.AuthorizationService;
import tech.flowcatalyst.dispatch.security.*;

@ApplicationScoped
public class DispatchJobService {

    @Inject
    AuthorizationService authz;

    public Job createJob(Principal principal, JobRequest request) {
        // Manual permission check
        authz.requirePermission(principal, DispatchPermissions.JobCreate.CODE);

        // Business logic
        Job job = new Job();
        job.name = request.name;
        job.persist();
        return job;
    }

    public boolean canUserDeleteJob(Principal principal, Job job) {
        // Check permission
        return authz.hasPermission(principal, DispatchPermissions.JobDelete.CODE);
    }
}
```

### Annotation Scanning (Startup)

The system scans for `@DefinePermission` and `@DefineRole` annotations at startup:

```java
package tech.flowcatalyst.platform.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;
import org.jboss.jandex.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SecurityMetadataRegistry {

    private static final Logger LOG = Logger.getLogger(SecurityMetadataRegistry.class);

    private final Map<String, PermissionDefinition> permissions = new ConcurrentHashMap<>();
    private final Map<String, RoleDefinition> roles = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent event, IndexView index) {
        scanPermissions(index);
        scanRoles(index);
        validateRolePermissions();
        logDiscoveredMetadata();
    }

    private void scanPermissions(IndexView index) {
        Collection<AnnotationInstance> annotations = index.getAnnotations(
            DotName.createSimple(DefinePermission.class.getName())
        );

        for (AnnotationInstance annotation : annotations) {
            ClassInfo classInfo = annotation.target().asClass();
            try {
                Class<?> clazz = Class.forName(classInfo.name().toString());
                PermissionDefinition permission = (PermissionDefinition) clazz.getDeclaredConstructor().newInstance();

                permissions.put(permission.getCode(), permission);
                LOG.infof("Registered permission: %s (%s)",
                    permission.getCode(),
                    permission.getDisplayName());

            } catch (Exception e) {
                LOG.errorf(e, "Failed to instantiate permission class: %s", classInfo.name());
            }
        }
    }

    private void scanRoles(IndexView index) {
        Collection<AnnotationInstance> annotations = index.getAnnotations(
            DotName.createSimple(DefineRole.class.getName())
        );

        for (AnnotationInstance annotation : annotations) {
            ClassInfo classInfo = annotation.target().asClass();
            try {
                Class<?> clazz = Class.forName(classInfo.name().toString());
                RoleDefinition role = (RoleDefinition) clazz.getDeclaredConstructor().newInstance();

                roles.put(role.getCode(), role);
                LOG.infof("Registered role: %s (%s) with %d permissions",
                    role.getCode(),
                    role.getDisplayName(),
                    role.getPermissionCodes().size());

            } catch (Exception e) {
                LOG.errorf(e, "Failed to instantiate role class: %s", classInfo.name());
            }
        }
    }

    private void validateRolePermissions() {
        for (RoleDefinition role : roles.values()) {
            for (String permCode : role.getPermissionCodes()) {
                if (!permissions.containsKey(permCode)) {
                    LOG.warnf("Role %s references unknown permission: %s",
                        role.getCode(), permCode);
                }
            }
        }
    }

    private void logDiscoveredMetadata() {
        LOG.infof("Security metadata scan complete: %d permissions, %d roles",
            permissions.size(), roles.size());
    }

    // Public API

    public PermissionDefinition getPermission(String code) {
        return permissions.get(code);
    }

    public RoleDefinition getRole(String code) {
        return roles.get(code);
    }

    public Collection<PermissionDefinition> getAllPermissions() {
        return Collections.unmodifiableCollection(permissions.values());
    }

    public Collection<RoleDefinition> getAllRoles() {
        return Collections.unmodifiableCollection(roles.values());
    }

    public List<PermissionDefinition> getPermissionsByModule(String module) {
        return permissions.values().stream()
            .filter(p -> p.getModule().equals(module))
            .toList();
    }

    public List<RoleDefinition> getRolesByModule(String module) {
        return roles.values().stream()
            .filter(r -> r.getModule().equals(module))
            .toList();
    }
}
```

### Authorization Service Updates

Update the existing `AuthorizationService` to use permission codes:

```java
package tech.flowcatalyst.platform.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import tech.flowcatalyst.platform.model.Principal;
import tech.flowcatalyst.platform.repository.PrincipalRoleRepository;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class AuthorizationService {

    @Inject
    PrincipalRoleRepository principalRoleRepo;

    @Inject
    SecurityMetadataRegistry metadataRegistry;

    /**
     * Check if principal has a specific permission by code.
     */
    public boolean hasPermission(Long principalId, String permissionCode) {
        Set<String> userRoleCodes = getRoleCodes(principalId);

        return userRoleCodes.stream()
            .map(roleCode -> metadataRegistry.getRole(roleCode))
            .filter(Objects::nonNull)
            .flatMap(role -> role.getPermissionCodes().stream())
            .anyMatch(permCode -> permCode.equals(permissionCode));
    }

    public boolean hasPermission(Principal principal, String permissionCode) {
        return hasPermission(principal.id, permissionCode);
    }

    /**
     * Check if principal has a specific permission by class.
     */
    public boolean hasPermission(Long principalId, Class<? extends PermissionDefinition> permClass) {
        try {
            PermissionDefinition perm = permClass.getDeclaredConstructor().newInstance();
            return hasPermission(principalId, perm.getCode());
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate permission class", e);
        }
    }

    /**
     * Require permission by code.
     */
    public void requirePermission(Long principalId, String permissionCode) {
        if (!hasPermission(principalId, permissionCode)) {
            throw new ForbiddenException("Missing required permission: " + permissionCode);
        }
    }

    public void requirePermission(Principal principal, String permissionCode) {
        requirePermission(principal.id, permissionCode);
    }

    /**
     * Check if principal has a specific role by code.
     */
    public boolean hasRole(Long principalId, String roleCode) {
        return getRoleCodes(principalId).contains(roleCode);
    }

    public boolean hasRole(Principal principal, String roleCode) {
        return hasRole(principal.id, roleCode);
    }

    /**
     * Check if principal has a specific role by class.
     */
    public boolean hasRole(Long principalId, Class<? extends RoleDefinition> roleClass) {
        try {
            RoleDefinition role = roleClass.getDeclaredConstructor().newInstance();
            return hasRole(principalId, role.getCode());
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate role class", e);
        }
    }

    /**
     * Get all role codes assigned to a principal.
     */
    public Set<String> getRoleCodes(Long principalId) {
        return principalRoleRepo.findByPrincipalId(principalId).stream()
            .map(pr -> pr.roleCode)
            .collect(Collectors.toSet());
    }

    /**
     * Get all effective permissions for a principal.
     * This includes all permissions from all their roles.
     */
    public Set<String> getEffectivePermissions(Long principalId) {
        return getRoleCodes(principalId).stream()
            .map(roleCode -> metadataRegistry.getRole(roleCode))
            .filter(Objects::nonNull)
            .flatMap(role -> role.getPermissionCodes().stream())
            .collect(Collectors.toSet());
    }
}
```

### Database Schema

Only store **string codes** in the database:

```sql
-- Principal roles (users assigned to roles)
CREATE TABLE principal_roles (
    principal_id BIGINT NOT NULL,
    role_code VARCHAR(255) NOT NULL,  -- e.g., 'dispatch:manager'
    granted_at TIMESTAMP DEFAULT NOW(),
    granted_by BIGINT,
    tenant_id BIGINT,  -- If role is tenant-scoped
    expires_at TIMESTAMP,  -- Optional: temporary role assignments
    PRIMARY KEY (principal_id, role_code),
    FOREIGN KEY (principal_id) REFERENCES auth_principals(id),
    FOREIGN KEY (granted_by) REFERENCES auth_principals(id)
);

CREATE INDEX idx_principal_roles_principal ON principal_roles(principal_id);
CREATE INDEX idx_principal_roles_role_code ON principal_roles(role_code);
CREATE INDEX idx_principal_roles_tenant ON principal_roles(tenant_id);

-- Optional: Cache table for performance
CREATE TABLE principal_permissions_cache (
    principal_id BIGINT NOT NULL,
    permission_code VARCHAR(255) NOT NULL,  -- e.g., 'dispatch:job:create'
    via_role_code VARCHAR(255) NOT NULL,
    last_updated TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (principal_id, permission_code, via_role_code)
);

CREATE INDEX idx_principal_permissions_principal ON principal_permissions_cache(principal_id);
```

### Updated Entity Model

```java
package tech.flowcatalyst.platform.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "principal_roles")
public class PrincipalRole extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "principal_id", nullable = false)
    public Long principalId;

    @Column(name = "role_code", nullable = false)
    public String roleCode;  // e.g., "dispatch:manager"

    @Column(name = "granted_at", nullable = false)
    public Instant grantedAt = Instant.now();

    @Column(name = "granted_by")
    public Long grantedBy;

    @Column(name = "tenant_id")
    public Long tenantId;  // Null = global role

    @Column(name = "expires_at")
    public Instant expiresAt;  // Null = no expiration

    public static List<PrincipalRole> findByPrincipalId(Long principalId) {
        return list("principalId", principalId);
    }

    public static List<PrincipalRole> findByRoleCode(String roleCode) {
        return list("roleCode", roleCode);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
```

---

## Configuration

### application.properties

```properties
# Enable Jandex indexing for annotation scanning
quarkus.index-dependency.flowcatalyst-dispatch.group-id=tech.flowcatalyst
quarkus.index-dependency.flowcatalyst-dispatch.artifact-id=flowcatalyst-dispatch

quarkus.index-dependency.flowcatalyst-billing.group-id=tech.flowcatalyst
quarkus.index-dependency.flowcatalyst-billing.artifact-id=flowcatalyst-billing

# Auto-index all dependencies in tech.flowcatalyst group
quarkus.arc.auto-inject-index-dependencies=true
```

---

## Best Practices

### 1. Module Organization

Each module should define its permissions/roles in a dedicated security package:

```
flowcatalyst-dispatch/
  src/main/java/tech/flowcatalyst/dispatch/
    security/
      DispatchPermissions.java
      DispatchRoles.java
    service/
      DispatchJobService.java
    resource/
      DispatchJobResource.java
```

### 2. Naming Conventions

**Permissions**:
- Class name: `{Domain}{Action}Permission` (e.g., `JobCreatePermission`)
- Code: `module:domain:action` (e.g., `dispatch:job:create`)
- Actions: `create`, `read`, `update`, `delete`, `list`, `assign`, `approve`, etc.

**Roles**:
- Class name: `{Module}{Purpose}Role` (e.g., `DispatchManagerRole`)
- Code: `module:role` (e.g., `dispatch:manager`)

### 3. Permission Granularity

- Create fine-grained permissions: `billing:invoice:create` vs coarse `billing:admin`
- Compose roles from permissions
- Prefer permission checks over role checks in code (more flexible)

### 4. Platform vs Module Roles

**Platform Roles** (`platform:*`):
- `platform:admin` - Full system access
- `platform:support` - Read-only access across all modules
- `platform:tenant-admin` - Admin within a specific tenant

**Module Roles** (`module:*`):
- `dispatch:manager` - Dispatch operations
- `billing:accountant` - Billing operations
- `warehouse:supervisor` - Warehouse operations

### 5. Testing

```java
@QuarkusTest
public class DispatchPermissionsTest {

    @Inject
    SecurityMetadataRegistry registry;

    @Inject
    AuthorizationService authz;

    @Test
    public void testDispatchManagerRoleHasAllPermissions() {
        RoleDefinition role = registry.getRole(DispatchManagerRole.CODE);

        assertThat(role).isNotNull();
        assertThat(role.getPermissionCodes()).contains(
            DispatchPermissions.JobCreate.CODE,
            DispatchPermissions.JobRead.CODE,
            DispatchPermissions.JobUpdate.CODE,
            DispatchPermissions.JobDelete.CODE
        );
    }

    @Test
    public void testPermissionCheck() {
        Principal principal = createTestPrincipal();
        assignRole(principal, DispatchManagerRole.CODE);

        assertTrue(authz.hasPermission(principal, DispatchPermissions.JobCreate.CODE));
        assertFalse(authz.hasPermission(principal, BillingPermissions.InvoiceCreate.CODE));
    }
}
```

---

## TypeScript SDK (Future)

Similar pattern using TypeScript decorators:

```typescript
// permissions.ts
import { DefinePermission } from '@flowcatalyst/platform';

@DefinePermission()
export class DispatchJobCreatePermission implements PermissionDefinition {
  static readonly CODE = 'dispatch:job:create';

  getCode(): string { return DispatchJobCreatePermission.CODE; }
  getDisplayName(): string { return 'Create Dispatch Jobs'; }
  getDescription(): string { return 'Allows creating dispatch jobs'; }
}

// roles.ts
import { DefineRole } from '@flowcatalyst/platform';

@DefineRole()
export class DispatchManagerRole implements RoleDefinition {
  static readonly CODE = 'dispatch:manager';

  getCode(): string { return DispatchManagerRole.CODE; }
  getDisplayName(): string { return 'Dispatch Manager'; }
  getDescription(): string { return 'Full dispatch access'; }

  getPermissionCodes(): string[] {
    return [
      DispatchJobCreatePermission.CODE,
      DispatchJobReadPermission.CODE,
      // ...
    ];
  }
}

// usage
import { RequiresPermission } from '@flowcatalyst/platform';

class DispatchJobController {
  @RequiresPermission(DispatchJobCreatePermission)
  async createJob(req: Request, res: Response) {
    // Auto-checked by decorator
  }
}
```

---

## PHP SDK (Future)

Similar pattern using PHP 8 attributes:

```php
<?php

namespace FlowCatalyst\Dispatch\Security;

use FlowCatalyst\Platform\Security\{PermissionDefinition, DefinePermission};

#[DefinePermission]
class DispatchJobCreatePermission implements PermissionDefinition
{
    public const CODE = 'dispatch:job:create';

    public function getCode(): string { return self::CODE; }
    public function getDisplayName(): string { return 'Create Dispatch Jobs'; }
    public function getDescription(): string { return 'Allows creating dispatch jobs'; }
}

#[DefineRole]
class DispatchManagerRole implements RoleDefinition
{
    public const CODE = 'dispatch:manager';

    public function getCode(): string { return self::CODE; }
    public function getDisplayName(): string { return 'Dispatch Manager'; }
    public function getDescription(): string { return 'Full dispatch access'; }

    public function getPermissionCodes(): array
    {
        return [
            DispatchJobCreatePermission::CODE,
            DispatchJobReadPermission::CODE,
            // ...
        ];
    }
}

// usage
use FlowCatalyst\Platform\Security\RequiresPermission;

class DispatchJobController
{
    #[RequiresPermission(DispatchJobCreatePermission::class)]
    public function createJob(Request $request): Response
    {
        // Auto-checked by attribute
    }
}
```

---

## Migration Guide

### From Existing RBAC

If you have existing roles/permissions in the database:

1. **Export existing permissions** to code classes
2. **Update `principal_roles` table** to use string codes instead of foreign keys
3. **Remove old tables**: `auth_roles`, `role_permissions`
4. **Run migration script**:

```sql
-- Migrate existing role assignments to string codes
UPDATE principal_roles pr
SET role_code = (SELECT name FROM auth_roles WHERE id = pr.role_id);

-- Drop old foreign key and role_id column
ALTER TABLE principal_roles DROP CONSTRAINT fk_role_id;
ALTER TABLE principal_roles DROP COLUMN role_id;

-- Clean up old tables
DROP TABLE role_permissions;
DROP TABLE auth_roles;
DROP TABLE auth_permissions;
```

---

## Benefits Summary

✅ **Type safety**: Compile-time validation, no magic strings
✅ **IDE support**: Autocomplete for all permissions/roles
✅ **Self-documenting**: Code is the source of truth
✅ **Versioned**: Permissions version with your application code
✅ **Flexible**: No database migrations when adding permissions
✅ **Multi-language**: Same pattern across Java/TypeScript/PHP
✅ **Module isolation**: Each module defines its own security model
✅ **Easy auditing**: Scan annotations to generate permission docs
✅ **Performance**: No joins, simple string lookups
✅ **Multi-tenant friendly**: String codes work naturally with tenant scoping
