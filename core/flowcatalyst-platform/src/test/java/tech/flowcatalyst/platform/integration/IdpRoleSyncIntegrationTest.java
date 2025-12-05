package tech.flowcatalyst.platform.integration;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.flowcatalyst.platform.authentication.IdpRoleMapping;
import tech.flowcatalyst.platform.authentication.IdpRoleMappingRepository;
import tech.flowcatalyst.platform.authentication.OidcSyncService;
import tech.flowcatalyst.platform.authorization.PrincipalRole;
import tech.flowcatalyst.platform.authorization.RoleService;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for IDP role synchronization.
 * CRITICAL SECURITY TESTS: Validates the IDP role authorization with real database.
 *
 * These tests verify the security control that prevents unauthorized roles
 * from being granted via compromised or misconfigured IDPs.
 *
 * Uses test role definitions from TestRoles and TestPermissions factories:
 * - test:admin (all test permissions)
 * - test:editor (create, view, update)
 * - test:viewer (view only)
 * - platform:test-tenant-admin (platform permissions)
 */
@QuarkusTest
@TestTransaction
class IdpRoleSyncIntegrationTest {

    @Inject
    OidcSyncService oidcSyncService;

    @Inject
    RoleService roleService;

    @Inject
    IdpRoleMappingRepository idpRoleMappingRepo;

    // ========================================
    // AUTHORIZED ROLE MAPPING TESTS
    // ========================================

    @Test
    @DisplayName("IDP role sync should assign authorized roles when mapping exists")
    void idpRoleSync_shouldAssignAuthorizedRoles_whenMappingExists() {
        // Arrange: Create IDP role mapping to test:admin role (defined in TestRoles)
        IdpRoleMapping mapping = new IdpRoleMapping();
        mapping.id = TsidGenerator.generate();
        mapping.idpRoleName = "keycloak-admin";
        mapping.internalRoleName = "test:admin";  // Maps to code-defined role
        idpRoleMappingRepo.persist(mapping);

        // Act: Sync OIDC login with whitelisted IDP role
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@customer.com",
            "Test User",
            "google-oauth2|123",
            null,
            List.of("keycloak-admin")
        );

