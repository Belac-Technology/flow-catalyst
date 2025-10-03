package tech.flowcatalyst.platform.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.flowcatalyst.platform.authentication.IdpType;
import tech.flowcatalyst.platform.principal.AnchorDomainRepository;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalType;
import tech.flowcatalyst.platform.principal.UserIdentity;
import tech.flowcatalyst.platform.tenant.Tenant;
import tech.flowcatalyst.platform.tenant.TenantAccessGrant;
import tech.flowcatalyst.platform.tenant.TenantAccessGrantRepository;
import tech.flowcatalyst.platform.tenant.TenantAccessService;
import tech.flowcatalyst.platform.tenant.TenantRepository;
import tech.flowcatalyst.platform.tenant.TenantStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TenantAccessService.
 * Tests tenant access calculation logic with mocked repositories.
 *
 * CRITICAL: This service determines which tenants a principal can access.
 * Bugs here could lead to unauthorized access or data breaches.
 */
@ExtendWith(MockitoExtension.class)
class TenantAccessServiceTest {

    @Mock
    private AnchorDomainRepository anchorDomainRepo;

    @Mock
    private TenantRepository tenantRepo;

    @Mock
    private TenantAccessGrantRepository grantRepo;

    @InjectMocks
    private TenantAccessService service;

    // ========================================
    // ANCHOR DOMAIN TESTS
    // ========================================

    @Test
    @DisplayName("getAccessibleTenants should return all tenants when user is anchor domain")
    void getAccessibleTenants_shouldReturnAllTenants_whenUserIsAnchorDomain() {
        // Arrange: Anchor domain user
        Principal principal = createUserPrincipal(1L, "admin@mycompany.com", null);

        when(anchorDomainRepo.existsByDomain("mycompany.com")).thenReturn(true);

        // Mock 5 active tenants
        List<Tenant> allTenants = List.of(
            createTenant(10L, "Tenant 1"),
            createTenant(20L, "Tenant 2"),
            createTenant(30L, "Tenant 3"),
            createTenant(40L, "Tenant 4"),
            createTenant(50L, "Tenant 5")
        );
        when(tenantRepo.findAllActive()).thenReturn(allTenants);

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Can access all 5 tenants
        assertThat(accessible).containsExactlyInAnyOrder(10L, 20L, 30L, 40L, 50L);

        // Verify no grants were checked (anchor domain bypasses grants)
        verify(grantRepo, never()).findByPrincipalId(anyLong());
    }

    @Test
    @DisplayName("getAccessibleTenants should exclude inactive tenants when user is anchor domain")
    void getAccessibleTenants_shouldExcludeInactiveTenants_whenUserIsAnchorDomain() {
        // Arrange: Anchor user
        Principal principal = createUserPrincipal(1L, "admin@mycompany.com", null);

        when(anchorDomainRepo.existsByDomain("mycompany.com")).thenReturn(true);

        // Only active tenants returned (inactive already filtered by repository)
        List<Tenant> activeTenants = List.of(
            createTenant(10L, "Active 1"),
            createTenant(20L, "Active 2")
        );
        when(tenantRepo.findAllActive()).thenReturn(activeTenants);

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Only active tenants
        assertThat(accessible).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    @DisplayName("getAccessibleTenants should return empty when anchor domain but no active tenants")
    void getAccessibleTenants_shouldReturnEmpty_whenAnchorDomainButNoActiveTenants() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "admin@mycompany.com", null);

