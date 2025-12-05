# Auth IdP Implementation Plan

## Overview

This document outlines the implementation plan for the FlowCatalyst authentication identity provider (IdP) with support for:

- **Embedded Mode**: Full IdP with token issuance, tenant management, federation
- **Remote Mode**: Token validation only, delegates auth to external IdP
- **SPA/Mobile Support**: Authorization Code + PKCE, refresh tokens
- **Tenant Management**: Admin APIs for tenants, users, and auth configuration
- **Role Management**: Code-first roles synced to Auth and external IDPs
- **Platform Admin UI**: Vue.js SPA for tenant/user/role management

---

## Key Concepts

### Roles and Permissions

Roles and permissions are **defined in application code** (code-first) and synced to:
1. Auth module database (always)
2. External IDPs like Keycloak/Entra (if configured)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     APPLICATION CODE                                     │
│                                                                          │
│  RoleDefinition: {subdomain}:{role-name}                                │
│    - platform:tenant-admin                                              │
│    - dispatch:operator                                                   │
│    - logistics:warehouse-manager                                        │
│                                                                          │
│  PermissionRegistry: Source of truth for role→permission mapping        │
└─────────────────────────────────────────────────────────────────────────┘
         │
         │ IdpRoleSyncService.syncNow()
         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     SYNC TARGETS                                         │
│                                                                          │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────┐  │
│  │   AUTH DB       │  │   Keycloak      │  │   Entra (Azure AD)      │  │
│  │   (always)      │  │   (if config'd) │  │   (if config'd)         │  │
│  │                 │  │                 │  │                         │  │
│  │  Role table     │  │  Realm Roles    │  │  App Roles              │  │
│  │  populated      │  │  synced         │  │  synced                 │  │
│  └─────────────────┘  └─────────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### Federated IDP Role Management

Each tenant can configure their external IDP to either:
1. **Manage roles** - Roles come from IDP token, read-only in Auth UI
2. **Authentication only** - IDP authenticates, but we manage roles in Auth

```
TenantAuthConfig
├── emailDomain: "acmecorp.com"
├── authProvider: OIDC
├── oidcIssuerUrl: "https://login.acmecorp.com"
├── idpManagesRoles: true/false  ← Determines role source
└── ...
```

| idpManagesRoles | Role Source | Auth UI Behavior |
|-----------------|-------------|------------------|
| `true` | External IDP token | Read-only (synced on login) |
| `false` | Auth DB | Full CRUD (admin assigns) |

### JWT Token Contents

Tokens include both **roles** and **permissions** (resolved at auth time):

```json
{
  "sub": "principal_id",
  "tenant_id": 12345,
  "email": "user@acmecorp.com",
  "roles": ["dispatch:operator", "platform:viewer"],
  "permissions": [
    "dispatch:job:read",
    "dispatch:job:execute",
    "platform:tenant:view"
  ],
  "iat": 1700000000,
  "exp": 1700003600
}
```

**Permissions are resolved at auth time** using the application's `PermissionRegistry`. Apps can check permissions directly without runtime DB lookups.

### PrincipalRole Source Tracking

```java
public enum RoleSource {
    MANUAL,  // Assigned via Auth UI
    IDP,     // Synced from external IDP token
    SYSTEM   // System-assigned (e.g., default roles)
}
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    flowcatalyst-platform module                          │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │                     ALWAYS ACTIVE (Core)                           │ │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────────┐ │ │
│  │  │ Entities        │  │ Repositories    │  │ Security Filters   │ │ │
│  │  │ - Principal     │  │ - PrincipalRepo │  │ - Token Validation │ │ │
│  │  │ - Tenant        │  │ - TenantRepo    │  │ - SecurityContext  │ │ │
│  │  │ - Role          │  │ - RoleRepo      │  │                    │ │ │
│  │  └─────────────────┘  └─────────────────┘  └────────────────────┘ │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │              EMBEDDED MODE ONLY (when auth.mode=embedded)          │ │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────────┐ │ │
│  │  │ IdP Endpoints   │  │ Admin APIs      │  │ Federation         │ │ │
│  │  │ - /auth/*       │  │ - /admin/users  │  │ - OIDC Redirect    │ │ │
│  │  │ - /oauth/*      │  │ - /admin/tenant │  │ - IdP Sync         │ │ │
│  │  │ - /.well-known  │  │ - /admin/roles  │  │ - Role Mapping     │ │ │
│  │  └─────────────────┘  └─────────────────┘  └────────────────────┘ │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │              REMOTE MODE ONLY (when auth.mode=remote)              │ │
│  │  ┌─────────────────────────────────────────────────────────────┐  │ │
│  │  │ RemoteJwksService - Fetches JWKS from external IdP          │  │ │
│  │  │ RedirectAuthResource - Redirects auth requests to remote    │  │ │
│  │  └─────────────────────────────────────────────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

## Configuration

```properties
# =============================================================================
# Auth Mode Configuration
# =============================================================================

# Mode: "embedded" (full IdP) or "remote" (token validation only)
flowcatalyst.auth.mode=embedded

# =============================================================================
# Embedded Mode Settings (when mode=embedded)
# =============================================================================

# JWT Configuration
flowcatalyst.auth.jwt.issuer=https://auth.example.com
flowcatalyst.auth.jwt.private-key-path=/keys/private.pem
flowcatalyst.auth.jwt.public-key-path=/keys/public.pem

# Token Expiry
flowcatalyst.auth.jwt.access-token-expiry=PT1H
flowcatalyst.auth.jwt.refresh-token-expiry=P30D
flowcatalyst.auth.jwt.session-token-expiry=PT8H
flowcatalyst.auth.jwt.authorization-code-expiry=PT10M

# Session Configuration
flowcatalyst.auth.session.secure=true
flowcatalyst.auth.session.same-site=Strict

# PKCE Configuration
flowcatalyst.auth.pkce.required=true

# =============================================================================
# Remote Mode Settings (when mode=remote)
# =============================================================================

# Remote IdP Configuration
flowcatalyst.auth.remote.issuer=https://external-idp.example.com
flowcatalyst.auth.remote.jwks-url=https://external-idp.example.com/.well-known/jwks.json
flowcatalyst.auth.remote.jwks-cache-duration=PT1H

# Redirect URLs (for auth requests in remote mode)
flowcatalyst.auth.remote.login-url=https://external-idp.example.com/auth/login
flowcatalyst.auth.remote.logout-url=https://external-idp.example.com/auth/logout
```

---

## Phase 1: Conditional Activation Infrastructure

### 1.1 Create Auth Mode Enum and Configuration

**File:** `authentication/AuthMode.java`

```java
package tech.flowcatalyst.platform.authentication;

public enum AuthMode {
    EMBEDDED,  // Full IdP - issues tokens, manages users/tenants
    REMOTE     // Token validation only - delegates to external IdP
}
```

**File:** `authentication/AuthConfig.java`

```java
package tech.flowcatalyst.platform.authentication;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "flowcatalyst.auth")
public interface AuthConfig {

    @WithDefault("embedded")
    AuthMode mode();

    JwtConfig jwt();

    SessionConfig session();

    PkceConfig pkce();

    RemoteConfig remote();

    interface JwtConfig {
        @WithDefault("flowcatalyst")
        String issuer();

        @WithName("private-key-path")
        Optional<String> privateKeyPath();

        @WithName("public-key-path")
        Optional<String> publicKeyPath();

        @WithName("access-token-expiry")
        @WithDefault("PT1H")
        Duration accessTokenExpiry();

        @WithName("refresh-token-expiry")
        @WithDefault("P30D")
        Duration refreshTokenExpiry();

        @WithName("session-token-expiry")
        @WithDefault("PT8H")
        Duration sessionTokenExpiry();

        @WithName("authorization-code-expiry")
        @WithDefault("PT10M")
        Duration authorizationCodeExpiry();
    }

    interface SessionConfig {
        @WithDefault("true")
        boolean secure();

        @WithName("same-site")
        @WithDefault("Strict")
        String sameSite();
    }

    interface PkceConfig {
        @WithDefault("true")
        boolean required();
    }

    interface RemoteConfig {
        Optional<String> issuer();

        @WithName("jwks-url")
        Optional<String> jwksUrl();

        @WithName("jwks-cache-duration")
        @WithDefault("PT1H")
        Duration jwksCacheDuration();

        @WithName("login-url")
        Optional<String> loginUrl();

        @WithName("logout-url")
        Optional<String> logoutUrl();
    }
}
```

### 1.2 Create Conditional Bean Producer

**File:** `authentication/AuthModeProducer.java`

```java
package tech.flowcatalyst.platform.authentication;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * Produces beans based on auth mode configuration.
 * Uses programmatic lookup since @IfBuildProperty requires build-time values.
 */
@ApplicationScoped
public class AuthModeProducer {

    @Inject
    AuthConfig authConfig;

    @Produces
    @ApplicationScoped
    public AuthMode authMode() {
        return authConfig.mode();
    }

    public boolean isEmbeddedMode() {
        return authConfig.mode() == AuthMode.EMBEDDED;
    }

    public boolean isRemoteMode() {
        return authConfig.mode() == AuthMode.REMOTE;
    }
}
```

### 1.3 Update Existing Resources with Conditional Activation

Use `@ActivateRequestContext` and check mode at runtime (since Quarkus `@IfBuildProperty` requires compile-time values, we use runtime checks for flexibility).

**File:** `authentication/EmbeddedModeFilter.java`

```java
package tech.flowcatalyst.platform.authentication;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

/**
 * Filter that blocks IdP endpoints when running in remote mode.
 */
@Provider
@EmbeddedModeOnly
public class EmbeddedModeFilter implements ContainerRequestFilter {

    @Inject
    AuthConfig authConfig;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (authConfig.mode() == AuthMode.REMOTE) {
            requestContext.abortWith(
                Response.status(Response.Status.NOT_FOUND)
                    .entity("IdP endpoints not available in remote mode")
                    .build()
            );
        }
    }
}
```

**File:** `authentication/EmbeddedModeOnly.java`

```java
package tech.flowcatalyst.platform.authentication;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for endpoints that are only available in embedded mode.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface EmbeddedModeOnly {}
```

### 1.4 Annotate Existing IdP Resources

Update existing resources to use the annotation:

```java
@Path("/auth")
@EmbeddedModeOnly  // <-- Add this
public class AuthResource { ... }

