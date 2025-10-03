package tech.flowcatalyst.platform.security;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.tenant.Tenant;
import tech.flowcatalyst.platform.tenant.TenantService;
import tech.flowcatalyst.platform.principal.UserService;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * SECURITY TESTS: Tenant Isolation
 *
 * These tests verify that tenant isolation is properly enforced.
 * Bugs in tenant isolation could lead to:
 * - Cross-tenant data leakage
 * - Unauthorized access to customer data
 * - Compliance violations (GDPR, SOC2, etc.)
 *
 * THREAT MODEL:
 * 1. User attempts to access another tenant's data
 * 2. Partner abuses multi-tenant access grants
 * 3. Tenant access grants not properly revoked
 * 4. Suspended/deactivated tenants still accessible
 */
@QuarkusTest
@TestTransaction
class TenantIsolationSecurityTest {

    @Inject
    TenantService tenantService;

    @Inject
    UserService userService;

    // ========================================
    // BASIC TENANT ISOLATION TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: User from Tenant A cannot access Tenant B")
    void shouldPreventCrossTenantAccess_whenUserBelongsToOneTenant() {
        // THREAT: User attempts to access data from another customer's tenant

        // Arrange: Create 2 separate customer tenants
        Tenant tenantA = tenantService.createTenant("Company A", "company-a");
        Tenant tenantB = tenantService.createTenant("Company B", "company-b");

        // Create user in Tenant A
        Principal userA = userService.createInternalUser(
            "alice@company-a.com",
            "SecurePass123!",
            "Alice from Company A",
            tenantA.id
        );

        // Act: Check accessible tenants
        Set<Long> accessible = tenantService.getAccessibleTenants(userA.id);

        // Assert: Can ONLY access own tenant
        assertThat(accessible).containsExactly(tenantA.id);
        assertThat(accessible).doesNotContain(tenantB.id);
        assertThat(accessible).hasSize(1);
    }

    @Test
    @DisplayName("SECURITY: Multiple users from different tenants are fully isolated")
    void shouldEnforceCompleteIsolation_whenUsersFromDifferentTenants() {
        // THREAT: Cross-contamination between customer tenants

        // Arrange: Create 3 customer tenants
        Tenant tenant1 = tenantService.createTenant("Customer 1", "customer-1");
        Tenant tenant2 = tenantService.createTenant("Customer 2", "customer-2");
        Tenant tenant3 = tenantService.createTenant("Customer 3", "customer-3");

        // Create user in each tenant
        Principal user1 = userService.createInternalUser(
            "user@customer1.com", "Pass123!Pass", "User 1", tenant1.id);
        Principal user2 = userService.createInternalUser(
            "user@customer2.com", "Pass123!Pass", "User 2", tenant2.id);
        Principal user3 = userService.createInternalUser(
            "user@customer3.com", "Pass123!Pass", "User 3", tenant3.id);

        // Act & Assert: Each user sees only their own tenant
        assertThat(tenantService.getAccessibleTenants(user1.id))
            .containsExactly(tenant1.id);
        assertThat(tenantService.getAccessibleTenants(user2.id))
            .containsExactly(tenant2.id);
        assertThat(tenantService.getAccessibleTenants(user3.id))
            .containsExactly(tenant3.id);

        // Verify no cross-tenant visibility
        Set<Long> user1Access = tenantService.getAccessibleTenants(user1.id);
        assertThat(user1Access).doesNotContain(tenant2.id, tenant3.id);
    }

    // ========================================
    // TENANT ACCESS GRANT SECURITY TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: Revoking tenant grant immediately removes access")
    void shouldImmediatelyRevokeAccess_whenTenantGrantDeleted() {
        // THREAT: Revoked partner still has access to customer data

        // Arrange: Create tenant and partner
        Tenant customerTenant = tenantService.createTenant("Customer", "customer");
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Logistics Partner",
            null
        );

        // Grant access
        tenantService.grantTenantAccess(partner.id, customerTenant.id);

