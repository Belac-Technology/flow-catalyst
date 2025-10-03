# Identity Provider Permission/Role Sync

## Overview

FlowCatalyst's code-defined permissions and roles can be synchronized to external Identity Providers (IdPs) like Keycloak and Azure AD (Entra ID). This enables:

1. **Centralized IAM**: Manage user role assignments in your corporate IdP
2. **SSO Integration**: Users authenticate via IdP, roles flow through OIDC tokens
3. **External Management**: Security teams can manage role assignments without touching your database
4. **Compliance**: Leverage IdP's audit trails and governance features

## Sync Strategy

### One-Way Sync (Code → IdP)
- Code remains the **source of truth** for role/permission definitions
- On application startup, sync discovered roles/permissions to IdP
- IdP is used for **assignment** (which users have which roles)
- Prevents drift between code and IdP

### Sync Timing
1. **Application Startup**: Initial sync on boot
2. **Admin Endpoint**: Manual trigger via API endpoint
3. **CI/CD Pipeline**: Sync during deployment
4. **Scheduled**: Optional periodic sync to detect drift

---

## Keycloak Integration

Keycloak is highly flexible and supports fine-grained role/permission models.

### Architecture

**Option 1: Client Roles (Recommended)**
- Each FlowCatalyst module becomes a Keycloak client
- Roles are created as client roles (e.g., `dispatch-manager`, `billing-viewer`)
- Permissions are stored as role attributes
- Use composite roles to bundle permissions

**Option 2: Realm Roles**
- Create realm-level roles for cross-application permissions
- Better for platform-wide roles like `platform-admin`

**Option 3: Fine-Grained Authorization (Advanced)**
- Use Keycloak's Authorization Services
- Map permissions to Keycloak resources/scopes
- Full RBAC/ABAC support

### Implementation

#### 1. Dependencies

Add to `build.gradle.kts`:

```kotlin
dependencies {
    // Keycloak Admin Client
    implementation("org.keycloak:keycloak-admin-client:23.0.0")
}
```

#### 2. Keycloak Sync Service

