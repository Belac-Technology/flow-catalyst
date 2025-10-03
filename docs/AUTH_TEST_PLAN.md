# Authentication Services Test Plan

## Overview

This document outlines a comprehensive testing strategy for the FlowCatalyst authentication and authorization system. The tests are organized into three categories:

1. **Unit Tests**: Test individual service methods with mocked dependencies
2. **Integration Tests**: Test full flows with real database interactions
3. **Security Tests**: Test critical security controls and attack scenarios

## Test Organization

```
src/test/java/tech/flowcatalyst/platform/
├── service/                          # Unit tests (mocked dependencies)
│   ├── TenantAccessServiceTest.java
│   ├── PasswordServiceTest.java
│   ├── TokenServiceTest.java
│   ├── AuthorizationServiceTest.java
│   ├── UserServiceTest.java
│   ├── TenantServiceTest.java
│   ├── RoleServiceTest.java
│   └── OidcSyncServiceTest.java      # ⚠️ CRITICAL SECURITY
│
├── integration/                      # Integration tests (real database)
│   ├── AuthenticationFlowIntegrationTest.java
│   ├── MultiTenantAccessIntegrationTest.java
│   ├── PermissionCheckIntegrationTest.java
│   └── IdpRoleSyncIntegrationTest.java
│
└── security/                         # Security-specific tests
    ├── IdpRoleAuthorizationSecurityTest.java  # ⚠️ CRITICAL
    ├── TenantIsolationSecurityTest.java
    ├── PasswordSecurityTest.java
    └── UnauthorizedAccessSecurityTest.java
```

---

## 1. Unit Tests (Mocked Dependencies)

### 1.1 TenantAccessServiceTest

**Purpose**: Test tenant access calculation logic

**Dependencies to Mock**:
- `AnchorDomainRepository`
- `TenantRepository`
- `TenantAccessGrantRepository`

**Test Cases**:

```java
@ExtendWith(MockitoExtension.class)
class TenantAccessServiceTest {

    @Mock AnchorDomainRepository anchorDomainRepo;
    @Mock TenantRepository tenantRepo;
    @Mock TenantAccessGrantRepository grantRepo;

    @InjectMocks TenantAccessService service;

    // ANCHOR DOMAIN TESTS
    @Test
    void getAccessibleTenants_shouldReturnAllTenants_whenUserIsAnchorDomain() {
        // Arrange: User with anchor domain email
        // Mock: anchorDomainRepo.existsByDomain() returns true
        // Mock: tenantRepo.findAllActive() returns 5 tenants
        // Act: service.getAccessibleTenants(principal)
        // Assert: Returns all 5 tenant IDs
    }

    @Test
    void getAccessibleTenants_shouldExcludeInactiveTenants_whenUserIsAnchorDomain() {
        // Arrange: Anchor user, mix of active/inactive tenants
        // Assert: Only active tenants returned
    }

    // HOME TENANT TESTS
    @Test
    void getAccessibleTenants_shouldReturnHomeTenant_whenUserHasHomeTenant() {
        // Arrange: User with tenantId = 123, no grants
        // Assert: Returns [123]
    }

    @Test
    void getAccessibleTenants_shouldReturnEmpty_whenUserHasNoHomeTenantAndNoGrants() {
        // Arrange: User with tenantId = null, no grants
        // Assert: Returns empty set
    }

    // TENANT ACCESS GRANT TESTS
    @Test
    void getAccessibleTenants_shouldReturnGrantedTenants_whenUserHasValidGrants() {
        // Arrange: User with 3 valid grants
        // Mock: grantRepo.findByPrincipalId() returns grants
        // Assert: Returns home tenant + 3 granted tenants
    }

    @Test
    void getAccessibleTenants_shouldExcludeExpiredGrants_whenGrantsHaveExpired() {
        // Arrange: Mix of expired and valid grants
        // Assert: Only valid grants included
    }

    @Test
    void getAccessibleTenants_shouldIncludeGrantsWithNullExpiry_whenExpiryIsNull() {
        // Arrange: Grant with expiresAt = null (never expires)
        // Assert: Grant included
    }

    // COMBINATION TESTS
    @Test
    void getAccessibleTenants_shouldCombineHomeAndGrants_whenBothExist() {
        // Arrange: User with home tenant + 2 grants
        // Assert: Returns 3 unique tenant IDs
    }

    @Test
    void getAccessibleTenants_shouldDeduplicateTenants_whenGrantMatchesHomeTenant() {
        // Arrange: Home tenant + grant for same tenant
        // Assert: Returns only 1 instance of tenant
    }
}
```

**Critical Edge Cases**:
- Null principal
- Null userIdentity
- Empty grant list
- All grants expired
- Circular references

---

### 1.2 PasswordServiceTest

**Purpose**: Test password hashing and validation

**No Dependencies** (standalone service)

**Test Cases**:

```java
class PasswordServiceTest {

    PasswordService service = new PasswordService();

    // HASHING TESTS
    @Test
    void hashPassword_shouldProduceDifferentHashes_whenCalledTwiceWithSamePassword() {
        // Act: Hash same password twice
        String hash1 = service.hashPassword("SecurePass123!");
        String hash2 = service.hashPassword("SecurePass123!");
        // Assert: Hashes are different (due to random salt)
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void hashPassword_shouldThrowException_whenPasswordIsNull() {
        // Assert: IllegalArgumentException thrown
    }

    @Test
    void hashPassword_shouldThrowException_whenPasswordIsEmpty() {
        // Assert: IllegalArgumentException thrown
    }

    // VERIFICATION TESTS
    @Test
    void verifyPassword_shouldReturnTrue_whenPasswordIsCorrect() {
        // Arrange: Hash a password
        String hash = service.hashPassword("SecurePass123!");
        // Act & Assert
        assertThat(service.verifyPassword("SecurePass123!", hash)).isTrue();
    }

    @Test
    void verifyPassword_shouldReturnFalse_whenPasswordIsIncorrect() {
        String hash = service.hashPassword("SecurePass123!");
        assertThat(service.verifyPassword("WrongPass123!", hash)).isFalse();
    }

    @Test
    void verifyPassword_shouldReturnFalse_whenPasswordIsNull() {
        String hash = service.hashPassword("SecurePass123!");
        assertThat(service.verifyPassword(null, hash)).isFalse();
    }

    @Test
    void verifyPassword_shouldReturnFalse_whenHashIsNull() {
        assertThat(service.verifyPassword("SecurePass123!", null)).isFalse();
    }

    @Test
    void verifyPassword_shouldReturnFalse_whenHashIsInvalid() {
        assertThat(service.verifyPassword("SecurePass123!", "invalid-hash")).isFalse();
    }

    // COMPLEXITY VALIDATION TESTS
    @Test
    void validatePasswordComplexity_shouldThrowException_whenPasswordTooShort() {
        // Arrange: Password with 11 chars (needs 12)
        // Assert: IllegalArgumentException with message about length
        assertThatThrownBy(() -> service.validatePasswordComplexity("Short123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 12 characters");
    }

    @Test
    void validatePasswordComplexity_shouldThrowException_whenNoUppercase() {
        assertThatThrownBy(() -> service.validatePasswordComplexity("lowercase123!"))
            .hasMessageContaining("uppercase");
    }

    @Test
    void validatePasswordComplexity_shouldThrowException_whenNoLowercase() {
        assertThatThrownBy(() -> service.validatePasswordComplexity("UPPERCASE123!"))
            .hasMessageContaining("lowercase");
    }

    @Test
    void validatePasswordComplexity_shouldThrowException_whenNoDigit() {
        assertThatThrownBy(() -> service.validatePasswordComplexity("NoDigitsHere!"))
            .hasMessageContaining("digit");
    }

    @Test
    void validatePasswordComplexity_shouldThrowException_whenNoSpecialChar() {
        assertThatThrownBy(() -> service.validatePasswordComplexity("NoSpecial123"))
            .hasMessageContaining("special character");
    }

    @Test
    void validatePasswordComplexity_shouldNotThrow_whenPasswordMeetsAllRequirements() {
        // Valid: 12+ chars, upper, lower, digit, special
        assertThatCode(() -> service.validatePasswordComplexity("ValidPass123!"))
            .doesNotThrowAnyException();
    }

    @Test
    void validateAndHashPassword_shouldHashPassword_whenPasswordIsValid() {
        String hash = service.validateAndHashPassword("ValidPass123!");
        assertThat(hash).isNotNull();
        assertThat(service.verifyPassword("ValidPass123!", hash)).isTrue();
    }

    @Test
    void validateAndHashPassword_shouldThrowException_whenPasswordInvalid() {
        assertThatThrownBy(() -> service.validateAndHashPassword("weak"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

**Critical Edge Cases**:
- Very long passwords (performance)
- Unicode characters in passwords
- Special characters edge cases
- Empty string vs null

---

### 1.3 TokenServiceTest

**Purpose**: Test JWT token generation

**Dependencies to Mock**: None (uses Quarkus JWT config)

**Test Cases**:

```java
@QuarkusTest
class TokenServiceTest {