        // Assert: User has test:admin role
        List<PrincipalRole> assignments = roleService.findAssignmentsByPrincipal(principal.id);
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).roleName).isEqualTo("test:admin");
        assertThat(assignments.get(0).assignmentSource).isEqualTo("IDP_SYNC");
    }

    @Test
    @DisplayName("SECURITY: IDP role sync should reject unauthorized roles when no mapping exists")
    void idpRoleSync_shouldRejectUnauthorizedRoles_whenNoMapping() {
        // CRITICAL SECURITY TEST with real database

        // Arrange: No IDP role mappings created (whitelist is empty)

        // Act: Sync login with unauthorized roles (attacker scenario)
        Principal principal = oidcSyncService.syncOidcLogin(
            "attacker@malicious.com",
            "Attacker",
            "evil-subject-123",
            null,
            List.of("super-admin", "platform-owner", "root", "god-mode")
        );

        // Assert: NO roles assigned
        List<PrincipalRole> assignments = roleService.findAssignmentsByPrincipal(principal.id);
        assertThat(assignments).isEmpty();
    }

    @Test
    @DisplayName("IDP role sync should update roles when user logs in again with different roles")
    void idpRoleSync_shouldUpdateRoles_whenUserLoginsAgainWithDifferentRoles() {
        // Arrange: Create 2 IDP role mappings to code-defined roles
        createIdpMapping("idp-role-1", "test:viewer");
        createIdpMapping("idp-role-2", "test:editor");

        // First login: User gets test:viewer role
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@customer.com",
            "User",
            "google-oauth2|123",
            null,
            List.of("idp-role-1")
        );

        assertThat(roleService.findAssignmentsByPrincipal(principal.id))
            .extracting(a -> a.roleName)
            .containsExactly("test:viewer");

        // Act: Second login with different role
        oidcSyncService.syncIdpRoles(principal, List.of("idp-role-2"));

        // Assert: Old role removed, new role added
        assertThat(roleService.findAssignmentsByPrincipal(principal.id))
            .extracting(a -> a.roleName)
            .containsExactly("test:editor");
    }

    @Test
    @DisplayName("IDP role sync should preserve manual roles when syncing IDP roles")
    void idpRoleSync_shouldPreserveManualRoles_whenSyncingIdpRoles() {
        // Arrange: User logs in with no IDP roles initially
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@customer.com",
            "User",
            "google-oauth2|123",
            null,
            List.of()
        );

        // Manually assign a role (e.g., by platform admin)
        roleService.assignRole(principal.id, "test:admin", "MANUAL");

        // Create IDP role mapping
        createIdpMapping("keycloak-viewer", "test:viewer");

        // Act: Sync IDP roles (user's IDP now grants them a role)
        oidcSyncService.syncIdpRoles(principal, List.of("keycloak-viewer"));

        // Assert: User has BOTH manual and IDP roles
        List<PrincipalRole> assignments = roleService.findAssignmentsByPrincipal(principal.id);
        assertThat(assignments).hasSize(2);
        assertThat(assignments).anySatisfy(a -> {
            assertThat(a.roleName).isEqualTo("test:admin");
            assertThat(a.assignmentSource).isEqualTo("MANUAL");
        });
        assertThat(assignments).anySatisfy(a -> {
            assertThat(a.roleName).isEqualTo("test:viewer");
            assertThat(a.assignmentSource).isEqualTo("IDP_SYNC");
        });
    }

    // ========================================
    // MULTIPLE ROLE MAPPING TESTS
    // ========================================

    @Test
    @DisplayName("IDP role sync should handle multiple authorized roles from same IDP")
    void idpRoleSync_shouldHandleMultipleRoles_whenIdpProvidesManyRoles() {
        // Arrange: Create 3 IDP role mappings to code-defined roles
        createIdpMapping("keycloak-viewer", "test:viewer");
        createIdpMapping("keycloak-editor", "test:editor");
        createIdpMapping("keycloak-admin", "test:admin");

        // Act: IDP grants all 3 roles
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@customer.com",
            "User",
            "google-oauth2|123",
            null,
            List.of("keycloak-viewer", "keycloak-editor", "keycloak-admin")
        );

        // Assert: User has all 3 roles
        assertThat(roleService.findAssignmentsByPrincipal(principal.id))
            .extracting(a -> a.roleName)
            .containsExactlyInAnyOrder("test:viewer", "test:editor", "test:admin");
    }

    @Test
    @DisplayName("SECURITY: IDP role sync should filter out unauthorized roles from mixed list")
    void idpRoleSync_shouldFilterUnauthorized_whenMixedWithAuthorized() {
        // Arrange: Only authorize 2 out of 5 roles
        createIdpMapping("keycloak-viewer", "test:viewer");
        createIdpMapping("keycloak-editor", "test:editor");

        // Act: IDP sends mix of authorized and unauthorized roles
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@customer.com",
            "User",
            "google-oauth2|123",
            null,
            List.of(
                "keycloak-viewer",         // Authorized
                "hacker-admin",            // UNAUTHORIZED
                "keycloak-editor",         // Authorized
                "default-roles-keycloak",  // UNAUTHORIZED (Keycloak default)
                "offline_access"           // UNAUTHORIZED (Keycloak default)
            )
        );

        // Assert: Only 2 authorized roles assigned
        assertThat(roleService.findAssignmentsByPrincipal(principal.id))
            .extracting(a -> a.roleName)
            .containsExactlyInAnyOrder("test:viewer", "test:editor");
    }

    // ========================================
    // IDP ROLE MAPPING MODIFICATION TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: Deleting IDP role mapping should revoke access on next login")
    void shouldRevokeAccess_whenIdpRoleMappingDeleted() {
        // Scenario: Platform admin realizes an IDP role was mistakenly authorized

        // Arrange: Create IDP role mapping
        IdpRoleMapping mapping = createIdpMapping("keycloak-admin", "test:admin");

        // User logs in and gets role
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@customer.com",
            "User",
            "google-oauth2|123",
            null,
            List.of("keycloak-admin")
        );

        assertThat(roleService.findAssignmentsByPrincipal(principal.id)).hasSize(1);

        // Act: Platform admin deletes the IDP role mapping (removes from whitelist)
        idpRoleMappingRepo.delete(mapping);

        // User logs in again with same IDP role
        oidcSyncService.syncIdpRoles(principal, List.of("keycloak-admin"));

        // Assert: Role removed (no longer whitelisted)
        assertThat(roleService.findAssignmentsByPrincipal(principal.id)).isEmpty();
    }

    @Test
    @DisplayName("Adding new IDP role mapping should grant access on next login")
    void shouldGrantAccess_whenNewIdpRoleMappingAdded() {
        // Arrange: User logs in before mapping exists
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@customer.com",
            "User",
            "google-oauth2|123",
            null,
            List.of("keycloak-viewer")
        );

        // No roles assigned (mapping doesn't exist yet)
        assertThat(roleService.findAssignmentsByPrincipal(principal.id)).isEmpty();

        // Act: Platform admin creates mapping
        createIdpMapping("keycloak-viewer", "test:viewer");

        // User logs in again
        oidcSyncService.syncIdpRoles(principal, List.of("keycloak-viewer"));

        // Assert: Role now granted
        assertThat(roleService.findAssignmentsByPrincipal(principal.id))
            .extracting(a -> a.roleName)
            .containsExactly("test:viewer");
    }

    // ========================================
    // EDGE CASES
    // ========================================

    @Test
    @DisplayName("IDP role sync should handle empty roles list from IDP")
    void idpRoleSync_shouldHandleEmpty_whenIdpSendsNoRoles() {
        // Arrange: Create mapping
        createIdpMapping("keycloak-admin", "test:admin");

        // User first login with role
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@customer.com",
            "User",
            "google-oauth2|123",
            null,
            List.of("keycloak-admin")
        );

        assertThat(roleService.findAssignmentsByPrincipal(principal.id)).hasSize(1);

        // Act: User logs in again but IDP sends empty roles (e.g., roles revoked in IDP)
        oidcSyncService.syncIdpRoles(principal, List.of());

        // Assert: All IDP roles removed
        assertThat(roleService.findAssignmentsByPrincipal(principal.id)).isEmpty();
    }

    @Test
    @DisplayName("IDP role sync should handle null roles list from IDP")
    void idpRoleSync_shouldHandleNull_whenIdpSendsNullRoles() {
        // Arrange
        createIdpMapping("keycloak-admin", "test:admin");

        Principal principal = oidcSyncService.syncOidcLogin(
            "user@customer.com",
            "User",
            "google-oauth2|123",
            null,
            List.of("keycloak-admin")
        );

        assertThat(roleService.findAssignmentsByPrincipal(principal.id)).hasSize(1);

        // Act: Null roles (edge case in IDP integration)
        oidcSyncService.syncIdpRoles(principal, null);

        // Assert: All IDP roles removed (clean slate)
        assertThat(roleService.findAssignmentsByPrincipal(principal.id)).isEmpty();
    }

    @Test
    @DisplayName("IDP role sync should deduplicate when multiple IDP roles map to same internal role")
    void idpRoleSync_shouldDeduplicate_whenMultipleIdpRolesMapToSameInternal() {
        // Arrange: 2 IDP roles map to same internal role
        // (e.g., "admin" and "administrator" both map to internal "test:admin" role)
        createIdpMapping("keycloak-admin", "test:admin");
        createIdpMapping("keycloak-administrator", "test:admin");

        // Act: IDP sends both role names
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@customer.com",
            "User",
            "google-oauth2|123",
            null,
            List.of("keycloak-admin", "keycloak-administrator")
        );

        // Assert: Only 1 internal role assigned (deduplicated)
        assertThat(roleService.findAssignmentsByPrincipal(principal.id))
            .hasSize(1)
            .extracting(a -> a.roleName)
            .containsExactly("test:admin");
    }

    // ========================================
    // ATTACK SCENARIO TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: Compromised IDP scenario - all malicious roles rejected")
    void shouldRejectAll_whenIdpCompromised() {
        // ATTACK SCENARIO: Partner's Keycloak server is compromised
        // Attacker modifies Keycloak to grant all users "super-admin" role

        // Arrange: No mappings (none of these roles are authorized)

        // Act: Multiple users login via compromised IDP
        Principal user1 = oidcSyncService.syncOidcLogin(
            "user1@partner.com", "User 1", "idp-1", null,
            List.of("super-admin", "platform-owner", "root"));

        Principal user2 = oidcSyncService.syncOidcLogin(
            "user2@partner.com", "User 2", "idp-2", null,
            List.of("super-admin"));

        // Assert: ALL malicious roles rejected, no privilege escalation
        assertThat(roleService.findAssignmentsByPrincipal(user1.id)).isEmpty();
        assertThat(roleService.findAssignmentsByPrincipal(user2.id)).isEmpty();
    }

    @Test
    @DisplayName("SECURITY: Misconfigured IDP sends internal role names - rejected")
    void shouldReject_whenIdpSendsInternalRoleNames() {
        // ATTACK SCENARIO: Misconfigured IDP sends internal role names
        // instead of IDP role names

        // Arrange: NO mapping for internal role names
        // (internal roles exist in code via TestRoles, but no IDP mapping)

        // Act: IDP misconfigured to send internal role name directly
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@misconfigured-idp.com",
            "User",
            "idp-123",
            null,
            List.of("test:admin", "platform:test-tenant-admin")  // Internal names!
        );

        // Assert: Rejected (internal names not in IDP whitelist)
        assertThat(roleService.findAssignmentsByPrincipal(principal.id)).isEmpty();
    }

    @Test
    @DisplayName("SECURITY: Role must exist in PermissionRegistry to be assigned")
    void shouldReject_whenMappingPointsToNonexistentRole() {
        // SECURITY: Even if IDP mapping exists, role must be defined in code

        // Arrange: Create mapping to non-existent role
        createIdpMapping("keycloak-fake", "nonexistent:role");

        // Act: Sync with mapped IDP role
        Principal principal = oidcSyncService.syncOidcLogin(
            "user@customer.com",
            "User",
            "google-oauth2|123",
            null,
            List.of("keycloak-fake")
        );

        // Assert: No role assigned (role doesn't exist in PermissionRegistry)
        assertThat(roleService.findAssignmentsByPrincipal(principal.id)).isEmpty();
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    private IdpRoleMapping createIdpMapping(String idpRoleName, String internalRoleName) {
        IdpRoleMapping mapping = new IdpRoleMapping();
        mapping.id = TsidGenerator.generate();
        mapping.idpRoleName = idpRoleName;
        mapping.internalRoleName = internalRoleName;
        idpRoleMappingRepo.persist(mapping);
        return mapping;
    }
}