@Path("/oauth")
@EmbeddedModeOnly  // <-- Add this
public class OAuth2TokenResource { ... }

@Path("/.well-known")
@EmbeddedModeOnly  // <-- Add this
public class WellKnownResource { ... }
```

---

## Phase 2: Authorization Code + PKCE Flow

### 2.1 Data Models

**File:** `authentication/oauth/AuthorizationCode.java`

```java
package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Stores authorization codes for the OAuth2 authorization code flow.
 * Short-lived (10 minutes) and single-use.
 */
@Entity
@Table(name = "auth_authorization_codes")
public class AuthorizationCode extends PanacheEntityBase {

    @Id
    @Column(length = 64)
    public String code;

    @Column(name = "client_id", nullable = false)
    public String clientId;

    @Column(name = "principal_id", nullable = false)
    public Long principalId;

    @Column(name = "redirect_uri", nullable = false, length = 1000)
    public String redirectUri;

    @Column(name = "scope", length = 500)
    public String scope;

    @Column(name = "code_challenge", length = 128)
    public String codeChallenge;

    @Column(name = "code_challenge_method", length = 10)
    public String codeChallengeMethod;

    @Column(name = "nonce", length = 128)
    public String nonce;

    @Column(name = "state", length = 128)
    public String state;

    @Column(name = "tenant_id")
    public Long tenantId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "used", nullable = false)
    public boolean used = false;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !used && !isExpired();
    }
}
```

**File:** `authentication/oauth/RefreshToken.java`

```java
package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Stores refresh tokens for long-lived sessions.
 * Can be revoked and have a configurable expiry.
 */
@Entity
@Table(name = "auth_refresh_tokens",
    indexes = {
        @Index(name = "idx_refresh_token_principal", columnList = "principal_id"),
        @Index(name = "idx_refresh_token_family", columnList = "token_family")
    }
)
public class RefreshToken extends PanacheEntityBase {

    @Id
    @Column(length = 64)
    public String tokenHash;  // Store hash, not plain token

    @Column(name = "principal_id", nullable = false)
    public Long principalId;

    @Column(name = "client_id")
    public String clientId;

    @Column(name = "tenant_id")
    public Long tenantId;

    @Column(name = "scope", length = 500)
    public String scope;

    /**
     * Token family for refresh token rotation.
     * All tokens in a family are invalidated on reuse detection.
     */
    @Column(name = "token_family", nullable = false, length = 64)
    public String tokenFamily;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    public boolean revoked = false;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    @Column(name = "replaced_by", length = 64)
    public String replacedBy;  // Hash of the new token (for rotation tracking)

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }
}
```

**File:** `authentication/oauth/OAuthClient.java`

```java
package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;

/**
 * OAuth2 client registration for SPAs and mobile apps.
 * Public clients (SPAs/mobile) don't have secrets but must use PKCE.
 */
@Entity
@Table(name = "auth_oauth_clients")
public class OAuthClient extends PanacheEntityBase {

    @Id
    public Long id;  // TSID

    @Column(name = "client_id", unique = true, nullable = false, length = 100)
    public String clientId;

    @Column(name = "client_name", nullable = false, length = 255)
    public String clientName;

    /**
     * Client type: PUBLIC (SPA/mobile) or CONFIDENTIAL (server-side)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 20)
    public ClientType clientType;

    /**
     * Hashed client secret (null for public clients)
     */
    @Column(name = "client_secret_hash", length = 500)
    public String clientSecretHash;

    /**
     * Allowed redirect URIs (comma-separated or JSON array)
     */
    @Column(name = "redirect_uris", nullable = false, length = 2000)
    public String redirectUris;

    /**
     * Allowed grant types (comma-separated)
     * e.g., "authorization_code,refresh_token"
     */
    @Column(name = "grant_types", nullable = false, length = 200)
    public String grantTypes;

    /**
     * Default scopes for this client
     */
    @Column(name = "default_scopes", length = 500)
    public String defaultScopes;

    /**
     * Whether PKCE is required (always true for public clients)
     */
    @Column(name = "pkce_required", nullable = false)
    public boolean pkceRequired = true;

    @Column(name = "tenant_id")
    public Long tenantId;  // Optional: restrict client to specific tenant

    @Column(name = "active", nullable = false)
    public boolean active = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isRedirectUriAllowed(String uri) {
        if (redirectUris == null) return false;
        for (String allowed : redirectUris.split(",")) {
            if (allowed.trim().equals(uri)) return true;
        }
        return false;
    }

    public boolean isGrantTypeAllowed(String grantType) {
        if (grantTypes == null) return false;
        for (String allowed : grantTypes.split(",")) {
            if (allowed.trim().equals(grantType)) return true;
        }
        return false;
    }

    public enum ClientType {
        PUBLIC,       // SPA, mobile app (no secret, PKCE required)
        CONFIDENTIAL  // Server-side app (has secret)
    }
}
```

### 2.2 Repositories

**File:** `authentication/oauth/AuthorizationCodeRepository.java`

```java
package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class AuthorizationCodeRepository implements PanacheRepositoryBase<AuthorizationCode, String> {

    public Optional<AuthorizationCode> findValidCode(String code) {
        return find("code = ?1 AND used = false AND expiresAt > ?2", code, Instant.now())
            .firstResultOptional();
    }

    @Transactional
    public void markAsUsed(String code) {
        update("used = true WHERE code = ?1", code);
    }

    @Transactional
    public long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }
}
```

**File:** `authentication/oauth/RefreshTokenRepository.java`

```java
package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class RefreshTokenRepository implements PanacheRepositoryBase<RefreshToken, String> {

    public Optional<RefreshToken> findValidToken(String tokenHash) {
        return find("tokenHash = ?1 AND revoked = false AND expiresAt > ?2",
            tokenHash, Instant.now()).firstResultOptional();
    }

    @Transactional
    public void revokeToken(String tokenHash, String replacedBy) {
        update("revoked = true, revokedAt = ?1, replacedBy = ?2 WHERE tokenHash = ?3",
            Instant.now(), replacedBy, tokenHash);
    }

    @Transactional
    public void revokeTokenFamily(String tokenFamily) {
        update("revoked = true, revokedAt = ?1 WHERE tokenFamily = ?2 AND revoked = false",
            Instant.now(), tokenFamily);
    }

    @Transactional
    public void revokeAllForPrincipal(Long principalId) {
        update("revoked = true, revokedAt = ?1 WHERE principalId = ?2 AND revoked = false",
            Instant.now(), principalId);
    }

    @Transactional
    public long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }
}
```

**File:** `authentication/oauth/OAuthClientRepository.java`

```java
package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class OAuthClientRepository implements PanacheRepositoryBase<OAuthClient, Long> {

    public Optional<OAuthClient> findByClientId(String clientId) {
        return find("clientId = ?1 AND active = true", clientId).firstResultOptional();
    }
}
```

### 2.3 PKCE Service

**File:** `authentication/oauth/PkceService.java`

```java
package tech.flowcatalyst.platform.authentication.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE (Proof Key for Code Exchange) implementation.
 * Required for public clients (SPAs, mobile apps).
 */
@ApplicationScoped
public class PkceService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Generate a cryptographically random code verifier.
     * Length: 43-128 characters (we use 64).
     */
    public String generateCodeVerifier() {
        byte[] bytes = new byte[48];  // 48 bytes = 64 base64url chars
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Generate code challenge from verifier using S256 method.
     */
    public String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Verify that the code verifier matches the challenge.
     *
     * @param codeVerifier The verifier provided in token request
     * @param codeChallenge The challenge stored with authorization code
     * @param method Challenge method (plain or S256)
     * @return true if valid
     */
    public boolean verifyCodeChallenge(String codeVerifier, String codeChallenge, String method) {
        if (codeVerifier == null || codeChallenge == null) {
            return false;
        }

        if ("plain".equals(method)) {
            return codeVerifier.equals(codeChallenge);
        } else if ("S256".equals(method) || method == null) {
            // S256 is default
            String computed = generateCodeChallenge(codeVerifier);
            return computed.equals(codeChallenge);
        }

        return false;
    }
}
```

### 2.4 Authorization Endpoint

**File:** `authentication/oauth/AuthorizationResource.java`

```java
package tech.flowcatalyst.platform.authentication.oauth;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import tech.flowcatalyst.platform.authentication.AuthConfig;
import tech.flowcatalyst.platform.authentication.EmbeddedModeOnly;
import tech.flowcatalyst.platform.authentication.JwtKeyService;
import tech.flowcatalyst.platform.authorization.PrincipalRole;
import tech.flowcatalyst.platform.authorization.PrincipalRoleRepository;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalRepository;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OAuth2 Authorization Endpoint.
 * Handles the authorization code flow for SPAs and mobile apps.
 */
@Path("/oauth")
@Tag(name = "OAuth2", description = "OAuth2 authorization endpoints")
@EmbeddedModeOnly
public class AuthorizationResource {

    private static final Logger LOG = Logger.getLogger(AuthorizationResource.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    AuthConfig authConfig;

    @Inject
    OAuthClientRepository clientRepo;

    @Inject
    AuthorizationCodeRepository codeRepo;

    @Inject
    RefreshTokenRepository refreshTokenRepo;

    @Inject
    PrincipalRepository principalRepo;

    @Inject
    PrincipalRoleRepository roleRepo;

    @Inject
    JwtKeyService jwtKeyService;

    @Inject
    PkceService pkceService;

    @Context
    UriInfo uriInfo;

    /**
     * Authorization endpoint - initiates the authorization code flow.
     *
     * GET /oauth/authorize?
     *   response_type=code
     *   &client_id=spa-client
     *   &redirect_uri=https://app.example.com/callback
     *   &scope=openid profile
     *   &state=xyz
     *   &code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
     *   &code_challenge_method=S256
     */
    @GET
    @Path("/authorize")
    @Operation(summary = "Start authorization code flow")
    public Response authorize(
            @QueryParam("response_type") String responseType,
            @QueryParam("client_id") String clientId,
            @QueryParam("redirect_uri") String redirectUri,
            @QueryParam("scope") String scope,
            @QueryParam("state") String state,
            @QueryParam("code_challenge") String codeChallenge,
            @QueryParam("code_challenge_method") @DefaultValue("S256") String codeChallengeMethod,
            @QueryParam("nonce") String nonce,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken
    ) {
        // Validate response_type
        if (!"code".equals(responseType)) {
            return errorRedirect(redirectUri, "unsupported_response_type",
                "Only 'code' response type is supported", state);
        }

        // Validate client
        Optional<OAuthClient> clientOpt = clientRepo.findByClientId(clientId);
        if (clientOpt.isEmpty()) {
            return errorRedirect(redirectUri, "invalid_client", "Unknown client_id", state);
        }

        OAuthClient client = clientOpt.get();

        // Validate redirect_uri
        if (!client.isRedirectUriAllowed(redirectUri)) {
            // Don't redirect to untrusted URI - return error directly
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "invalid_redirect_uri"))
                .build();
        }