    @Inject TokenService service;

    @Test
    void issueToken_shouldIncludeSubject_whenCalled() {
        // Act: Issue token for principal 12345
        String token = service.issueToken(12345L, "USER", Duration.ofHours(8));
        // Assert: Decode JWT and verify subject = "12345"
        JsonWebToken jwt = decodeToken(token);
        assertThat(jwt.getSubject()).isEqualTo("12345");
    }

    @Test
    void issueToken_shouldIncludeType_whenCalled() {
        String token = service.issueToken(12345L, "USER", Duration.ofHours(8));
        JsonWebToken jwt = decodeToken(token);
        assertThat(jwt.getClaim("type")).isEqualTo("USER");
    }

    @Test
    void issueToken_shouldSetExpiry_whenExpiryProvided() {
        Instant before = Instant.now();
        String token = service.issueToken(12345L, "USER", Duration.ofHours(8));
        Instant after = Instant.now();

        JsonWebToken jwt = decodeToken(token);
        Instant expiry = Instant.ofEpochSecond(jwt.getExpirationTime());

        // Expiry should be ~8 hours from now
        assertThat(expiry).isAfter(before.plus(Duration.ofHours(7)));
        assertThat(expiry).isBefore(after.plus(Duration.ofHours(9)));
    }

    @Test
    void issueToken_shouldUseDefaultExpiry_whenExpiryIsNull() {
        String token = service.issueToken(12345L, "SERVICE", null);
        JsonWebToken jwt = decodeToken(token);
        // Should use default expiry (365 days)
        Instant expiry = Instant.ofEpochSecond(jwt.getExpirationTime());
        assertThat(expiry).isAfter(Instant.now().plus(Duration.ofDays(364)));
    }

    @Test
    void issueTokenWithRoles_shouldIncludeGroups_whenRolesProvided() {
        Set<String> roles = Set.of("admin", "operator");
        String token = service.issueTokenWithRoles(12345L, "USER", roles, Duration.ofHours(8));

        JsonWebToken jwt = decodeToken(token);
        assertThat(jwt.getGroups()).containsExactlyInAnyOrder("admin", "operator");
    }

    @Test
    void issueSessionToken_shouldHave8HourExpiry_whenCalled() {
        Set<String> roles = Set.of("user");
        String token = service.issueSessionToken(12345L, roles);

        JsonWebToken jwt = decodeToken(token);
        Instant expiry = Instant.ofEpochSecond(jwt.getExpirationTime());

        // Should expire in ~8 hours
        assertThat(expiry).isAfter(Instant.now().plus(Duration.ofHours(7)));
        assertThat(expiry).isBefore(Instant.now().plus(Duration.ofHours(9)));
    }

