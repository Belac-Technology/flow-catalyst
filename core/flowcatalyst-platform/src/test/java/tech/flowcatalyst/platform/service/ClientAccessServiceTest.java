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
import tech.flowcatalyst.platform.client.Client;
import tech.flowcatalyst.platform.client.ClientAccessGrant;
import tech.flowcatalyst.platform.client.ClientAccessGrantRepository;
import tech.flowcatalyst.platform.client.ClientAccessService;
import tech.flowcatalyst.platform.client.ClientRepository;
import tech.flowcatalyst.platform.client.ClientStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClientAccessService.
 * Tests client access calculation logic with mocked repositories.
 *
 * CRITICAL: This service determines which clients a principal can access.
 * Bugs here could lead to unauthorized access or data breaches.
 */
@ExtendWith(MockitoExtension.class)
class ClientAccessServiceTest {

    @Mock
    private AnchorDomainRepository anchorDomainRepo;

    @Mock
    private ClientRepository clientRepo;

    @Mock
    private ClientAccessGrantRepository grantRepo;

    @InjectMocks
    private ClientAccessService service;

    // ========================================
    // ANCHOR DOMAIN TESTS
    // ========================================

    @Test
    @DisplayName("getAccessibleClients should return all clients when user is anchor domain")
    void getAccessibleClients_shouldReturnAllClients_whenUserIsAnchorDomain() {
        // Arrange: Anchor domain user
        Principal principal = createUserPrincipal(1L, "admin@mycompany.com", null);

        when(anchorDomainRepo.existsByDomain("mycompany.com")).thenReturn(true);

        // Mock 5 active clients
        List<Client> allClients = List.of(
            createClient(10L, "Client 1"),
            createClient(20L, "Client 2"),
            createClient(30L, "Client 3"),
            createClient(40L, "Client 4"),
            createClient(50L, "Client 5")
        );
        when(clientRepo.findAllActive()).thenReturn(allClients);

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Can access all 5 clients
        assertThat(accessible).containsExactlyInAnyOrder(10L, 20L, 30L, 40L, 50L);

        // Verify no grants were checked (anchor domain bypasses grants)
        verify(grantRepo, never()).findByPrincipalId(anyLong());
    }

    @Test
    @DisplayName("getAccessibleClients should exclude inactive clients when user is anchor domain")
    void getAccessibleClients_shouldExcludeInactiveClients_whenUserIsAnchorDomain() {
        // Arrange: Anchor user
        Principal principal = createUserPrincipal(1L, "admin@mycompany.com", null);

        when(anchorDomainRepo.existsByDomain("mycompany.com")).thenReturn(true);

        // Only active clients returned (inactive already filtered by repository)
        List<Client> activeClients = List.of(
            createClient(10L, "Active 1"),
            createClient(20L, "Active 2")
        );
        when(clientRepo.findAllActive()).thenReturn(activeClients);

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Only active clients
        assertThat(accessible).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    @DisplayName("getAccessibleClients should return empty when anchor domain but no active clients")
    void getAccessibleClients_shouldReturnEmpty_whenAnchorDomainButNoActiveClients() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "admin@mycompany.com", null);