```java
package tech.flowcatalyst.platform.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.RoleRepresentation;
import tech.flowcatalyst.platform.security.PermissionDefinition;
import tech.flowcatalyst.platform.security.RoleDefinition;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class KeycloakSyncService {

    private static final Logger LOG = Logger.getLogger(KeycloakSyncService.class);

    @Inject
    SecurityMetadataRegistry metadataRegistry;

    @ConfigProperty(name = "keycloak.admin.url")
    String keycloakUrl;

    @ConfigProperty(name = "keycloak.admin.realm")
    String adminRealm;

    @ConfigProperty(name = "keycloak.admin.client-id")
    String adminClientId;

    @ConfigProperty(name = "keycloak.admin.client-secret")
    String adminClientSecret;

    @ConfigProperty(name = "keycloak.target.realm")
    String targetRealm;

    @ConfigProperty(name = "keycloak.target.client-id")
    String targetClientId;

    @ConfigProperty(name = "keycloak.sync.enabled", defaultValue = "false")
    boolean syncEnabled;

    /**
     * Sync all discovered roles and permissions to Keycloak
     */
    public void syncToKeycloak() {
        if (!syncEnabled) {
            LOG.info("Keycloak sync is disabled");
            return;
        }

        LOG.info("Starting Keycloak sync...");

        try (Keycloak keycloak = createAdminClient()) {
            RealmResource realm = keycloak.realm(targetRealm);
            ClientResource client = getClientResource(realm, targetClientId);

            syncRoles(client);
            syncPermissionsAsAttributes(client);

            LOG.info("Keycloak sync completed successfully");

        } catch (Exception e) {
            LOG.error("Keycloak sync failed", e);
            throw new RuntimeException("Failed to sync to Keycloak", e);
        }
    }

    /**
     * Sync roles to Keycloak as client roles
     */
    private void syncRoles(ClientResource client) {
        RolesResource rolesResource = client.roles();

        Collection<RoleDefinition> roles = metadataRegistry.getAllRoles();
        LOG.infof("Syncing %d roles to Keycloak", roles.size());

        for (RoleDefinition role : roles) {
            try {
                RoleRepresentation kcRole = findOrCreateRole(rolesResource, role);

                // Update role metadata
                kcRole.setDescription(role.getDescription());

                // Store permissions as role attributes
                Map<String, List<String>> attributes = new HashMap<>();
                attributes.put("flowcatalyst.code", List.of(role.getCode()));
                attributes.put("flowcatalyst.module", List.of(role.getModule()));
                attributes.put("flowcatalyst.permissions",
                    new ArrayList<>(role.getPermissionCodes()));
                attributes.put("flowcatalyst.displayName", List.of(role.getDisplayName()));

                kcRole.setAttributes(attributes);

                // Update in Keycloak
                rolesResource.get(kcRole.getName()).update(kcRole);

                LOG.debugf("Synced role: %s", role.getCode());

            } catch (Exception e) {
                LOG.errorf(e, "Failed to sync role: %s", role.getCode());
            }
        }
    }

    /**
     * Find existing role or create new one
     */
    private RoleRepresentation findOrCreateRole(RolesResource rolesResource, RoleDefinition role) {
        String roleName = role.getCode(); // Use code as Keycloak role name

        try {
            return rolesResource.get(roleName).toRepresentation();
        } catch (Exception e) {
            // Role doesn't exist, create it
            RoleRepresentation newRole = new RoleRepresentation();
            newRole.setName(roleName);
            newRole.setDescription(role.getDescription());
            newRole.setClientRole(true);

            rolesResource.create(newRole);

            LOG.infof("Created new Keycloak role: %s", roleName);

            return rolesResource.get(roleName).toRepresentation();
        }
    }

    /**
     * Sync permissions as role attributes (for reference/auditing)
     */
    private void syncPermissionsAsAttributes(ClientResource client) {
        Collection<PermissionDefinition> permissions = metadataRegistry.getAllPermissions();
        LOG.infof("Recording %d permissions in Keycloak attributes", permissions.size());

        // Create a metadata role to store all permission definitions
        RolesResource rolesResource = client.roles();
        RoleRepresentation metadataRole = findOrCreateMetadataRole(rolesResource);

        Map<String, List<String>> permissionData = new HashMap<>();

        for (PermissionDefinition perm : permissions) {
            permissionData.put(
                "perm." + perm.getCode(),
                List.of(perm.getDisplayName(), perm.getDescription())
            );
        }

        metadataRole.setAttributes(permissionData);
        rolesResource.get(metadataRole.getName()).update(metadataRole);
    }

    /**
     * Find or create a special role to hold permission metadata
     */
    private RoleRepresentation findOrCreateMetadataRole(RolesResource rolesResource) {
        String metadataRoleName = "_flowcatalyst_permissions_metadata";

        try {
            return rolesResource.get(metadataRoleName).toRepresentation();
        } catch (Exception e) {
            RoleRepresentation role = new RoleRepresentation();
            role.setName(metadataRoleName);
            role.setDescription("FlowCatalyst permission metadata (do not assign to users)");
            role.setClientRole(true);

            rolesResource.create(role);
            return rolesResource.get(metadataRoleName).toRepresentation();
        }
    }

    /**
     * Create Keycloak admin client
     */
    private Keycloak createAdminClient() {
        return KeycloakBuilder.builder()
            .serverUrl(keycloakUrl)
            .realm(adminRealm)
            .clientId(adminClientId)
            .clientSecret(adminClientSecret)
            .grantType("client_credentials")
            .build();
    }

    /**
     * Get client resource by client ID
     */
    private ClientResource getClientResource(RealmResource realm, String clientId) {
        return realm.clients().findByClientId(clientId).stream()
            .findFirst()
            .map(client -> realm.clients().get(client.getId()))
            .orElseThrow(() -> new RuntimeException("Client not found: " + clientId));
    }

    /**
     * Sync user roles from Keycloak to local database
     * Called during OIDC authentication
     */
    public void syncUserRolesFromKeycloak(Long principalId, Collection<String> keycloakRoles) {
        // Filter to only FlowCatalyst roles (by module prefix)
        Set<String> validRoles = keycloakRoles.stream()
            .filter(role -> metadataRegistry.getRole(role) != null)
            .collect(Collectors.toSet());

        // Sync to database
        syncUserRolesToDatabase(principalId, validRoles);
    }

    private void syncUserRolesToDatabase(Long principalId, Set<String> roleCodes) {
        // Implementation to update principal_roles table
        // Remove roles not in keycloakRoles
        // Add roles that are missing
    }
}
```

