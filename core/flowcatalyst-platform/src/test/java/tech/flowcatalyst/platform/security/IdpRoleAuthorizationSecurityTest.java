package tech.flowcatalyst.platform.security;

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
 * CRITICAL SECURITY TESTS: IDP Role Authorization
 *
 * These tests validate the most important security control in the authentication system:
 * preventing unauthorized role grants via compromised or misconfigured Identity Providers.
 *
 * THREAT MODEL:
 * 1. Partner's IDP is compromised → Attacker grants themselves admin roles
 * 2. Partner's IDP is misconfigured → Accidentally grants wrong roles
 * 3. Partner modifies IDP configuration → Attempts privilege escalation
 *
 * SECURITY CONTROL:
 * Only IDP roles explicitly whitelisted in idp_role_mappings table are accepted.
 * All other roles are rejected and logged.
 *
 * BUSINESS IMPACT:
 * Failure of this control could allow:
 * - Unauthorized access to customer data
 * - Privilege escalation attacks
 * - Complete platform compromise
 *
 * Uses test role definitions from TestRoles factory:
 * - test:admin, test:editor, test:viewer
 * - platform:test-tenant-admin
 */
@QuarkusTest
@TestTransaction
class IdpRoleAuthorizationSecurityTest {

    @Inject
    OidcSyncService oidcSyncService;

    @Inject
    RoleService roleService;

    @Inject
    IdpRoleMappingRepository idpRoleMappingRepo;

    // ========================================
    // ATTACK SCENARIO 1: Compromised IDP
    // ========================================

    @Test
    @DisplayName("SECURITY: Compromised IDP grants super-admin role → REJECTED")
    void shouldPreventUnauthorizedSuperAdminGrant_whenIdpCompromised() {
        // ATTACK SCENARIO:
        // Partner's Keycloak server is compromised by attacker.
        // Attacker modifies Keycloak configuration to grant all users "super-admin" role.
        // Attacker attempts to login and gain platform admin access.

        // ARRANGE: No IDP role mappings exist
        // (super-admin role is NOT in the whitelist)

        // ACT: Attacker logs in via compromised IDP
        Principal attacker = oidcSyncService.syncOidcLogin(
            "attacker@partner.com",
            "Malicious Actor",
            "compromised-idp-subject-123",
            null,
            List.of("super-admin", "platform-owner", "god-mode", "root")
        );

        // ASSERT: NO roles assigned - attack prevented
        List<PrincipalRole> assignments = roleService.findAssignmentsByPrincipal(attacker.id);
        assertThat(assignments).isEmpty();

        // VERIFY: User created but powerless
        assertThat(attacker).isNotNull();
        assertThat(attacker.active).isTrue();
        assertThat(attacker.userIdentity.email).isEqualTo("attacker@partner.com");

        // Security layer prevented privilege escalation
        // Logs should contain warnings about unauthorized roles (not tested here)
    }