        // Validate PKCE for public clients
        if (client.clientType == OAuthClient.ClientType.PUBLIC || client.pkceRequired) {
            if (codeChallenge == null || codeChallenge.isEmpty()) {
                return errorRedirect(redirectUri, "invalid_request",
                    "code_challenge required for this client", state);
            }
        }

        // Check if user is already authenticated
        if (sessionToken == null || sessionToken.isEmpty()) {
            // Redirect to login page with original params
            String loginUrl = buildLoginUrl(responseType, clientId, redirectUri, scope,
                state, codeChallenge, codeChallengeMethod, nonce);
            return Response.seeOther(URI.create(loginUrl)).build();
        }

        // Validate session and get principal
        Long principalId = validateSessionAndGetPrincipalId(sessionToken);
        if (principalId == null) {
            String loginUrl = buildLoginUrl(responseType, clientId, redirectUri, scope,
                state, codeChallenge, codeChallengeMethod, nonce);
            return Response.seeOther(URI.create(loginUrl)).build();
        }

        // Generate authorization code
        String code = generateAuthorizationCode();

        // Store authorization code
        AuthorizationCode authCode = new AuthorizationCode();
        authCode.code = code;
        authCode.clientId = clientId;
        authCode.principalId = principalId;
        authCode.redirectUri = redirectUri;
        authCode.scope = scope;
        authCode.codeChallenge = codeChallenge;
        authCode.codeChallengeMethod = codeChallengeMethod;
        authCode.nonce = nonce;
        authCode.state = state;
        authCode.expiresAt = Instant.now().plus(authConfig.jwt().authorizationCodeExpiry());

        codeRepo.persist(authCode);

        // Redirect with code
        String callback = redirectUri + "?code=" + code;
        if (state != null) {
            callback += "&state=" + state;
        }

        LOG.infof("Authorization code issued for client %s, principal %d", clientId, principalId);
        return Response.seeOther(URI.create(callback)).build();
    }

    /**
     * Token endpoint - exchanges authorization code for tokens.
     * Also handles refresh_token grant.
     */
    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(summary = "Exchange code for tokens or refresh tokens")
    public Response token(
            @HeaderParam("Authorization") String authHeader,
            @FormParam("grant_type") String grantType,
            @FormParam("code") String code,
            @FormParam("redirect_uri") String redirectUri,
            @FormParam("client_id") String formClientId,
            @FormParam("client_secret") String formClientSecret,
            @FormParam("code_verifier") String codeVerifier,
            @FormParam("refresh_token") String refreshToken,
            @FormParam("scope") String scope
    ) {
        if (grantType == null) {
            return tokenError("invalid_request", "grant_type is required");
        }

        return switch (grantType) {
            case "authorization_code" -> handleAuthorizationCodeGrant(
                authHeader, code, redirectUri, formClientId, formClientSecret, codeVerifier);
            case "refresh_token" -> handleRefreshTokenGrant(
                authHeader, refreshToken, formClientId, formClientSecret, scope);
            case "client_credentials" -> handleClientCredentialsGrant(
                authHeader, formClientId, formClientSecret, scope);
            default -> tokenError("unsupported_grant_type", "Grant type not supported: " + grantType);
        };
    }

    private Response handleAuthorizationCodeGrant(
            String authHeader, String code, String redirectUri,
            String formClientId, String formClientSecret, String codeVerifier) {

        if (code == null) {
            return tokenError("invalid_request", "code is required");
        }

        // Find authorization code
        Optional<AuthorizationCode> codeOpt = codeRepo.findValidCode(code);
        if (codeOpt.isEmpty()) {
            return tokenError("invalid_grant", "Invalid or expired authorization code");
        }

        AuthorizationCode authCode = codeOpt.get();

        // Validate client
        String clientId = formClientId != null ? formClientId : parseClientIdFromAuth(authHeader);
        if (!authCode.clientId.equals(clientId)) {
            return tokenError("invalid_grant", "Client mismatch");
        }

        Optional<OAuthClient> clientOpt = clientRepo.findByClientId(clientId);
        if (clientOpt.isEmpty()) {
            return tokenError("invalid_client", "Unknown client");
        }

        OAuthClient client = clientOpt.get();

        // Validate redirect_uri matches
        if (!authCode.redirectUri.equals(redirectUri)) {
            return tokenError("invalid_grant", "redirect_uri mismatch");
        }

        // Validate PKCE
        if (authCode.codeChallenge != null) {
            if (codeVerifier == null) {
                return tokenError("invalid_grant", "code_verifier required");
            }
            if (!pkceService.verifyCodeChallenge(codeVerifier, authCode.codeChallenge,
                    authCode.codeChallengeMethod)) {
                return tokenError("invalid_grant", "Invalid code_verifier");
            }
        }

        // Validate client secret for confidential clients
        if (client.clientType == OAuthClient.ClientType.CONFIDENTIAL) {
            // TODO: Verify client secret
        }

        // Mark code as used (single use)
        codeRepo.markAsUsed(code);

        // Load principal and roles
        Optional<Principal> principalOpt = principalRepo.findByIdOptional(authCode.principalId);
        if (principalOpt.isEmpty()) {
            return tokenError("invalid_grant", "User not found");
        }

        Principal principal = principalOpt.get();
        Set<String> roles = loadRoles(principal.id);

        // Issue tokens
        String accessToken = jwtKeyService.issueSessionToken(principal.id,
            principal.userIdentity != null ? principal.userIdentity.email : null, roles);

        // Generate refresh token
        String refreshTokenValue = generateRefreshToken();
        String tokenFamily = generateTokenFamily();
        storeRefreshToken(refreshTokenValue, principal.id, clientId, authCode.tenantId,
            authCode.scope, tokenFamily);

        LOG.infof("Tokens issued for principal %d via authorization_code grant", principal.id);

        return Response.ok(new TokenResponse(
            accessToken,
            "Bearer",
            authConfig.jwt().sessionTokenExpiry().toSeconds(),
            refreshTokenValue,
            authCode.scope
        )).build();
    }

    private Response handleRefreshTokenGrant(
            String authHeader, String refreshToken, String clientId, String clientSecret, String scope) {

        if (refreshToken == null) {
            return tokenError("invalid_request", "refresh_token is required");
        }

        String tokenHash = hashToken(refreshToken);
        Optional<RefreshToken> tokenOpt = refreshTokenRepo.findValidToken(tokenHash);

        if (tokenOpt.isEmpty()) {
            // Check if this is a reused token (potential theft)
            RefreshToken reusedToken = refreshTokenRepo.find("tokenHash", tokenHash).firstResult();
            if (reusedToken != null && reusedToken.revoked) {
                // Token reuse detected - revoke entire family
                LOG.warnf("Refresh token reuse detected! Revoking token family: %s",
                    reusedToken.tokenFamily);
                refreshTokenRepo.revokeTokenFamily(reusedToken.tokenFamily);
            }
            return tokenError("invalid_grant", "Invalid or expired refresh token");
        }

        RefreshToken token = tokenOpt.get();

        // Load principal and roles
        Optional<Principal> principalOpt = principalRepo.findByIdOptional(token.principalId);
        if (principalOpt.isEmpty() || !principalOpt.get().active) {
            return tokenError("invalid_grant", "User not found or inactive");
        }

        Principal principal = principalOpt.get();
        Set<String> roles = loadRoles(principal.id);

        // Issue new access token
        String accessToken = jwtKeyService.issueSessionToken(principal.id,
            principal.userIdentity != null ? principal.userIdentity.email : null, roles);

        // Rotate refresh token
        String newRefreshToken = generateRefreshToken();
        String newTokenHash = hashToken(newRefreshToken);

        // Revoke old token and link to new one
        refreshTokenRepo.revokeToken(tokenHash, newTokenHash);

        // Store new token in same family
        storeRefreshToken(newRefreshToken, principal.id, token.clientId,
            token.tenantId, token.scope, token.tokenFamily);

        LOG.infof("Tokens refreshed for principal %d", principal.id);

        return Response.ok(new TokenResponse(
            accessToken,
            "Bearer",
            authConfig.jwt().sessionTokenExpiry().toSeconds(),
            newRefreshToken,
            token.scope
        )).build();
    }

    private Response handleClientCredentialsGrant(
            String authHeader, String clientId, String clientSecret, String scope) {
        // Delegate to existing OAuth2TokenResource logic
        // This is for service accounts, already implemented
        return tokenError("unsupported_grant_type",
            "Use /oauth/token endpoint for client_credentials");
    }

    // Helper methods

    private String generateAuthorizationCode() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateTokenFamily() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void storeRefreshToken(String token, Long principalId, String clientId,
            Long tenantId, String scope, String tokenFamily) {
        RefreshToken rt = new RefreshToken();
        rt.tokenHash = hashToken(token);
        rt.principalId = principalId;
        rt.clientId = clientId;
        rt.tenantId = tenantId;
        rt.scope = scope;
        rt.tokenFamily = tokenFamily;
        rt.expiresAt = Instant.now().plus(authConfig.jwt().refreshTokenExpiry());
        refreshTokenRepo.persist(rt);
    }

    private Set<String> loadRoles(Long principalId) {
        return roleRepo.findByPrincipalId(principalId).stream()
            .map(pr -> pr.roleName)
            .collect(Collectors.toSet());
    }

    private Long validateSessionAndGetPrincipalId(String sessionToken) {
        try {
            io.smallrye.jwt.auth.principal.JWTParser parser =
                io.smallrye.jwt.auth.principal.JWTParser.create();
            org.eclipse.microprofile.jwt.JsonWebToken jwt = parser.parse(sessionToken);
            return Long.parseLong(jwt.getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    private String parseClientIdFromAuth(String authHeader) {
        // Parse from Basic auth header
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(
                    authHeader.substring(6)), java.nio.charset.StandardCharsets.UTF_8);
                return decoded.split(":")[0];
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private String buildLoginUrl(String responseType, String clientId, String redirectUri,
            String scope, String state, String codeChallenge, String codeChallengeMethod, String nonce) {
        // Build URL to login page with all OAuth params preserved
        StringBuilder url = new StringBuilder("/auth/login?");
        url.append("oauth=true");
        url.append("&response_type=").append(responseType);
        url.append("&client_id=").append(clientId);
        url.append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri,
            java.nio.charset.StandardCharsets.UTF_8));
        if (scope != null) url.append("&scope=").append(scope);
        if (state != null) url.append("&state=").append(state);
        if (codeChallenge != null) url.append("&code_challenge=").append(codeChallenge);
        if (codeChallengeMethod != null) url.append("&code_challenge_method=").append(codeChallengeMethod);
        if (nonce != null) url.append("&nonce=").append(nonce);
        return url.toString();
    }

    private Response errorRedirect(String redirectUri, String error, String description, String state) {
        if (redirectUri == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", error, "error_description", description))
                .build();
        }
        String url = redirectUri + "?error=" + error + "&error_description=" +
            java.net.URLEncoder.encode(description, java.nio.charset.StandardCharsets.UTF_8);
        if (state != null) url += "&state=" + state;
        return Response.seeOther(URI.create(url)).build();
    }

    private Response tokenError(String error, String description) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(Map.of("error", error, "error_description", description))
            .build();
    }

    public record TokenResponse(
        String access_token,
        String token_type,
        long expires_in,
        String refresh_token,
        String scope
    ) {}
}
```

---

## Phase 3: Tenant Selection Flow

### 3.1 Tenant Selection After Login

For users with access to multiple tenants, they need to select which tenant context to use.

**File:** `authentication/TenantSelectionResource.java`

```java
package tech.flowcatalyst.platform.authentication;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalRepository;
import tech.flowcatalyst.platform.tenant.Tenant;
import tech.flowcatalyst.platform.tenant.TenantAccessService;
import tech.flowcatalyst.platform.tenant.TenantRepository;

