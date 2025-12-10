package tech.flowcatalyst.platform.security;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.client.Client;
import tech.flowcatalyst.platform.client.ClientService;
import tech.flowcatalyst.platform.principal.UserService;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * SECURITY TESTS: Client Isolation
 *
 * These tests verify that client isolation is properly enforced.
 * Bugs in client isolation could lead to:
 * - Cross-client data leakage
 * - Unauthorized access to customer data
 * - Compliance violations (GDPR, SOC2, etc.)
 *
 * THREAT MODEL:
 * 1. User attempts to access another client's data
 * 2. Partner abuses multi-client access grants
 * 3. Client access grants not properly revoked
 * 4. Suspended/deactivated clients still accessible
 */
@QuarkusTest
@TestTransaction
class ClientIsolationSecurityTest {

    @Inject
    ClientService clientService;

    @Inject
    UserService userService;

    // ========================================
    // BASIC CLIENT ISOLATION TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: User from Client A cannot access Client B")
    void shouldPreventCrossClientAccess_whenUserBelongsToOneClient() {
        // THREAT: User attempts to access data from another customer's client

        // Arrange: Create 2 separate customer clients
        Client clientA = clientService.createClient("Company A", "company-a");
        Client clientB = clientService.createClient("Company B", "company-b");

        // Create user in Client A
        Principal userA = userService.createInternalUser(
            "alice@company-a.com",
            "SecurePass123!",
            "Alice from Company A",
            clientA.id
        );

        // Act: Check accessible clients
        Set<String> accessible = clientService.getAccessibleClients(userA.id);

        // Assert: Can ONLY access own client
        assertThat(accessible).containsExactly(clientA.id);
        assertThat(accessible).doesNotContain(clientB.id);
        assertThat(accessible).hasSize(1);
    }

    @Test
    @DisplayName("SECURITY: Multiple users from different clients are fully isolated")
    void shouldEnforceCompleteIsolation_whenUsersFromDifferentClients() {
        // THREAT: Cross-contamination between customer clients

        // Arrange: Create 3 customer clients
        Client client1 = clientService.createClient("Customer 1", "customer-1");
        Client client2 = clientService.createClient("Customer 2", "customer-2");
        Client client3 = clientService.createClient("Customer 3", "customer-3");

        // Create user in each client
        Principal user1 = userService.createInternalUser(
            "user@customer1.com", "Pass123!Pass", "User 1", client1.id);
        Principal user2 = userService.createInternalUser(
            "user@customer2.com", "Pass123!Pass", "User 2", client2.id);
        Principal user3 = userService.createInternalUser(
            "user@customer3.com", "Pass123!Pass", "User 3", client3.id);

        // Act & Assert: Each user sees only their own client
        assertThat(clientService.getAccessibleClients(user1.id))
            .containsExactly(client1.id);
        assertThat(clientService.getAccessibleClients(user2.id))
            .containsExactly(client2.id);
        assertThat(clientService.getAccessibleClients(user3.id))
            .containsExactly(client3.id);

        // Verify no cross-client visibility
        Set<String> user1Access = clientService.getAccessibleClients(user1.id);
        assertThat(user1Access).doesNotContain(client2.id, client3.id);
    }

    // ========================================
    // CLIENT ACCESS GRANT SECURITY TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: Revoking client grant immediately removes access")
    void shouldImmediatelyRevokeAccess_whenClientGrantDeleted() {
        // THREAT: Revoked partner still has access to customer data

        // Arrange: Create client and partner
        Client customerClient = clientService.createClient("Customer", "customer");
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Logistics Partner",
            null
        );

        // Grant access
        clientService.grantClientAccess(partner.id, customerClient.id);

        // Verify access granted
        assertThat(clientService.getAccessibleClients(partner.id))
            .contains(customerClient.id);

        // Act: Revoke access
        clientService.revokeClientAccess(partner.id, customerClient.id);