    @Test
    @DisplayName("SECURITY: Compromised IDP with mix of valid and malicious roles")
    void shouldFilterMaliciousRoles_whenIdpCompromisedPartially() {
        // ATTACK SCENARIO:
        // Sophisticated attacker compromises IDP and adds malicious roles
        // alongside legitimate roles to avoid detection.

        // ARRANGE: Only authorize one legitimate role (maps to code-defined test:viewer)
        createIdpMapping("partner-viewer", "test:viewer");

        // ACT: Attacker logs in with mix of legitimate and malicious roles
        Principal attacker = oidcSyncService.syncOidcLogin(
            "attacker@partner.com",
            "Attacker",
            "idp-123",
            null,
            List.of(
                "partner-viewer",    // Legitimate (whitelisted)
                "super-admin",       // MALICIOUS
                "platform-admin",    // MALICIOUS
                "root"               // MALICIOUS
            )
        );

        // ASSERT: Only legitimate role granted, malicious roles rejected
        List<PrincipalRole> assignments = roleService.findAssignmentsByPrincipal(attacker.id);
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).roleName).isEqualTo("test:viewer");

        // Attack partially prevented - attacker only got intended access
    }

    // ========================================
    // ATTACK SCENARIO 2: Misconfigured IDP
    // ========================================

    @Test
    @DisplayName("SECURITY: Misconfigured IDP sends internal role names → REJECTED")
    void shouldRejectInternalRoleNames_whenIdpSendsWrongFormat() {
        // ATTACK SCENARIO:
        // Partner's IDP is misconfigured and sends FlowCatalyst internal role names
        // instead of IDP role names. This could be accidental or intentional.

        // ARRANGE: Internal roles exist in code (TestRoles), but no IDP mappings for internal names

        // ACT: Misconfigured IDP sends internal role names
        Principal user = oidcSyncService.syncOidcLogin(
            "user@partner.com",
            "User",
            "idp-123",
            null,
            List.of("test:admin", "platform:test-tenant-admin")  // Internal names!
        );

        // ASSERT: Rejected - internal role names not in IDP whitelist
        List<PrincipalRole> assignments = roleService.findAssignmentsByPrincipal(user.id);
        assertThat(assignments).isEmpty();
    }

    @Test
    @DisplayName("SECURITY: IDP sends roles with similar names to internal roles")
    void shouldRejectSimilarRoleNames_whenNotExactMatch() {
        // ATTACK SCENARIO:
        // Attacker tries role names similar to internal roles, hoping for weak matching.

        // ARRANGE: Create IDP mapping for specific IDP role name (maps to test:admin)
        createIdpMapping("keycloak-admin", "test:admin");

        // ACT: Attacker tries variations of the role name
        Principal attacker = oidcSyncService.syncOidcLogin(
            "attacker@partner.com",
            "Attacker",
            "idp-123",
            null,
            List.of(
                "admin",           // No mapping
                "Admin",           // Uppercase variant
                "ADMIN",           // All caps
                "admin ",          // With space
                " admin",          // Leading space
                "keycloak-Admin"   // Wrong case
            )
        );

        // ASSERT: All rejected - exact match required
        List<PrincipalRole> assignments = roleService.findAssignmentsByPrincipal(attacker.id);
        assertThat(assignments).isEmpty();
    }

    // ========================================
    // ATTACK SCENARIO 3: IDP Role Injection
    // ========================================

    @Test
    @DisplayName("SECURITY: Multiple compromised IDPs attack simultaneously")
    void shouldDefendAgainstMultipleCompromisedIdps_whenAttackersCoordinate() {
        // ATTACK SCENARIO:
        // Multiple partner IDPs are compromised.
        // Attackers coordinate to grant themselves maximum privileges.

        // ARRANGE: Only one legitimate mapping exists
        createIdpMapping("partner-editor", "test:editor");

        // ACT: Three different attackers from different compromised IDPs
        Principal attacker1 = oidcSyncService.syncOidcLogin(
            "attacker1@partner-a.com", "Attacker 1", "idp-a-123", null,
            List.of("super-admin", "platform-owner"));

        Principal attacker2 = oidcSyncService.syncOidcLogin(
            "attacker2@partner-b.com", "Attacker 2", "idp-b-456", null,
            List.of("root", "god-mode"));

        Principal attacker3 = oidcSyncService.syncOidcLogin(
            "attacker3@partner-c.com", "Attacker 3", "idp-c-789", null,
            List.of("admin", "superuser"));

        // ASSERT: All attacks blocked - no roles granted
        assertThat(roleService.findAssignmentsByPrincipal(attacker1.id)).isEmpty();
        assertThat(roleService.findAssignmentsByPrincipal(attacker2.id)).isEmpty();
        assertThat(roleService.findAssignmentsByPrincipal(attacker3.id)).isEmpty();
    }

    // ========================================
    // ATTACK SCENARIO 4: Role Mapping Manipulation
    // ========================================

    @Test
    @DisplayName("SECURITY: Deleting IDP role mapping immediately revokes access")
    void shouldImmediatelyRevokeAccess_whenIdpRoleMappingDeleted() {
        // SCENARIO:
        // Platform admin discovers a partner IDP role was incorrectly authorized.
        // They delete the IDP role mapping to revoke access.
        // Users who already have the role should lose it on next login.

        // ARRANGE: Create IDP mapping to test:admin role
        IdpRoleMapping mapping = createIdpMapping("partner-sensitive", "test:admin");

        // User logs in and gets role
        Principal user = oidcSyncService.syncOidcLogin(
            "user@partner.com", "User", "idp-123", null,
            List.of("partner-sensitive"));

        assertThat(roleService.findAssignmentsByPrincipal(user.id)).hasSize(1);

        // ACT: Platform admin deletes mapping (removes from whitelist)
        idpRoleMappingRepo.delete(mapping);

        // User logs in again with same IDP role
        oidcSyncService.syncIdpRoles(user, List.of("partner-sensitive"));

        // ASSERT: Role immediately revoked
        assertThat(roleService.findAssignmentsByPrincipal(user.id)).isEmpty();
    }

    @Test
    @DisplayName("SECURITY: Cannot bypass whitelist by creating duplicate mapping")
    void shouldEnforceUniqueMappings_whenDuplicateAttempted() {
        // SCENARIO:
        // Attacker with platform admin access tries to create duplicate mapping
        // to bypass security controls.

        // ARRANGE: Create legitimate mapping
        createIdpMapping("partner-admin", "test:admin");

        // ACT & ASSERT: Attempting to create duplicate should be prevented
        // (This would be tested in IdpRoleMappingRepository tests,
        // but we verify the behavior here as well)

        // The database unique constraint should prevent this
        assertThatCode(() -> {
            createIdpMapping("partner-admin", "test:admin");
        }).isInstanceOf(Exception.class);
    }

    // ========================================
    // DEFENSE VERIFICATION TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: Empty IDP roles list removes all IDP-granted roles")
    void shouldRemoveAllIdpRoles_whenIdpSendsEmptyList() {
        // SCENARIO:
        // IDP revokes all roles for a user (e.g., user account suspended in IDP).
        // System should remove all IDP-granted roles on next login.

        // ARRANGE: User has IDP roles
        createIdpMapping("partner-editor", "test:editor");

        Principal user = oidcSyncService.syncOidcLogin(
            "user@partner.com", "User", "idp-123", null,
            List.of("partner-editor"));

        assertThat(roleService.findAssignmentsByPrincipal(user.id)).hasSize(1);

        // ACT: IDP sends empty roles (user suspended/revoked)
        oidcSyncService.syncIdpRoles(user, List.of());

        // ASSERT: All IDP roles removed
        assertThat(roleService.findAssignmentsByPrincipal(user.id)).isEmpty();
    }

    @Test
    @DisplayName("SECURITY: Manual role assignments preserved during IDP sync")
    void shouldPreserveManualRoles_whenIdpRolesSynced() {
        // SCENARIO:
        // Platform admin manually assigns sensitive role to user.
        // IDP role sync should NOT remove manually assigned roles.

        // ARRANGE: User with manual role
        Principal user = oidcSyncService.syncOidcLogin(
            "user@partner.com", "User", "idp-123", null, List.of());

        roleService.assignRole(user.id, "test:admin", "MANUAL");

        // Create IDP mapping and grant IDP role
        createIdpMapping("partner-viewer", "test:viewer");

        // ACT: Sync IDP roles
        oidcSyncService.syncIdpRoles(user, List.of("partner-viewer"));

        // ASSERT: Both manual and IDP roles present
        assertThat(roleService.findAssignmentsByPrincipal(user.id))
            .extracting(a -> a.roleName)
            .containsExactlyInAnyOrder("test:admin", "test:viewer");
    }

    @Test
    @DisplayName("SECURITY: IDP role changes reflected on every login")
    void shouldUpdateRolesOnEveryLogin_whenIdpRolesChange() {
        // SCENARIO:
        // User's roles in IDP change between logins.
        // System should reflect current IDP state.

        // ARRANGE: Create 3 IDP role mappings to code-defined roles
        createIdpMapping("idp-role-1", "test:viewer");
        createIdpMapping("idp-role-2", "test:editor");
        createIdpMapping("idp-role-3", "test:admin");

        // Login 1: User has test:viewer role
        Principal user = oidcSyncService.syncOidcLogin(
            "user@partner.com", "User", "idp-123", null,
            List.of("idp-role-1"));

        assertThat(roleService.findAssignmentsByPrincipal(user.id))
            .extracting(a -> a.roleName)
            .containsExactly("test:viewer");

        // Login 2: User promoted to viewer + editor
        oidcSyncService.syncIdpRoles(user, List.of("idp-role-1", "idp-role-2"));

        assertThat(roleService.findAssignmentsByPrincipal(user.id))
            .extracting(a -> a.roleName)
            .containsExactlyInAnyOrder("test:viewer", "test:editor");

        // Login 3: User changed to admin only
        oidcSyncService.syncIdpRoles(user, List.of("idp-role-3"));

        assertThat(roleService.findAssignmentsByPrincipal(user.id))
            .extracting(a -> a.roleName)
            .containsExactly("test:admin");
    }

    // ========================================
    // COMMON IDP DEFAULT ROLES TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: Keycloak default roles are filtered out")
    void shouldFilterOutKeycloakDefaultRoles_whenNotWhitelisted() {
        // SCENARIO:
        // Keycloak sends default roles like "offline_access", "uma_authorization".
        // These should be filtered unless explicitly whitelisted.

        // ARRANGE: Create legitimate mapping
        createIdpMapping("partner-viewer", "test:viewer");

        // ACT: Login with Keycloak defaults + legitimate role
        Principal user = oidcSyncService.syncOidcLogin(
            "user@partner.com", "User", "keycloak-123", null,
            List.of(
                "partner-viewer",           // Legitimate
                "offline_access",           // Keycloak default
                "uma_authorization",        // Keycloak default
                "default-roles-keycloak"    // Keycloak default
            )
        );

        // ASSERT: Only legitimate role granted
        assertThat(roleService.findAssignmentsByPrincipal(user.id))
            .hasSize(1)
            .extracting(a -> a.roleName)
            .containsExactly("test:viewer");
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
        idpRoleMappingRepo.flush(); // Force immediate constraint check
        return mapping;
    }
}