        // Verify access granted
        assertThat(tenantService.getAccessibleTenants(partner.id))
            .contains(customerTenant.id);

        // Act: Revoke access
        tenantService.revokeTenantAccess(partner.id, customerTenant.id);

        // Assert: Access IMMEDIATELY removed (no grace period)
        Set<Long> accessAfterRevoke = tenantService.getAccessibleTenants(partner.id);
        assertThat(accessAfterRevoke).doesNotContain(customerTenant.id);
        assertThat(accessAfterRevoke).isEmpty();
    }

    @Test
    @DisplayName("SECURITY: Cannot grant duplicate tenant access")
    void shouldPreventDuplicateGrants_whenGrantAlreadyExists() {
        // THREAT: Duplicate grants could bypass security controls

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

        // Act & Assert: Second grant should fail
        assertThatThrownBy(() ->
            tenantService.grantTenantAccess(partner.id, tenant.id)
        )
        .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("SECURITY: Cannot grant access to user's home tenant")
    void shouldPreventRedundantGrant_whenUserAlreadyHasHomeTenant() {
        // THREAT: Redundant grants could confuse access control logic

        // Arrange
        Tenant tenant = tenantService.createTenant("Customer", "customer");
        Principal user = userService.createInternalUser(
            "user@customer.com",
            "SecurePass123!",
            "User",
            tenant.id  // This is their home tenant
        );

        // Act & Assert: Cannot grant same tenant
        assertThatThrownBy(() ->
            tenantService.grantTenantAccess(user.id, tenant.id)
        )
        .hasMessageContaining("already belongs to this tenant");
    }

    // ========================================
    // SUSPENDED/DEACTIVATED TENANT TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: Deactivated tenant not accessible")
    void shouldPreventAccess_whenTenantDeactivated() {
        // THREAT: Deactivated customer tenant still accessible (e.g., non-payment)

        // Arrange: Create tenant and user
        Tenant tenant = tenantService.createTenant("Customer", "customer");
        Principal user = userService.createInternalUser(
            "user@customer.com",
            "SecurePass123!",
            "User",
            tenant.id
        );

        // Verify access before deactivation
        assertThat(tenantService.getAccessibleTenants(user.id))
            .contains(tenant.id);

        // Act: Deactivate tenant (e.g., account suspended)
        tenantService.deactivateTenant(tenant.id, "NON_PAYMENT", "billing-system");

        // Assert: Tenant not in accessible list
        Set<Long> accessible = tenantService.getAccessibleTenants(user.id);
        assertThat(accessible).doesNotContain(tenant.id);
    }

    @Test
    @DisplayName("SECURITY: Suspended tenant not accessible")
    void shouldPreventAccess_whenTenantSuspended() {
        // THREAT: Suspended tenant still accessible during suspension period

        // Arrange
        Tenant tenant = tenantService.createTenant("Customer", "customer");
        Principal user = userService.createInternalUser(
            "user@customer.com",
            "SecurePass123!",
            "User",
            tenant.id
        );

        // Verify access before suspension
        assertThat(tenantService.getAccessibleTenants(user.id))
            .contains(tenant.id);

        // Act: Suspend tenant
        tenantService.suspendTenant(tenant.id, "PAYMENT_FAILED", "billing-system");

        // Assert: Tenant not accessible during suspension
        Set<Long> accessible = tenantService.getAccessibleTenants(user.id);
        assertThat(accessible).doesNotContain(tenant.id);
    }

    @Test
    @DisplayName("SECURITY: Reactivating tenant restores access")
    void shouldRestoreAccess_whenTenantReactivated() {
        // SCENARIO: Tenant pays overdue invoice and is reactivated

        // Arrange: Create and suspend tenant
        Tenant tenant = tenantService.createTenant("Customer", "customer");
        Principal user = userService.createInternalUser(
            "user@customer.com",
            "SecurePass123!",
            "User",
            tenant.id
        );

        tenantService.suspendTenant(tenant.id, "PAYMENT_FAILED", "system");

        // Verify no access during suspension
        assertThat(tenantService.getAccessibleTenants(user.id))
            .doesNotContain(tenant.id);

        // Act: Reactivate tenant (payment received)
        tenantService.activateTenant(tenant.id, "billing-system");

        // Assert: Access restored
        Set<Long> accessible = tenantService.getAccessibleTenants(user.id);
        assertThat(accessible).contains(tenant.id);
    }

    // ========================================
    // PARTNER MULTI-TENANT ACCESS TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: Partner can only access explicitly granted tenants")
    void shouldEnforceExplicitGrants_whenPartnerHasMultiTenantAccess() {
        // THREAT: Partner abuses access to view tenants not granted to them

        // Arrange: Create 5 customer tenants
        Tenant t1 = tenantService.createTenant("Customer 1", "customer-1");
        Tenant t2 = tenantService.createTenant("Customer 2", "customer-2");
        Tenant t3 = tenantService.createTenant("Customer 3", "customer-3");
        Tenant t4 = tenantService.createTenant("Customer 4", "customer-4");
        Tenant t5 = tenantService.createTenant("Customer 5", "customer-5");

        // Create partner with access to ONLY t1, t2, and t3
        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Logistics Partner",
            null
        );

        tenantService.grantTenantAccess(partner.id, t1.id);
        tenantService.grantTenantAccess(partner.id, t2.id);
        tenantService.grantTenantAccess(partner.id, t3.id);

        // Act
        Set<Long> accessible = tenantService.getAccessibleTenants(partner.id);

        // Assert: Can ONLY access granted tenants
        assertThat(accessible).containsExactlyInAnyOrder(t1.id, t2.id, t3.id);
        assertThat(accessible).doesNotContain(t4.id, t5.id);
        assertThat(accessible).hasSize(3);
    }

    @Test
    @DisplayName("SECURITY: Partner with zero grants has zero access")
    void shouldHaveNoAccess_whenPartnerHasNoGrants() {
        // SCENARIO: New partner registered but not yet granted access

        // Arrange: Create tenants
        Tenant t1 = tenantService.createTenant("Customer 1", "customer-1");
        Tenant t2 = tenantService.createTenant("Customer 2", "customer-2");

        // Create partner with NO grants
        Principal partner = userService.createInternalUser(
            "newpartner@logistics.com",
            "SecurePass123!",
            "New Partner",
            null  // No home tenant
        );

        // Act
        Set<Long> accessible = tenantService.getAccessibleTenants(partner.id);

        // Assert: Zero access
        assertThat(accessible).isEmpty();
    }

    @Test
    @DisplayName("SECURITY: Revoking one grant does not affect other grants")
    void shouldPreserveOtherGrants_whenOneGrantRevoked() {
        // THREAT: Revoking one grant accidentally revokes all grants

        // Arrange: Partner with access to 3 tenants
        Tenant t1 = tenantService.createTenant("Customer 1", "customer-1");
        Tenant t2 = tenantService.createTenant("Customer 2", "customer-2");
        Tenant t3 = tenantService.createTenant("Customer 3", "customer-3");

        Principal partner = userService.createInternalUser(
            "partner@logistics.com",
            "SecurePass123!",
            "Partner",
            null
        );

        tenantService.grantTenantAccess(partner.id, t1.id);
        tenantService.grantTenantAccess(partner.id, t2.id);
        tenantService.grantTenantAccess(partner.id, t3.id);

        // Verify initial access
        assertThat(tenantService.getAccessibleTenants(partner.id))
            .containsExactlyInAnyOrder(t1.id, t2.id, t3.id);

        // Act: Revoke access to t2 only
        tenantService.revokeTenantAccess(partner.id, t2.id);

        // Assert: Still has access to t1 and t3
        Set<Long> accessible = tenantService.getAccessibleTenants(partner.id);
        assertThat(accessible).containsExactlyInAnyOrder(t1.id, t3.id);
        assertThat(accessible).doesNotContain(t2.id);
    }

    // ========================================
    // DATA LEAKAGE PREVENTION TESTS
    // ========================================

    @Test
    @DisplayName("SECURITY: User from Tenant A cannot see users from Tenant B")
    void shouldPreventUserListLeakage_betweenTenants() {
        // THREAT: Tenant isolation bypass via user enumeration

        // Arrange: Create 2 tenants with users
        Tenant tenantA = tenantService.createTenant("Company A", "company-a");
        Tenant tenantB = tenantService.createTenant("Company B", "company-b");

        Principal userA1 = userService.createInternalUser(
            "alice@company-a.com", "Pass123!Pass", "Alice", tenantA.id);
        Principal userA2 = userService.createInternalUser(
            "bob@company-a.com", "Pass123!Pass", "Bob", tenantA.id);

        Principal userB1 = userService.createInternalUser(
            "charlie@company-b.com", "Pass123!Pass", "Charlie", tenantB.id);

        // Act: Get users for each tenant
        var tenantAUsers = userService.findByTenant(tenantA.id);
        var tenantBUsers = userService.findByTenant(tenantB.id);

        // Assert: Complete isolation
        assertThat(tenantAUsers)
            .extracting(p -> p.id)
            .containsExactlyInAnyOrder(userA1.id, userA2.id)
            .doesNotContain(userB1.id);

        assertThat(tenantBUsers)
            .extracting(p -> p.id)
            .containsExactly(userB1.id)
            .doesNotContain(userA1.id, userA2.id);
    }

    // ========================================
    // EDGE CASES
    // ========================================

    @Test
    @DisplayName("SECURITY: Deactivating and reactivating tenant multiple times")
    void shouldHandleMultipleStatusChanges_correctly() {
        // SCENARIO: Tenant goes through multiple status changes

        // Arrange
        Tenant tenant = tenantService.createTenant("Customer", "customer");
        Principal user = userService.createInternalUser(
            "user@customer.com", "Pass123!Pass", "User", tenant.id);

        // Cycle 1: Active → Suspended → Active
        assertThat(tenantService.getAccessibleTenants(user.id)).contains(tenant.id);

        tenantService.suspendTenant(tenant.id, "PAYMENT_FAILED", "system");
        assertThat(tenantService.getAccessibleTenants(user.id)).doesNotContain(tenant.id);

        tenantService.activateTenant(tenant.id, "system");
        assertThat(tenantService.getAccessibleTenants(user.id)).contains(tenant.id);

        // Cycle 2: Active → Deactivated → Active
        tenantService.deactivateTenant(tenant.id, "TEST", "system");
        assertThat(tenantService.getAccessibleTenants(user.id)).doesNotContain(tenant.id);

        tenantService.activateTenant(tenant.id, "system");
        assertThat(tenantService.getAccessibleTenants(user.id)).contains(tenant.id);
    }

    @Test
    @DisplayName("SECURITY: User with deactivated home tenant has no access")
    void shouldHaveNoAccess_whenHomeTenantDeactivated() {
        // SCENARIO: User's home tenant is deactivated (e.g., company went out of business)

        // Arrange: User has home tenant + grant to another tenant
        Tenant homeTenant = tenantService.createTenant("Home", "home");
        Tenant grantedTenant = tenantService.createTenant("Granted", "granted");

        Principal user = userService.createInternalUser(
            "user@home.com",
            "Pass123!Pass",
            "User",
            homeTenant.id
        );

        tenantService.grantTenantAccess(user.id, grantedTenant.id);

        // Verify initial access to both
        assertThat(tenantService.getAccessibleTenants(user.id))
            .containsExactlyInAnyOrder(homeTenant.id, grantedTenant.id);

        // Act: Deactivate home tenant
        tenantService.deactivateTenant(homeTenant.id, "BUSINESS_CLOSED", "system");

        // Assert: Can still access granted tenant, but not home tenant
        Set<Long> accessible = tenantService.getAccessibleTenants(user.id);
        assertThat(accessible).containsExactly(grantedTenant.id);
        assertThat(accessible).doesNotContain(homeTenant.id);
    }
}