        // Assert: Access IMMEDIATELY removed (no grace period)
        Set<String> accessAfterRevoke = clientService.getAccessibleClients(partner.id);
        assertThat(accessAfterRevoke).doesNotContain(customerClient.id);
        assertThat(accessAfterRevoke).isEmpty();
    }

    @Test
    @DisplayName("SECURITY: Cannot grant duplicate client access")
    void shouldPreventDuplicateGrants_whenGrantAlreadyExists() {
        // THREAT: Duplicate grants could bypass security controls

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

        // Act & Assert: Second grant should fail
        assertThatThrownBy(() ->
            clientService.grantClientAccess(partner.id, client.id)
        )
        .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("SECURITY: Cannot grant access to user's home client")
    void shouldPreventRedundantGrant_whenUserAlreadyHasHomeClient() {
        // THREAT: Redundant grants could confuse access control logic

        // Arrange
        Client client = clientService.createClient("Customer", "customer");
        Principal user = userService.createInternalUser(
            "user@customer.com",
            "SecurePass123!",
            "User",
            client.id  // This is their home client
        );

        // Act & Assert: Cannot grant same client
        assertThatThrownBy(() ->
            clientService.grantClientAccess(user.id, client.id)
        )
        .hasMessageContaining("already belongs to this client");
    }

    // ========================================
    // SUSPENDED/DEACTIVATED CLIENT TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: Deactivated client not accessible")
    void shouldPreventAccess_whenClientDeactivated() {
        // THREAT: Deactivated customer client still accessible (e.g., non-payment)

        // Arrange: Create client and user
        Client client = clientService.createClient("Customer", "customer");
        Principal user = userService.createInternalUser(
            "user@customer.com",
            "SecurePass123!",
            "User",
            client.id
        );

        // Verify access before deactivation
        assertThat(clientService.getAccessibleClients(user.id))
            .contains(client.id);

        // Act: Deactivate client (e.g., account suspended)
        clientService.deactivateClient(client.id, "NON_PAYMENT", "billing-system");

        // Assert: Client not in accessible list
        Set<String> accessible = clientService.getAccessibleClients(user.id);
        assertThat(accessible).doesNotContain(client.id);
    }

    @Test
    @DisplayName("SECURITY: Suspended client not accessible")
    void shouldPreventAccess_whenClientSuspended() {
        // THREAT: Suspended client still accessible during suspension period

        // Arrange
        Client client = clientService.createClient("Customer", "customer");
        Principal user = userService.createInternalUser(
            "user@customer.com",
            "SecurePass123!",
            "User",
            client.id
        );

        // Verify access before suspension
        assertThat(clientService.getAccessibleClients(user.id))
            .contains(client.id);

        // Act: Suspend client
        clientService.suspendClient(client.id, "PAYMENT_FAILED", "billing-system");

        // Assert: Client not accessible during suspension
        Set<String> accessible = clientService.getAccessibleClients(user.id);
        assertThat(accessible).doesNotContain(client.id);
    }

    @Test
    @DisplayName("SECURITY: Reactivating client restores access")
    void shouldRestoreAccess_whenClientReactivated() {
        // SCENARIO: Client pays overdue invoice and is reactivated

        // Arrange: Create and suspend client
        Client client = clientService.createClient("Customer", "customer");
        Principal user = userService.createInternalUser(
            "user@customer.com",
            "SecurePass123!",
            "User",
            client.id
        );

        clientService.suspendClient(client.id, "PAYMENT_FAILED", "system");

        // Verify no access during suspension
        assertThat(clientService.getAccessibleClients(user.id))
            .doesNotContain(client.id);

        // Act: Reactivate client (payment received)
        clientService.activateClient(client.id, "billing-system");

        // Assert: Access restored
        Set<String> accessible = clientService.getAccessibleClients(user.id);
        assertThat(accessible).contains(client.id);
    }

    // ========================================
    // PARTNER MULTI-CLIENT ACCESS TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: Partner can only access explicitly granted clients")
    void shouldEnforceExplicitGrants_whenPartnerHasMultiClientAccess() {
        // THREAT: Partner abuses access to view clients not granted to them

        // Arrange: Create 5 customer clients
        Client c1 = clientService.createClient("Customer 1", "customer-1");
        Client c2 = clientService.createClient("Customer 2", "customer-2");
        Client c3 = clientService.createClient("Customer 3", "customer-3");
        Client c4 = clientService.createClient("Customer 4", "customer-4");
        Client c5 = clientService.createClient("Customer 5", "customer-5");

        // Create partner with access to ONLY c1, c2, and c3
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Logistics Partner",
            null
        );

        clientService.grantClientAccess(partner.id, c1.id);
        clientService.grantClientAccess(partner.id, c2.id);
        clientService.grantClientAccess(partner.id, c3.id);

        // Act
        Set<String> accessible = clientService.getAccessibleClients(partner.id);

        // Assert: Can ONLY access granted clients
        assertThat(accessible).containsExactlyInAnyOrder(c1.id, c2.id, c3.id);
        assertThat(accessible).doesNotContain(c4.id, c5.id);
        assertThat(accessible).hasSize(3);
    }

    @Test
    @DisplayName("SECURITY: Partner with zero grants has zero access")
    void shouldHaveNoAccess_whenPartnerHasNoGrants() {
        // SCENARIO: New partner registered but not yet granted access

        // Arrange: Create clients
        Client c1 = clientService.createClient("Customer 1", "customer-1");
        Client c2 = clientService.createClient("Customer 2", "customer-2");

        // Create partner with NO grants
        Principal partner = userService.createInternalUser(
            "newpartner@logistics.com",
            "SecurePass123!",
            "New Partner",
            null  // No home client
        );

        // Act
        Set<String> accessible = clientService.getAccessibleClients(partner.id);

        // Assert: Zero access
        assertThat(accessible).isEmpty();
    }

    @Test
    @DisplayName("SECURITY: Revoking one grant does not affect other grants")
    void shouldPreserveOtherGrants_whenOneGrantRevoked() {
        // THREAT: Revoking one grant accidentally revokes all grants

        // Arrange: Partner with access to 3 clients
        Client c1 = clientService.createClient("Customer 1", "customer-1");
        Client c2 = clientService.createClient("Customer 2", "customer-2");
        Client c3 = clientService.createClient("Customer 3", "customer-3");

        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner",
            null
        );

        clientService.grantClientAccess(partner.id, c1.id);
        clientService.grantClientAccess(partner.id, c2.id);
        clientService.grantClientAccess(partner.id, c3.id);

        // Verify initial access
        assertThat(clientService.getAccessibleClients(partner.id))
            .containsExactlyInAnyOrder(c1.id, c2.id, c3.id);

        // Act: Revoke access to c2 only
        clientService.revokeClientAccess(partner.id, c2.id);

        // Assert: Still has access to c1 and c3
        Set<String> accessible = clientService.getAccessibleClients(partner.id);
        assertThat(accessible).containsExactlyInAnyOrder(c1.id, c3.id);
        assertThat(accessible).doesNotContain(c2.id);
    }

    // ========================================
    // DATA LEAKAGE PREVENTION TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: User from Client A cannot see users from Client B")
    void shouldPreventUserListLeakage_betweenClients() {
        // THREAT: Client isolation bypass via user enumeration

        // Arrange: Create 2 clients with users
        Client clientA = clientService.createClient("Company A", "company-a");
        Client clientB = clientService.createClient("Company B", "company-b");

        Principal userA1 = userService.createInternalUser(
            "alice@company-a.com", "Pass123!Pass", "Alice", clientA.id);
        Principal userA2 = userService.createInternalUser(
            "bob@company-a.com", "Pass123!Pass", "Bob", clientA.id);

        Principal userB1 = userService.createInternalUser(
            "charlie@company-b.com", "Pass123!Pass", "Charlie", clientB.id);

        // Act: Get users for each client
        var clientAUsers = userService.findByClient(clientA.id);
        var clientBUsers = userService.findByClient(clientB.id);

        // Assert: Complete isolation
        assertThat(clientAUsers)
            .extracting(p -> p.id)
            .containsExactlyInAnyOrder(userA1.id, userA2.id)
            .doesNotContain(userB1.id);

        assertThat(clientBUsers)
            .extracting(p -> p.id)
            .containsExactly(userB1.id)
            .doesNotContain(userA1.id, userA2.id);
    }

    // ========================================
    // EDGE CASES
    // ========================================

    @Test
    @DisplayName("SECURITY: Deactivating and reactivating client multiple times")
    void shouldHandleMultipleStatusChanges_correctly() {
        // SCENARIO: Client goes through multiple status changes

        // Arrange
        Client client = clientService.createClient("Customer", "customer");
        Principal user = userService.createInternalUser(
            "user@customer.com", "Pass123!Pass", "User", client.id);

        // Cycle 1: Active → Suspended → Active
        assertThat(clientService.getAccessibleClients(user.id)).contains(client.id);

        clientService.suspendClient(client.id, "PAYMENT_FAILED", "system");
        assertThat(clientService.getAccessibleClients(user.id)).doesNotContain(client.id);

        clientService.activateClient(client.id, "system");
        assertThat(clientService.getAccessibleClients(user.id)).contains(client.id);

        // Cycle 2: Active → Deactivated → Active
        clientService.deactivateClient(client.id, "TEST", "system");
        assertThat(clientService.getAccessibleClients(user.id)).doesNotContain(client.id);

        clientService.activateClient(client.id, "system");
        assertThat(clientService.getAccessibleClients(user.id)).contains(client.id);
    }

    @Test
    @DisplayName("SECURITY: User with deactivated home client has no access")
    void shouldHaveNoAccess_whenHomeClientDeactivated() {
        // SCENARIO: User's home client is deactivated (e.g., company went out of business)

        // Arrange: User has home client + grant to another client
        Client homeClient = clientService.createClient("Home", "home");
        Client grantedClient = clientService.createClient("Granted", "granted");

        Principal user = userService.createInternalUser(
            "user@home.com",
            "Pass123!Pass",
            "User",
            homeClient.id
        );

        clientService.grantClientAccess(user.id, grantedClient.id);

        // Verify initial access to both
        assertThat(clientService.getAccessibleClients(user.id))
            .containsExactlyInAnyOrder(homeClient.id, grantedClient.id);

        // Act: Deactivate home client
        clientService.deactivateClient(homeClient.id, "BUSINESS_CLOSED", "system");

        // Assert: Can still access granted client, but not home client
        Set<String> accessible = clientService.getAccessibleClients(user.id);
        assertThat(accessible).containsExactly(grantedClient.id);
        assertThat(accessible).doesNotContain(homeClient.id);
    }
}