import java.util.List;
import java.util.Set;

/**
 * Endpoints for tenant selection and switching.
 * Users with multi-tenant access can choose which tenant context to work in.
 */
@Path("/auth/tenant")
@Tag(name = "Tenant Selection", description = "Tenant context management")
@Produces(MediaType.APPLICATION_JSON)
@EmbeddedModeOnly
public class TenantSelectionResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    PrincipalRepository principalRepo;

    @Inject
    TenantRepository tenantRepo;

    @Inject
    TenantAccessService tenantAccessService;

    @Inject
    JwtKeyService jwtKeyService;

    /**
     * Get list of tenants the current user can access.
     */
    @GET
    @Path("/accessible")
    @Operation(summary = "List accessible tenants for current user")
    public Response getAccessibleTenants() {
        Long principalId = Long.parseLong(jwt.getSubject());

        Principal principal = principalRepo.findByIdOptional(principalId)
            .orElseThrow(() -> new NotFoundException("Principal not found"));

        Set<Long> tenantIds = tenantAccessService.getAccessibleTenants(principal);

        List<TenantInfo> tenants = tenantRepo.find("id in ?1", tenantIds).stream()
            .map(t -> new TenantInfo(t.id, t.name, t.identifier))
            .toList();

        return Response.ok(new AccessibleTenantsResponse(tenants, principal.tenantId)).build();
    }

    /**
     * Switch to a different tenant context.
     * Issues a new token with the selected tenant.
     */
    @POST
    @Path("/switch")
    @Operation(summary = "Switch to a different tenant")
    public Response switchTenant(SwitchTenantRequest request) {
        Long principalId = Long.parseLong(jwt.getSubject());

        Principal principal = principalRepo.findByIdOptional(principalId)
            .orElseThrow(() -> new NotFoundException("Principal not found"));

        // Verify access to requested tenant
        Set<Long> accessibleTenants = tenantAccessService.getAccessibleTenants(principal);
        if (!accessibleTenants.contains(request.tenantId())) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity(new ErrorResponse("Access denied to tenant"))
                .build();
        }

        // Get tenant info
        Tenant tenant = tenantRepo.findByIdOptional(request.tenantId())
            .orElseThrow(() -> new NotFoundException("Tenant not found"));

        // Issue new token with tenant claim
        Set<String> roles = new java.util.HashSet<>(jwt.getGroups());
        String newToken = jwtKeyService.issueSessionTokenWithTenant(
            principalId,
            principal.userIdentity != null ? principal.userIdentity.email : null,
            roles,
            request.tenantId()
        );

        return Response.ok(new SwitchTenantResponse(
            newToken,
            new TenantInfo(tenant.id, tenant.name, tenant.identifier)
        )).build();
    }

    // DTOs

    public record TenantInfo(Long id, String name, String identifier) {}

    public record AccessibleTenantsResponse(List<TenantInfo> tenants, Long currentTenantId) {}

    public record SwitchTenantRequest(Long tenantId) {}

    public record SwitchTenantResponse(String token, TenantInfo tenant) {}

    public record ErrorResponse(String error) {}
}
```

### 3.2 Update JwtKeyService for Tenant Claims

Add method to `JwtKeyService`:

```java
/**
 * Issue a session token with tenant context.
 */
public String issueSessionTokenWithTenant(Long principalId, String email,
        Set<String> roles, Long tenantId) {
    return Jwt.issuer(issuer)
            .subject(String.valueOf(principalId))
            .claim("email", email)
            .claim("type", "USER")
            .claim("tenant_id", tenantId)
            .groups(roles)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plus(sessionTokenExpiry))
            .jws()
            .keyId(keyId)
            .sign(privateKey);
}
```

---

## Phase 4: Admin REST APIs

### 4.1 Tenant Admin Resource

**File:** `admin/TenantAdminResource.java`

```java
package tech.flowcatalyst.platform.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.authentication.EmbeddedModeOnly;
import tech.flowcatalyst.platform.tenant.*;

import java.util.List;

/**
 * Admin endpoints for tenant management.
 * Requires platform-admin role.
 */
@Path("/admin/tenants")
@Tag(name = "Tenant Admin", description = "Tenant management endpoints")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("platform-admin")
@EmbeddedModeOnly
public class TenantAdminResource {

    @Inject
    TenantService tenantService;

    @Inject
    TenantRepository tenantRepo;

    @Inject
    TenantAuthConfigRepository authConfigRepo;

    /**
     * List all tenants.
     */
    @GET
    @Operation(summary = "List all tenants")
    public List<TenantResponse> listTenants(
            @QueryParam("status") TenantStatus status,
            @QueryParam("limit") @DefaultValue("100") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {

        List<Tenant> tenants;
        if (status != null) {
            tenants = tenantRepo.find("status", status).page(offset / limit, limit).list();
        } else {
            tenants = tenantRepo.findAll().page(offset / limit, limit).list();
        }

        return tenants.stream().map(this::toResponse).toList();
    }

    /**
     * Get tenant by ID.
     */
    @GET
    @Path("/{id}")
    @Operation(summary = "Get tenant details")
    public TenantResponse getTenant(@PathParam("id") Long id) {
        Tenant tenant = tenantRepo.findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException("Tenant not found"));
        return toResponse(tenant);
    }

    /**
     * Create a new tenant.
     */
    @POST
    @Transactional
    @Operation(summary = "Create a new tenant")
    public Response createTenant(@Valid CreateTenantRequest request) {
        Tenant tenant = tenantService.createTenant(request.name(), request.identifier());
        return Response.status(Response.Status.CREATED)
            .entity(toResponse(tenant))
            .build();
    }

    /**
     * Update tenant.
     */
    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Update tenant")
    public TenantResponse updateTenant(@PathParam("id") Long id, @Valid UpdateTenantRequest request) {
        Tenant tenant = tenantService.updateTenant(id, request.name());
        return toResponse(tenant);
    }

    /**
     * Change tenant status.
     */
    @POST
    @Path("/{id}/status")
    @Transactional
    @Operation(summary = "Change tenant status")
    public Response changeStatus(@PathParam("id") Long id, @Valid ChangeStatusRequest request,
            @Context SecurityContext securityContext) {
        String changedBy = securityContext.getUserPrincipal().getName();
        tenantService.changeTenantStatus(id, request.status(), request.reason(),
            request.note(), changedBy);
        return Response.ok().build();
    }

    /**
     * Configure authentication for a tenant's email domain.
     */
    @POST
    @Path("/{id}/auth-config")
    @Transactional
    @Operation(summary = "Configure tenant authentication")
    public Response configureAuth(@PathParam("id") Long id, @Valid AuthConfigRequest request) {
        // Validate tenant exists
        Tenant tenant = tenantRepo.findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException("Tenant not found"));

        // Check if config already exists for this domain
        TenantAuthConfig config = authConfigRepo.findByEmailDomain(request.emailDomain())
            .orElse(new TenantAuthConfig());

        config.emailDomain = request.emailDomain();
        config.authProvider = request.authProvider();
        config.oidcIssuerUrl = request.oidcIssuerUrl();
        config.oidcClientId = request.oidcClientId();
        config.oidcClientSecretEncrypted = request.oidcClientSecret(); // TODO: Encrypt

        if (config.id == null) {
            config.id = tech.flowcatalyst.platform.shared.TsidGenerator.generate();
        }

        config.validateOidcConfig();
        authConfigRepo.persist(config);

        return Response.ok().build();
    }

    // DTOs

    public record CreateTenantRequest(
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "^[a-z0-9-]+$") String identifier
    ) {}

    public record UpdateTenantRequest(@NotBlank String name) {}

    public record ChangeStatusRequest(
        TenantStatus status,
        String reason,
        String note
    ) {}

    public record AuthConfigRequest(
        @NotBlank String emailDomain,
        tech.flowcatalyst.platform.authentication.AuthProvider authProvider,
        String oidcIssuerUrl,
        String oidcClientId,
        String oidcClientSecret
    ) {}

    public record TenantResponse(
        Long id,
        String name,
        String identifier,
        TenantStatus status,
        String statusReason,
        java.time.Instant createdAt
    ) {}

    private TenantResponse toResponse(Tenant t) {
        return new TenantResponse(t.id, t.name, t.identifier, t.status, t.statusReason, t.createdAt);
    }
}
```

### 4.2 User Admin Resource

**File:** `admin/UserAdminResource.java`

```java
package tech.flowcatalyst.platform.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.authentication.EmbeddedModeOnly;
import tech.flowcatalyst.platform.authentication.IdpType;
import tech.flowcatalyst.platform.authorization.PrincipalRole;
import tech.flowcatalyst.platform.authorization.PrincipalRoleRepository;
import tech.flowcatalyst.platform.authorization.RoleService;
import tech.flowcatalyst.platform.principal.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin endpoints for user management.
 */
