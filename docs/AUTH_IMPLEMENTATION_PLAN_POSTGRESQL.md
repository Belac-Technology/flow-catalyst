# Authentication Service Implementation Plan (PostgreSQL)

## Overview

This document provides a detailed implementation plan for completing the authentication service in FlowCatalyst.

**Database:** PostgreSQL with Hibernate ORM Panache
**Authentication Methods:** Internal (email/password), OIDC (Keycloak)
**Authorization Model:** RBAC with future ABAC support
**Primary Keys:** TSID (Time-Sorted ID) using Long type
**Location:** `core/flowcatalyst-platform` module

## Current Status

### ✅ Already Implemented

**Entity Layer (in `model/` package):**
- [x] `Principal` - Unified identity for users and service accounts
- [x] `UserIdentity` - Embedded in Principal for user authentication
- [x] `ServiceAccount` - Embedded in Principal for service-to-service auth
- [x] `Tenant` - Customer organizations
- [x] `Role` - Application-level roles with JSONB permissions
- [x] `Permission` - Permission definition (embedded in Role as JSON)
- [x] `PrincipalRole` - Role assignments to principals
- [x] `TenantAccessGrant` - Explicit multi-tenant access grants
- [x] `AnchorDomain` - Email domains with global tenant access
- [x] `IdpRoleMapping` - IDP role authorization mappings
- [x] Enums: `PrincipalType`, `IdpType`

**Repository Layer (in `repository/` package):**
- [x] `PrincipalRepository` - Basic queries (findByEmail, findByClientId, findByExternalIdpId)
- [x] `TenantRepository` - Basic tenant queries
- [x] `RoleRepository` - Basic role queries
- [x] `PrincipalRoleRepository` - Role assignment queries
- [x] `TenantAccessGrantRepository` - Tenant access queries
- [x] `AnchorDomainRepository` - Anchor domain queries
- [x] `IdpRoleMappingRepository` - IDP role mapping queries

**Service Layer (in `service/` package):**
- [x] `AuthenticationService` - Skeleton with TODO comments
- [x] `AuthorizationService` - Skeleton with TODO comments

### ❌ Not Yet Implemented

**Missing Entity:**
- [ ] `TenantAuthConfig` - Authentication provider configuration per email domain

**Database Migrations:**
- [ ] Flyway SQL migrations for all tables
- [ ] Indexes for performance
- [ ] Initial data seeding scripts

**Service Layer:**
- [ ] All service implementations (skeletons exist but no logic)
- [ ] `TenantAccessService` - Calculate accessible tenants
- [ ] `TokenService` - JWT generation/validation
- [ ] `OidcSyncService` - OIDC user and role synchronization
- [ ] `UserService`, `TenantService`, `RoleService`, `ServiceAccountService` - CRUD operations

**Security Layer:**
- [ ] `SecurityContext` - Thread-local principal storage
- [ ] Security filters - Request/response filters
- [ ] Identity providers - Quarkus security integration
- [ ] Password hashing utilities

**REST Resources:**
- [ ] Authentication endpoints (login, logout, switch-tenant)
- [ ] Admin endpoints (users, tenants, roles, service-accounts, IDP role mappings)

**Configuration:**
- [ ] `AuthConfig` - @ConfigMapping for auth properties
- [ ] application.properties configuration

**Bootstrap:**
- [ ] `RoleBootstrap` - Seed default roles on startup
- [ ] Initial admin user setup

**Exception Handling:**
- [ ] Custom exception classes
- [ ] JAX-RS exception mappers

**Testing:**
- [ ] Unit tests for services
- [ ] Integration tests for auth flows
- [ ] Security tests

**Documentation:**
- [ ] Update auth-architecture.md to remove MongoDB references

---

## Implementation Plan

## Phase 1: Database Schema & Migrations

### 1.1 Create TenantAuthConfig Entity

**File:** `model/TenantAuthConfig.java`

**Purpose:** Store authentication provider configuration per email domain. This enforces SSO policy - users cannot choose their auth method, it's determined by their email domain.

**Implementation:**