#### 3. Configuration

```properties
# Keycloak Admin API
keycloak.admin.url=https://keycloak.example.com
keycloak.admin.realm=master
keycloak.admin.client-id=admin-cli
keycloak.admin.client-secret=your-admin-secret

# Target realm and client for role sync
keycloak.target.realm=flowcatalyst
keycloak.target.client-id=flowcatalyst-platform

# Enable sync
keycloak.sync.enabled=true
```

#### 4. Startup Sync

```java
package tech.flowcatalyst.platform.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class IdpSyncBootstrap {

    @Inject
    KeycloakSyncService keycloakSync;

    @Inject
    AzureAdSyncService azureAdSync;

    void onStart(@Observes StartupEvent event) {
        // Sync after metadata registry is populated
        keycloakSync.syncToKeycloak();
        azureAdSync.syncToAzureAd();
    }
}
```

#### 5. Keycloak Token Mapping

Configure Keycloak to include role codes in JWT tokens:

**Client Scope → Mappers → Add Builtin → realm roles**

Or create custom mapper:
- Mapper Type: User Client Role
- Client ID: flowcatalyst-platform
- Token Claim Name: roles
- Claim JSON Type: String
- Add to ID token: ON
- Add to access token: ON

Your JWT will contain:
```json
{
  "sub": "user123",
  "roles": ["dispatch:manager", "billing:viewer"],
  "realm_access": {
    "roles": ["platform:admin"]
  }
}
```

---

## Azure AD (Entra ID) Integration

Azure AD uses **App Roles** which are coarser-grained than Keycloak. Strategy is to map FlowCatalyst roles to Azure App Roles.

### Architecture

**Approach**: Sync FlowCatalyst roles as Azure AD App Roles
- Each role becomes an App Role in the app registration
- Permissions are encoded in the role description/metadata
- Azure AD groups can also be used for role assignment

**Limitations**:
- App Roles are defined in manifest (250 role limit)
- Less flexible than Keycloak
- Updates require app registration changes

### Implementation

#### 1. Dependencies

```kotlin
dependencies {
    // Microsoft Graph SDK
    implementation("com.microsoft.graph:microsoft-graph:5.80.0")
    implementation("com.azure:azure-identity:1.11.0")
}
```

#### 2. Azure AD Sync Service