@Path("/admin/users")
@Tag(name = "User Admin", description = "User management endpoints")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"platform-admin", "tenant-admin"})
@EmbeddedModeOnly
public class UserAdminResource {

    @Inject
    UserService userService;

    @Inject
    PrincipalRepository principalRepo;

    @Inject
    PrincipalRoleRepository roleRepo;

    @Inject
    RoleService roleService;

    /**
     * List users (optionally filtered by tenant).
     */
    @GET
    @Operation(summary = "List users")
    public List<UserResponse> listUsers(
            @QueryParam("tenantId") Long tenantId,
            @QueryParam("active") Boolean active,
            @QueryParam("limit") @DefaultValue("100") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {

        List<Principal> users;
        if (tenantId != null && active != null) {
            users = principalRepo.find("tenantId = ?1 AND type = ?2 AND active = ?3",
                tenantId, PrincipalType.USER, active).page(offset / limit, limit).list();
        } else if (tenantId != null) {
            users = userService.findByTenant(tenantId);
        } else {
            users = principalRepo.find("type", PrincipalType.USER).page(offset / limit, limit).list();
        }

        return users.stream().map(this::toResponse).toList();
    }

    /**
     * Get user by ID.
     */
    @GET
    @Path("/{id}")
    @Operation(summary = "Get user details")
    public UserResponse getUser(@PathParam("id") Long id) {
        Principal principal = principalRepo.findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException("User not found"));

        if (principal.type != PrincipalType.USER) {
            throw new NotFoundException("User not found");
        }

        return toResponse(principal);
    }

    /**
     * Create internal user (password-based).
     */
    @POST
    @Transactional
    @Operation(summary = "Create internal user")
    public Response createUser(@Valid CreateUserRequest request) {
        Principal user = userService.createInternalUser(
            request.email(),
            request.password(),
            request.name(),
            request.tenantId()
        );

        // Assign roles if specified
        if (request.roles() != null) {
            for (String roleName : request.roles()) {
                roleService.assignRole(user.id, roleName, "MANUAL");
            }
        }

        return Response.status(Response.Status.CREATED)
            .entity(toResponse(user))
            .build();
    }

    /**
     * Update user.
     */
    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Update user")
    public UserResponse updateUser(@PathParam("id") Long id, @Valid UpdateUserRequest request) {
        Principal user = userService.updateUser(id, request.name());
        return toResponse(user);
    }

    /**
     * Deactivate user.
     */
    @POST
    @Path("/{id}/deactivate")
    @Transactional
    @Operation(summary = "Deactivate user")
    public Response deactivateUser(@PathParam("id") Long id) {
        userService.deactivateUser(id);
        return Response.ok().build();
    }

    /**
     * Activate user.
     */
    @POST
    @Path("/{id}/activate")
    @Transactional
    @Operation(summary = "Activate user")
    public Response activateUser(@PathParam("id") Long id) {
        userService.activateUser(id);
        return Response.ok().build();
    }

    /**
     * Reset user password (admin action).
     */
    @POST
    @Path("/{id}/reset-password")
    @Transactional
    @RolesAllowed("platform-admin")
    @Operation(summary = "Reset user password")
    public Response resetPassword(@PathParam("id") Long id, @Valid ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword());
        return Response.ok().build();
    }

    /**
     * Assign role to user.
     */
    @POST
    @Path("/{id}/roles")
    @Transactional
    @Operation(summary = "Assign role to user")
    public Response assignRole(@PathParam("id") Long id, @Valid AssignRoleRequest request) {
        roleService.assignRole(id, request.roleName(), "MANUAL");
        return Response.ok().build();
    }

    /**
     * Remove role from user.
     */
    @DELETE
    @Path("/{id}/roles/{roleName}")
    @Transactional
    @Operation(summary = "Remove role from user")
    public Response removeRole(@PathParam("id") Long id, @PathParam("roleName") String roleName) {
        roleService.revokeRole(id, roleName);
        return Response.ok().build();
    }

    // DTOs

    public record CreateUserRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotBlank String name,
        Long tenantId,
        Set<String> roles
    ) {}

    public record UpdateUserRequest(@NotBlank String name) {}

    public record ResetPasswordRequest(@NotBlank String newPassword) {}

    public record AssignRoleRequest(@NotBlank String roleName) {}

    public record UserResponse(
        Long id,
        String name,
        String email,
        IdpType idpType,
        Long tenantId,
        boolean active,
        Set<String> roles,
        java.time.Instant lastLoginAt,
        java.time.Instant createdAt
    ) {}

    private UserResponse toResponse(Principal p) {
        Set<String> roles = roleRepo.findByPrincipalId(p.id).stream()
            .map(pr -> pr.roleName)
            .collect(Collectors.toSet());

        return new UserResponse(
            p.id,
            p.name,
            p.userIdentity != null ? p.userIdentity.email : null,
            p.userIdentity != null ? p.userIdentity.idpType : null,
            p.tenantId,
            p.active,
            roles,
            p.userIdentity != null ? p.userIdentity.lastLoginAt : null,
            p.createdAt
        );
    }
}
```

### 4.3 OAuth Client Admin Resource

**File:** `admin/OAuthClientAdminResource.java`

```java
package tech.flowcatalyst.platform.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.authentication.EmbeddedModeOnly;
import tech.flowcatalyst.platform.authentication.oauth.OAuthClient;
import tech.flowcatalyst.platform.authentication.oauth.OAuthClientRepository;
import tech.flowcatalyst.platform.principal.PasswordService;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Admin endpoints for OAuth client management.
 * Used to register SPAs, mobile apps, and service clients.
 */
@Path("/admin/oauth-clients")
@Tag(name = "OAuth Client Admin", description = "OAuth client management")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("platform-admin")
@EmbeddedModeOnly
public class OAuthClientAdminResource {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    OAuthClientRepository clientRepo;

    @Inject
    PasswordService passwordService;

    /**
     * List all OAuth clients.
     */
    @GET
    @Operation(summary = "List OAuth clients")
    public List<OAuthClientResponse> listClients() {
        return clientRepo.listAll().stream()
            .map(this::toResponse)
            .toList();
    }

    /**
     * Get OAuth client by ID.
     */
    @GET
    @Path("/{id}")
    @Operation(summary = "Get OAuth client")
    public OAuthClientResponse getClient(@PathParam("id") Long id) {
        OAuthClient client = clientRepo.findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException("Client not found"));
        return toResponse(client);
    }

    /**
     * Register a new OAuth client.
     */
    @POST
    @Transactional
    @Operation(summary = "Register new OAuth client")
    public Response createClient(@Valid CreateOAuthClientRequest request) {
        OAuthClient client = new OAuthClient();
        client.id = TsidGenerator.generate();
        client.clientId = generateClientId();
        client.clientName = request.clientName();
        client.clientType = request.clientType();
        client.redirectUris = String.join(",", request.redirectUris());
        client.grantTypes = String.join(",", request.grantTypes());
        client.defaultScopes = request.defaultScopes();
        client.pkceRequired = request.clientType() == OAuthClient.ClientType.PUBLIC || request.pkceRequired();
        client.tenantId = request.tenantId();

        String clientSecret = null;
        if (request.clientType() == OAuthClient.ClientType.CONFIDENTIAL) {
            clientSecret = generateClientSecret();
            client.clientSecretHash = passwordService.hashPassword(clientSecret);
        }

        clientRepo.persist(client);

        // Return with secret (only shown once)
        return Response.status(Response.Status.CREATED)
            .entity(new CreateOAuthClientResponse(
                client.id,
                client.clientId,
                clientSecret,  // Only returned on creation
                client.clientName,
                client.clientType
            ))
            .build();
    }

    /**
     * Update OAuth client.
     */
    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Update OAuth client")
    public OAuthClientResponse updateClient(@PathParam("id") Long id,
            @Valid UpdateOAuthClientRequest request) {
        OAuthClient client = clientRepo.findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException("Client not found"));

        client.clientName = request.clientName();
        client.redirectUris = String.join(",", request.redirectUris());
        client.grantTypes = String.join(",", request.grantTypes());
        client.defaultScopes = request.defaultScopes();
        client.active = request.active();

        return toResponse(client);
    }

    /**
     * Rotate client secret (confidential clients only).
     */
    @POST
    @Path("/{id}/rotate-secret")
    @Transactional
    @Operation(summary = "Rotate client secret")
    public Response rotateSecret(@PathParam("id") Long id) {
        OAuthClient client = clientRepo.findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException("Client not found"));

        if (client.clientType != OAuthClient.ClientType.CONFIDENTIAL) {
            throw new BadRequestException("Cannot rotate secret for public client");
        }

        String newSecret = generateClientSecret();
        client.clientSecretHash = passwordService.hashPassword(newSecret);

        return Response.ok(new RotateSecretResponse(newSecret)).build();
    }

    /**
     * Delete OAuth client.
     */
    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Delete OAuth client")
    public Response deleteClient(@PathParam("id") Long id) {
        boolean deleted = clientRepo.deleteById(id);
        if (!deleted) {
            throw new NotFoundException("Client not found");
        }
        return Response.noContent().build();
    }

    // Helpers

    private String generateClientId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "fc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateClientSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // DTOs

    public record CreateOAuthClientRequest(
        @NotBlank String clientName,
        OAuthClient.ClientType clientType,
        List<String> redirectUris,
        List<String> grantTypes,
        String defaultScopes,
        boolean pkceRequired,
        Long tenantId
    ) {}

    public record UpdateOAuthClientRequest(
        @NotBlank String clientName,
        List<String> redirectUris,
        List<String> grantTypes,
        String defaultScopes,
        boolean active
    ) {}

    public record CreateOAuthClientResponse(
        Long id,
        String clientId,
        String clientSecret,  // Only shown once
        String clientName,
        OAuthClient.ClientType clientType
    ) {}

    public record RotateSecretResponse(String clientSecret) {}

    public record OAuthClientResponse(
        Long id,
        String clientId,
        String clientName,
        OAuthClient.ClientType clientType,
        List<String> redirectUris,
        List<String> grantTypes,
        boolean pkceRequired,
        boolean active
    ) {}

    private OAuthClientResponse toResponse(OAuthClient c) {
        return new OAuthClientResponse(
            c.id,
            c.clientId,
            c.clientName,
            c.clientType,
            List.of(c.redirectUris.split(",")),
            List.of(c.grantTypes.split(",")),
            c.pkceRequired,
            c.active
        );
    }
}
```

---

## Phase 5: Remote Mode Support

### 5.1 Remote JWKS Service

**File:** `authentication/remote/RemoteJwksService.java`

```java
package tech.flowcatalyst.platform.authentication.remote;