```java
package tech.flowcatalyst.platform.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "auth_tenant_auth_config",
    indexes = {
        @Index(name = "idx_auth_tenant_auth_config_domain", columnList = "email_domain", unique = true)
    }
)
public class TenantAuthConfig extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(name = "email_domain", unique = true, nullable = false, length = 255)
    public String emailDomain;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 20)
    public AuthProvider authProvider;

    // OIDC configuration (nullable - only used if authProvider = OIDC)
    @Column(name = "oidc_issuer_url", length = 500)
    public String oidcIssuerUrl;

    @Column(name = "oidc_client_id", length = 255)
    public String oidcClientId;

    @Column(name = "oidc_client_secret_encrypted", length = 1000)
    public String oidcClientSecretEncrypted;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = tech.flowcatalyst.platform.util.TsidGenerator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public TenantAuthConfig() {
    }
}
```

**Enum:**

```java
package tech.flowcatalyst.platform.model;

public enum AuthProvider {
    INTERNAL,  // Email/password authentication
    OIDC       // OpenID Connect (Keycloak, Auth0, etc.)
}
```

**Repository:**

```java
package tech.flowcatalyst.platform.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.platform.model.TenantAuthConfig;

import java.util.Optional;

@ApplicationScoped
public class TenantAuthConfigRepository implements PanacheRepositoryBase<TenantAuthConfig, Long> {

    public Optional<TenantAuthConfig> findByEmailDomain(String emailDomain) {
        return find("emailDomain", emailDomain).firstResultOptional();
    }
}
```

### 1.2 Create Flyway Migrations

**Location:** `core/flowcatalyst-platform/src/main/resources/db/migration/`

**File:** `V1_001__create_auth_tables.sql`

```sql
-- Tenants table
CREATE TABLE IF NOT EXISTS auth_tenants (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) UNIQUE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auth_tenant_slug ON auth_tenants(slug);
CREATE INDEX idx_auth_tenant_status ON auth_tenants(status);

-- Principals table (users + service accounts)
CREATE TABLE IF NOT EXISTS auth_principals (
    id BIGINT PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    tenant_id BIGINT REFERENCES auth_tenants(id),
    name VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- User Identity fields (embedded)
    user_email VARCHAR(255),
    user_email_domain VARCHAR(255),
    user_idp_type VARCHAR(20),
    user_external_idp_id VARCHAR(255),
    user_password_hash VARCHAR(500),
    user_last_login_at TIMESTAMP,

    -- Service Account fields (embedded)
    sa_client_id VARCHAR(255),
    sa_client_secret_hash VARCHAR(500),
    sa_last_used_at TIMESTAMP,

    CONSTRAINT chk_principal_type CHECK (type IN ('USER', 'SERVICE')),
    CONSTRAINT chk_user_email_unique UNIQUE NULLS NOT DISTINCT (user_email),
    CONSTRAINT chk_sa_client_id_unique UNIQUE NULLS NOT DISTINCT (sa_client_id)
);

CREATE INDEX idx_auth_principal_tenant_id ON auth_principals(tenant_id);
CREATE INDEX idx_auth_principal_type ON auth_principals(type);
CREATE INDEX idx_auth_principal_user_email ON auth_principals(user_email);
CREATE INDEX idx_auth_principal_sa_client_id ON auth_principals(sa_client_id);
CREATE INDEX idx_auth_principal_user_external_idp_id ON auth_principals(user_external_idp_id);

-- Anchor Domains table (email domains with global access)
CREATE TABLE IF NOT EXISTS auth_anchor_domains (
    id BIGINT PRIMARY KEY,
    email_domain VARCHAR(255) UNIQUE NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auth_anchor_domain_email ON auth_anchor_domains(email_domain);

-- Tenant Auth Config table (authentication provider per domain)
CREATE TABLE IF NOT EXISTS auth_tenant_auth_config (
    id BIGINT PRIMARY KEY,
    email_domain VARCHAR(255) UNIQUE NOT NULL,
    auth_provider VARCHAR(20) NOT NULL,
    oidc_issuer_url VARCHAR(500),
    oidc_client_id VARCHAR(255),
    oidc_client_secret_encrypted VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_auth_provider CHECK (auth_provider IN ('INTERNAL', 'OIDC'))
);

CREATE INDEX idx_auth_tenant_auth_config_domain ON auth_tenant_auth_config(email_domain);

-- Tenant Access Grants table (explicit multi-tenant access for partners)
CREATE TABLE IF NOT EXISTS auth_tenant_access_grants (
    id BIGINT PRIMARY KEY,
    principal_id BIGINT NOT NULL REFERENCES auth_principals(id),
    tenant_id BIGINT NOT NULL REFERENCES auth_tenants(id),
    granted_at TIMESTAMP NOT NULL DEFAULT NOW(),
    granted_by BIGINT REFERENCES auth_principals(id),
    expires_at TIMESTAMP,
    notes TEXT,

    CONSTRAINT uk_principal_tenant UNIQUE (principal_id, tenant_id)
);

CREATE INDEX idx_auth_tag_principal_id ON auth_tenant_access_grants(principal_id);
CREATE INDEX idx_auth_tag_tenant_id ON auth_tenant_access_grants(tenant_id);

-- Roles table (application-level roles with JSONB permissions)
CREATE TABLE IF NOT EXISTS auth_roles (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    description VARCHAR(500),
    is_system BOOLEAN NOT NULL DEFAULT FALSE,
    permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auth_role_name ON auth_roles(name);

-- Principal Roles table (role assignments)
CREATE TABLE IF NOT EXISTS auth_principal_roles (
    id BIGINT PRIMARY KEY,
    principal_id BIGINT NOT NULL REFERENCES auth_principals(id),
    role_id BIGINT NOT NULL REFERENCES auth_roles(id),
    source VARCHAR(20) NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT NOW(),
    granted_by BIGINT REFERENCES auth_principals(id),

    CONSTRAINT uk_principal_role UNIQUE (principal_id, role_id),
    CONSTRAINT chk_role_source CHECK (source IN ('MANUAL', 'IDP', 'SYSTEM'))
);

CREATE INDEX idx_auth_principal_role_principal_id ON auth_principal_roles(principal_id);
CREATE INDEX idx_auth_principal_role_role_id ON auth_principal_roles(role_id);

-- IDP Role Mappings table (CRITICAL SECURITY: explicit IDP role authorization)
CREATE TABLE IF NOT EXISTS auth_idp_role_mappings (
    id BIGINT PRIMARY KEY,
    idp_role_name VARCHAR(255) UNIQUE NOT NULL,
    internal_role_id BIGINT NOT NULL REFERENCES auth_roles(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auth_idp_role_mapping_idp_name ON auth_idp_role_mappings(idp_role_name);
CREATE INDEX idx_auth_idp_role_mapping_internal_role ON auth_idp_role_mappings(internal_role_id);

-- Tenant Notes table (additional tenant metadata)
CREATE TABLE IF NOT EXISTS auth_tenant_notes (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES auth_tenants(id),
    note_type VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by BIGINT REFERENCES auth_principals(id)
);

CREATE INDEX idx_auth_tenant_note_tenant_id ON auth_tenant_notes(tenant_id);
```