```java
package tech.flowcatalyst.platform.service;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.models.AppRole;
import com.microsoft.graph.models.Application;
import com.microsoft.graph.requests.GraphServiceClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.flowcatalyst.platform.security.RoleDefinition;

import java.util.*;

@ApplicationScoped
public class AzureAdSyncService {

    private static final Logger LOG = Logger.getLogger(AzureAdSyncService.class);

    @Inject
    SecurityMetadataRegistry metadataRegistry;

    @ConfigProperty(name = "azure.tenant-id")
    String tenantId;

    @ConfigProperty(name = "azure.client-id")
    String clientId;

    @ConfigProperty(name = "azure.client-secret")
    String clientSecret;

    @ConfigProperty(name = "azure.application-id")
    String applicationId;

    @ConfigProperty(name = "azure.sync.enabled", defaultValue = "false")
    boolean syncEnabled;

    /**
     * Sync roles to Azure AD as App Roles
     */
    public void syncToAzureAd() {
        if (!syncEnabled) {
            LOG.info("Azure AD sync is disabled");
            return;
        }

        LOG.info("Starting Azure AD sync...");

        try {
            GraphServiceClient<okhttp3.Request> graphClient = createGraphClient();

            // Get the application registration
            Application app = graphClient.applications()
                .byApplicationId(applicationId)
                .buildRequest()
                .get();

            // Get existing app roles
            List<AppRole> existingRoles = app.appRoles != null
                ? new ArrayList<>(app.appRoles)
                : new ArrayList<>();

            // Sync FlowCatalyst roles
            List<AppRole> updatedRoles = syncRoles(existingRoles);

            // Update application
            Application appUpdate = new Application();
            appUpdate.appRoles = updatedRoles;

            graphClient.applications()
                .byApplicationId(app.id)
                .buildRequest()
                .patch(appUpdate);

            LOG.info("Azure AD sync completed successfully");

        } catch (Exception e) {
            LOG.error("Azure AD sync failed", e);
            throw new RuntimeException("Failed to sync to Azure AD", e);
        }
    }

    /**
     * Sync FlowCatalyst roles to Azure App Roles
     */
    private List<AppRole> syncRoles(List<AppRole> existingRoles) {
        Collection<RoleDefinition> fcRoles = metadataRegistry.getAllRoles();
        LOG.infof("Syncing %d roles to Azure AD", fcRoles.size());

        Map<String, AppRole> roleMap = new HashMap<>();

        // Index existing roles by value (our role code)
        for (AppRole existing : existingRoles) {
            roleMap.put(existing.value, existing);
        }

        // Update or create roles
        for (RoleDefinition fcRole : fcRoles) {
            AppRole appRole = roleMap.get(fcRole.getCode());

            if (appRole == null) {
                // Create new app role
                appRole = new AppRole();
                appRole.id = UUID.randomUUID();
                appRole.value = fcRole.getCode();
                appRole.allowedMemberTypes = List.of("User", "Application");
                appRole.isEnabled = true;
            }

            // Update metadata
            appRole.displayName = fcRole.getDisplayName();
            appRole.description = buildDescription(fcRole);

            roleMap.put(fcRole.getCode(), appRole);
        }

        return new ArrayList<>(roleMap.values());
    }

    /**
     * Build role description with permission list
     */
    private String buildDescription(RoleDefinition role) {
        StringBuilder desc = new StringBuilder(role.getDescription());
        desc.append("\n\nPermissions: ");
        desc.append(String.join(", ", role.getPermissionCodes()));
        return desc.toString();
    }

    /**
     * Create Microsoft Graph client
     */
    private GraphServiceClient<okhttp3.Request> createGraphClient() {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
            .tenantId(tenantId)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .build();

        TokenCredentialAuthProvider authProvider =
            new TokenCredentialAuthProvider(
                List.of("https://graph.microsoft.com/.default"),
                credential
            );

        return GraphServiceClient.builder()
            .authenticationProvider(authProvider)
            .buildClient();
    }

    /**
     * Sync user roles from Azure AD to local database
     * Called during OIDC authentication
     */
    public void syncUserRolesFromAzureAd(Long principalId, Collection<String> azureRoles) {
        // Azure roles come as GUIDs or role values in the token
        // Filter to FlowCatalyst roles
        Set<String> validRoles = azureRoles.stream()
            .filter(role -> metadataRegistry.getRole(role) != null)
            .collect(Collectors.toSet());

        // Sync to database
        syncUserRolesToDatabase(principalId, validRoles);
    }

    private void syncUserRolesToDatabase(Long principalId, Set<String> roleCodes) {
        // Implementation to update principal_roles table
    }
}
```

#### 3. Configuration

```properties
# Azure AD
azure.tenant-id=your-tenant-id
azure.client-id=your-app-client-id
azure.client-secret=your-client-secret
azure.application-id=your-application-object-id

# Enable sync
azure.sync.enabled=true
```

#### 4. Azure AD Token Claims

Configure App Registration to include roles in tokens:
1. Go to Azure Portal → App Registrations → Your App
2. Token Configuration → Add groups claim
3. Roles are automatically included in `roles` claim

JWT will contain:
```json
{
  "sub": "user@example.com",
  "roles": ["dispatch:manager", "billing:viewer"],
  "oid": "user-object-id"
}
```

---

## OIDC Token Processing

Update your authentication service to extract roles from OIDC tokens:

