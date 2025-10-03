package tech.flowcatalyst.platform.cli;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import tech.flowcatalyst.platform.authentication.idp.AnchorIdpProperties;
import tech.flowcatalyst.platform.authentication.idp.IdpRoleSyncService;
import tech.flowcatalyst.platform.authentication.idp.IdpSyncAdapter;
import tech.flowcatalyst.platform.authentication.idp.IdpSyncException;
import tech.flowcatalyst.platform.authentication.idp.PlatformIdpProperties;
import tech.flowcatalyst.platform.authorization.PermissionDefinition;
import tech.flowcatalyst.platform.authorization.PermissionRegistry;
import tech.flowcatalyst.platform.authorization.RoleDefinition;

import java.util.Collection;

/**
 * CLI commands for managing IDP synchronization.
 *
 * Usage:
 *   ./gradlew :core:flowcatalyst-platform:quarkusDev -- idp sync
 *   ./gradlew :core:flowcatalyst-platform:quarkusDev -- idp test-connection
 *   ./gradlew :core:flowcatalyst-platform:quarkusDev -- idp list-roles
 *   ./gradlew :core:flowcatalyst-platform:quarkusDev -- idp status
 */
@Command(
    name = "idp",
    mixinStandardHelpOptions = true,
    description = "Manage platform IDP synchronization",
    subcommands = {
        IdpCommand.SyncCommand.class,
        IdpCommand.TestConnectionCommand.class,
        IdpCommand.ListRolesCommand.class,
        IdpCommand.ListPermissionsCommand.class,
        IdpCommand.StatusCommand.class
    }
)
public class IdpCommand implements Runnable {

    @Override
    public void run() {
        // Show help when no subcommand is specified
        CommandLine.usage(this, System.out);
    }

    @Command(name = "sync", description = "Sync all roles to the platform IDP")
    static class SyncCommand implements Runnable {

        @Inject
        IdpRoleSyncService syncService;

        @Override
        public void run() {
            System.out.println("Syncing roles to platform IDP...");

            try {
                syncService.syncNow();
                System.out.println("✓ Sync completed successfully");
            } catch (IdpSyncException e) {
                System.err.println("✗ Sync failed: " + e.getMessage());
                if (e.getCause() != null) {
                    System.err.println("  Cause: " + e.getCause().getMessage());
                }
                System.exit(1);
            }
        }
    }

    @Command(name = "test-connection", description = "Test connection to all IDP sync targets")
    static class TestConnectionCommand implements Runnable {

        @Inject
        IdpRoleSyncService syncService;

        @Override
        public void run() {
            boolean anyConfigured = false;
            boolean allSucceeded = true;

            System.out.println("Testing IDP connections...");
            System.out.println();

            // Test platform IDP
            if (syncService.isPlatformIdpEnabled()) {
                anyConfigured = true;
                System.out.print("Platform IDP: ");
                try {
                    IdpSyncAdapter adapter = syncService.createPlatformAdapter();
                    boolean connected = adapter.testConnection();
                    if (connected) {
                        System.out.println("✓ Connected");
                    } else {
                        System.out.println("✗ Failed");
                        allSucceeded = false;
                    }
                } catch (Exception e) {
                    System.out.println("✗ Error: " + e.getMessage());
                    allSucceeded = false;
                }
            }

            // Test anchor IDP
            if (syncService.isAnchorIdpEnabled()) {
                anyConfigured = true;
                System.out.print("Anchor Tenant IDP: ");
                try {
                    IdpSyncAdapter adapter = syncService.createAnchorAdapter();
                    boolean connected = adapter.testConnection();
                    if (connected) {
                        System.out.println("✓ Connected");
                    } else {
                        System.out.println("✗ Failed");
                        allSucceeded = false;
                    }
                } catch (Exception e) {
                    System.out.println("✗ Error: " + e.getMessage());
                    allSucceeded = false;
                }
            }

            if (!anyConfigured) {
                System.err.println("✗ No IDPs configured");
                System.err.println("Configure IDPs in application.properties:");
                System.err.println("  flowcatalyst.idp.platform.enabled=true");
                System.err.println("  flowcatalyst.idp.anchor.enabled=true");
                System.exit(1);
                return;
            }

            System.out.println();
            if (allSucceeded) {
                System.out.println("✓ All connections successful");
            } else {
                System.err.println("✗ Some connections failed");
                System.exit(1);
            }
        }
    }