    @Test
    void issueServiceAccountToken_shouldHaveLongExpiry_whenCalled() {
        String token = service.issueServiceAccountToken(12345L);

        JsonWebToken jwt = decodeToken(token);
        assertThat(jwt.getClaim("type")).isEqualTo("SERVICE");

        Instant expiry = Instant.ofEpochSecond(jwt.getExpirationTime());
        assertThat(expiry).isAfter(Instant.now().plus(Duration.ofDays(364)));
    }
}
```

---

### 1.4 AuthorizationServiceTest

**Purpose**: Test RBAC permission checking

**Dependencies to Mock**:
- `PrincipalRoleRepository`
- `RoleRepository`

**Test Cases**:

```java
@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

    @Mock PrincipalRoleRepository principalRoleRepo;
    @Mock RoleRepository roleRepo;

    @InjectMocks AuthorizationService service;

    @Test
    void hasPermission_shouldReturnTrue_whenPrincipalHasPermission() {
        // Arrange: Principal has role with permission "dispatch-job:create"
        PrincipalRole pr = new PrincipalRole();
        pr.roleId = 1L;
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        Role role = new Role();
        role.id = 1L;
        role.permissions = List.of(new Permission("dispatch-job", "create", "Create jobs"));
        when(roleRepo.find("id in ?1", Set.of(1L))).thenReturn(mockQuery(List.of(role)));

        // Act & Assert
        assertThat(service.hasPermission(123L, "dispatch-job", "create")).isTrue();
    }

    @Test
    void hasPermission_shouldReturnFalse_whenPrincipalLacksPermission() {
        // Arrange: Principal has role but wrong permission
        PrincipalRole pr = new PrincipalRole();
        pr.roleId = 1L;
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        Role role = new Role();
        role.permissions = List.of(new Permission("dispatch-job", "read", "Read jobs"));
        when(roleRepo.find("id in ?1", Set.of(1L))).thenReturn(mockQuery(List.of(role)));

        // Act & Assert
        assertThat(service.hasPermission(123L, "dispatch-job", "create")).isFalse();
    }

    @Test
    void hasPermission_shouldReturnFalse_whenPrincipalHasNoRoles() {
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of());
        assertThat(service.hasPermission(123L, "dispatch-job", "create")).isFalse();
    }

    @Test
    void hasPermission_shouldReturnTrue_whenMultipleRolesAndOneHasPermission() {
        // Arrange: 2 roles, only second has the permission
        PrincipalRole pr1 = new PrincipalRole(); pr1.roleId = 1L;
        PrincipalRole pr2 = new PrincipalRole(); pr2.roleId = 2L;
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr1, pr2));

        Role role1 = new Role();
        role1.permissions = List.of(new Permission("other", "read", ""));

        Role role2 = new Role();
        role2.permissions = List.of(new Permission("dispatch-job", "create", ""));

        when(roleRepo.find("id in ?1", Set.of(1L, 2L)))
            .thenReturn(mockQuery(List.of(role1, role2)));

        assertThat(service.hasPermission(123L, "dispatch-job", "create")).isTrue();
    }

    @Test
    void requirePermission_shouldNotThrow_whenPrincipalHasPermission() {
        // Setup hasPermission to return true
        assertThatCode(() -> service.requirePermission(123L, "dispatch-job", "create"))
            .doesNotThrowAnyException();
    }

    @Test
    void requirePermission_shouldThrowForbiddenException_whenPrincipalLacksPermission() {
        // Setup hasPermission to return false
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.requirePermission(123L, "dispatch-job", "create"))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("dispatch-job:create");
    }

    @Test
    void hasRole_shouldReturnTrue_whenPrincipalHasRole() {
        // Setup principal with "admin" role
        assertThat(service.hasRole(123L, "admin")).isTrue();
    }

    @Test
    void hasAnyRole_shouldReturnTrue_whenPrincipalHasOneOfRoles() {
        // Setup principal with "operator" role
        assertThat(service.hasAnyRole(123L, "admin", "operator", "viewer")).isTrue();
    }

    @Test
    void hasAllRoles_shouldReturnTrue_whenPrincipalHasAllRoles() {
        // Setup principal with both "admin" and "operator" roles
        assertThat(service.hasAllRoles(123L, "admin", "operator")).isTrue();
    }

    @Test
    void hasAllRoles_shouldReturnFalse_whenPrincipalMissingOneRole() {
        // Setup principal with only "admin" role
        assertThat(service.hasAllRoles(123L, "admin", "operator")).isFalse();
    }
}
```

---

### 1.5 UserServiceTest

**Purpose**: Test user CRUD operations

**Dependencies to Mock**:
- `PrincipalRepository`
- `PasswordService`

**Test Cases**:

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock PrincipalRepository principalRepo;
    @Mock PasswordService passwordService;

    @InjectMocks UserService service;

    // CREATE INTERNAL USER TESTS
    @Test
    void createInternalUser_shouldCreateUser_whenValidInput() {
        // Arrange
        when(principalRepo.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(passwordService.validateAndHashPassword("SecurePass123!"))
            .thenReturn("$2a$10$hashedhash");

        // Act
        Principal principal = service.createInternalUser(
            "user@example.com", "SecurePass123!", "Test User", 1L);

        // Assert
        assertThat(principal.type).isEqualTo(PrincipalType.USER);
        assertThat(principal.tenantId).isEqualTo(1L);
        assertThat(principal.userIdentity.email).isEqualTo("user@example.com");
        assertThat(principal.userIdentity.emailDomain).isEqualTo("example.com");
        assertThat(principal.userIdentity.idpType).isEqualTo(IdpType.INTERNAL);
        assertThat(principal.userIdentity.passwordHash).isEqualTo("$2a$10$hashedhash");
        verify(principalRepo).persist(principal);
    }

    @Test
    void createInternalUser_shouldThrowException_whenEmailIsNull() {
        assertThatThrownBy(() -> service.createInternalUser(null, "pass", "name", 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Email");
    }

    @Test
    void createInternalUser_shouldThrowException_whenEmailAlreadyExists() {
        when(principalRepo.findByEmail("existing@example.com"))
            .thenReturn(Optional.of(new Principal()));

        assertThatThrownBy(() -> service.createInternalUser(
            "existing@example.com", "SecurePass123!", "User", 1L))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void createInternalUser_shouldExtractDomainCorrectly_whenEmailValid() {
        when(principalRepo.findByEmail(any())).thenReturn(Optional.empty());
        when(passwordService.validateAndHashPassword(any())).thenReturn("hash");

        Principal p = service.createInternalUser(
            "user@subdomain.example.com", "pass", "name", 1L);

        assertThat(p.userIdentity.emailDomain).isEqualTo("subdomain.example.com");
    }

    @Test
    void createInternalUser_shouldThrowException_whenEmailInvalidFormat() {
        assertThatThrownBy(() -> service.createInternalUser(
            "notanemail", "pass", "name", 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid email");
    }

    // CREATE OR UPDATE OIDC USER TESTS
    @Test
    void createOrUpdateOidcUser_shouldCreateNewUser_whenUserDoesNotExist() {
        when(principalRepo.findByEmail("oidc@example.com")).thenReturn(Optional.empty());

        Principal p = service.createOrUpdateOidcUser(
            "oidc@example.com", "OIDC User", "google-123", 1L);

        assertThat(p.type).isEqualTo(PrincipalType.USER);
        assertThat(p.userIdentity.idpType).isEqualTo(IdpType.OIDC);
        assertThat(p.userIdentity.externalIdpId).isEqualTo("google-123");
        assertThat(p.userIdentity.passwordHash).isNull();
        assertThat(p.userIdentity.lastLoginAt).isNotNull();
        verify(principalRepo).persist(p);
    }

    @Test
    void createOrUpdateOidcUser_shouldUpdateExisting_whenUserExists() {
        Principal existing = new Principal();
        existing.id = 123L;
        existing.name = "Old Name";
        existing.userIdentity = new UserIdentity();
        existing.userIdentity.email = "oidc@example.com";
        existing.userIdentity.externalIdpId = "old-id";

        when(principalRepo.findByEmail("oidc@example.com"))
            .thenReturn(Optional.of(existing));

        Principal updated = service.createOrUpdateOidcUser(
            "oidc@example.com", "New Name", "new-id", 1L);

        assertThat(updated.id).isEqualTo(123L);
        assertThat(updated.name).isEqualTo("New Name");
        assertThat(updated.userIdentity.externalIdpId).isEqualTo("new-id");
        assertThat(updated.userIdentity.lastLoginAt).isNotNull();
        verify(principalRepo, never()).persist(any()); // Update, not persist
    }

    // PASSWORD OPERATIONS TESTS
    @Test
    void resetPassword_shouldUpdatePasswordHash_whenValid() {
        Principal principal = createTestInternalUser(123L);
        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(principal));
        when(passwordService.validateAndHashPassword("NewPass123!"))
            .thenReturn("$2a$10$newhash");

        service.resetPassword(123L, "NewPass123!");

        assertThat(principal.userIdentity.passwordHash).isEqualTo("$2a$10$newhash");
    }

    @Test
    void resetPassword_shouldThrowException_whenUserNotFound() {
        when(principalRepo.findByIdOptional(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(999L, "NewPass123!"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void resetPassword_shouldThrowException_whenUserIsOIDC() {
        Principal oidcUser = createTestOidcUser(123L);
        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(oidcUser));

        assertThatThrownBy(() -> service.resetPassword(123L, "NewPass123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("OIDC");
    }

    @Test
    void changePassword_shouldUpdatePassword_whenOldPasswordCorrect() {
        Principal principal = createTestInternalUser(123L);
        principal.userIdentity.passwordHash = "$2a$10$oldhash";

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(principal));
        when(passwordService.verifyPassword("OldPass123!", "$2a$10$oldhash"))
            .thenReturn(true);
        when(passwordService.validateAndHashPassword("NewPass123!"))
            .thenReturn("$2a$10$newhash");

        service.changePassword(123L, "OldPass123!", "NewPass123!");

        assertThat(principal.userIdentity.passwordHash).isEqualTo("$2a$10$newhash");
    }

    @Test
    void changePassword_shouldThrowException_whenOldPasswordIncorrect() {
        Principal principal = createTestInternalUser(123L);
        principal.userIdentity.passwordHash = "$2a$10$oldhash";

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(principal));
        when(passwordService.verifyPassword("WrongPass!", "$2a$10$oldhash"))
            .thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(123L, "WrongPass!", "NewPass123!"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("incorrect");
    }

    // ACTIVATION/DEACTIVATION TESTS
    @Test
    void deactivateUser_shouldSetActiveFalse_whenUserExists() {
        Principal principal = createTestInternalUser(123L);
        principal.active = true;

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(principal));

        service.deactivateUser(123L);

        assertThat(principal.active).isFalse();
    }

    @Test
    void activateUser_shouldSetActiveTrue_whenUserExists() {
        Principal principal = createTestInternalUser(123L);
        principal.active = false;

        when(principalRepo.findByIdOptional(123L)).thenReturn(Optional.of(principal));

        service.activateUser(123L);

        assertThat(principal.active).isTrue();
    }

    // Helper methods
    private Principal createTestInternalUser(Long id) {
        Principal p = new Principal();
        p.id = id;
        p.type = PrincipalType.USER;
        p.userIdentity = new UserIdentity();
        p.userIdentity.idpType = IdpType.INTERNAL;
        return p;
    }

    private Principal createTestOidcUser(Long id) {
        Principal p = new Principal();
        p.id = id;
        p.type = PrincipalType.USER;
        p.userIdentity = new UserIdentity();
        p.userIdentity.idpType = IdpType.OIDC;
        return p;
    }
}
```