```java
package tech.flowcatalyst.platform.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.oidc.OidcSession;
import org.eclipse.microprofile.jwt.JsonWebToken;
import tech.flowcatalyst.platform.model.Principal;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class OidcAuthenticationService {

    @Inject
    JsonWebToken jwt;

    @Inject
    KeycloakSyncService keycloakSync;

    @Inject
    AzureAdSyncService azureAdSync;

    @Inject
    TenantAuthConfigRepository authConfigRepo;

    /**
     * Sync user roles from OIDC token to database
     */
    public void syncUserRolesFromToken(Principal principal, Long tenantId) {
        TenantAuthConfig authConfig = authConfigRepo.findByTenantId(tenantId);

        if (authConfig == null) {
            return;
        }

        // Extract roles from token
        Set<String> tokenRoles = extractRolesFromToken();

        // Sync based on provider type
        switch (authConfig.provider) {
            case KEYCLOAK:
                keycloakSync.syncUserRolesFromKeycloak(principal.id, tokenRoles);
                break;

            case AZURE_AD:
                azureAdSync.syncUserRolesFromAzureAd(principal.id, tokenRoles);
                break;

            default:
                LOG.warnf("Unknown auth provider: %s", authConfig.provider);
        }
    }

    /**
     * Extract role codes from JWT token
     */
    private Set<String> extractRolesFromToken() {
        if (jwt == null) {
            return Collections.emptySet();
        }

        // Try standard 'roles' claim
        Set<String> roles = jwt.claim("roles").orElse(Collections.emptySet());

        if (roles.isEmpty()) {
            // Try Azure AD 'roles' claim
            List<String> azureRoles = jwt.claim("roles").orElse(Collections.emptyList());
            roles = Set.copyOf(azureRoles);
        }

        if (roles.isEmpty()) {
            // Try Keycloak realm_access.roles
            var realmAccess = jwt.<Map<String, Object>>claim("realm_access").orElse(null);
            if (realmAccess != null && realmAccess.containsKey("roles")) {
                roles = Set.copyOf((List<String>) realmAccess.get("roles"));
            }
        }

        return roles;
    }
}
```

---

## Sync Admin Endpoints

Create REST endpoints to manually trigger syncs:

```java
package tech.flowcatalyst.platform.resource;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import tech.flowcatalyst.platform.service.KeycloakSyncService;
import tech.flowcatalyst.platform.service.AzureAdSyncService;

@Path("/api/admin/idp-sync")
@Produces("application/json")
public class IdpSyncResource {

    @Inject
    KeycloakSyncService keycloakSync;

    @Inject
    AzureAdSyncService azureAdSync;

    @POST
    @Path("/keycloak")
    @RolesAllowed("platform:admin")
    public Response syncToKeycloak() {
        try {
            keycloakSync.syncToKeycloak();
            return Response.ok(Map.of("status", "success")).build();
        } catch (Exception e) {
            return Response.status(500)
                .entity(Map.of("status", "error", "message", e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/azure-ad")
    @RolesAllowed("platform:admin")
    public Response syncToAzureAd() {
        try {
            azureAdSync.syncToAzureAd();
            return Response.ok(Map.of("status", "success")).build();
        } catch (Exception e) {
            return Response.status(500)
                .entity(Map.of("status", "error", "message", e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/all")
    @RolesAllowed("platform:admin")
    public Response syncToAll() {
        Map<String, String> results = new HashMap<>();

        try {
            keycloakSync.syncToKeycloak();
            results.put("keycloak", "success");
        } catch (Exception e) {
            results.put("keycloak", "error: " + e.getMessage());
        }

        try {
            azureAdSync.syncToAzureAd();
            results.put("azureAd", "success");
        } catch (Exception e) {
            results.put("azureAd", "error: " + e.getMessage());
        }

        return Response.ok(results).build();
    }
}
```

---

## Multi-Tenant Scenarios

Different tenants can use different IdPs:

```java
@ApplicationScoped
public class MultiTenantIdpSyncService {

    @Inject
    TenantAuthConfigRepository authConfigRepo;

    /**
     * Sync roles to IdP for specific tenant
     */
    public void syncForTenant(Long tenantId) {
        TenantAuthConfig config = authConfigRepo.findByTenantId(tenantId);

        if (config == null || !config.enabled) {
            return;
        }

        switch (config.provider) {
            case KEYCLOAK:
                syncToKeycloak(config);
                break;

            case AZURE_AD:
                syncToAzureAd(config);
                break;

            case OKTA:
                syncToOkta(config);
                break;
        }
    }

    private void syncToKeycloak(TenantAuthConfig config) {
        // Use tenant-specific Keycloak realm/client
        KeycloakSyncService tenantSync = new KeycloakSyncService(
            config.oidcIssuer,
            config.clientId,
            config.clientSecret
        );
        tenantSync.syncToKeycloak();
    }
}
```

---

## CI/CD Integration

Add IdP sync to your deployment pipeline:

