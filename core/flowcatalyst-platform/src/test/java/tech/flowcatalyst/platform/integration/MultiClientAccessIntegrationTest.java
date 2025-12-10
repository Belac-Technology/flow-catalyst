package tech.flowcatalyst.platform.integration;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.flowcatalyst.platform.principal.AnchorDomain;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.client.Client;
import tech.flowcatalyst.platform.principal.AnchorDomainRepository;
import tech.flowcatalyst.platform.client.ClientService;
import tech.flowcatalyst.platform.principal.UserService;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for multi-client access control.
 * Tests end-to-end client access calculation with real database.
 */
@QuarkusTest
@TestTransaction
class MultiClientAccessIntegrationTest {

    @Inject
    ClientService clientService;

    @Inject
    UserService userService;

    @Inject
    AnchorDomainRepository anchorDomainRepo;

    // ========================================
    // ANCHOR DOMAIN USER TESTS
    // ========================================

    @Test
    @DisplayName("Anchor domain user should access all active clients")
    void anchorDomainUser_shouldAccessAllClients_whenDomainIsAnchor() {
        // Arrange: Register anchor domain (e.g., internal company domain)
        AnchorDomain anchor = new AnchorDomain();
        anchor.id = TsidGenerator.generate();
        anchor.domain = "mycompany.com";
        anchorDomainRepo.persist(anchor);

        // Create several clients (customer accounts)
        Client client1 = clientService.createClient("Customer A", "customer-a");
        Client client2 = clientService.createClient("Customer B", "customer-b");
        Client client3 = clientService.createClient("Customer C", "customer-c");

        // Create anchor domain user (internal employee)
        Principal admin = userService.createInternalUser(
            "admin@mycompany.com",
            "SecurePass123!",
            "Platform Admin",
            null  // No home client - has access to all
        );

        // Act: Get accessible clients
        Set<String> accessible = clientService.getAccessibleClients(admin.id);

        // Assert: Can access all active clients
        assertThat(accessible).containsExactlyInAnyOrder(
            client1.id,
            client2.id,
            client3.id
        );
    }

    @Test
    @DisplayName("Anchor domain user should not see inactive clients")
    void anchorDomainUser_shouldNotSeeInactive_whenClientsDeactivated() {
        // Arrange: Register anchor domain
        AnchorDomain anchor = new AnchorDomain();
        anchor.id = TsidGenerator.generate();
        anchor.domain = "mycompany.com";
        anchorDomainRepo.persist(anchor);

        // Create 3 clients
        Client active1 = clientService.createClient("Active 1", "active-1");
        Client active2 = clientService.createClient("Active 2", "active-2");
        Client inactive = clientService.createClient("Inactive", "inactive");

        // Deactivate one client
        clientService.deactivateClient(inactive.id, "Test deactivation", "system");

        // Create anchor user
        Principal admin = userService.createInternalUser(
            "admin@mycompany.com",
            "SecurePass123!",
            "Admin",
            null
        );

        // Act
        Set<Long> accessible = clientService.getAccessibleClients(admin.id);

        // Assert: Only active clients visible
        assertThat(accessible).containsExactlyInAnyOrder(active1.id, active2.id);
        assertThat(accessible).doesNotContain(inactive.id);
    }

    // ========================================
    // HOME CLIENT USER TESTS
    // ========================================

    @Test
    @DisplayName("Regular user should only access home client")
    void regularUser_shouldAccessOnlyHomeClient_whenNoGrants() {
        // Arrange: Create 2 customer clients
        Client client1 = clientService.createClient("Customer A", "customer-a");
        Client client2 = clientService.createClient("Customer B", "customer-b");

        // Create user belonging to client 1
        Principal user = userService.createInternalUser(
            "user@customer-a.com",
            "SecurePass123!",
            "Customer A User",
            client1.id
        );

        // Act
        Set<Long> accessible = clientService.getAccessibleClients(user.id);

        // Assert: Only has access to home client
        assertThat(accessible).containsExactly(client1.id);
        assertThat(accessible).doesNotContain(client2.id);
    }

    @Test
    @DisplayName("User with no home client and no grants should have no access")
    void user_shouldHaveNoAccess_whenNoHomeClientAndNoGrants() {
        // Arrange: Create client
        Client client = clientService.createClient("Customer A", "customer-a");

        // Create user with NO home client (e.g., partner user before grants)
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner User",
            null  // No home client
        );

        // Act
        Set<Long> accessible = clientService.getAccessibleClients(partner.id);