    @Command(name = "list-roles", description = "List all roles in the permission registry")
    static class ListRolesCommand implements Runnable {

        @Inject
        PermissionRegistry registry;

        @CommandLine.Option(names = {"-v", "--verbose"}, description = "Show role permissions")
        boolean verbose;

        @Override
        public void run() {
            Collection<RoleDefinition> roles = registry.getAllRoles();

            System.out.println("Roles in registry: " + roles.size());
            System.out.println();

            // Group by subdomain
            roles.stream()
                .sorted((a, b) -> a.toRoleString().compareTo(b.toRoleString()))
                .forEach(role -> {
                    System.out.println("  " + role.toRoleString());
                    System.out.println("    Description: " + role.description());
                    System.out.println("    Permissions: " + role.permissions().size());

                    if (verbose) {
                        role.permissions().forEach(perm ->
                            System.out.println("      - " + perm)
                        );
                    }
                    System.out.println();
                });
        }
    }

    @Command(name = "list-permissions", description = "List all permissions in the permission registry")
    static class ListPermissionsCommand implements Runnable {

        @Inject
        PermissionRegistry registry;

        @CommandLine.Option(names = {"-s", "--subdomain"}, description = "Filter by subdomain")
        String subdomain;

        @Override
        public void run() {
            Collection<PermissionDefinition> permissions = registry.getAllPermissions();

            if (subdomain != null) {
                permissions = permissions.stream()
                    .filter(p -> p.subdomain().equals(subdomain))
                    .toList();
            }

            System.out.println("Permissions in registry: " + permissions.size());
            if (subdomain != null) {
                System.out.println("Filtered by subdomain: " + subdomain);
            }
            System.out.println();

            permissions.stream()
                .sorted((a, b) -> a.toPermissionString().compareTo(b.toPermissionString()))
                .forEach(perm -> {
                    System.out.println("  " + perm.toPermissionString());
                    System.out.println("    " + perm.description());
                    System.out.println();
                });
        }
    }

    @Command(name = "status", description = "Show IDP configuration and registry status")
    static class StatusCommand implements Runnable {

        @Inject
        PermissionRegistry registry;

        @Inject
        IdpRoleSyncService syncService;

        @Inject
        PlatformIdpProperties platformIdpProperties;

        @Inject
        AnchorIdpProperties anchorIdpProperties;

        @Override
        public void run() {
            System.out.println("IDP Sync Configuration");
            System.out.println("======================");
            System.out.println();

            // Platform IDP
            System.out.println("Platform IDP:");
            if (platformIdpProperties.enabled()) {
                System.out.println("  Enabled: Yes");
                System.out.println("  Type: " + platformIdpProperties.type());
                System.out.println("  Name: " + platformIdpProperties.name().orElse("(not set)"));
            } else {
                System.out.println("  Enabled: No");
            }
            System.out.println();

            // Anchor IDP
            System.out.println("Anchor Tenant IDP:");
            if (anchorIdpProperties.enabled()) {
                System.out.println("  Enabled: Yes");
                System.out.println("  Type: " + anchorIdpProperties.type());
                System.out.println("  Name: " + anchorIdpProperties.name().orElse("(not set)"));
            } else {
                System.out.println("  Enabled: No");
            }
            System.out.println();

            if (!platformIdpProperties.enabled() && !anchorIdpProperties.enabled()) {
                System.out.println("⚠ No IDPs configured for sync");
                System.out.println();
                System.out.println("Configure IDPs in application.properties:");
                System.out.println("  flowcatalyst.idp.platform.enabled=true");
                System.out.println("  flowcatalyst.idp.anchor.enabled=true");
                System.out.println();
            }

            System.out.println("Registry Statistics");
            System.out.println("===================");
            System.out.println("Total roles: " + registry.getAllRoles().size());
            System.out.println("Total permissions: " + registry.getAllPermissions().size());
        }
    }
}