---

### 1.6 OidcSyncServiceTest ⚠️ CRITICAL SECURITY

**Purpose**: Test IDP role authorization (CRITICAL SECURITY CONTROL)

**Dependencies to Mock**:
- `IdpRoleMappingRepository`
- `RoleService`
- `UserService`

**Test Cases**:

```java
@ExtendWith(MockitoExtension.class)
class OidcSyncServiceTest {

    @Mock IdpRoleMappingRepository idpRoleMappingRepo;
    @Mock RoleService roleService;
    @Mock UserService userService;

    @InjectMocks OidcSyncService service;

    // ========================================
    // CRITICAL SECURITY TESTS - IDP ROLE AUTHORIZATION
    // ========================================

    @Test
    void syncIdpRoles_shouldAcceptAuthorizedRole_whenRoleInMappings() {
        // ARRANGE: IDP sends "keycloak-admin" role
        Principal principal = createTestPrincipal(123L);
        List<String> idpRoles = List.of("keycloak-admin");

        // Mock: "keycloak-admin" is in idp_role_mappings, maps to internal role 10
        IdpRoleMapping mapping = new IdpRoleMapping();
        mapping.idpRoleName = "keycloak-admin";
        mapping.internalRoleId = 10L;
        when(idpRoleMappingRepo.findByIdpRoleName("keycloak-admin"))
            .thenReturn(Optional.of(mapping));

        when(roleService.removeRolesBySource(123L, "IDP_SYNC")).thenReturn(0L);

        // ACT
        Set<Long> acceptedRoles = service.syncIdpRoles(principal, idpRoles);

        // ASSERT
        assertThat(acceptedRoles).containsExactly(10L);
        verify(roleService).assignRole(123L, 10L, "IDP_SYNC");
    }

    @Test
    void syncIdpRoles_shouldRejectUnauthorizedRole_whenRoleNotInMappings() {
        // CRITICAL SECURITY TEST
        // ARRANGE: IDP sends "super-admin" role (not authorized)
        Principal principal = createTestPrincipal(123L);
        List<String> idpRoles = List.of("super-admin");

        // Mock: "super-admin" is NOT in idp_role_mappings
        when(idpRoleMappingRepo.findByIdpRoleName("super-admin"))
            .thenReturn(Optional.empty());

        when(roleService.removeRolesBySource(123L, "IDP_SYNC")).thenReturn(0L);

        // ACT
        Set<Long> acceptedRoles = service.syncIdpRoles(principal, idpRoles);

        // ASSERT
        assertThat(acceptedRoles).isEmpty(); // NO roles accepted
        verify(roleService, never()).assignRole(anyLong(), anyLong(), anyString());
        // Should log security warning (verify with log capture if possible)
    }

    @Test
    void syncIdpRoles_shouldAcceptOnlyAuthorizedRoles_whenMixedRoles() {
        // CRITICAL SECURITY TEST
        // ARRANGE: IDP sends mix of authorized and unauthorized roles
        Principal principal = createTestPrincipal(123L);
        List<String> idpRoles = List.of(
            "keycloak-operator",    // AUTHORIZED → maps to role 20
            "malicious-admin",      // UNAUTHORIZED → rejected
            "keycloak-viewer"       // AUTHORIZED → maps to role 30
        );

        IdpRoleMapping mapping1 = new IdpRoleMapping();
        mapping1.idpRoleName = "keycloak-operator";
        mapping1.internalRoleId = 20L;

        IdpRoleMapping mapping2 = new IdpRoleMapping();
        mapping2.idpRoleName = "keycloak-viewer";
        mapping2.internalRoleId = 30L;

        when(idpRoleMappingRepo.findByIdpRoleName("keycloak-operator"))
            .thenReturn(Optional.of(mapping1));
        when(idpRoleMappingRepo.findByIdpRoleName("malicious-admin"))
            .thenReturn(Optional.empty());
        when(idpRoleMappingRepo.findByIdpRoleName("keycloak-viewer"))
            .thenReturn(Optional.of(mapping2));

        when(roleService.removeRolesBySource(123L, "IDP_SYNC")).thenReturn(0L);

        // ACT
        Set<Long> acceptedRoles = service.syncIdpRoles(principal, idpRoles);

        // ASSERT: Only 2 authorized roles accepted
        assertThat(acceptedRoles).containsExactlyInAnyOrder(20L, 30L);
        verify(roleService).assignRole(123L, 20L, "IDP_SYNC");
        verify(roleService).assignRole(123L, 30L, "IDP_SYNC");
        verify(roleService, times(2)).assignRole(anyLong(), anyLong(), eq("IDP_SYNC"));
    }

    @Test
    void syncIdpRoles_shouldRemoveOldIdpRoles_beforeAddingNew() {
        // ARRANGE: Principal previously had IDP roles, now gets new ones
        Principal principal = createTestPrincipal(123L);
        List<String> idpRoles = List.of("keycloak-admin");

        IdpRoleMapping mapping = new IdpRoleMapping();
        mapping.idpRoleName = "keycloak-admin";
        mapping.internalRoleId = 10L;
        when(idpRoleMappingRepo.findByIdpRoleName("keycloak-admin"))
            .thenReturn(Optional.of(mapping));

        // Mock: Principal had 2 old IDP-sourced roles
        when(roleService.removeRolesBySource(123L, "IDP_SYNC")).thenReturn(2L);

        // ACT
        service.syncIdpRoles(principal, idpRoles);

        // ASSERT: Old roles removed BEFORE new roles added
        InOrder inOrder = inOrder(roleService);
        inOrder.verify(roleService).removeRolesBySource(123L, "IDP_SYNC");
        inOrder.verify(roleService).assignRole(123L, 10L, "IDP_SYNC");
    }

    @Test
    void syncIdpRoles_shouldNotRemoveManualRoles_onlyIdpRoles() {
        // IMPORTANT: Only IDP_SYNC roles removed, not MANUAL assignments
        Principal principal = createTestPrincipal(123L);

        // ACT
        service.syncIdpRoles(principal, List.of());

        // ASSERT: Only removes IDP_SYNC source
        verify(roleService).removeRolesBySource(123L, "IDP_SYNC");
        verify(roleService, never()).removeRolesBySource(eq(123L), eq("MANUAL"));
    }

    @Test
    void syncIdpRoles_shouldHandleEmptyRoleList_whenNoRolesProvided() {
        Principal principal = createTestPrincipal(123L);
        when(roleService.removeRolesBySource(123L, "IDP_SYNC")).thenReturn(2L);

        Set<Long> acceptedRoles = service.syncIdpRoles(principal, List.of());

        assertThat(acceptedRoles).isEmpty();
        verify(roleService).removeRolesBySource(123L, "IDP_SYNC"); // Old roles removed
        verify(roleService, never()).assignRole(anyLong(), anyLong(), anyString());
    }

    @Test
    void syncIdpRoles_shouldHandleNullRoleList_whenNoRolesProvided() {
        Principal principal = createTestPrincipal(123L);
        when(roleService.removeRolesBySource(123L, "IDP_SYNC")).thenReturn(0L);

        Set<Long> acceptedRoles = service.syncIdpRoles(principal, null);

        assertThat(acceptedRoles).isEmpty();
    }

    // ========================================
    // ATTACK SCENARIO TESTS
    // ========================================

    @Test
    void syncIdpRoles_shouldPreventAttack_compromisedIdpGrantsSuperAdmin() {
        // ATTACK SCENARIO: Compromised partner IDP grants all users "super-admin"
        // EXPECTED: Role rejected because not in idp_role_mappings

        Principal principal = createTestPrincipal(123L);
        principal.userIdentity.email = "attacker@partner.com";

        List<String> maliciousRoles = List.of("super-admin", "platform-owner", "god-mode");

        // Mock: None of these roles in mappings table
        when(idpRoleMappingRepo.findByIdpRoleName(anyString()))
            .thenReturn(Optional.empty());
        when(roleService.removeRolesBySource(123L, "IDP_SYNC")).thenReturn(0L);

        // ACT
        Set<Long> acceptedRoles = service.syncIdpRoles(principal, maliciousRoles);

        // ASSERT: ALL roles rejected
        assertThat(acceptedRoles).isEmpty();
        verify(roleService, never()).assignRole(anyLong(), anyLong(), anyString());
        // Security warnings should be logged (3 times)
    }

    @Test
    void syncIdpRoles_shouldPreventAttack_misconfiguredIdpSendsWrongRoles() {
        // ATTACK SCENARIO: Misconfigured IDP sends internal role names instead of IDP names
        // EXPECTED: Rejected because internal role names don't match IDP role mappings

        Principal principal = createTestPrincipal(123L);
        List<String> wrongRoles = List.of("platform-admin", "tenant-admin"); // Internal names!

        when(idpRoleMappingRepo.findByIdpRoleName(anyString()))
            .thenReturn(Optional.empty());
        when(roleService.removeRolesBySource(123L, "IDP_SYNC")).thenReturn(0L);

        Set<Long> acceptedRoles = service.syncIdpRoles(principal, wrongRoles);

        assertThat(acceptedRoles).isEmpty();
    }

    // ========================================
    // USER SYNC TESTS
    // ========================================

    @Test
    void syncOidcUser_shouldCreateOrUpdateUser_whenCalled() {
        Principal principal = createTestPrincipal(123L);
        when(userService.createOrUpdateOidcUser("user@example.com", "User", "google-123", 1L))
            .thenReturn(principal);

        Principal result = service.syncOidcUser("user@example.com", "User", "google-123", 1L);

        assertThat(result).isEqualTo(principal);
        verify(userService).updateLastLogin(123L);
    }

    @Test
    void syncOidcLogin_shouldSyncBothUserAndRoles_whenCalled() {
        Principal principal = createTestPrincipal(123L);
        when(userService.createOrUpdateOidcUser(any(), any(), any(), any()))
            .thenReturn(principal);
        when(roleService.removeRolesBySource(123L, "IDP_SYNC")).thenReturn(0L);

        List<String> idpRoles = List.of("keycloak-admin");
        IdpRoleMapping mapping = new IdpRoleMapping();
        mapping.internalRoleId = 10L;
        when(idpRoleMappingRepo.findByIdpRoleName("keycloak-admin"))
            .thenReturn(Optional.of(mapping));

        Principal result = service.syncOidcLogin(
            "user@example.com", "User", "google-123", 1L, idpRoles);

        assertThat(result).isEqualTo(principal);
        verify(userService).createOrUpdateOidcUser("user@example.com", "User", "google-123", 1L);
        verify(userService).updateLastLogin(123L);
        verify(roleService).assignRole(123L, 10L, "IDP_SYNC");
    }

    // Helper method
    private Principal createTestPrincipal(Long id) {
        Principal p = new Principal();
        p.id = id;
        p.type = PrincipalType.USER;
        p.userIdentity = new UserIdentity();
        p.userIdentity.email = "test@example.com";
        p.userIdentity.idpType = IdpType.OIDC;
        return p;
    }
}
```