        // Assert: No access to any client
        assertThat(accessible).isEmpty();
    }

    // ========================================
    // CLIENT ACCESS GRANT TESTS
    // ========================================

    @Test
    @DisplayName("Partner user should access granted clients")
    void partnerUser_shouldAccessGrantedClients_whenGrantsExist() {
        // Arrange: Create 3 customer clients
        Client customerA = clientService.createClient("Customer A", "customer-a");
        Client customerB = clientService.createClient("Customer B", "customer-b");
        Client customerC = clientService.createClient("Customer C", "customer-c");

        // Create partner user (no home client)
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Logistics Partner",
            null
        );

        // Grant access to customers A and B (not C)
        clientService.grantClientAccess(partner.id, customerA.id);
        clientService.grantClientAccess(partner.id, customerB.id);

        // Act
        Set<Long> accessible = clientService.getAccessibleClients(partner.id);

        // Assert: Can access A and B, but not C
        assertThat(accessible).containsExactlyInAnyOrder(customerA.id, customerB.id);
        assertThat(accessible).doesNotContain(customerC.id);
    }

    @Test
    @DisplayName("User with home client and grants should access both")
    void user_shouldAccessBoth_whenHasHomeClientAndGrants() {
        // Arrange: Create 3 clients
        Client homeClient = clientService.createClient("Home Client", "home");
        Client grantedA = clientService.createClient("Granted A", "granted-a");
        Client grantedB = clientService.createClient("Granted B", "granted-b");

        // Create user with home client
        Principal user = userService.createInternalUser(
            "user@example.com",
            "SecurePass123!",
            "User",
            homeClient.id
        );

        // Grant access to additional clients
        clientService.grantClientAccess(user.id, grantedA.id);
        clientService.grantClientAccess(user.id, grantedB.id);

        // Act
        Set<Long> accessible = clientService.getAccessibleClients(user.id);

        // Assert: Access to home + 2 granted = 3 total
        assertThat(accessible).containsExactlyInAnyOrder(
            homeClient.id,
            grantedA.id,
            grantedB.id
        );
    }

    @Test
    @DisplayName("Revoking client grant should immediately remove access")
    void revokeClientGrant_shouldRemoveAccess_whenGrantRevoked() {
        // Arrange: Create client and user
        Client client = clientService.createClient("Customer", "customer");
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner",
            null
        );

        // Grant access
        clientService.grantClientAccess(partner.id, client.id);

        // Verify access granted
        Set<Long> beforeRevoke = clientService.getAccessibleClients(partner.id);
        assertThat(beforeRevoke).contains(client.id);

        // Act: Revoke access
        clientService.revokeClientAccess(partner.id, client.id);

        // Assert: Access immediately removed
        Set<Long> afterRevoke = clientService.getAccessibleClients(partner.id);
        assertThat(afterRevoke).doesNotContain(client.id);
        assertThat(afterRevoke).isEmpty();
    }

    // ========================================
    // GRANT MANAGEMENT TESTS
    // ========================================

    @Test
    @DisplayName("Cannot grant access if user already has home client")
    void grantClientAccess_shouldFail_whenUserAlreadyHasHomeClient() {
        // Arrange: Create client
        Client client = clientService.createClient("Customer", "customer");

        // Create user with this client as home
        Principal user = userService.createInternalUser(
            "user@customer.com",
            "SecurePass123!",
            "User",
            client.id
        );

        // Act & Assert: Cannot grant same client (redundant)
        assertThatThrownBy(() ->
            clientService.grantClientAccess(user.id, client.id)
        )
        .hasMessageContaining("already belongs to this client");
    }

    @Test
    @DisplayName("Cannot grant same client access twice")
    void grantClientAccess_shouldFail_whenGrantAlreadyExists() {
        // Arrange
        Client client = clientService.createClient("Customer", "customer");
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner",
            null
        );

        // First grant
        clientService.grantClientAccess(partner.id, client.id);

        // Act & Assert: Second grant fails
        assertThatThrownBy(() ->
            clientService.grantClientAccess(partner.id, client.id)
        )
        .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("Revoking non-existent grant should fail")
    void revokeClientAccess_shouldFail_whenGrantDoesNotExist() {
        // Arrange
        Client client = clientService.createClient("Customer", "customer");
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner",
            null
        );

        // Act & Assert: Revoke without grant
        assertThatThrownBy(() ->
            clientService.revokeClientAccess(partner.id, client.id)
        )
        .hasMessageContaining("not found");
    }

    // ========================================
    // CLIENT ISOLATION TESTS
    // ========================================

    @Test
    @DisplayName("Users from different clients should not see each other")
    void users_shouldNotSeeEachOther_whenFromDifferentClients() {
        // Arrange: Create 2 separate clients
        Client clientA = clientService.createClient("Client A", "client-a");
        Client clientB = clientService.createClient("Client B", "client-b");

        // Create users in each client
        Principal userA = userService.createInternalUser(
            "user@client-a.com",
            "SecurePass123!",
            "User A",
            clientA.id
        );

        Principal userB = userService.createInternalUser(
            "user@client-b.com",
            "SecurePass123!",
            "User B",
            clientB.id
        );

        // Act
        Set<Long> userAAccess = clientService.getAccessibleClients(userA.id);
        Set<Long> userBAccess = clientService.getAccessibleClients(userB.id);

        // Assert: Complete isolation
        assertThat(userAAccess).containsExactly(clientA.id);
        assertThat(userBAccess).containsExactly(clientB.id);
        assertThat(userAAccess).doesNotContain(clientB.id);
        assertThat(userBAccess).doesNotContain(clientA.id);
    }

    // ========================================
    // COMPLEX SCENARIOS
    // ========================================

    @Test
    @DisplayName("Complex scenario: Anchor user, regular users, and partner users")
    void complexScenario_shouldWorkCorrectly_withMultipleUserTypes() {
        // Arrange: Register anchor domain
        AnchorDomain anchor = new AnchorDomain();
        anchor.id = TsidGenerator.generate();
        anchor.domain = "platform.com";
        anchorDomainRepo.persist(anchor);

        // Create 3 customer clients
        Client customer1 = clientService.createClient("Customer 1", "customer-1");
        Client customer2 = clientService.createClient("Customer 2", "customer-2");
        Client customer3 = clientService.createClient("Customer 3", "customer-3");

        // Create platform admin (anchor domain)
        Principal platformAdmin = userService.createInternalUser(
            "admin@platform.com",
            "SecurePass123!",
            "Platform Admin",
            null
        );

        // Create customer users (home clients)
        Principal customer1User = userService.createInternalUser(
            "user@customer1.com",
            "SecurePass123!",
            "Customer 1 User",
            customer1.id
        );

        Principal customer2User = userService.createInternalUser(
            "user@customer2.com",
            "SecurePass123!",
            "Customer 2 User",
            customer2.id
        );

        // Create partner with grants to customers 1 and 2
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Logistics Partner",
            null
        );

        clientService.grantClientAccess(partner.id, customer1.id);
        clientService.grantClientAccess(partner.id, customer2.id);

        // Act: Get access for all users
        Set<Long> adminAccess = clientService.getAccessibleClients(platformAdmin.id);
        Set<Long> customer1Access = clientService.getAccessibleClients(customer1User.id);
        Set<Long> customer2Access = clientService.getAccessibleClients(customer2User.id);
        Set<Long> partnerAccess = clientService.getAccessibleClients(partner.id);

        // Assert: Platform admin sees all
        assertThat(adminAccess).containsExactlyInAnyOrder(
            customer1.id, customer2.id, customer3.id
        );

        // Assert: Customer users see only their client
        assertThat(customer1Access).containsExactly(customer1.id);
        assertThat(customer2Access).containsExactly(customer2.id);

        // Assert: Partner sees only granted clients
        assertThat(partnerAccess).containsExactlyInAnyOrder(customer1.id, customer2.id);
        assertThat(partnerAccess).doesNotContain(customer3.id);
    }

    @Test
    @DisplayName("Deactivating client should not affect access calculation logic")
    void deactivatingClient_shouldBeFilteredOut_whenCalculatingAccess() {
        // Arrange: Create 2 clients
        Client active = clientService.createClient("Active", "active");
        Client toBeDeactivated = clientService.createClient("Will Deactivate", "deactivate");

        // Create partner with access to both
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner",
            null
        );

        clientService.grantClientAccess(partner.id, active.id);
        clientService.grantClientAccess(partner.id, toBeDeactivated.id);

        // Verify initial access
        Set<Long> beforeDeactivation = clientService.getAccessibleClients(partner.id);
        assertThat(beforeDeactivation).containsExactlyInAnyOrder(active.id, toBeDeactivated.id);

        // Act: Deactivate one client
        clientService.deactivateClient(toBeDeactivated.id, "Test", "system");

        // Assert: Deactivated client not in accessible list
        Set<Long> afterDeactivation = clientService.getAccessibleClients(partner.id);
        assertThat(afterDeactivation).containsExactly(active.id);
        assertThat(afterDeactivation).doesNotContain(toBeDeactivated.id);
    }
}