**File:** `V1_002__seed_default_roles.sql`

```sql
-- Seed default roles with permissions
INSERT INTO auth_roles (id, name, description, is_system, permissions, created_at)
VALUES
  (1, 'platform-admin', 'Full system access', true,
   '[
     {"resource":"tenant","action":"create","description":"Create new tenant organizations"},
     {"resource":"tenant","action":"read","description":"List accessible tenants"},
     {"resource":"tenant","action":"update","description":"Update tenant settings"},
     {"resource":"tenant","action":"delete","description":"Delete a tenant"},
     {"resource":"dispatch-job","action":"create","description":"Create dispatch jobs"},
     {"resource":"dispatch-job","action":"read","description":"View dispatch jobs"},
     {"resource":"dispatch-job","action":"update","description":"Modify dispatch jobs"},
     {"resource":"dispatch-job","action":"delete","description":"Delete dispatch jobs"},
     {"resource":"dispatch-job","action":"execute","description":"Execute dispatch jobs"},
     {"resource":"user","action":"create","description":"Create users"},
     {"resource":"user","action":"read","description":"View users"},
     {"resource":"user","action":"update","description":"Modify users"},
     {"resource":"user","action":"delete","description":"Delete users"},
     {"resource":"role","action":"create","description":"Create roles"},
     {"resource":"role","action":"read","description":"View roles"},
     {"resource":"role","action":"update","description":"Modify roles"},
     {"resource":"role","action":"delete","description":"Delete roles"},
     {"resource":"service-account","action":"create","description":"Create service accounts"},
     {"resource":"service-account","action":"read","description":"View service accounts"},
     {"resource":"service-account","action":"update","description":"Modify service accounts"},
     {"resource":"service-account","action":"delete","description":"Delete service accounts"},
     {"resource":"idp-role-mapping","action":"create","description":"Authorize IDP roles"},
     {"resource":"idp-role-mapping","action":"read","description":"View IDP role mappings"},
     {"resource":"idp-role-mapping","action":"delete","description":"Revoke IDP role authorizations"}
   ]'::jsonb, NOW()),

  (2, 'tenant-admin', 'Tenant administrator', true,
   '[
     {"resource":"dispatch-job","action":"create","description":"Create dispatch jobs"},
     {"resource":"dispatch-job","action":"read","description":"View dispatch jobs"},
     {"resource":"dispatch-job","action":"update","description":"Modify dispatch jobs"},
     {"resource":"dispatch-job","action":"delete","description":"Delete dispatch jobs"},
     {"resource":"dispatch-job","action":"execute","description":"Execute dispatch jobs"},
     {"resource":"user","action":"read","description":"View users"},
     {"resource":"user","action":"update","description":"Modify users"}
   ]'::jsonb, NOW()),

  (3, 'operator', 'Job operator', true,
   '[
     {"resource":"dispatch-job","action":"read","description":"View dispatch jobs"},
     {"resource":"dispatch-job","action":"execute","description":"Execute dispatch jobs"}
   ]'::jsonb, NOW()),

  (4, 'viewer', 'Read-only access', true,
   '[
     {"resource":"dispatch-job","action":"read","description":"View dispatch jobs"}
   ]'::jsonb, NOW());
```