**CRITICAL NOTE**: This test suite is the most important because it validates the security control that prevents unauthorized access via compromised/misconfigured IDPs.

---

## 2. Integration Tests (Real Database)

Integration tests use `@QuarkusTest` with a real PostgreSQL database (or H2 in-memory for tests).

### 2.1 AuthenticationFlowIntegrationTest

**Purpose**: Test complete authentication flows end-to-end

```java
@QuarkusTest
@TestTransaction
class AuthenticationFlowIntegrationTest {

    @Inject UserService userService;
    @Inject PasswordService passwordService;
    @Inject AuthorizationService authzService;
    @Inject RoleService roleService;

    @Test
    void internalAuth_shouldAuthenticateUser_whenCredentialsCorrect() {
        // ARRANGE: Create user with password
        Principal user = userService.createInternalUser(
            "test@example.com", "ValidPass123!", "Test User", null);

        // ACT: Verify password
        boolean valid = passwordService.verifyPassword(
            "ValidPass123!", user.userIdentity.passwordHash);

        // ASSERT
        assertThat(valid).isTrue();
    }

    @Test
    void internalAuth_shouldRejectUser_whenPasswordWrong() {
        Principal user = userService.createInternalUser(
            "test@example.com", "ValidPass123!", "Test User", null);

        boolean valid = passwordService.verifyPassword(
            "WrongPass!", user.userIdentity.passwordHash);

        assertThat(valid).isFalse();
    }

    @Test
    void internalAuth_shouldRejectUser_whenDeactivated() {
        Principal user = userService.createInternalUser(
            "test@example.com", "ValidPass123!", "Test User", null);

        userService.deactivateUser(user.id);

        // Check user is deactivated
        Principal deactivated = userService.findById(user.id).get();
        assertThat(deactivated.active).isFalse();

        // Authentication should check active flag and reject
    }

    @Test
    void roleBasedAccess_shouldGrantPermissions_whenRoleAssigned() {
        // ARRANGE: Create user
        Principal user = userService.createInternalUser(
            "test@example.com", "ValidPass123!", "Test User", null);

        // Create role with permission
        Role role = roleService.createRole(
            "operator",
            "Operator role",
            List.of(new Permission("dispatch-job", "create", "Create jobs")),
            false
        );

        // Assign role to user
        roleService.assignRole(user.id, role.id, "MANUAL");

        // ACT & ASSERT: User should have permission
        assertThat(authzService.hasPermission(user.id, "dispatch-job", "create")).isTrue();
        assertThat(authzService.hasPermission(user.id, "dispatch-job", "delete")).isFalse();
    }
}
```

