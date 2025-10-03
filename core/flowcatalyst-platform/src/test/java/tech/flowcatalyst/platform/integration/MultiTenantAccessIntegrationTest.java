package tech.flowcatalyst.platform.integration;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.flowcatalyst.platform.principal.AnchorDomain;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.tenant.Tenant;
import tech.flowcatalyst.platform.principal.AnchorDomainRepository;
import tech.flowcatalyst.platform.tenant.TenantService;
import tech.flowcatalyst.platform.principal.UserService;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for multi-tenant access control.
 * Tests end-to-end tenant access calculation with real database.
 */
@QuarkusTest
@TestTransaction
class MultiTenantAccessIntegrationTest {

    @Inject
    TenantService tenantService;

    @Inject
    UserService userService;

    @Inject
    AnchorDomainRepository anchorDomainRepo;

    // ========================================
    // ANCHOR DOMAIN USER TESTS
    // ========================================

    @Test
    @DisplayName("Anchor domain user should access all active tenants")
    void anchorDomainUser_shouldAccessAllTenants_whenDomainIsAnchor() {
        // Arrange: Register anchor domain (e.g., internal company domain)
        AnchorDomain anchor = new AnchorDomain();
        anchor.id = TsidGenerator.generate();
        anchor.domain = "mycompany.com";
        anchorDomainRepo.persist(anchor);

        // Create several tenants (customer accounts)
        Tenant tenant1 = tenantService.createTenant("Customer A", "customer-a");
        Tenant tenant2 = tenantService.createTenant("Customer B", "customer-b");
        Tenant tenant3 = tenantService.createTenant("Customer C", "customer-c");

        // Create anchor domain user (internal employee)
        Principal admin = userService.createInternalUser(
            "admin@mycompany.com",
            "SecurePass123!",
            "Platform Admin",
            null  // No home tenant - has access to all
        );

        // Act: Get accessible tenants
        Set<Long> accessible = tenantService.getAccessibleTenants(admin.id);

        // Assert: Can access all active tenants
        assertThat(accessible).containsExactlyInAnyOrder(
            tenant1.id,
            tenant2.id,
            tenant3.id
        );
    }

    @Test
    @DisplayName("Anchor domain user should not see inactive tenants")
    void anchorDomainUser_shouldNotSeeInactive_whenTenantsDeactivated() {
        // Arrange: Register anchor domain
        AnchorDomain anchor = new AnchorDomain();
        anchor.id = TsidGenerator.generate();
        anchor.domain = "mycompany.com";
        anchorDomainRepo.persist(anchor);

        // Create 3 tenants
        Tenant active1 = tenantService.createTenant("Active 1", "active-1");
        Tenant active2 = tenantService.createTenant("Active 2", "active-2");
        Tenant inactive = tenantService.createTenant("Inactive", "inactive");

        // Deactivate one tenant
        tenantService.deactivateTenant(inactive.id, "Test deactivation", "system");

        // Create anchor user
        Principal admin = userService.createInternalUser(
            "admin@mycompany.com",
            "SecurePass123!",
            "Admin",
            null
        );

        // Act
        Set<Long> accessible = tenantService.getAccessibleTenants(admin.id);

        // Assert: Only active tenants visible
        assertThat(accessible).containsExactlyInAnyOrder(active1.id, active2.id);
        assertThat(accessible).doesNotContain(inactive.id);
    }

    // ========================================
    // HOME TENANT USER TESTS
    // ========================================

    @Test
    @DisplayName("Regular user should only access home tenant")
    void regularUser_shouldAccessOnlyHomeTenant_whenNoGrants() {
        // Arrange: Create 2 customer tenants
        Tenant tenant1 = tenantService.createTenant("Customer A", "customer-a");
        Tenant tenant2 = tenantService.createTenant("Customer B", "customer-b");

        // Create user belonging to tenant 1
        Principal user = userService.createInternalUser(
            "user@customer-a.com",
            "SecurePass123!",
            "Customer A User",
            tenant1.id
        );

        // Act
        Set<Long> accessible = tenantService.getAccessibleTenants(user.id);

        // Assert: Only has access to home tenant
        assertThat(accessible).containsExactly(tenant1.id);
        assertThat(accessible).doesNotContain(tenant2.id);
    }

    @Test
    @DisplayName("User with no home tenant and no grants should have no access")
    void user_shouldHaveNoAccess_whenNoHomeTenantAndNoGrants() {
        // Arrange: Create tenant
        Tenant tenant = tenantService.createTenant("Customer A", "customer-a");

        // Create user with NO home tenant (e.g., partner user before grants)
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner User",
            null  // No home tenant
        );

