package tech.flowcatalyst.platform.service;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.flowcatalyst.platform.authorization.PermissionRegistry;
import tech.flowcatalyst.platform.authorization.PrincipalRole;
import tech.flowcatalyst.platform.authorization.PrincipalRoleRepository;
import tech.flowcatalyst.platform.authorization.RoleService;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalRepository;
import tech.flowcatalyst.platform.principal.PrincipalType;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RoleService.
 * Tests role assignment with code-first roles from PermissionRegistry.
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private PermissionRegistry permissionRegistry;

    @Mock
    private PrincipalRoleRepository principalRoleRepo;

    @Mock
    private PrincipalRepository principalRepo;

    @InjectMocks
    private RoleService service;

    // ========================================
    // assignRole TESTS
    // ========================================

    @Test
    @DisplayName("assignRole should create assignment when principal exists and role is in registry")
    void assignRole_shouldCreateAssignment_whenPrincipalAndRoleExist() {
        // Arrange
        Long principalId = 100L;
        String roleName = "test:admin";

        when(principalRepo.findByIdOptional(principalId)).thenReturn(Optional.of(createPrincipal(principalId)));
        when(permissionRegistry.hasRole(roleName)).thenReturn(true);
        when(principalRoleRepo.count("principalId = ?1 AND roleName = ?2", principalId, roleName))
            .thenReturn(0L);

        // Act
        PrincipalRole result = service.assignRole(principalId, roleName, "MANUAL");

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.id).isNotNull(); // TSID generated
        assertThat(result.principalId).isEqualTo(principalId);
        assertThat(result.roleName).isEqualTo(roleName);
        assertThat(result.assignmentSource).isEqualTo("MANUAL");

        verify(principalRoleRepo).persist(result);
    }

    @Test
    @DisplayName("assignRole should default to MANUAL when assignmentSource is null")
    void assignRole_shouldDefaultToManual_whenAssignmentSourceIsNull() {
        // Arrange
        Long principalId = 100L;
        String roleName = "test:admin";

        when(principalRepo.findByIdOptional(principalId)).thenReturn(Optional.of(createPrincipal(principalId)));
        when(permissionRegistry.hasRole(roleName)).thenReturn(true);
        when(principalRoleRepo.count("principalId = ?1 AND roleName = ?2", principalId, roleName))
            .thenReturn(0L);

        // Act
        PrincipalRole result = service.assignRole(principalId, roleName, null);

        // Assert
        assertThat(result.assignmentSource).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("assignRole should throw NotFoundException when principal does not exist")
    void assignRole_shouldThrowNotFoundException_whenPrincipalDoesNotExist() {
        // Arrange
        when(principalRepo.findByIdOptional(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.assignRole(999L, "test:admin", "MANUAL"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Principal not found");

        verify(principalRoleRepo, never()).persist(any(PrincipalRole.class));
    }

    @Test
    @DisplayName("assignRole should throw BadRequestException when role is not in registry")
    void assignRole_shouldThrowBadRequestException_whenRoleNotInRegistry() {
        // Arrange
        Long principalId = 100L;
        String roleName = "unknown:role";

        when(principalRepo.findByIdOptional(principalId)).thenReturn(Optional.of(createPrincipal(principalId)));
        when(permissionRegistry.hasRole(roleName)).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> service.assignRole(principalId, roleName, "MANUAL"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Role not defined in PermissionRegistry");

        verify(principalRoleRepo, never()).persist(any(PrincipalRole.class));
    }

    @Test
    @DisplayName("assignRole should throw exception when assignment already exists")
    void assignRole_shouldThrowException_whenAssignmentAlreadyExists() {
        // Arrange
        Long principalId = 100L;
        String roleName = "test:admin";

        when(principalRepo.findByIdOptional(principalId)).thenReturn(Optional.of(createPrincipal(principalId)));
        when(permissionRegistry.hasRole(roleName)).thenReturn(true);
        when(principalRoleRepo.count("principalId = ?1 AND roleName = ?2", principalId, roleName))
            .thenReturn(1L); // Already exists

        // Act & Assert
        assertThatThrownBy(() -> service.assignRole(principalId, roleName, "MANUAL"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Role already assigned to principal");

        verify(principalRoleRepo, never()).persist(any(PrincipalRole.class));
    }

    @Test
    @DisplayName("assignRole should accept IDP_SYNC as assignment source")
    void assignRole_shouldAcceptIdpSync_asAssignmentSource() {
        // Arrange
        Long principalId = 100L;
        String roleName = "test:admin";

        when(principalRepo.findByIdOptional(principalId)).thenReturn(Optional.of(createPrincipal(principalId)));
        when(permissionRegistry.hasRole(roleName)).thenReturn(true);
        when(principalRoleRepo.count("principalId = ?1 AND roleName = ?2", principalId, roleName))
            .thenReturn(0L);

        // Act
        PrincipalRole result = service.assignRole(principalId, roleName, "IDP_SYNC");

        // Assert
        assertThat(result.assignmentSource).isEqualTo("IDP_SYNC");
    }

    // ========================================
    // removeRole TESTS
    // ========================================

    @Test
    @DisplayName("removeRole should remove assignment when assignment exists")
    void removeRole_shouldRemoveAssignment_whenAssignmentExists() {
        // Arrange
        Long principalId = 100L;
        String roleName = "test:admin";

        when(principalRoleRepo.delete("principalId = ?1 AND roleName = ?2", principalId, roleName))
            .thenReturn(1L); // 1 row deleted

        // Act
        service.removeRole(principalId, roleName);

        // Assert
        verify(principalRoleRepo).delete("principalId = ?1 AND roleName = ?2", principalId, roleName);
    }

    @Test
    @DisplayName("removeRole should throw NotFoundException when assignment does not exist")
    void removeRole_shouldThrowNotFoundException_whenAssignmentDoesNotExist() {
        // Arrange
        when(principalRoleRepo.delete("principalId = ?1 AND roleName = ?2", 100L, "test:admin"))
            .thenReturn(0L); // No rows deleted

        // Act & Assert
        assertThatThrownBy(() -> service.removeRole(100L, "test:admin"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Role assignment not found");
    }

    // ========================================
    // removeRolesBySource TESTS
    // ========================================

    @Test
    @DisplayName("removeRolesBySource should remove roles with matching source")
    void removeRolesBySource_shouldRemoveRoles_whenSourceMatches() {
        // Arrange
        Long principalId = 100L;
        String source = "IDP_SYNC";

        when(principalRoleRepo.delete("principalId = ?1 AND assignmentSource = ?2", principalId, source))
            .thenReturn(3L); // 3 rows deleted

        // Act
        long result = service.removeRolesBySource(principalId, source);

        // Assert
        assertThat(result).isEqualTo(3L);
        verify(principalRoleRepo).delete("principalId = ?1 AND assignmentSource = ?2", principalId, source);
    }

    @Test
    @DisplayName("removeRolesBySource should return 0 when no roles match")
    void removeRolesBySource_shouldReturnZero_whenNoRolesMatch() {
        // Arrange
        when(principalRoleRepo.delete("principalId = ?1 AND assignmentSource = ?2", 100L, "IDP_SYNC"))
            .thenReturn(0L);

        // Act
        long result = service.removeRolesBySource(100L, "IDP_SYNC");

        // Assert
        assertThat(result).isEqualTo(0L);
    }

    @Test
    @DisplayName("removeRolesBySource should only remove roles with specified source")
    void removeRolesBySource_shouldOnlyRemoveSpecifiedSource_whenMultipleSourcesExist() {
        // Arrange
        Long principalId = 100L;

        when(principalRoleRepo.delete("principalId = ?1 AND assignmentSource = ?2", principalId, "IDP_SYNC"))
            .thenReturn(2L);

        // Act
        long result = service.removeRolesBySource(principalId, "IDP_SYNC");

        // Assert
        assertThat(result).isEqualTo(2L);
        // Verify it doesn't remove MANUAL assignments
        verify(principalRoleRepo, never()).delete("principalId = ?1 AND assignmentSource = ?2", principalId, "MANUAL");
    }

    // ========================================
    // findAssignmentsByPrincipal TESTS
    // ========================================

    @Test
    @DisplayName("findAssignmentsByPrincipal should return assignments when principal has assignments")
    void findAssignmentsByPrincipal_shouldReturnAssignments_whenPrincipalHasAssignments() {
        // Arrange
        Long principalId = 100L;
        PrincipalRole pr1 = createPrincipalRole(1L, principalId, "test:admin");
        PrincipalRole pr2 = createPrincipalRole(2L, principalId, "test:viewer");

        when(principalRoleRepo.findByPrincipalId(principalId)).thenReturn(List.of(pr1, pr2));

        // Act
        List<PrincipalRole> result = service.findAssignmentsByPrincipal(principalId);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder(pr1, pr2);
    }

    @Test
    @DisplayName("findAssignmentsByPrincipal should return empty when principal has no assignments")
    void findAssignmentsByPrincipal_shouldReturnEmpty_whenPrincipalHasNoAssignments() {
        // Arrange
        when(principalRoleRepo.findByPrincipalId(100L)).thenReturn(List.of());

        // Act
        List<PrincipalRole> result = service.findAssignmentsByPrincipal(100L);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findAssignmentsByPrincipal should return assignments with different sources")
    void findAssignmentsByPrincipal_shouldReturnAssignments_withDifferentSources() {
        // Arrange
        Long principalId = 100L;
        PrincipalRole manualAssignment = createPrincipalRole(1L, principalId, "test:admin");
        manualAssignment.assignmentSource = "MANUAL";

        PrincipalRole idpAssignment = createPrincipalRole(2L, principalId, "test:viewer");
        idpAssignment.assignmentSource = "IDP_SYNC";

        when(principalRoleRepo.findByPrincipalId(principalId)).thenReturn(List.of(manualAssignment, idpAssignment));

        // Act
        List<PrincipalRole> result = service.findAssignmentsByPrincipal(principalId);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(pr -> {
            assertThat(pr.roleName).isEqualTo("test:admin");
            assertThat(pr.assignmentSource).isEqualTo("MANUAL");
        });
        assertThat(result).anySatisfy(pr -> {
            assertThat(pr.roleName).isEqualTo("test:viewer");
            assertThat(pr.assignmentSource).isEqualTo("IDP_SYNC");
        });
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    private PrincipalRole createPrincipalRole(Long id, Long principalId, String roleName) {
        PrincipalRole pr = new PrincipalRole();
        pr.id = id;
        pr.principalId = principalId;
        pr.roleName = roleName;
        pr.assignmentSource = "MANUAL";
        return pr;
    }

    private Principal createPrincipal(Long id) {
        Principal p = new Principal();
        p.id = id;
        p.type = PrincipalType.USER;
        p.name = "Test Principal";
        p.active = true;
        return p;
    }
}