### 2.2 IdpRoleSyncIntegrationTest ⚠️ CRITICAL

**Purpose**: Test IDP role sync with real database

```java
@QuarkusTest
@TestTransaction
class IdpRoleSyncIntegrationTest {

    @Inject OidcSyncService oidcSyncService;
    @Inject RoleService roleService;
    @Inject IdpRoleMappingRepository idpRoleMappingRepo;
    @Inject PrincipalRepository principalRepo;

    @Test
    void idpRoleSync_shouldAssignAuthorizedRoles_whenMappingExists() {
        // ARRANGE: Create internal role
        Role operatorRole = roleService.createRole(
            "operator", "Operator", List.of(), false);

        // Create IDP role mapping
        IdpRoleMapping mapping = new IdpRoleMapping();
        mapping.id = TsidGenerator.generate();
        mapping.idpRoleName = "keycloak-operator";
        mapping.internalRoleId = operatorRole.id;
        idpRoleMappingRepo.persist(mapping);

        // ACT: Sync OIDC login with IDP role
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@example.com",
            "OIDC User",
            "google-123",
            null,
            List.of("keycloak-operator")
        );

        // ASSERT: User has operator role
        List<Role> roles = roleService.findRolesByPrincipal(principal.id);
        assertThat(roles).hasSize(1);
        assertThat(roles.get(0).name).isEqualTo("operator");
    }

    @Test
    void idpRoleSync_shouldRejectUnauthorizedRoles_whenNoMapping() {
        // CRITICAL SECURITY TEST with real database

        // ACT: Sync with unauthorized role
        Principal principal = oidcSyncService.syncOidcLogin(
            "attacker@partner.com",
            "Attacker",
            "evil-123",
            null,
            List.of("super-admin", "platform-owner") // NO mappings for these
        );

        // ASSERT: NO roles assigned
        List<Role> roles = roleService.findRolesByPrincipal(principal.id);
        assertThat(roles).isEmpty();
    }

    @Test
    void idpRoleSync_shouldUpdateRoles_whenUserLoginsAgainWithDifferentRoles() {
        // ARRANGE: Create 2 role mappings
        Role role1 = roleService.createRole("role1", "Role 1", List.of(), false);
        Role role2 = roleService.createRole("role2", "Role 2", List.of(), false);

        createIdpMapping("idp-role-1", role1.id);
        createIdpMapping("idp-role-2", role2.id);

        // First login: User gets role1
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@example.com", "User", "google-123", null,
            List.of("idp-role-1"));

        assertThat(roleService.findRolesByPrincipal(principal.id))
            .extracting(r -> r.name)
            .containsExactly("role1");

        // Second login: User now has role2 instead
        oidcSyncService.syncIdpRoles(principal, List.of("idp-role-2"));

        // ASSERT: Old role removed, new role added
        assertThat(roleService.findRolesByPrincipal(principal.id))
            .extracting(r -> r.name)
            .containsExactly("role2");
    }

    @Test
    void idpRoleSync_shouldPreserveManualRoles_whenSyncingIdpRoles() {
        // ARRANGE: User has manually assigned role
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@example.com", "User", "google-123", null, List.of());

        Role manualRole = roleService.createRole("manual-role", "Manual", List.of(), false);
        roleService.assignRole(principal.id, manualRole.id, "MANUAL");

        // Create IDP role
        Role idpRole = roleService.createRole("idp-role", "IDP", List.of(), false);
        createIdpMapping("keycloak-role", idpRole.id);

        // ACT: Sync IDP roles
        oidcSyncService.syncIdpRoles(principal, List.of("keycloak-role"));

        // ASSERT: User has BOTH manual and IDP roles
        assertThat(roleService.findRolesByPrincipal(principal.id))
            .extracting(r -> r.name)
            .containsExactlyInAnyOrder("manual-role", "idp-role");
    }

    private void createIdpMapping(String idpRoleName, Long internalRoleId) {
        IdpRoleMapping mapping = new IdpRoleMapping();
        mapping.id = TsidGenerator.generate();
        mapping.idpRoleName = idpRoleName;
        mapping.internalRoleId = internalRoleId;
        idpRoleMappingRepo.persist(mapping);
    }
}
```

### 2.3 MultiTenantAccessIntegrationTest

**Purpose**: Test tenant access calculation end-to-end