```yaml
# .github/workflows/deploy.yml
name: Deploy

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v3

      - name: Build application
        run: ./gradlew build

      - name: Deploy to production
        run: ./deploy.sh

      - name: Sync roles to Keycloak
        run: |
          curl -X POST https://api.flowcatalyst.io/api/admin/idp-sync/keycloak \
            -H "Authorization: Bearer ${{ secrets.ADMIN_TOKEN }}"

      - name: Sync roles to Azure AD
        run: |
          curl -X POST https://api.flowcatalyst.io/api/admin/idp-sync/azure-ad \
            -H "Authorization: Bearer ${{ secrets.ADMIN_TOKEN }}"
```

---

## Best Practices

### 1. Sync Direction
✅ **Code → IdP**: Code is source of truth
❌ **IdP → Code**: Don't let IdP changes modify code

### 2. Role Assignment
- **Code defines roles** (what permissions they have)
- **IdP assigns roles** (which users have them)
- Keep assignment in IdP, definitions in code

### 3. Error Handling
- Sync failures should not block application startup
- Log sync errors for manual review
- Provide admin endpoints to retry sync

### 4. Validation
- Validate IdP roles match code-defined roles on user login
- Warn about unknown roles in tokens
- Auto-remove deprecated roles from user assignments

### 5. Testing
- Mock IdP APIs in tests
- Test sync with fake roles/permissions
- Verify token parsing extracts correct roles

### 6. Monitoring
- Track sync success/failure rates
- Alert on sync errors
- Monitor drift between code and IdP

---

## Comparison Matrix

| Feature | Keycloak | Azure AD | Local DB Only |
|---------|----------|----------|---------------|
| Fine-grained permissions | ✅ Excellent | ⚠️ Limited | ✅ Full control |
| Role attributes/metadata | ✅ Yes | ⚠️ Limited | ✅ Yes |
| SSO integration | ✅ Built-in | ✅ Built-in | ❌ Manual |
| External user management | ✅ Yes | ✅ Yes | ❌ No |
| Multi-tenant support | ✅ Realms | ⚠️ Tenants | ✅ Custom |
| Audit logging | ✅ Built-in | ✅ Built-in | ⚠️ Custom |
| Cost | ✅ Free/OSS | 💰 Premium features | ✅ Free |
| Complexity | ⚠️ Medium | ⚠️ Medium | ✅ Simple |
| Role limit | ✅ Unlimited | ⚠️ 250 app roles | ✅ Unlimited |

---

## Migration Path

### Phase 1: Local DB Only
- Define roles/permissions in code
- Store assignments in local DB
- No IdP integration

### Phase 2: Add IdP Sync
- Enable IdP sync for role definitions
- Continue using local DB for assignments
- Validate sync works correctly

### Phase 3: IdP Assignments
- Move role assignments to IdP
- Sync from IdP tokens to local DB
- Use local DB as cache

### Phase 4: Full IdP Integration
- IdP is authoritative for assignments
- Local DB is read-only cache
- Sync on every login

---

## Example: End-to-End Flow

1. **Developer defines role in code**:
   ```java
   @DefineRole
   public class DispatchManagerRole implements RoleDefinition {
       public static final String CODE = "dispatch:manager";
       // ...
   }
   ```

2. **Application starts, syncs to Keycloak**:
   - Creates client role `dispatch:manager`
   - Stores permissions in role attributes

3. **Admin assigns role in Keycloak UI**:
   - User "john@example.com" → assign role `dispatch:manager`

4. **User logs in via OIDC**:
   - Keycloak issues token with `roles: ["dispatch:manager"]`

5. **Application processes token**:
   - Extracts role `dispatch:manager`
   - Syncs to `principal_roles` table
   - User now has all permissions from that role

6. **User makes API request**:
   - `@RequiresPermission(DispatchJobCreatePermission.class)` checks local DB
   - Finds user has `dispatch:manager` role
   - Role includes `dispatch:job:create` permission
   - Request succeeds

---

## Summary

**Key Benefits**:
- ✅ Centralized role management in corporate IdP
- ✅ SSO with role propagation
- ✅ Code remains source of truth for definitions
- ✅ IdP handles assignment and user management
- ✅ Local DB caches for performance
- ✅ Supports multiple IdPs per tenant

**Recommended Approach**:
- Use **Keycloak** for maximum flexibility
- Use **Azure AD** for enterprise/Microsoft shops
- Sync **one-way** (code → IdP) for role definitions
- Sync **user assignments** from IdP → local DB on login
- Keep local DB as performance cache
