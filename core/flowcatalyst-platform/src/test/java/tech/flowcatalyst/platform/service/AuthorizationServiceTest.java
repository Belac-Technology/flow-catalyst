package tech.flowcatalyst.platform.service;

import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.flowcatalyst.platform.authorization.AuthorizationService;
import tech.flowcatalyst.platform.authorization.PermissionDefinition;
import tech.flowcatalyst.platform.authorization.PermissionRegistry;
import tech.flowcatalyst.platform.authorization.PrincipalRole;
import tech.flowcatalyst.platform.authorization.PrincipalRoleRepository;
import tech.flowcatalyst.platform.authorization.RoleDefinition;
import tech.flowcatalyst.platform.principal.Principal;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthorizationService.
 * Tests RBAC permission checking logic with code-first permissions.
 *
 * CRITICAL: This service enforces authorization across the platform.
 * Bugs here could allow unauthorized access to resources.
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

    @Mock
    private PrincipalRoleRepository principalRoleRepo;

    @Mock
    private PermissionRegistry permissionRegistry;

    @InjectMocks
    private AuthorizationService service;

    // ========================================
    // hasPermission TESTS (permission string)
    // ========================================

    @Test
    @DisplayName("hasPermission should return true when principal has permission")
    void hasPermission_shouldReturnTrue_whenPrincipalHasPermission() {
        // Arrange: Principal has role "test:admin" with permission "test:context:resource:create"
        Long principalId = 123L;

        PrincipalRole pr = createPrincipalRole(123L, "test:admin");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        when(permissionRegistry.getPermissionsForRoles(Set.of("test:admin")))
            .thenReturn(Set.of(
                "test:context:resource:create",
                "test:context:resource:view",
                "test:context:resource:update",
                "test:context:resource:delete"
            ));

        // Act
        boolean hasPermission = service.hasPermission(principalId, "test:context:resource:create");

        // Assert
        assertThat(hasPermission).isTrue();
    }

    @Test
    @DisplayName("hasPermission should return false when principal lacks permission")
    void hasPermission_shouldReturnFalse_whenPrincipalLacksPermission() {
        // Arrange: Principal has role "test:viewer" with only view permission
        Long principalId = 123L;

        PrincipalRole pr = createPrincipalRole(123L, "test:viewer");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        when(permissionRegistry.getPermissionsForRoles(Set.of("test:viewer")))
            .thenReturn(Set.of("test:context:resource:view"));

        // Act
        boolean hasPermission = service.hasPermission(principalId, "test:context:resource:create");

        // Assert: Has view but not create
        assertThat(hasPermission).isFalse();
    }

    @Test
    @DisplayName("hasPermission should return false when principal has no roles")
    void hasPermission_shouldReturnFalse_whenPrincipalHasNoRoles() {
        // Arrange: No roles assigned
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of());
        when(permissionRegistry.getPermissionsForRoles(Set.of())).thenReturn(Set.of());

        // Act
        boolean hasPermission = service.hasPermission(123L, "test:context:resource:create");

        // Assert
        assertThat(hasPermission).isFalse();
    }

    @Test
    @DisplayName("hasPermission should return true when multiple roles and one has permission")
    void hasPermission_shouldReturnTrue_whenMultipleRolesAndOneHasPermission() {
        // Arrange: 2 roles, only "test:editor" has create permission
        Long principalId = 123L;

        PrincipalRole pr1 = createPrincipalRole(123L, "test:viewer");
        PrincipalRole pr2 = createPrincipalRole(123L, "test:editor");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr1, pr2));

        when(permissionRegistry.getPermissionsForRoles(Set.of("test:viewer", "test:editor")))
            .thenReturn(Set.of(
                "test:context:resource:view",
                "test:context:resource:create",
                "test:context:resource:update"
            ));

        // Act
        boolean hasPermission = service.hasPermission(principalId, "test:context:resource:create");

        // Assert
        assertThat(hasPermission).isTrue();
    }

    @Test
    @DisplayName("hasPermission should return false when permission is similar but not exact")
    void hasPermission_shouldReturnFalse_whenPermissionIsSimilarButNotExact() {
        // Arrange: Has "test:context:resource:view" but needs "test:context:resource:create"
        Long principalId = 123L;

        PrincipalRole pr = createPrincipalRole(123L, "test:viewer");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        when(permissionRegistry.getPermissionsForRoles(Set.of("test:viewer")))
            .thenReturn(Set.of("test:context:resource:view", "test:context:resource:update"));

        // Act
        boolean hasPermission = service.hasPermission(principalId, "test:context:resource:create");

        // Assert: Different action
        assertThat(hasPermission).isFalse();
    }

    @Test
    @DisplayName("hasPermission should be case sensitive for permission strings")
    void hasPermission_shouldBeCaseSensitive_whenCheckingPermissions() {
        // Arrange
        Long principalId = 123L;

        PrincipalRole pr = createPrincipalRole(123L, "test:admin");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        when(permissionRegistry.getPermissionsForRoles(Set.of("test:admin")))
            .thenReturn(Set.of("test:context:resource:create"));

        // Act & Assert: Different case should fail
        assertThat(service.hasPermission(principalId, "Test:context:resource:create")).isFalse();
        assertThat(service.hasPermission(principalId, "test:context:resource:CREATE")).isFalse();
        assertThat(service.hasPermission(principalId, "test:context:resource:create")).isTrue();
    }

    // ========================================
    // hasPermission (semantic parts) TESTS
    // ========================================

    @Test
    @DisplayName("hasPermission should work with semantic parts")
    void hasPermission_shouldWork_withSemanticParts() {
        // Arrange
        Long principalId = 123L;

        PrincipalRole pr = createPrincipalRole(123L, "test:admin");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        when(permissionRegistry.getPermissionsForRoles(Set.of("test:admin")))
            .thenReturn(Set.of("test:context:resource:create"));

        // Act
        boolean hasPermission = service.hasPermission(principalId, "test", "context", "resource", "create");

        // Assert
        assertThat(hasPermission).isTrue();
    }

    // ========================================
    // hasPermission (Principal object) TESTS
    // ========================================

    @Test
    @DisplayName("hasPermission with Principal should delegate to ID-based method")
    void hasPermission_shouldDelegateToIdMethod_whenCalledWithPrincipal() {
        // Arrange
        Principal principal = new Principal();
        principal.id = 123L;

        PrincipalRole pr = createPrincipalRole(123L, "test:admin");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        when(permissionRegistry.getPermissionsForRoles(Set.of("test:admin")))
            .thenReturn(Set.of("test:context:resource:create"));

        // Act
        boolean hasPermission = service.hasPermission(principal, "test:context:resource:create");

        // Assert
        assertThat(hasPermission).isTrue();
        verify(principalRoleRepo).findByPrincipalId(123L);
    }

    // ========================================
    // requirePermission TESTS
    // ========================================

    @Test
    @DisplayName("requirePermission should not throw when principal has permission")
    void requirePermission_shouldNotThrow_whenPrincipalHasPermission() {
        // Arrange
        Long principalId = 123L;

        PrincipalRole pr = createPrincipalRole(123L, "test:admin");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        when(permissionRegistry.getPermissionsForRoles(Set.of("test:admin")))
            .thenReturn(Set.of("test:context:resource:create"));

        // Act & Assert
        assertThatCode(() -> service.requirePermission(principalId, "test:context:resource:create"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("requirePermission should throw ForbiddenException when principal lacks permission")
    void requirePermission_shouldThrowForbiddenException_whenPrincipalLacksPermission() {
        // Arrange: No roles
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of());
        when(permissionRegistry.getPermissionsForRoles(Set.of())).thenReturn(Set.of());

        // Act & Assert
        assertThatThrownBy(() -> service.requirePermission(123L, "test:context:resource:create"))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("Missing permission")
            .hasMessageContaining("test:context:resource:create");
    }

    @Test
    @DisplayName("requirePermission with Principal should delegate correctly")
    void requirePermission_shouldDelegate_whenCalledWithPrincipal() {
        // Arrange
        Principal principal = new Principal();
        principal.id = 123L;

        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of());
        when(permissionRegistry.getPermissionsForRoles(Set.of())).thenReturn(Set.of());

        // Act & Assert
        assertThatThrownBy(() -> service.requirePermission(principal, "test:context:resource:create"))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("requirePermission with semantic parts should work")
    void requirePermission_shouldWork_withSemanticParts() {
        // Arrange
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of());
        when(permissionRegistry.getPermissionsForRoles(Set.of())).thenReturn(Set.of());

        // Act & Assert
        assertThatThrownBy(() -> service.requirePermission(123L, "test", "context", "resource", "create"))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining("test:context:resource:create");
    }

    // ========================================
    // getRoleNames TESTS
    // ========================================

    @Test
    @DisplayName("getRoleNames should return role names for principal")
    void getRoleNames_shouldReturnRoleNames_whenPrincipalHasRoles() {
        // Arrange
        PrincipalRole pr1 = createPrincipalRole(123L, "test:admin");
        PrincipalRole pr2 = createPrincipalRole(123L, "test:editor");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr1, pr2));

        // Act
        Set<String> roleNames = service.getRoleNames(123L);

        // Assert
        assertThat(roleNames).containsExactlyInAnyOrder("test:admin", "test:editor");
    }

    @Test
    @DisplayName("getRoleNames should return empty set when no roles")
    void getRoleNames_shouldReturnEmpty_whenNoRoles() {
        // Arrange
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of());

        // Act
        Set<String> roleNames = service.getRoleNames(123L);

        // Assert
        assertThat(roleNames).isEmpty();
    }

    // ========================================
    // getPermissions TESTS
    // ========================================

    @Test
    @DisplayName("getPermissions should return all permissions from all roles")
    void getPermissions_shouldReturnAllPermissions_whenPrincipalHasMultipleRoles() {
        // Arrange: 2 roles with different permissions
        PrincipalRole pr1 = createPrincipalRole(123L, "test:editor");
        PrincipalRole pr2 = createPrincipalRole(123L, "test:viewer");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr1, pr2));

        when(permissionRegistry.getPermissionsForRoles(Set.of("test:editor", "test:viewer")))
            .thenReturn(Set.of(
                "test:context:resource:create",
                "test:context:resource:view",
                "test:context:resource:update"
            ));

        // Act
        Set<String> permissions = service.getPermissions(123L);

        // Assert
        assertThat(permissions).containsExactlyInAnyOrder(
            "test:context:resource:create",
            "test:context:resource:view",
            "test:context:resource:update"
        );
    }

    @Test
    @DisplayName("getPermissions should return empty set when principal has no roles")
    void getPermissions_shouldReturnEmpty_whenPrincipalHasNoRoles() {
        // Arrange
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of());
        when(permissionRegistry.getPermissionsForRoles(Set.of())).thenReturn(Set.of());

        // Act
        Set<String> permissions = service.getPermissions(123L);

        // Assert
        assertThat(permissions).isEmpty();
    }

    // ========================================
    // getRoleDefinitions TESTS
    // ========================================

    @Test
    @DisplayName("getRoleDefinitions should return role definitions for principal")
    void getRoleDefinitions_shouldReturnRoleDefinitions_whenPrincipalHasRoles() {
        // Arrange
        PrincipalRole pr = createPrincipalRole(123L, "test:admin");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        RoleDefinition adminRole = RoleDefinition.makeFromStrings(
            "test",
            "admin",
            Set.of("test:context:resource:create", "test:context:resource:view"),
            "Test admin role"
        );

        when(permissionRegistry.getRole("test:admin"))
            .thenReturn(Optional.of(adminRole));

        // Act
        Set<RoleDefinition> roleDefs = service.getRoleDefinitions(123L);

        // Assert
        assertThat(roleDefs).hasSize(1);
        assertThat(roleDefs).contains(adminRole);
    }

    // ========================================
    // getPermissionDefinitions TESTS
    // ========================================

    @Test
    @DisplayName("getPermissionDefinitions should return permission definitions for principal")
    void getPermissionDefinitions_shouldReturnPermissionDefinitions_whenPrincipalHasPermissions() {
        // Arrange
        PrincipalRole pr = createPrincipalRole(123L, "test:admin");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        when(permissionRegistry.getPermissionsForRoles(Set.of("test:admin")))
            .thenReturn(Set.of("test:context:resource:create"));

        PermissionDefinition permDef = PermissionDefinition.make(
            "test",
            "context",
            "resource",
            "create",
            "Test create permission"
        );

        when(permissionRegistry.getPermission("test:context:resource:create"))
            .thenReturn(Optional.of(permDef));

        // Act
        Set<PermissionDefinition> permDefs = service.getPermissionDefinitions(123L);

        // Assert
        assertThat(permDefs).hasSize(1);
        assertThat(permDefs).contains(permDef);
    }

    // ========================================
    // hasRole TESTS
    // ========================================

    @Test
    @DisplayName("hasRole should return true when principal has role")
    void hasRole_shouldReturnTrue_whenPrincipalHasRole() {
        // Arrange
        PrincipalRole pr = createPrincipalRole(123L, "test:admin");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        // Act
        boolean hasRole = service.hasRole(123L, "test:admin");

        // Assert
        assertThat(hasRole).isTrue();
    }

    @Test
    @DisplayName("hasRole should return false when principal lacks role")
    void hasRole_shouldReturnFalse_whenPrincipalLacksRole() {
        // Arrange: Has "test:editor" but checking for "test:admin"
        PrincipalRole pr = createPrincipalRole(123L, "test:editor");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        // Act
        boolean hasRole = service.hasRole(123L, "test:admin");

        // Assert
        assertThat(hasRole).isFalse();
    }

    // ========================================
    // hasAnyRole TESTS
    // ========================================

    @Test
    @DisplayName("hasAnyRole should return true when principal has one of the roles")
    void hasAnyRole_shouldReturnTrue_whenPrincipalHasOneOfRoles() {
        // Arrange: Has "test:editor"
        PrincipalRole pr = createPrincipalRole(123L, "test:editor");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        // Act: Checking for admin OR editor OR viewer
        boolean hasAnyRole = service.hasAnyRole(123L, "test:admin", "test:editor", "test:viewer");

        // Assert
        assertThat(hasAnyRole).isTrue();
    }

    @Test
    @DisplayName("hasAnyRole should return false when principal has none of the roles")
    void hasAnyRole_shouldReturnFalse_whenPrincipalHasNoneOfRoles() {
        // Arrange: Has "test:viewer"
        PrincipalRole pr = createPrincipalRole(123L, "test:viewer");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        // Act: Checking for admin OR editor (doesn't have either)
        boolean hasAnyRole = service.hasAnyRole(123L, "test:admin", "test:editor");

        // Assert
        assertThat(hasAnyRole).isFalse();
    }

    // ========================================
    // hasAllRoles TESTS
    // ========================================

    @Test
    @DisplayName("hasAllRoles should return true when principal has all roles")
    void hasAllRoles_shouldReturnTrue_whenPrincipalHasAllRoles() {
        // Arrange: Has both "test:admin" and "test:editor"
        PrincipalRole pr1 = createPrincipalRole(123L, "test:admin");
        PrincipalRole pr2 = createPrincipalRole(123L, "test:editor");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr1, pr2));

        // Act
        boolean hasAllRoles = service.hasAllRoles(123L, "test:admin", "test:editor");

        // Assert
        assertThat(hasAllRoles).isTrue();
    }

    @Test
    @DisplayName("hasAllRoles should return false when principal missing one role")
    void hasAllRoles_shouldReturnFalse_whenPrincipalMissingOneRole() {
        // Arrange: Has only "test:admin"
        PrincipalRole pr = createPrincipalRole(123L, "test:admin");
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of(pr));

        // Act: Needs both admin AND editor
        boolean hasAllRoles = service.hasAllRoles(123L, "test:admin", "test:editor");

        // Assert: Missing editor
        assertThat(hasAllRoles).isFalse();
    }

    @Test
    @DisplayName("hasAllRoles should return false when principal has no roles")
    void hasAllRoles_shouldReturnFalse_whenPrincipalHasNoRoles() {
        // Arrange
        when(principalRoleRepo.findByPrincipalId(123L)).thenReturn(List.of());

        // Act
        boolean hasAllRoles = service.hasAllRoles(123L, "test:admin", "test:editor");

        // Assert
        assertThat(hasAllRoles).isFalse();
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    private PrincipalRole createPrincipalRole(Long principalId, String roleName) {
        PrincipalRole pr = new PrincipalRole();
        pr.principalId = principalId;
        pr.roleName = roleName;
        return pr;
    }
}