## Phase 2: Service Layer Implementation

### 2.1 TenantAccessService

**File:** `service/TenantAccessService.java`

**Purpose:** Calculate which tenants a principal can access based on:
1. Anchor domain membership (global access)
2. Home tenant (principal.tenantId)
3. Explicit grants (TenantAccessGrant)

**Implementation:**

```java
package tech.flowcatalyst.platform.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.flowcatalyst.platform.model.Principal;
import tech.flowcatalyst.platform.model.PrincipalType;
import tech.flowcatalyst.platform.model.Tenant;
import tech.flowcatalyst.platform.model.TenantAccessGrant;
import tech.flowcatalyst.platform.repository.AnchorDomainRepository;
import tech.flowcatalyst.platform.repository.TenantAccessGrantRepository;
import tech.flowcatalyst.platform.repository.TenantRepository;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class TenantAccessService {

    @Inject
    TenantRepository tenantRepo;

    @Inject
    AnchorDomainRepository anchorDomainRepo;

    @Inject
    TenantAccessGrantRepository grantRepo;

    /**
     * Calculate which tenants a principal can access.
     *
     * Rules:
     * 1. Anchor domain users → ALL tenants
     * 2. Home tenant (principal.tenantId) if exists
     * 3. Explicitly granted tenants (TenantAccessGrant)
     */
    public Set<Long> getAccessibleTenants(Principal principal) {
        Set<Long> tenantIds = new HashSet<>();

        // 1. Check if anchor domain user (global access)
        if (principal.type == PrincipalType.USER && principal.userIdentity != null) {
            String domain = principal.userIdentity.emailDomain;
            if (anchorDomainRepo.existsByDomain(domain)) {
                // Return ALL active tenant IDs
                return tenantRepo.findAllActive().stream()
                    .map(t -> t.id)
                    .collect(java.util.stream.Collectors.toSet());
            }
        }

        // 2. Add home tenant if exists
        if (principal.tenantId != null) {
            tenantIds.add(principal.tenantId);
        }

        // 3. Add explicitly granted tenants (filter expired grants)
        List<TenantAccessGrant> grants = grantRepo.findByPrincipalId(principal.id);
        Instant now = Instant.now();

        grants.stream()
            .filter(g -> g.expiresAt == null || g.expiresAt.isAfter(now))
            .map(g -> g.tenantId)
            .forEach(tenantIds::add);

        return tenantIds;
    }

    /**
     * Check if principal can access a specific tenant.
     */
    public boolean canAccessTenant(Principal principal, Long tenantId) {
        return getAccessibleTenants(principal).contains(tenantId);
    }
}
```

**Add to TenantRepository:**

```java
public List<Tenant> findAllActive() {
    return find("status", TenantStatus.ACTIVE).list();
}
```

**Add to AnchorDomainRepository:**

```java
public boolean existsByDomain(String domain) {
    return find("emailDomain", domain).count() > 0;
}
```

### 2.2 Password Hashing Utilities