        // Act
        Set<Long> accessible = tenantService.getAccessibleTenants(partner.id);

        // Assert: No access to any tenant
        assertThat(accessible).isEmpty();
    }

    // ========================================
    // TENANT ACCESS GRANT TESTS
    // ========================================

    @Test
    @DisplayName("Partner user should access granted tenants")
    void partnerUser_shouldAccessGrantedTenants_whenGrantsExist() {
        // Arrange: Create 3 customer tenants
        Tenant customerA = tenantService.createTenant("Customer A", "customer-a");
        Tenant customerB = tenantService.createTenant("Customer B", "customer-b");
        Tenant customerC = tenantService.createTenant("Customer C", "customer-c");

        // Create partner user (no home tenant)
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Logistics Partner",
            null
        );

        // Grant access to customers A and B (not C)
        tenantService.grantTenantAccess(partner.id, customerA.id);
        tenantService.grantTenantAccess(partner.id, customerB.id);

        // Act
        Set<Long> accessible = tenantService.getAccessibleTenants(partner.id);

        // Assert: Can access A and B, but not C
        assertThat(accessible).containsExactlyInAnyOrder(customerA.id, customerB.id);
        assertThat(accessible).doesNotContain(customerC.id);
    }

    @Test
    @DisplayName("User with home tenant and grants should access both")
    void user_shouldAccessBoth_whenHasHomeTenantAndGrants() {
        // Arrange: Create 3 tenants
        Tenant homeTenant = tenantService.createTenant("Home Tenant", "home");
        Tenant grantedA = tenantService.createTenant("Granted A", "granted-a");
        Tenant grantedB = tenantService.createTenant("Granted B", "granted-b");

        // Create user with home tenant
        Principal user = userService.createInternalUser(
            "user@example.com",
            "SecurePass123!",
            "User",
            homeTenant.id
        );

        // Grant access to additional tenants
        tenantService.grantTenantAccess(user.id, grantedA.id);
        tenantService.grantTenantAccess(user.id, grantedB.id);

        // Act
        Set<Long> accessible = tenantService.getAccessibleTenants(user.id);

        // Assert: Access to home + 2 granted = 3 total
        assertThat(accessible).containsExactlyInAnyOrder(
            homeTenant.id,
            grantedA.id,
            grantedB.id
        );
    }

    @Test
    @DisplayName("Revoking tenant grant should immediately remove access")
    void revokeTenantGrant_shouldRemoveAccess_whenGrantRevoked() {
        // Arrange: Create tenant and user
        Tenant tenant = tenantService.createTenant("Customer", "customer");
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner",
            null
        );

        // Grant access
        tenantService.grantTenantAccess(partner.id, tenant.id);

        // Verify access granted
        Set<Long> beforeRevoke = tenantService.getAccessibleTenants(partner.id);
        assertThat(beforeRevoke).contains(tenant.id);

        // Act: Revoke access
        tenantService.revokeTenantAccess(partner.id, tenant.id);

        // Assert: Access immediately removed
        Set<Long> afterRevoke = tenantService.getAccessibleTenants(partner.id);
        assertThat(afterRevoke).doesNotContain(tenant.id);
        assertThat(afterRevoke).isEmpty();
    }

    // ========================================
    // GRANT MANAGEMENT TESTS
    // ========================================

    @Test
    @DisplayName("Cannot grant access if user already has home tenant")
    void grantTenantAccess_shouldFail_whenUserAlreadyHasHomeTenant() {
        // Arrange: Create tenant
        Tenant tenant = tenantService.createTenant("Customer", "customer");

        // Create user with this tenant as home
        Principal user = userService.createInternalUser(
            "user@customer.com",
            "SecurePass123!",
            "User",
            tenant.id
        );

        // Act & Assert: Cannot grant same tenant (redundant)
        assertThatThrownBy(() ->
            tenantService.grantTenantAccess(user.id, tenant.id)
        )
        .hasMessageContaining("already belongs to this tenant");
    }

    @Test
    @DisplayName("Cannot grant same tenant access twice")
    void grantTenantAccess_shouldFail_whenGrantAlreadyExists() {
        // Arrange
        Tenant tenant = tenantService.createTenant("Customer", "customer");
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner",
            null
        );

        // First grant
        tenantService.grantTenantAccess(partner.id, tenant.id);

        // Act & Assert: Second grant fails
        assertThatThrownBy(() ->
            tenantService.grantTenantAccess(partner.id, tenant.id)
        )
        .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("Revoking non-existent grant should fail")
    void revokeTenantAccess_shouldFail_whenGrantDoesNotExist() {
        // Arrange
        Tenant tenant = tenantService.createTenant("Customer", "customer");
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner",
            null
        );

        // Act & Assert: Revoke without grant
        assertThatThrownBy(() ->
            tenantService.revokeTenantAccess(partner.id, tenant.id)
        )
        .hasMessageContaining("not found");
    }

    // ========================================
    // TENANT ISOLATION TESTS
    // ========================================

    @Test
    @DisplayName("Users from different tenants should not see each other")
    void users_shouldNotSeeEachOther_whenFromDifferentTenants() {
        // Arrange: Create 2 separate tenants
        Tenant tenantA = tenantService.createTenant("Tenant A", "tenant-a");
        Tenant tenantB = tenantService.createTenant("Tenant B", "tenant-b");

        // Create users in each tenant
        Principal userA = userService.createInternalUser(
            "user@tenant-a.com",
            "SecurePass123!",
            "User A",
            tenantA.id
        );

        Principal userB = userService.createInternalUser(
            "user@tenant-b.com",
            "SecurePass123!",
            "User B",
            tenantB.id
        );

        // Act
        Set<Long> userAAccess = tenantService.getAccessibleTenants(userA.id);
        Set<Long> userBAccess = tenantService.getAccessibleTenants(userB.id);

        // Assert: Complete isolation
        assertThat(userAAccess).containsExactly(tenantA.id);
        assertThat(userBAccess).containsExactly(tenantB.id);
        assertThat(userAAccess).doesNotContain(tenantB.id);
        assertThat(userBAccess).doesNotContain(tenantA.id);
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

        // Create 3 customer tenants
        Tenant customer1 = tenantService.createTenant("Customer 1", "customer-1");
        Tenant customer2 = tenantService.createTenant("Customer 2", "customer-2");
        Tenant customer3 = tenantService.createTenant("Customer 3", "customer-3");

        // Create platform admin (anchor domain)
        Principal platformAdmin = userService.createInternalUser(
            "admin@platform.com",
            "SecurePass123!",
            "Platform Admin",
            null
        );

        // Create customer users (home tenants)
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

        tenantService.grantTenantAccess(partner.id, customer1.id);
        tenantService.grantTenantAccess(partner.id, customer2.id);

        // Act: Get access for all users
        Set<Long> adminAccess = tenantService.getAccessibleTenants(platformAdmin.id);
        Set<Long> customer1Access = tenantService.getAccessibleTenants(customer1User.id);
        Set<Long> customer2Access = tenantService.getAccessibleTenants(customer2User.id);
        Set<Long> partnerAccess = tenantService.getAccessibleTenants(partner.id);

        // Assert: Platform admin sees all
        assertThat(adminAccess).containsExactlyInAnyOrder(
            customer1.id, customer2.id, customer3.id
        );

        // Assert: Customer users see only their tenant
        assertThat(customer1Access).containsExactly(customer1.id);
        assertThat(customer2Access).containsExactly(customer2.id);

        // Assert: Partner sees only granted tenants
        assertThat(partnerAccess).containsExactlyInAnyOrder(customer1.id, customer2.id);
        assertThat(partnerAccess).doesNotContain(customer3.id);
    }

    @Test
    @DisplayName("Deactivating tenant should not affect access calculation logic")
    void deactivatingTenant_shouldBeFilteredOut_whenCalculatingAccess() {
        // Arrange: Create 2 tenants
        Tenant active = tenantService.createTenant("Active", "active");
        Tenant toBeDeactivated = tenantService.createTenant("Will Deactivate", "deactivate");

        // Create partner with access to both
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner",
            null
        );

        tenantService.grantTenantAccess(partner.id, active.id);
        tenantService.grantTenantAccess(partner.id, toBeDeactivated.id);

        // Verify initial access
        Set<Long> beforeDeactivation = tenantService.getAccessibleTenants(partner.id);
        assertThat(beforeDeactivation).containsExactlyInAnyOrder(active.id, toBeDeactivated.id);

        // Act: Deactivate one tenant
        tenantService.deactivateTenant(toBeDeactivated.id, "Test", "system");

        // Assert: Deactivated tenant not in accessible list
        Set<Long> afterDeactivation = tenantService.getAccessibleTenants(partner.id);
        assertThat(afterDeactivation).containsExactly(active.id);
        assertThat(afterDeactivation).doesNotContain(toBeDeactivated.id);
    }
}