        when(anchorDomainRepo.existsByDomain("mycompany.com")).thenReturn(true);
        when(tenantRepo.findAllActive()).thenReturn(List.of());

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert
        assertThat(accessible).isEmpty();
    }

    // ========================================
    // HOME TENANT TESTS
    // ========================================

    @Test
    @DisplayName("getAccessibleTenants should return home tenant when user has home tenant")
    void getAccessibleTenants_shouldReturnHomeTenant_whenUserHasHomeTenant() {
        // Arrange: User with home tenant, not anchor domain
        Principal principal = createUserPrincipal(1L, "user@customer.com", 123L);

        when(anchorDomainRepo.existsByDomain("customer.com")).thenReturn(false);
        when(grantRepo.findByPrincipalId(1L)).thenReturn(List.of());
        when(tenantRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createTenant(id, "Tenant " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Returns home tenant
        assertThat(accessible).containsExactly(123L);
    }

    @Test
    @DisplayName("getAccessibleTenants should return empty when user has no home tenant and no grants")
    void getAccessibleTenants_shouldReturnEmpty_whenUserHasNoHomeTenantAndNoGrants() {
        // Arrange: Partner user with no home tenant, not anchor domain
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);
        when(grantRepo.findByPrincipalId(1L)).thenReturn(List.of());
        // No need to mock findByIds - tenantIds will be empty (no home tenant, no grants)

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Empty
        assertThat(accessible).isEmpty();
    }

    // ========================================
    // TENANT ACCESS GRANT TESTS
    // ========================================

    @Test
    @DisplayName("getAccessibleTenants should return granted tenants when user has valid grants")
    void getAccessibleTenants_shouldReturnGrantedTenants_whenUserHasValidGrants() {
        // Arrange: Partner with no home tenant but 3 grants
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);

        // Mock 3 valid grants (no expiry)
        List<TenantAccessGrant> grants = List.of(
            createGrant(1L, 100L, null),
            createGrant(1L, 200L, null),
            createGrant(1L, 300L, null)
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(tenantRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createTenant(id, "Tenant " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: 3 granted tenants
        assertThat(accessible).containsExactlyInAnyOrder(100L, 200L, 300L);
    }

    @Test
    @DisplayName("getAccessibleTenants should exclude expired grants when grants have expired")
    void getAccessibleTenants_shouldExcludeExpiredGrants_whenGrantsHaveExpired() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);

        Instant now = Instant.now();
        List<TenantAccessGrant> grants = List.of(
            createGrant(1L, 100L, now.plus(1, ChronoUnit.DAYS)),  // Valid (expires tomorrow)
            createGrant(1L, 200L, now.minus(1, ChronoUnit.DAYS)), // Expired yesterday
            createGrant(1L, 300L, null)                            // Never expires
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(tenantRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createTenant(id, "Tenant " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Only non-expired grants
        assertThat(accessible).containsExactlyInAnyOrder(100L, 300L);
    }

    @Test
    @DisplayName("getAccessibleTenants should include grants with null expiry when expiry is null")
    void getAccessibleTenants_shouldIncludeGrantsWithNullExpiry_whenExpiryIsNull() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);

        List<TenantAccessGrant> grants = List.of(
            createGrant(1L, 100L, null) // Never expires
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(tenantRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createTenant(id, "Tenant " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Grant included
        assertThat(accessible).containsExactly(100L);
    }

    @Test
    @DisplayName("getAccessibleTenants should return empty when all grants expired")
    void getAccessibleTenants_shouldReturnEmpty_whenAllGrantsExpired() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);

        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        List<TenantAccessGrant> grants = List.of(
            createGrant(1L, 100L, yesterday),
            createGrant(1L, 200L, yesterday)
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        // No need to mock findByIds - tenantIds will be empty due to expired grants

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Empty
        assertThat(accessible).isEmpty();
    }

    // ========================================
    // COMBINATION TESTS
    // ========================================

    @Test
    @DisplayName("getAccessibleTenants should combine home and grants when both exist")
    void getAccessibleTenants_shouldCombineHomeAndGrants_whenBothExist() {
        // Arrange: User with home tenant + 2 grants
        Principal principal = createUserPrincipal(1L, "user@customer.com", 123L);

        when(anchorDomainRepo.existsByDomain("customer.com")).thenReturn(false);

        List<TenantAccessGrant> grants = List.of(
            createGrant(1L, 456L, null),
            createGrant(1L, 789L, null)
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(tenantRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createTenant(id, "Tenant " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Home + 2 grants = 3 tenants
        assertThat(accessible).containsExactlyInAnyOrder(123L, 456L, 789L);
    }

    @Test
    @DisplayName("getAccessibleTenants should deduplicate tenants when grant matches home tenant")
    void getAccessibleTenants_shouldDeduplicateTenants_whenGrantMatchesHomeTenant() {
        // Arrange: Home tenant 123, grant also for 123
        Principal principal = createUserPrincipal(1L, "user@customer.com", 123L);

        when(anchorDomainRepo.existsByDomain("customer.com")).thenReturn(false);

        List<TenantAccessGrant> grants = List.of(
            createGrant(1L, 123L, null), // Same as home tenant
            createGrant(1L, 456L, null)
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(tenantRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createTenant(id, "Tenant " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Deduplicated - only 2 unique tenants
        assertThat(accessible).containsExactlyInAnyOrder(123L, 456L);
        assertThat(accessible).hasSize(2); // Not 3
    }

    @Test
    @DisplayName("getAccessibleTenants should prioritize anchor domain over home tenant")
    void getAccessibleTenants_shouldPrioritizeAnchorDomain_whenUserHasBothAnchorAndHome() {
        // Arrange: User has home tenant BUT also anchor domain
        Principal principal = createUserPrincipal(1L, "admin@mycompany.com", 999L);

        when(anchorDomainRepo.existsByDomain("mycompany.com")).thenReturn(true);

        List<Tenant> allTenants = List.of(
            createTenant(10L, "Tenant 1"),
            createTenant(20L, "Tenant 2")
        );
        when(tenantRepo.findAllActive()).thenReturn(allTenants);

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Returns ALL tenants (anchor domain logic), not just home
        assertThat(accessible).containsExactlyInAnyOrder(10L, 20L);

        // Home tenant and grants never checked
        verify(grantRepo, never()).findByPrincipalId(anyLong());
    }

    // ========================================
    // EDGE CASES
    // ========================================

    @Test
    @DisplayName("getAccessibleTenants should handle null userIdentity gracefully")
    void getAccessibleTenants_shouldHandleNullUserIdentity_whenUserIdentityIsNull() {
        // Arrange: Service account (no userIdentity)
        Principal serviceAccount = new Principal();
        serviceAccount.id = 1L;
        serviceAccount.type = PrincipalType.SERVICE;
        serviceAccount.tenantId = 123L;
        serviceAccount.userIdentity = null; // No user identity

        when(grantRepo.findByPrincipalId(1L)).thenReturn(List.of());
        when(tenantRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createTenant(id, "Tenant " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleTenants(serviceAccount);

        // Assert: Should return home tenant (doesn't crash)
        assertThat(accessible).containsExactly(123L);

        // Should not check anchor domain (no email domain)
        verify(anchorDomainRepo, never()).existsByDomain(anyString());
    }

    @Test
    @DisplayName("getAccessibleTenants should handle service account with no home tenant")
    void getAccessibleTenants_shouldReturnEmpty_whenServiceAccountHasNoHomeTenant() {
        // Arrange: Service account with no home tenant
        Principal serviceAccount = new Principal();
        serviceAccount.id = 1L;
        serviceAccount.type = PrincipalType.SERVICE;
        serviceAccount.tenantId = null;
        serviceAccount.userIdentity = null;

        when(grantRepo.findByPrincipalId(1L)).thenReturn(List.of());
        // No need to mock findByIds - tenantIds will be empty (no home tenant, no grants)

        // Act
        Set<Long> accessible = service.getAccessibleTenants(serviceAccount);

        // Assert: Empty
        assertThat(accessible).isEmpty();
    }

    @Test
    @DisplayName("getAccessibleTenants should handle empty grant list")
    void getAccessibleTenants_shouldHandleEmptyGrantList_whenNoGrantsExist() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "user@customer.com", 123L);

        when(anchorDomainRepo.existsByDomain("customer.com")).thenReturn(false);
        when(grantRepo.findByPrincipalId(1L)).thenReturn(List.of());
        when(tenantRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createTenant(id, "Tenant " + id))
                .toList();
        }); // Empty

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Just home tenant
        assertThat(accessible).containsExactly(123L);
    }

    @Test
    @DisplayName("getAccessibleTenants should handle grant expiring at exact current time")
    void getAccessibleTenants_shouldExcludeGrant_whenExpiresAtExactCurrentTime() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);

        // Grant expires in exactly 1 second (edge case)
        Instant almostNow = Instant.now().plus(1, ChronoUnit.MILLIS);
        List<TenantAccessGrant> grants = List.of(
            createGrant(1L, 100L, almostNow)
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(tenantRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createTenant(id, "Tenant " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleTenants(principal);

        // Assert: Should be included (expires in future, even if barely)
        assertThat(accessible).containsExactly(100L);
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    private Principal createUserPrincipal(Long id, String email, Long tenantId) {
        Principal p = new Principal();
        p.id = id;
        p.type = PrincipalType.USER;
        p.tenantId = tenantId;

        UserIdentity identity = new UserIdentity();
        identity.email = email;
        identity.emailDomain = extractDomain(email);
        identity.idpType = IdpType.INTERNAL;

        p.userIdentity = identity;
        return p;
    }

    private Tenant createTenant(Long id, String name) {
        Tenant t = new Tenant();
        t.id = id;
        t.name = name;
        t.status = TenantStatus.ACTIVE;
        return t;
    }

    private TenantAccessGrant createGrant(Long principalId, Long tenantId, Instant expiresAt) {
        TenantAccessGrant grant = new TenantAccessGrant();
        grant.principalId = principalId;
        grant.tenantId = tenantId;
        grant.expiresAt = expiresAt;
        grant.grantedAt = Instant.now();
        return grant;
    }

    private String extractDomain(String email) {
        int atIndex = email.indexOf('@');
        return email.substring(atIndex + 1);
    }
}