**File:** `service/PasswordService.java`

**Implementation:**

```java
package tech.flowcatalyst.platform.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.wildfly.security.password.Password;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.WildFlyElytronPasswordProvider;
import org.wildfly.security.password.interfaces.BCryptPassword;
import org.wildfly.security.password.spec.EncryptablePasswordSpec;
import org.wildfly.security.password.spec.IteratedSaltedPasswordAlgorithmSpec;
import org.wildfly.security.password.util.ModularCrypt;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

/**
 * Password hashing and validation using BCrypt via WildFly Elytron.
 * Quarkus includes Elytron which provides BCrypt support.
 */
@ApplicationScoped
public class PasswordService {

    static {
        // Register WildFly Elytron password provider
        WildFlyElytronPasswordProvider.getInstance();
    }

    private static final String BCRYPT_ALGORITHM = BCryptPassword.ALGORITHM_BCRYPT;
    private static final int BCRYPT_ITERATIONS = 10;

    /**
     * Hash a password using BCrypt.
     */
    public String hashPassword(String plainPassword) {
        try {
            PasswordFactory factory = PasswordFactory.getInstance(BCRYPT_ALGORITHM);

            IteratedSaltedPasswordAlgorithmSpec spec =
                new IteratedSaltedPasswordAlgorithmSpec(BCRYPT_ITERATIONS, new byte[16]);

            EncryptablePasswordSpec encryptSpec =
                new EncryptablePasswordSpec(plainPassword.toCharArray(), spec);

            Password password = factory.generatePassword(encryptSpec);

            return ModularCrypt.encodeAsString(password);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    /**
     * Verify a password against a hash.
     */
    public boolean verifyPassword(String plainPassword, String passwordHash) {
        try {
            PasswordFactory factory = PasswordFactory.getInstance(BCRYPT_ALGORITHM);
            Password userPassword = ModularCrypt.decode(passwordHash);
            Password inputPassword = factory.translate(userPassword);

            return factory.verify(inputPassword, plainPassword.toCharArray());
        } catch (NoSuchAlgorithmException | InvalidKeyException | InvalidKeySpecException e) {
            return false;
        }
    }

    /**
     * Validate password complexity requirements.
     */
    public void validatePasswordComplexity(String password) {
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("Password must be at least 12 characters");
        }

        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = password.chars().anyMatch(ch -> "!@#$%^&*()_+-=[]{}|;:,.<>?".indexOf(ch) >= 0);

        if (!hasUpper || !hasLower || !hasDigit || !hasSpecial) {
            throw new IllegalArgumentException(
                "Password must contain uppercase, lowercase, digit, and special character");
        }
    }
}
```

### 2.3 TokenService (JWT)

**File:** `service/TokenService.java`

**Purpose:** Generate and validate JWT tokens for service accounts and internal auth.

**Implementation:**

```java
package tech.flowcatalyst.platform.service;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@ApplicationScoped
public class TokenService {

    @ConfigProperty(name = "flowcatalyst.auth.jwt.issuer", defaultValue = "flowcatalyst")
    String issuer;

    @ConfigProperty(name = "flowcatalyst.auth.jwt.expiry", defaultValue = "365d")
    Duration defaultExpiry;

    /**
     * Issue a JWT token for a service account or user session.
     */
    public String issueToken(Long principalId, String principalType, Duration expiry) {
        if (expiry == null) {
            expiry = defaultExpiry;
        }

        return Jwt.issuer(issuer)
            .subject(String.valueOf(principalId))
            .claim("type", principalType)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plus(expiry))
            .sign();
    }

    /**
     * Issue a token with role claims.
     */
    public String issueTokenWithRoles(Long principalId, String principalType, Set<String> roles, Duration expiry) {
        if (expiry == null) {
            expiry = defaultExpiry;
        }

        return Jwt.issuer(issuer)
            .subject(String.valueOf(principalId))
            .claim("type", principalType)
            .groups(roles)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plus(expiry))
            .sign();
    }
}
```

## Phase 3: Complete Service Layer

### 3.1 AuthenticationService Implementation

**Responsibilities:**
- Email domain lookup to determine auth method
- Internal email/password authentication
- OIDC authentication flow
- Session management

### 3.2 OidcSyncService (CRITICAL SECURITY)

**Key Security Requirement:** Only IDP roles explicitly listed in `auth_idp_role_mappings` are accepted. Unknown roles are REJECTED and logged.