import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import tech.flowcatalyst.platform.authentication.AuthConfig;
import tech.flowcatalyst.platform.authentication.AuthMode;

import java.io.StringReader;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.math.BigInteger;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service that fetches and caches JWKS from remote IdP.
 * Used in remote mode for token validation.
 */
@ApplicationScoped
public class RemoteJwksService {

    private static final Logger LOG = Logger.getLogger(RemoteJwksService.class);

    @Inject
    AuthConfig authConfig;

    @Inject
    @RestClient
    JwksClient jwksClient;

    private final Map<String, PublicKey> keyCache = new ConcurrentHashMap<>();

    /**
     * Get public key by key ID from remote JWKS.
     */
    @CacheResult(cacheName = "remote-jwks")
    public PublicKey getPublicKey(String keyId) {
        if (authConfig.mode() != AuthMode.REMOTE) {
            throw new IllegalStateException("Remote JWKS only available in remote mode");
        }

        // Check local cache first
        PublicKey cached = keyCache.get(keyId);
        if (cached != null) {
            return cached;
        }

        // Fetch JWKS from remote
        String jwksJson = jwksClient.getJwks();
        JsonObject jwks = Json.createReader(new StringReader(jwksJson)).readObject();
        JsonArray keys = jwks.getJsonArray("keys");

        for (int i = 0; i < keys.size(); i++) {
            JsonObject key = keys.getJsonObject(i);
            String kid = key.getString("kid", null);

            if (kid != null && kid.equals(keyId)) {
                PublicKey publicKey = parseRsaPublicKey(key);
                keyCache.put(kid, publicKey);
                return publicKey;
            }
        }

        LOG.warnf("Key not found in remote JWKS: %s", keyId);
        return null;
    }

    /**
     * Get the configured remote issuer.
     */
    public String getRemoteIssuer() {
        return authConfig.remote().issuer().orElseThrow(
            () -> new IllegalStateException("Remote issuer not configured"));
    }

    private PublicKey parseRsaPublicKey(JsonObject jwk) {
        try {
            String n = jwk.getString("n");
            String e = jwk.getString("e");

            byte[] nBytes = Base64.getUrlDecoder().decode(n);
            byte[] eBytes = Base64.getUrlDecoder().decode(e);

            BigInteger modulus = new BigInteger(1, nBytes);
            BigInteger exponent = new BigInteger(1, eBytes);

            RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return factory.generatePublic(spec);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to parse RSA public key from JWK", ex);
        }
    }
}
```

**File:** `authentication/remote/JwksClient.java`

```java
package tech.flowcatalyst.platform.authentication.remote;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client for fetching JWKS from remote IdP.
 */
@RegisterRestClient(configKey = "remote-jwks")
public interface JwksClient {

    @GET
    @Path("/.well-known/jwks.json")
    @Produces(MediaType.APPLICATION_JSON)
    String getJwks();
}
```

### 5.2 Redirect Auth Resource (Remote Mode)

**File:** `authentication/remote/RedirectAuthResource.java`

```java
package tech.flowcatalyst.platform.authentication.remote;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.authentication.AuthConfig;
import tech.flowcatalyst.platform.authentication.AuthMode;

import java.net.URI;

/**
 * Auth endpoints in remote mode - redirects to external IdP.
 */
@Path("/auth")
@Tag(name = "Authentication (Remote)", description = "Redirects to external IdP")
public class RedirectAuthResource {

    @Inject
    AuthConfig authConfig;

    @Context
    UriInfo uriInfo;

    /**
     * Redirect login requests to remote IdP.
     */
    @GET
    @Path("/login")
    @Operation(summary = "Redirect to external IdP login")
    public Response login(@QueryParam("redirect_uri") String redirectUri) {
        if (authConfig.mode() != AuthMode.REMOTE) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String loginUrl = authConfig.remote().loginUrl()
            .orElseThrow(() -> new IllegalStateException("Remote login URL not configured"));

        if (redirectUri != null) {
            loginUrl += "?redirect_uri=" + java.net.URLEncoder.encode(redirectUri,
                java.nio.charset.StandardCharsets.UTF_8);
        }

        return Response.seeOther(URI.create(loginUrl)).build();
    }

    /**
     * Redirect logout requests to remote IdP.
     */
    @POST
    @Path("/logout")
    @Operation(summary = "Redirect to external IdP logout")
    public Response logout() {
        if (authConfig.mode() != AuthMode.REMOTE) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String logoutUrl = authConfig.remote().logoutUrl()
            .orElseThrow(() -> new IllegalStateException("Remote logout URL not configured"));

        return Response.seeOther(URI.create(logoutUrl)).build();
    }
}
```

---

## Phase 6: OIDC Federation (Embedded Mode)

### 6.1 Auth Method Lookup

**File:** `authentication/AuthLookupResource.java`

```java
package tech.flowcatalyst.platform.authentication;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.tenant.TenantAuthConfig;
import tech.flowcatalyst.platform.tenant.TenantAuthConfigRepository;

/**
 * Lookup endpoint to determine authentication method for an email.
 * SPAs use this to decide whether to show password form or redirect to SSO.
 */
@Path("/auth")
@Tag(name = "Authentication", description = "Auth lookup and login")
@Produces(MediaType.APPLICATION_JSON)
@EmbeddedModeOnly
public class AuthLookupResource {

    @Inject
    TenantAuthConfigRepository authConfigRepo;

    /**
     * Lookup authentication method for an email address.
     *
     * Returns:
     * - INTERNAL: Show password form
     * - OIDC: Redirect to SSO (includes redirect URL)
     */
    @GET
    @Path("/lookup")
    @Operation(summary = "Lookup auth method for email")
    public Response lookup(@QueryParam("email") String email) {
        if (email == null || !email.contains("@")) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("Valid email required"))
                .build();
        }

        String domain = email.substring(email.indexOf('@') + 1).toLowerCase();

        // Check for domain-specific auth config
        var configOpt = authConfigRepo.findByEmailDomain(domain);

        if (configOpt.isEmpty()) {
            // Default to internal auth
            return Response.ok(new AuthLookupResponse(
                AuthProvider.INTERNAL,
                null,
                null
            )).build();
        }

        TenantAuthConfig config = configOpt.get();

        if (config.authProvider == AuthProvider.OIDC) {
            // Return OIDC redirect info
            return Response.ok(new AuthLookupResponse(
                AuthProvider.OIDC,
                config.oidcIssuerUrl,
                buildOidcRedirectUrl(config)
            )).build();
        }

        return Response.ok(new AuthLookupResponse(
            AuthProvider.INTERNAL,
            null,
            null
        )).build();
    }

    private String buildOidcRedirectUrl(TenantAuthConfig config) {
        // Build the OIDC authorization URL
        return config.oidcIssuerUrl + "/protocol/openid-connect/auth" +
            "?client_id=" + config.oidcClientId +
            "&response_type=code" +
            "&scope=openid profile email";
    }

    // DTOs

    public record AuthLookupResponse(
        AuthProvider authMethod,
        String oidcIssuer,
        String redirectUrl
    ) {}

    public record ErrorResponse(String error) {}
}
```

### 6.2 OIDC Callback Handler

**File:** `authentication/OidcCallbackResource.java`

```java
package tech.flowcatalyst.platform.authentication;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import tech.flowcatalyst.platform.authentication.idp.IdpRoleSyncService;
import tech.flowcatalyst.platform.authorization.PrincipalRole;
import tech.flowcatalyst.platform.authorization.PrincipalRoleRepository;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.UserService;
import tech.flowcatalyst.platform.tenant.TenantAuthConfig;
import tech.flowcatalyst.platform.tenant.TenantAuthConfigRepository;

import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles OIDC callback after external IdP authentication.
 */
@Path("/auth/oidc")
@Tag(name = "OIDC Federation", description = "External IdP integration")
@EmbeddedModeOnly
public class OidcCallbackResource {

    private static final Logger LOG = Logger.getLogger(OidcCallbackResource.class);

    @Inject
    TenantAuthConfigRepository authConfigRepo;

    @Inject
    UserService userService;

    @Inject
    IdpRoleSyncService roleSyncService;

    @Inject
    PrincipalRoleRepository roleRepo;

    @Inject
    JwtKeyService jwtKeyService;

    @Inject
    OidcSyncService oidcSyncService;

    /**
     * OIDC callback - exchanges code for tokens and creates/updates user.
     */
    @GET
    @Path("/callback")
    @Transactional
    @Operation(summary = "OIDC callback handler")
    public Response callback(
            @QueryParam("code") String code,
            @QueryParam("state") String state,
            @QueryParam("error") String error,
            @QueryParam("error_description") String errorDescription) {

        if (error != null) {
            LOG.warnf("OIDC error: %s - %s", error, errorDescription);
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity("Authentication failed: " + errorDescription)
                .build();
        }

        if (code == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("Missing authorization code")
                .build();
        }

        // TODO: Parse state to get original client request params
        // TODO: Exchange code for tokens with external IdP
        // TODO: Validate ID token and extract claims

        // For now, this is a placeholder - full implementation requires:
        // 1. State management (store original request, validate CSRF)
        // 2. Token exchange with external IdP
        // 3. ID token validation
        // 4. User provisioning/update
        // 5. Role sync from external IdP

        return Response.status(Response.Status.NOT_IMPLEMENTED)
            .entity("OIDC callback not fully implemented yet")
            .build();
    }

    /**
     * Initiate OIDC login to external IdP.
     */
    @GET
    @Path("/login")
    @Operation(summary = "Initiate OIDC login")
    public Response initiateOidcLogin(
            @QueryParam("email") String email,
            @QueryParam("redirect_uri") String redirectUri) {

        if (email == null || !email.contains("@")) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("Valid email required")
                .build();
        }