```java
@QuarkusTest
@TestTransaction
class MultiTenantAccessIntegrationTest {

    @Inject TenantService tenantService;
    @Inject UserService userService;
    @Inject TenantAccessService tenantAccessService;
    @Inject AnchorDomainRepository anchorDomainRepo;

    @Test
    void anchorDomainUser_shouldAccessAllTenants_whenDomainIsAnchor() {
        // ARRANGE: Register anchor domain
        AnchorDomain anchor = new AnchorDomain();
        anchor.id = TsidGenerator.generate();
        anchor.emailDomain = "mycompany.com";
        anchor.description = "Internal employees";
        anchorDomainRepo.persist(anchor);

        // Create 3 tenants
        Tenant t1 = tenantService.createTenant("Tenant 1", "tenant-1");
        Tenant t2 = tenantService.createTenant("Tenant 2", "tenant-2");
        Tenant t3 = tenantService.createTenant("Tenant 3", "tenant-3");

        // Create anchor user
        Principal admin = userService.createInternalUser(
            "admin@mycompany.com", "ValidPass123!", "Admin", null);

        // ACT
        Set<Long> accessible = tenantService.getAccessibleTenants(admin.id);

        // ASSERT: Can access all tenants
        assertThat(accessible).containsExactlyInAnyOrder(t1.id, t2.id, t3.id);
    }

    @Test
    void regularUser_shouldAccessOnlyHomeTenant_whenNoGrants() {
        // ARRANGE
        Tenant t1 = tenantService.createTenant("Tenant 1", "tenant-1");
        Tenant t2 = tenantService.createTenant("Tenant 2", "tenant-2");

        Principal user = userService.createInternalUser(
            "user@customer.com", "ValidPass123!", "User", t1.id);

        // ACT
        Set<Long> accessible = tenantService.getAccessibleTenants(user.id);

        // ASSERT: Only home tenant
        assertThat(accessible).containsExactly(t1.id);
    }

    @Test
    void partnerUser_shouldAccessGrantedTenants_whenGrantsExist() {
        // ARRANGE: Create 3 tenants
        Tenant t1 = tenantService.createTenant("Customer A", "customer-a");
        Tenant t2 = tenantService.createTenant("Customer B", "customer-b");
        Tenant t3 = tenantService.createTenant("Customer C", "customer-c");

        // Partner user with no home tenant
        Principal partner = userService.createInternalUser(
            "partner@logistics.com", "ValidPass123!", "Partner", null);

        // Grant access to t1 and t2
        tenantService.grantTenantAccess(partner.id, t1.id);
        tenantService.grantTenantAccess(partner.id, t2.id);

        // ACT
        Set<Long> accessible = tenantService.getAccessibleTenants(partner.id);

        // ASSERT: Can access granted tenants
        assertThat(accessible).containsExactlyInAnyOrder(t1.id, t2.id);
    }

    @Test
    void tenantAccessRevocation_shouldRemoveAccess_whenGrantRevoked() {
        Tenant tenant = tenantService.createTenant("Tenant", "tenant");
        Principal user = userService.createInternalUser(
            "user@example.com", "ValidPass123!", "User", null);

        tenantService.grantTenantAccess(user.id, tenant.id);
        Set<Long> before = tenantService.getAccessibleTenants(user.id);
        assertThat(before).contains(tenant.id);

        // ACT: Revoke access
        tenantService.revokeTenantAccess(user.id, tenant.id);

        // ASSERT
        Set<Long> after = tenantService.getAccessibleTenants(user.id);
        assertThat(after).doesNotContain(tenant.id);
    }
}
```

---

## 3. Security Tests

### 3.1 IdpRoleAuthorizationSecurityTest ⚠️ CRITICAL

**Purpose**: Comprehensive security testing of IDP role authorization

```java
@QuarkusTest
@TestTransaction
class IdpRoleAuthorizationSecurityTest {

    @Inject OidcSyncService oidcSyncService;
    @Inject RoleService roleService;
    @Inject IdpRoleMappingRepository idpRoleMappingRepo;

    @Test
    @DisplayName("SECURITY: Compromised IDP grants super-admin → REJECTED")
    void shouldPreventUnauthorizedSuperAdminGrant_whenIdpCompromised() {
        // ATTACK SCENARIO 1: Partner IDP is compromised
        // Attacker modifies IDP to grant all users "super-admin" role

        // SETUP: No IDP role mappings exist (super-admin not authorized)

        // ACT: Attacker logs in via compromised IDP
        Principal attacker = oidcSyncService.syncOidcLogin(
            "attacker@partner.com",
            "Attacker",
            "evil-subject",
            null,
            List.of("super-admin", "platform-owner", "god-mode")
        );

        // ASSERT: NO roles assigned
        List<Role> roles = roleService.findRolesByPrincipal(attacker.id);
        assertThat(roles).isEmpty();

        // VERIFY: Security warning logged (check logs if log capture enabled)
    }

    @Test
    @DisplayName("SECURITY: Misconfigured IDP sends internal role names → REJECTED")
    void shouldRejectInternalRoleNames_whenIdpSendsWrongFormat() {
        // ATTACK SCENARIO 2: Misconfigured IDP sends internal role names

        // SETUP: No mappings for internal role names

        // ACT
        Principal user = oidcSyncService.syncOidcLogin(
            "user@partner.com",
            "User",
            "subject-123",
            null,
            List.of("platform-admin", "tenant-admin") // Internal names!
        );

        // ASSERT: Rejected
        assertThat(roleService.findRolesByPrincipal(user.id)).isEmpty();
    }

    @Test
    @DisplayName("SECURITY: IDP role mapping deletion revokes access on next login")
    void shouldRevokeAccess_whenIdpRoleMappingDeleted() {
        // SCENARIO: Platform admin realizes IDP role was mistakenly authorized

        // SETUP: Create authorized role mapping
        Role operatorRole = roleService.createRole("operator", "Operator", List.of(), false);
        IdpRoleMapping mapping = createIdpMapping("keycloak-operator", operatorRole.id);

        // User logs in and gets role
        Principal user = oidcSyncService.syncOidcLogin(
            "user@partner.com", "User", "sub-123", null,
            List.of("keycloak-operator"));

        assertThat(roleService.findRolesByPrincipal(user.id)).hasSize(1);

        // ACT: Platform admin deletes IDP role mapping
        idpRoleMappingRepo.delete(mapping);

        // User logs in again
        oidcSyncService.syncIdpRoles(user, List.of("keycloak-operator"));

        // ASSERT: Role removed
        assertThat(roleService.findRolesByPrincipal(user.id)).isEmpty();
    }

    @Test
    @DisplayName("SECURITY: Only platform admin can create IDP role mappings")
    void shouldRequirePlatformAdminPermission_whenCreatingIdpMapping() {
        // This test validates the authorization on IdpRoleMappingAdminResource
        // (Implementation depends on SecurityContext and AuthorizationService)

        // TODO: Test that non-admin users cannot create IDP role mappings
        // Requires AuthResource implementation
    }

    private IdpRoleMapping createIdpMapping(String idpName, Long internalRoleId) {
        IdpRoleMapping m = new IdpRoleMapping();
        m.id = TsidGenerator.generate();
        m.idpRoleName = idpName;
        m.internalRoleId = internalRoleId;
        idpRoleMappingRepo.persist(m);
        return m;
    }
}
```

### 3.2 TenantIsolationSecurityTest

**Purpose**: Verify tenant isolation is enforced