### 3.3 AuthorizationService Implementation

**Responsibilities:**
- Check if principal has specific permission
- Simple RBAC only - tenant isolation handled in business logic

### 3.4 CRUD Services

- UserService
- TenantService
- RoleService
- ServiceAccountService

## Phase 4: Security Integration

### 4.1 SecurityContext

Thread-local storage for:
- Current principal
- Active tenant ID
- Accessible tenant IDs

### 4.2 Security Filters

- Request filter: Populate SecurityContext
- Response filter: Clear SecurityContext

### 4.3 Identity Providers

- InternalIdentityProvider - email/password
- OidcIdentityAugmentor - OIDC integration
- ServiceTokenValidator - JWT validation

## Phase 5: REST Resources

### 5.1 AuthResource

- POST /auth/lookup - Get auth method for email
- POST /auth/login/internal - Internal login
- GET /auth/login/oidc/redirect - OIDC redirect
- GET /auth/login/oidc/callback - OIDC callback
- POST /auth/logout - Logout
- POST /auth/switch-tenant - Switch active tenant

### 5.2 Admin Resources

- UserAdminResource
- TenantAdminResource
- RoleAdminResource
- ServiceAccountAdminResource
- IdpRoleMappingAdminResource (platform-admin only)

## Phase 6: Configuration & Bootstrap

### 6.1 AuthConfig

@ConfigMapping for all auth properties

### 6.2 RoleBootstrap

Seed roles on startup if not exists (already handled by SQL migration)

## Phase 7: Testing

### 7.1 Unit Tests

- TenantAccessServiceTest
- AuthorizationServiceTest
- PasswordServiceTest
- TokenServiceTest

### 7.2 Integration Tests

- Internal auth flow
- OIDC auth flow
- Tenant isolation
- IDP role authorization (CRITICAL)

### 7.3 Security Tests

- Unauthorized IDP role rejection
- Tenant access validation
- Permission checks
- Password complexity

## Phase 8: Documentation

### 8.1 Update auth-architecture.md

- Remove all MongoDB references
- Update to reflect PostgreSQL/Hibernate ORM
- Update entity diagrams
- Update code examples

### 8.2 README

- Integration guide
- Configuration reference
- Bootstrap instructions
- Security considerations

---

## Implementation Priority

### High Priority (MVP)

1. ✅ Entity models (DONE)
2. ✅ Repositories (DONE)
3. Database migrations
4. TenantAccessService
5. PasswordService
6. AuthenticationService (internal only)
7. AuthorizationService
8. SecurityContext + Filters
9. Basic auth endpoints

### Medium Priority

1. TokenService
2. OidcSyncService
3. OIDC authentication flow
4. Admin resources
5. CRUD services
6. Configuration
7. Caching

### Low Priority (Post-MVP)

1. Advanced features (MFA, WebAuthn)
2. Audit logging
3. Admin UI
4. Additional IDP support (SAML)

---

## Key Design Decisions

### PostgreSQL vs MongoDB

- ✅ **Using PostgreSQL** with Hibernate ORM Panache
- TSID for primary keys (Long type)
- JSONB for permissions (flexible schema)
- Flyway for migrations
- JPA relationships and indexes

### Architecture Simplifications

1. **Roles are global** - Not scoped to tenants
2. **Permissions embedded in roles** - Stored as JSONB for fast loading
3. **Simple RBAC** - Tenant isolation in business logic, not auth system
4. **Anchor domains** - Not anchor tenants
5. **IDP role authorization** - Explicit whitelist prevents unauthorized access

### Security Controls

1. **IDP role authorization table** - Critical security control
2. **Reject unknown IDP roles** - Log as WARNING
3. **Platform admin only** - For IDP role authorizations
4. **Audit all operations** - IDP role mappings, role assignments, tenant grants
5. **Password complexity** - Enforced at service layer
6. **BCrypt/Argon2** - Strong password hashing
7. **HttpOnly, Secure cookies** - Session security

---

## Next Steps

1. **Create TenantAuthConfig entity and repository**
2. **Write Flyway migrations**
3. **Implement TenantAccessService**
4. **Implement PasswordService**
5. **Implement TokenService**
6. **Begin AuthenticationService implementation**

---

**Document Status:** Ready for implementation
**Last Updated:** 2025-11-03
**Database:** PostgreSQL (not MongoDB)