        String domain = email.substring(email.indexOf('@') + 1).toLowerCase();

        var configOpt = authConfigRepo.findByEmailDomain(domain);
        if (configOpt.isEmpty() || configOpt.get().authProvider != AuthProvider.OIDC) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("OIDC not configured for this domain")
                .build();
        }

        TenantAuthConfig config = configOpt.get();

        // Build authorization URL
        // TODO: Generate and store state for CSRF protection
        String state = java.util.UUID.randomUUID().toString();
        String authUrl = config.oidcIssuerUrl + "/protocol/openid-connect/auth" +
            "?client_id=" + config.oidcClientId +
            "&response_type=code" +
            "&scope=openid profile email" +
            "&redirect_uri=" + java.net.URLEncoder.encode(
                getCallbackUrl(), java.nio.charset.StandardCharsets.UTF_8) +
            "&state=" + state;

        return Response.seeOther(URI.create(authUrl)).build();
    }

    private String getCallbackUrl() {
        // TODO: Build from config or request context
        return "http://localhost:8080/auth/oidc/callback";
    }
}
```

---

## Phase 7: Database Migrations

### 7.1 Flyway Migration for OAuth Tables

**File:** `db/migration/V1_010__create_oauth_tables.sql`

```sql
-- OAuth Clients table (SPAs, mobile apps, service clients)
CREATE TABLE IF NOT EXISTS auth_oauth_clients (
    id BIGINT PRIMARY KEY,
    client_id VARCHAR(100) UNIQUE NOT NULL,
    client_name VARCHAR(255) NOT NULL,
    client_type VARCHAR(20) NOT NULL,
    client_secret_hash VARCHAR(500),
    redirect_uris VARCHAR(2000) NOT NULL,
    grant_types VARCHAR(200) NOT NULL,
    default_scopes VARCHAR(500),
    pkce_required BOOLEAN NOT NULL DEFAULT TRUE,
    tenant_id BIGINT REFERENCES auth_tenants(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_client_type CHECK (client_type IN ('PUBLIC', 'CONFIDENTIAL'))
);

CREATE INDEX idx_oauth_client_client_id ON auth_oauth_clients(client_id);
CREATE INDEX idx_oauth_client_tenant ON auth_oauth_clients(tenant_id);

-- Authorization Codes table (short-lived, single-use)
CREATE TABLE IF NOT EXISTS auth_authorization_codes (
    code VARCHAR(64) PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    principal_id BIGINT NOT NULL REFERENCES auth_principals(id),
    redirect_uri VARCHAR(1000) NOT NULL,
    scope VARCHAR(500),
    code_challenge VARCHAR(128),
    code_challenge_method VARCHAR(10),
    nonce VARCHAR(128),
    state VARCHAR(128),
    tenant_id BIGINT REFERENCES auth_tenants(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_auth_code_expires ON auth_authorization_codes(expires_at);

-- Refresh Tokens table
CREATE TABLE IF NOT EXISTS auth_refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    principal_id BIGINT NOT NULL REFERENCES auth_principals(id),
    client_id VARCHAR(100),
    tenant_id BIGINT REFERENCES auth_tenants(id),
    scope VARCHAR(500),
    token_family VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMP,
    replaced_by VARCHAR(64)
);

CREATE INDEX idx_refresh_token_principal ON auth_refresh_tokens(principal_id);
CREATE INDEX idx_refresh_token_family ON auth_refresh_tokens(token_family);
CREATE INDEX idx_refresh_token_expires ON auth_refresh_tokens(expires_at);
```

---

## Phase 8: Scheduled Cleanup Jobs

**File:** `authentication/TokenCleanupJob.java`

```java
package tech.flowcatalyst.platform.authentication;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import tech.flowcatalyst.platform.authentication.oauth.AuthorizationCodeRepository;
import tech.flowcatalyst.platform.authentication.oauth.RefreshTokenRepository;

/**
 * Scheduled job to clean up expired tokens and authorization codes.
 */
@ApplicationScoped
public class TokenCleanupJob {

    private static final Logger LOG = Logger.getLogger(TokenCleanupJob.class);

    @Inject
    AuthorizationCodeRepository codeRepo;

    @Inject
    RefreshTokenRepository refreshTokenRepo;

    @Inject
    AuthConfig authConfig;

    /**
     * Clean up expired authorization codes every hour.
     */
    @Scheduled(every = "1h")
    @Transactional
    public void cleanupExpiredCodes() {
        if (authConfig.mode() != AuthMode.EMBEDDED) {
            return;
        }

        long deleted = codeRepo.deleteExpired();
        if (deleted > 0) {
            LOG.infof("Deleted %d expired authorization codes", deleted);
        }
    }

    /**
     * Clean up expired refresh tokens daily.
     */
    @Scheduled(cron = "0 0 3 * * ?")  // 3 AM daily
    @Transactional
    public void cleanupExpiredRefreshTokens() {
        if (authConfig.mode() != AuthMode.EMBEDDED) {
            return;
        }

        long deleted = refreshTokenRepo.deleteExpired();
        if (deleted > 0) {
            LOG.infof("Deleted %d expired refresh tokens", deleted);
        }
    }
}
```

---

## Implementation Order

### Phase 1: Foundation (Week 1)
1. [ ] Create `AuthConfig` with `@ConfigMapping`
2. [ ] Create `AuthMode` enum
3. [ ] Create `EmbeddedModeOnly` annotation and filter
4. [ ] Annotate existing resources with `@EmbeddedModeOnly`
5. [ ] Test conditional activation

### Phase 2: OAuth2 Authorization Code + PKCE (Week 2)
1. [ ] Create `OAuthClient`, `AuthorizationCode`, `RefreshToken` entities
2. [ ] Create repositories for OAuth entities
3. [ ] Implement `PkceService`
4. [ ] Implement `AuthorizationResource` (authorize + token endpoints)
5. [ ] Add refresh token rotation
6. [ ] Write Flyway migration

### Phase 3: Tenant Selection (Week 2)
1. [ ] Implement `TenantSelectionResource`
2. [ ] Add `issueSessionTokenWithTenant` to `JwtKeyService`
3. [ ] Add tenant claim to token validation

### Phase 4: Admin APIs (Week 3)
1. [ ] Implement `TenantAdminResource`
2. [ ] Implement `UserAdminResource`
3. [ ] Implement `OAuthClientAdminResource`

### Phase 5: Remote Mode (Week 3)
1. [ ] Implement `RemoteJwksService`
2. [ ] Implement `JwksClient` REST client
3. [ ] Implement `RedirectAuthResource`
4. [ ] Update `InternalIdentityProvider` for remote mode

### Phase 6: OIDC Federation (Week 4)
1. [ ] Implement `AuthLookupResource`
2. [ ] Implement `OidcCallbackResource` (full implementation)
3. [ ] Add state management for CSRF protection
4. [ ] Integrate with `OidcSyncService`

### Phase 7: Cleanup & Polish (Week 4)
1. [ ] Implement `TokenCleanupJob`
2. [ ] Add integration tests
3. [ ] Update documentation

---

## Testing Checklist

### Embedded Mode
- [ ] Internal login with username/password
- [ ] OAuth2 authorization code flow (SPA)
- [ ] PKCE validation
- [ ] Refresh token issuance and rotation
- [ ] Refresh token reuse detection
- [ ] Tenant selection for multi-tenant users
- [ ] Admin APIs (CRUD operations)
- [ ] OIDC federation redirect and callback

### Remote Mode
- [ ] Token validation with remote JWKS
- [ ] Auth endpoints return 404 or redirect
- [ ] JWKS caching works correctly

### Security
- [ ] PKCE required for public clients
- [ ] Authorization codes are single-use
- [ ] Refresh token family revocation on reuse
- [ ] Proper CORS headers for SPAs
- [ ] CSRF protection on state parameter

---

## Configuration Examples

### Embedded Mode (Full IdP)

```properties
flowcatalyst.auth.mode=embedded
flowcatalyst.auth.jwt.issuer=https://auth.mycompany.com
flowcatalyst.auth.jwt.private-key-path=/etc/secrets/jwt-private.pem
flowcatalyst.auth.jwt.public-key-path=/etc/secrets/jwt-public.pem
flowcatalyst.auth.jwt.access-token-expiry=PT1H
flowcatalyst.auth.jwt.refresh-token-expiry=P30D
flowcatalyst.auth.pkce.required=true
```

### Remote Mode (Validation Only)

```properties
flowcatalyst.auth.mode=remote
flowcatalyst.auth.remote.issuer=https://auth.mycompany.com
flowcatalyst.auth.remote.jwks-url=https://auth.mycompany.com/.well-known/jwks.json
flowcatalyst.auth.remote.login-url=https://auth.mycompany.com/auth/login
flowcatalyst.auth.remote.logout-url=https://auth.mycompany.com/auth/logout
```

---

## Phase 9: Platform Admin UI

### 9.1 UI Architecture

The Platform Admin UI is a Vue.js SPA that provides management interfaces for:
- Tenant management
- User management
- Role assignment
- OAuth client management
- Auth configuration

**Tech Stack:**
- Runtime: Bun
- Language: TypeScript
- Framework: Vue.js 3 (Composition API)
- Build: Vite
- UI Components: DaisyUI (Tailwind CSS)

### 9.2 Project Structure

```
core/flowcatalyst-platform-ui/
├── package.json
├── vite.config.ts
├── tsconfig.json
├── tailwind.config.js
├── index.html
├── src/
│   ├── main.ts
│   ├── App.vue
│   ├── router/
│   │   └── index.ts
│   ├── stores/                    # Pinia stores
│   │   ├── auth.ts
│   │   ├── tenants.ts
│   │   └── users.ts
│   ├── composables/               # Shared logic
│   │   ├── useApi.ts
│   │   └── useAuth.ts
│   ├── components/
│   │   ├── platform/              # Platform-specific components
│   │   │   ├── TenantList.vue
│   │   │   ├── TenantForm.vue
│   │   │   ├── UserList.vue
│   │   │   ├── UserForm.vue
│   │   │   ├── RoleAssignment.vue
│   │   │   └── AuthConfigForm.vue
│   │   └── shared/                # Reusable UI components
│   │       ├── DataTable.vue
│   │       ├── Modal.vue
│   │       ├── Badge.vue
│   │       └── ...
│   ├── views/
│   │   ├── Dashboard.vue
│   │   ├── TenantsView.vue
│   │   ├── UsersView.vue
│   │   ├── RolesView.vue
│   │   └── OAuthClientsView.vue
│   └── types/
│       └── index.ts               # TypeScript interfaces
└── dist/                          # Built output → copied to platform module
```

### 9.3 Route Structure

```
/platform                    → Dashboard (redirect from / in standalone mode)
/platform/tenants            → Tenant list
/platform/tenants/:id        → Tenant details
/platform/tenants/:id/auth   → Tenant auth configuration
/platform/users              → User list
/platform/users/:id          → User details
/platform/users/:id/roles    → User role assignment
/platform/roles              → Role definitions (read-only, from code)
/platform/oauth-clients      → OAuth client management
/platform/settings           → Platform settings
```

### 9.4 API Routes

All Platform Admin APIs are under `/api/platform/*`:

```
GET    /api/platform/tenants              → List tenants
POST   /api/platform/tenants              → Create tenant
GET    /api/platform/tenants/:id          → Get tenant
PUT    /api/platform/tenants/:id          → Update tenant
POST   /api/platform/tenants/:id/status   → Change tenant status
POST   /api/platform/tenants/:id/auth     → Configure tenant auth

GET    /api/platform/users                → List users
POST   /api/platform/users                → Create user
GET    /api/platform/users/:id            → Get user
PUT    /api/platform/users/:id            → Update user
POST   /api/platform/users/:id/roles      → Assign role
DELETE /api/platform/users/:id/roles/:role → Remove role

GET    /api/platform/roles                → List role definitions (from code)
GET    /api/platform/roles/:name          → Get role with permissions

GET    /api/platform/oauth-clients        → List OAuth clients
POST   /api/platform/oauth-clients        → Create OAuth client
PUT    /api/platform/oauth-clients/:id    → Update OAuth client
DELETE /api/platform/oauth-clients/:id    → Delete OAuth client
```

### 9.5 Serving the SPA

**Embedded Mode:**
```
Application serves:
  /platform              → SPA index.html
  /platform/*            → SPA (client-side routing)
  /platform/assets/*     → Static assets
  /api/platform/*        → REST APIs
```

**Standalone Mode:**
```
Auth Service serves:
  /                      → Redirect to /platform
  /platform              → SPA index.html
  /platform/*            → SPA (client-side routing)
  /api/platform/*        → REST APIs
```

**Remote Mode (other apps):**
```
App redirects /platform to standalone Auth Service URL
```

### 9.6 Build Integration

The SPA build output is copied to the platform module's resources:

```
core/flowcatalyst-platform-ui/dist/
    └── → core/flowcatalyst-platform/src/main/resources/META-INF/resources/platform/
```

Gradle task or build script:
```bash
cd core/flowcatalyst-platform-ui
bun install
bun run build
cp -r dist/* ../flowcatalyst-platform/src/main/resources/META-INF/resources/platform/
```

### 9.7 Quarkus SPA Routing

```java
@ApplicationScoped
public class PlatformSpaRouting {

    @Route(path = "/platform", methods = Route.HttpMethod.GET)
    @Route(path = "/platform/*", methods = Route.HttpMethod.GET)
    public void servePlatformSpa(RoutingContext ctx) {
        String path = ctx.normalizedPath();

        // If requesting a file with extension, serve it directly
        if (path.contains(".")) {
            ctx.next();
            return;
        }

        // Otherwise, serve index.html for client-side routing
        ctx.reroute("/platform/index.html");
    }
}
```

### 9.8 Shared Platform UI Components

The `components/shared/` directory contains reusable components that can also be used by applications building on the platform:

```typescript
// Export from platform-ui package
export { DataTable } from './components/shared/DataTable.vue'
export { Modal } from './components/shared/Modal.vue'
export { Badge } from './components/shared/Badge.vue'
export { UserAvatar } from './components/shared/UserAvatar.vue'
export { TenantSelector } from './components/shared/TenantSelector.vue'
// ... etc
```

Applications can import these components:
```typescript
import { DataTable, Modal } from '@flowcatalyst/platform-ui'
```

---

## Phase 10: TenantAuthConfig Updates

### 10.1 Add idpManagesRoles Field

Update `TenantAuthConfig` entity:

```java
@Entity
@Table(name = "auth_tenant_auth_config")
public class TenantAuthConfig extends PanacheEntityBase {

    // ... existing fields ...

    /**
     * If true, roles come from the external IDP token.
     * If false, roles are managed in Auth DB (even for federated users).
     */
    @Column(name = "idp_manages_roles", nullable = false)
    public boolean idpManagesRoles = false;

    /**
     * Claim name in OIDC token that contains roles.
     * Common values: "roles", "groups", "realm_access.roles"
     */
    @Column(name = "idp_roles_claim", length = 100)
    public String idpRolesClaim = "roles";
}
```

### 10.2 Flyway Migration

```sql
-- V1_011__add_idp_manages_roles.sql
ALTER TABLE auth_tenant_auth_config
ADD COLUMN idp_manages_roles BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE auth_tenant_auth_config
ADD COLUMN idp_roles_claim VARCHAR(100) DEFAULT 'roles';
```

---

## Phase 11: Role Sync to Auth DB

### 11.1 Auth Role Table

Store role definitions synced from application code:

```sql
-- V1_012__create_auth_roles_table.sql
CREATE TABLE IF NOT EXISTS auth_roles (
    id BIGINT PRIMARY KEY,
    role_name VARCHAR(100) UNIQUE NOT NULL,  -- e.g., "platform:tenant-admin"
    subdomain VARCHAR(50) NOT NULL,           -- e.g., "platform"
    description VARCHAR(500),
    permission_count INT NOT NULL DEFAULT 0,
    synced_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auth_roles_subdomain ON auth_roles(subdomain);
```

### 11.2 Update IdpRoleSyncService

Add sync to Auth DB:

```java
/**
 * Sync role definitions to Auth DB.
 * Called on startup and when roles are updated.
 */
public void syncRolesToAuthDb(Collection<RoleDefinition> roles) {
    for (RoleDefinition role : roles) {
        AuthRole authRole = authRoleRepo.findByRoleName(role.toRoleString())
            .orElse(new AuthRole());

        authRole.roleName = role.toRoleString();
        authRole.subdomain = role.subdomain();
        authRole.description = role.description();
        authRole.permissionCount = role.permissions().size();
        authRole.syncedAt = Instant.now();

        if (authRole.id == null) {
            authRole.id = TsidGenerator.generate();
        }

        authRoleRepo.persist(authRole);
    }

    LOG.infof("Synced %d roles to Auth DB", roles.size());
}
```

---

## Phase 12: Federated User Role Sync

### 12.1 On Login: Sync Roles from IDP Token

When a user logs in via federated IDP and `idpManagesRoles=true`:

```java
/**
 * Sync roles from OIDC token to Auth DB.
 * Called during federated login when idpManagesRoles=true.
 */
public void syncRolesFromIdpToken(Principal principal, Set<String> idpRoles,
        TenantAuthConfig authConfig) {

    if (!authConfig.idpManagesRoles) {
        return; // We manage roles, not IDP
    }

    // Get current IDP-sourced roles
    Set<String> currentIdpRoles = principalRoleRepo
        .findByPrincipalIdAndSource(principal.id, RoleSource.IDP)
        .stream()
        .map(pr -> pr.roleName)
        .collect(Collectors.toSet());

    // Roles to add (in IDP but not in our DB)
    Set<String> toAdd = new HashSet<>(idpRoles);
    toAdd.removeAll(currentIdpRoles);

    // Roles to remove (in our DB but not in IDP)
    Set<String> toRemove = new HashSet<>(currentIdpRoles);
    toRemove.removeAll(idpRoles);

    // Add new roles
    for (String roleName : toAdd) {
        // Only add if role exists in our registry
        if (permissionRegistry.hasRole(roleName)) {
            PrincipalRole pr = new PrincipalRole();
            pr.id = TsidGenerator.generate();
            pr.principalId = principal.id;
            pr.roleName = roleName;
            pr.source = RoleSource.IDP;
            pr.assignedAt = Instant.now();
            principalRoleRepo.persist(pr);
        } else {
            LOG.warnf("Ignoring unknown role from IDP: %s", roleName);
        }
    }

    // Remove stale roles
    for (String roleName : toRemove) {
        principalRoleRepo.delete(
            "principalId = ?1 AND roleName = ?2 AND source = ?3",
            principal.id, roleName, RoleSource.IDP
        );
    }

    LOG.infof("Synced %d roles for principal %d from IDP (added: %d, removed: %d)",
        idpRoles.size(), principal.id, toAdd.size(), toRemove.size());
}
```

---

## Updated Implementation Order

### Phase 1-8: (As documented above)

### Phase 9: Platform Admin UI
1. [ ] Create `core/flowcatalyst-platform-ui/` project
2. [ ] Set up Vite + Vue 3 + TypeScript + DaisyUI
3. [ ] Implement shared components
4. [ ] Implement tenant management views
5. [ ] Implement user management views
6. [ ] Implement role assignment view
7. [ ] Implement OAuth client management
8. [ ] Configure build output to platform resources
9. [ ] Add SPA routing in Quarkus

### Phase 10: TenantAuthConfig Updates
1. [ ] Add `idpManagesRoles` and `idpRolesClaim` fields
2. [ ] Write Flyway migration
3. [ ] Update AuthConfigRequest DTO

### Phase 11: Role Sync to Auth DB
1. [ ] Create `auth_roles` table
2. [ ] Update `IdpRoleSyncService` to sync to Auth DB
3. [ ] Add role list endpoint for UI

### Phase 12: Federated User Role Sync
1. [ ] Add `RoleSource` enum
2. [ ] Update `PrincipalRole` entity with source field
3. [ ] Implement `syncRolesFromIdpToken`
4. [ ] Integrate with OIDC callback handler

---

**Document Status:** Ready for implementation
**Created:** 2025-11-26
**Updated:** 2025-11-26 (Added roles, JWT content, Platform UI)
**Auth Modes:** Embedded (full IdP) + Remote (validation only)
**OAuth2 Flows:** Authorization Code + PKCE, Refresh Token, Client Credentials
**UI Stack:** Bun + TypeScript + Vue.js 3 + Vite + DaisyUI