```java
@QuarkusTest
@TestTransaction
class TenantIsolationSecurityTest {

    @Inject TenantService tenantService;
    @Inject UserService userService;

    @Test
    @DisplayName("SECURITY: User cannot access another tenant's data")
    void shouldPreventCrossTenantAccess_whenUserBelongsToOneTenant() {
        // ARRANGE
        Tenant tenant1 = tenantService.createTenant("Tenant 1", "tenant-1");
        Tenant tenant2 = tenantService.createTenant("Tenant 2", "tenant-2");

        Principal user1 = userService.createInternalUser(
            "user1@tenant1.com", "ValidPass123!", "User 1", tenant1.id);

        // ACT
        Set<Long> accessible = tenantService.getAccessibleTenants(user1.id);

        // ASSERT: Can only access own tenant
        assertThat(accessible).containsExactly(tenant1.id);
        assertThat(accessible).doesNotContain(tenant2.id);
    }

    @Test
    @DisplayName("SECURITY: Revoking tenant grant immediately removes access")
    void shouldImmediatelyRevokeAccess_whenTenantGrantDeleted() {
        Tenant tenant = tenantService.createTenant("Tenant", "tenant");
        Principal user = userService.createInternalUser(
            "user@example.com", "ValidPass123!", "User", null);

        tenantService.grantTenantAccess(user.id, tenant.id);
        tenantService.revokeTenantAccess(user.id, tenant.id);

        Set<Long> accessible = tenantService.getAccessibleTenants(user.id);
        assertThat(accessible).doesNotContain(tenant.id);
    }

    @Test
    @DisplayName("SECURITY: Suspended tenant not accessible")
    void shouldPreventAccess_whenTenantSuspended() {
        Tenant tenant = tenantService.createTenant("Tenant", "tenant");
        Principal user = userService.createInternalUser(
            "user@tenant.com", "ValidPass123!", "User", tenant.id);

        // ACT: Suspend tenant
        tenantService.suspendTenant(tenant.id, "PAYMENT_FAILED", "system");

        // ASSERT: Tenant should not be in active list
        // (Depends on TenantAccessService implementation filtering by status)
        Set<Long> accessible = tenantService.getAccessibleTenants(user.id);
        // May or may not include suspended tenant depending on business logic
        // Document expected behavior
    }
}
```

### 3.3 PasswordSecurityTest

**Purpose**: Test password security controls

```java
@QuarkusTest
@TestTransaction
class PasswordSecurityTest {

    @Inject UserService userService;
    @Inject PasswordService passwordService;

    @Test
    @DisplayName("SECURITY: Weak passwords rejected")
    void shouldRejectWeakPasswords_whenCreatingUser() {
        String[] weakPasswords = {
            "short",                    // Too short
            "nouppercase123!",         // No uppercase
            "NOLOWERCASE123!",         // No lowercase
            "NoDigitsHere!",           // No digits
            "NoSpecial123"             // No special char
        };

        for (String weak : weakPasswords) {
            assertThatThrownBy(() -> userService.createInternalUser(
                "user@example.com", weak, "User", null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("SECURITY: Password hashes are different (salt)")
    void shouldProduceDifferentHashes_whenSamePasswordUsed() {
        Principal user1 = userService.createInternalUser(
            "user1@example.com", "SamePass123!", "User 1", null);
        Principal user2 = userService.createInternalUser(
            "user2@example.com", "SamePass123!", "User 2", null);

        assertThat(user1.userIdentity.passwordHash)
            .isNotEqualTo(user2.userIdentity.passwordHash);
    }

    @Test
    @DisplayName("SECURITY: Cannot change password without old password")
    void shouldRejectPasswordChange_whenOldPasswordIncorrect() {
        Principal user = userService.createInternalUser(
            "user@example.com", "OldPass123!", "User", null);

        assertThatThrownBy(() -> userService.changePassword(
            user.id, "WrongOldPass!", "NewPass123!"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("incorrect");
    }

    @Test
    @DisplayName("SECURITY: OIDC users cannot have passwords")
    void shouldRejectPasswordOperations_whenUserIsOIDC() {
        Principal oidcUser = userService.createOrUpdateOidcUser(
            "oidc@example.com", "OIDC User", "google-123", null);

        // Cannot reset password for OIDC users
        assertThatThrownBy(() -> userService.resetPassword(oidcUser.id, "NewPass123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("OIDC");
    }
}
```

---

## 4. Test Data Helpers

Create test fixture utilities:

```java
@ApplicationScoped
public class TestDataFactory {

    @Inject TenantService tenantService;
    @Inject UserService userService;
    @Inject RoleService roleService;

    public Tenant createTestTenant(String suffix) {
        return tenantService.createTenant(
            "Tenant " + suffix,
            "tenant-" + suffix.toLowerCase()
        );
    }

    public Principal createTestUser(String email, Long tenantId) {
        return userService.createInternalUser(
            email, "TestPass123!", "Test User", tenantId);
    }

    public Role createTestRole(String name, List<Permission> permissions) {
        return roleService.createRole(name, "Test role", permissions, false);
    }

    public Role createRoleWithPermissions(String name, String... permissionPairs) {
        List<Permission> permissions = new ArrayList<>();
        for (int i = 0; i < permissionPairs.length; i += 2) {
            permissions.add(new Permission(
                permissionPairs[i],
                permissionPairs[i+1],
                ""
            ));
        }
        return createTestRole(name, permissions);
    }
}
```

---

## 5. Test Execution Plan

### Phase 1: Unit Tests (No Database)
1. PasswordServiceTest (standalone)
2. TokenServiceTest (Quarkus config only)
3. Other service tests with mocked repositories

**Priority**: High
**Estimated Time**: 2-3 days
**Blockers**: None

### Phase 2: Integration Tests (Database Required)
1. Setup test database (H2 or PostgreSQL testcontainer)
2. IdpRoleSyncIntegrationTest ⚠️ CRITICAL
3. AuthenticationFlowIntegrationTest
4. MultiTenantAccessIntegrationTest

**Priority**: Critical
**Estimated Time**: 2-3 days
**Blockers**: Need Flyway migrations

### Phase 3: Security Tests (Full System)
1. IdpRoleAuthorizationSecurityTest ⚠️ CRITICAL
2. TenantIsolationSecurityTest
3. PasswordSecurityTest
4. End-to-end attack scenario tests

**Priority**: Critical
**Estimated Time**: 2 days
**Blockers**: Need all services implemented

---

## 6. Test Coverage Goals

| Component | Target Coverage |
|-----------|----------------|
| OidcSyncService | 100% (CRITICAL) |
| TenantAccessService | 100% (CRITICAL) |
| PasswordService | 100% |
| AuthorizationService | 95% |
| UserService | 90% |
| TenantService | 90% |
| RoleService | 90% |
| TokenService | 85% |

---

## 7. Testing Best Practices

1. **Test Naming**: Use `should_expectedBehavior_when_condition` format
2. **Arrange-Act-Assert**: Clear separation in all tests
3. **Test Independence**: Each test should run in isolation
4. **Test Transactions**: Use `@TestTransaction` for automatic rollback
5. **Mock Sparingly**: Only mock external dependencies, not domain logic
6. **Security Focus**: Every security-critical path must have explicit tests
7. **Edge Cases**: Test null values, empty collections, boundary conditions
8. **Error Messages**: Verify exception messages are helpful
9. **Log Verification**: Security warnings should be logged (use log capture)
10. **Documentation**: Each test class should have javadoc explaining purpose

---

## 8. Continuous Integration

Tests should be run:
- On every commit (fast unit tests)
- On every PR (all tests)
- Nightly (security tests + performance tests)
- Before release (full test suite + manual security review)

---

## Summary

This test plan provides comprehensive coverage of the authentication system with special emphasis on security-critical components. The **OidcSyncService** and **IDP role authorization** tests are the highest priority as they protect against the most serious security threats.

**Total Estimated Tests**: ~150-200 test methods
**Critical Security Tests**: ~30 test methods
**Implementation Time**: 6-8 days for full coverage