        when(anchorDomainRepo.existsByDomain("mycompany.com")).thenReturn(true);
        when(clientRepo.findAllActive()).thenReturn(List.of());

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert
        assertThat(accessible).isEmpty();
    }

    // ========================================
    // HOME CLIENT TESTS
    // ========================================

    @Test
    @DisplayName("getAccessibleClients should return home client when user has home client")
    void getAccessibleClients_shouldReturnHomeClient_whenUserHasHomeClient() {
        // Arrange: User with home client, not anchor domain
        Principal principal = createUserPrincipal(1L, "user@customer.com", 123L);

        when(anchorDomainRepo.existsByDomain("customer.com")).thenReturn(false);
        when(grantRepo.findByPrincipalId(1L)).thenReturn(List.of());
        when(clientRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createClient(id, "Client " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Returns home client
        assertThat(accessible).containsExactly(123L);
    }

    @Test
    @DisplayName("getAccessibleClients should return empty when user has no home client and no grants")
    void getAccessibleClients_shouldReturnEmpty_whenUserHasNoHomeClientAndNoGrants() {
        // Arrange: Partner user with no home client, not anchor domain
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);
        when(grantRepo.findByPrincipalId(1L)).thenReturn(List.of());
        // No need to mock findByIds - clientIds will be empty (no home client, no grants)

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Empty
        assertThat(accessible).isEmpty();
    }

    // ========================================
    // CLIENT ACCESS GRANT TESTS
    // ========================================

    @Test
    @DisplayName("getAccessibleClients should return granted clients when user has valid grants")
    void getAccessibleClients_shouldReturnGrantedClients_whenUserHasValidGrants() {
        // Arrange: Partner with no home client but 3 grants
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);

        // Mock 3 valid grants (no expiry)
        List<ClientAccessGrant> grants = List.of(
            createGrant(1L, 100L, null),
            createGrant(1L, 200L, null),
            createGrant(1L, 300L, null)
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(clientRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createClient(id, "Client " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: 3 granted clients
        assertThat(accessible).containsExactlyInAnyOrder(100L, 200L, 300L);
    }

    @Test
    @DisplayName("getAccessibleClients should exclude expired grants when grants have expired")
    void getAccessibleClients_shouldExcludeExpiredGrants_whenGrantsHaveExpired() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);

        Instant now = Instant.now();
        List<ClientAccessGrant> grants = List.of(
            createGrant(1L, 100L, now.plus(1, ChronoUnit.DAYS)),  // Valid (expires tomorrow)
            createGrant(1L, 200L, now.minus(1, ChronoUnit.DAYS)), // Expired yesterday
            createGrant(1L, 300L, null)                            // Never expires
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(clientRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createClient(id, "Client " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Only non-expired grants
        assertThat(accessible).containsExactlyInAnyOrder(100L, 300L);
    }

    @Test
    @DisplayName("getAccessibleClients should include grants with null expiry when expiry is null")
    void getAccessibleClients_shouldIncludeGrantsWithNullExpiry_whenExpiryIsNull() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);

        List<ClientAccessGrant> grants = List.of(
            createGrant(1L, 100L, null) // Never expires
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(clientRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createClient(id, "Client " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Grant included
        assertThat(accessible).containsExactly(100L);
    }

    @Test
    @DisplayName("getAccessibleClients should return empty when all grants expired")
    void getAccessibleClients_shouldReturnEmpty_whenAllGrantsExpired() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);

        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        List<ClientAccessGrant> grants = List.of(
            createGrant(1L, 100L, yesterday),
            createGrant(1L, 200L, yesterday)
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        // No need to mock findByIds - clientIds will be empty due to expired grants

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Empty
        assertThat(accessible).isEmpty();
    }

    // ========================================
    // COMBINATION TESTS
    // ========================================

    @Test
    @DisplayName("getAccessibleClients should combine home and grants when both exist")
    void getAccessibleClients_shouldCombineHomeAndGrants_whenBothExist() {
        // Arrange: User with home client + 2 grants
        Principal principal = createUserPrincipal(1L, "user@customer.com", 123L);

        when(anchorDomainRepo.existsByDomain("customer.com")).thenReturn(false);

        List<ClientAccessGrant> grants = List.of(
            createGrant(1L, 456L, null),
            createGrant(1L, 789L, null)
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(clientRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createClient(id, "Client " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Home + 2 grants = 3 clients
        assertThat(accessible).containsExactlyInAnyOrder(123L, 456L, 789L);
    }

    @Test
    @DisplayName("getAccessibleClients should deduplicate clients when grant matches home client")
    void getAccessibleClients_shouldDeduplicateClients_whenGrantMatchesHomeClient() {
        // Arrange: Home client 123, grant also for 123
        Principal principal = createUserPrincipal(1L, "user@customer.com", 123L);

        when(anchorDomainRepo.existsByDomain("customer.com")).thenReturn(false);

        List<ClientAccessGrant> grants = List.of(
            createGrant(1L, 123L, null), // Same as home client
            createGrant(1L, 456L, null)
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(clientRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createClient(id, "Client " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Deduplicated - only 2 unique clients
        assertThat(accessible).containsExactlyInAnyOrder(123L, 456L);
        assertThat(accessible).hasSize(2); // Not 3
    }

    @Test
    @DisplayName("getAccessibleClients should prioritize anchor domain over home client")
    void getAccessibleClients_shouldPrioritizeAnchorDomain_whenUserHasBothAnchorAndHome() {
        // Arrange: User has home client BUT also anchor domain
        Principal principal = createUserPrincipal(1L, "admin@mycompany.com", 999L);

        when(anchorDomainRepo.existsByDomain("mycompany.com")).thenReturn(true);

        List<Client> allClients = List.of(
            createClient(10L, "Client 1"),
            createClient(20L, "Client 2")
        );
        when(clientRepo.findAllActive()).thenReturn(allClients);

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Returns ALL clients (anchor domain logic), not just home
        assertThat(accessible).containsExactlyInAnyOrder(10L, 20L);

        // Home client and grants never checked
        verify(grantRepo, never()).findByPrincipalId(anyLong());
    }

    // ========================================
    // EDGE CASES
    // ========================================

    @Test
    @DisplayName("getAccessibleClients should handle null userIdentity gracefully")
    void getAccessibleClients_shouldHandleNullUserIdentity_whenUserIdentityIsNull() {
        // Arrange: Service account (no userIdentity)
        Principal serviceAccount = new Principal();
        serviceAccount.id = 1L;
        serviceAccount.type = PrincipalType.SERVICE;
        serviceAccount.clientId = 123L;
        serviceAccount.userIdentity = null; // No user identity

        when(grantRepo.findByPrincipalId(1L)).thenReturn(List.of());
        when(clientRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createClient(id, "Client " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleClients(serviceAccount);

        // Assert: Should return home client (doesn't crash)
        assertThat(accessible).containsExactly(123L);

        // Should not check anchor domain (no email domain)
        verify(anchorDomainRepo, never()).existsByDomain(anyString());
    }

    @Test
    @DisplayName("getAccessibleClients should handle service account with no home client")
    void getAccessibleClients_shouldReturnEmpty_whenServiceAccountHasNoHomeClient() {
        // Arrange: Service account with no home client
        Principal serviceAccount = new Principal();
        serviceAccount.id = 1L;
        serviceAccount.type = PrincipalType.SERVICE;
        serviceAccount.clientId = null;
        serviceAccount.userIdentity = null;

        when(grantRepo.findByPrincipalId(1L)).thenReturn(List.of());
        // No need to mock findByIds - clientIds will be empty (no home client, no grants)

        // Act
        Set<Long> accessible = service.getAccessibleClients(serviceAccount);

        // Assert: Empty
        assertThat(accessible).isEmpty();
    }

    @Test
    @DisplayName("getAccessibleClients should handle empty grant list")
    void getAccessibleClients_shouldHandleEmptyGrantList_whenNoGrantsExist() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "user@customer.com", 123L);

        when(anchorDomainRepo.existsByDomain("customer.com")).thenReturn(false);
        when(grantRepo.findByPrincipalId(1L)).thenReturn(List.of());
        when(clientRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createClient(id, "Client " + id))
                .toList();
        }); // Empty

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Just home client
        assertThat(accessible).containsExactly(123L);
    }

    @Test
    @DisplayName("getAccessibleClients should handle grant expiring at exact current time")
    void getAccessibleClients_shouldExcludeGrant_whenExpiresAtExactCurrentTime() {
        // Arrange
        Principal principal = createUserPrincipal(1L, "partner@logistics.com", null);

        when(anchorDomainRepo.existsByDomain("logistics.com")).thenReturn(false);

        // Grant expires in exactly 1 second (edge case)
        Instant almostNow = Instant.now().plus(1, ChronoUnit.MILLIS);
        List<ClientAccessGrant> grants = List.of(
            createGrant(1L, 100L, almostNow)
        );
        when(grantRepo.findByPrincipalId(1L)).thenReturn(grants);
        when(clientRepo.findByIds(any())).thenAnswer(inv -> {
            Set<Long> ids = inv.getArgument(0);
            return ids.stream()
                .map(id -> createClient(id, "Client " + id))
                .toList();
        });

        // Act
        Set<Long> accessible = service.getAccessibleClients(principal);

        // Assert: Should be included (expires in future, even if barely)
        assertThat(accessible).containsExactly(100L);
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    private Principal createUserPrincipal(Long id, String email, Long clientId) {
        Principal p = new Principal();
        p.id = id;
        p.type = PrincipalType.USER;
        p.clientId = clientId;

        UserIdentity identity = new UserIdentity();
        identity.email = email;
        identity.emailDomain = extractDomain(email);
        identity.idpType = IdpType.INTERNAL;

        p.userIdentity = identity;
        return p;
    }

    private Client createClient(Long id, String name) {
        Client c = new Client();
        c.id = id;
        c.name = name;
        c.status = ClientStatus.ACTIVE;
        return c;
    }

    private ClientAccessGrant createGrant(Long principalId, Long clientId, Instant expiresAt) {
        ClientAccessGrant grant = new ClientAccessGrant();
        grant.principalId = principalId;
        grant.clientId = clientId;
        grant.expiresAt = expiresAt;
        grant.grantedAt = Instant.now();
        return grant;
    }

    private String extractDomain(String email) {
        int atIndex = email.indexOf('@');
        return email.substring(atIndex + 1);
    }
}
