package tech.flowcatalyst.platform.admin;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import tech.flowcatalyst.platform.application.Application;
import tech.flowcatalyst.platform.application.ApplicationRepository;
import tech.flowcatalyst.platform.authentication.EmbeddedModeOnly;
import tech.flowcatalyst.platform.authentication.JwtKeyService;
import tech.flowcatalyst.platform.authentication.oauth.OAuthClient;
import tech.flowcatalyst.platform.authentication.oauth.OAuthClient.ClientType;
import tech.flowcatalyst.platform.authentication.oauth.OAuthClientRepository;
import tech.flowcatalyst.platform.security.secrets.SecretService;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin API for OAuth2 client management.
 *
 * <p>Provides CRUD operations for OAuth clients including:
 * <ul>
 *   <li>Create public clients (SPAs, mobile apps)</li>
 *   <li>Create confidential clients (server-side apps)</li>
 *   <li>Manage client secrets (encrypted at rest)</li>
 *   <li>Configure redirect URIs and grant types</li>
 *   <li>Associate clients with applications</li>
 * </ul>
 *
 * <p>All operations require admin-level permissions.
 */
@Path("/api/admin/oauth-clients")
@Tag(name = "OAuth Client Admin", description = "Administrative operations for OAuth2 client management")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@EmbeddedModeOnly
public class OAuthClientAdminResource {

    private static final Logger LOG = Logger.getLogger(OAuthClientAdminResource.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Inject
    OAuthClientRepository clientRepo;

    @Inject
    ApplicationRepository applicationRepo;

    @Inject
    SecretService secretService;

    @Inject
    JwtKeyService jwtKeyService;

    // ==================== CRUD Operations ====================

    /**
     * List all OAuth clients.
     */
    @GET
    @Operation(summary = "List all OAuth clients")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "List of OAuth clients",
            content = @Content(schema = @Schema(implementation = ClientListResponse.class))),
        @APIResponse(responseCode = "401", description = "Not authenticated")
    })
    public Response listClients(
            @QueryParam("applicationId") @Parameter(description = "Filter by associated application") String applicationId,
            @QueryParam("active") @Parameter(description = "Filter by active status") Boolean active,
            @CookieParam("fc_session") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        List<OAuthClient> clients;
        if (applicationId != null && active != null) {
            clients = clientRepo.findByApplicationIdAndActive(applicationId, active);
        } else if (applicationId != null) {
            clients = clientRepo.findByApplicationId(applicationId);
        } else if (active != null) {
            clients = clientRepo.findByActive(active);
        } else {
            clients = clientRepo.listAll();
        }

        // Build a map of application IDs to names for the response
        Set<String> allAppIds = clients.stream()
            .flatMap(c -> c.applicationIds != null ? c.applicationIds.stream() : java.util.stream.Stream.empty())
            .collect(Collectors.toSet());
        Map<String, String> appIdToName = new HashMap<>();
        if (!allAppIds.isEmpty()) {
            applicationRepo.findByIds(allAppIds)
                .forEach(app -> appIdToName.put(app.id, app.name));
        }

        List<ClientDto> dtos = clients.stream()
            .map(c -> toDto(c, appIdToName))
            .toList();

        return Response.ok(new ClientListResponse(dtos, dtos.size())).build();
    }

    /**
     * Get a specific OAuth client by ID.
     */
    @GET
    @Path("/{id}")
    @Operation(summary = "Get OAuth client by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Client details",
            content = @Content(schema = @Schema(implementation = ClientDto.class))),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response getClient(
            @PathParam("id") String id,
            @CookieParam("fc_session") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        return clientRepo.findByIdOptional(id)
            .map(client -> {
                Map<String, String> appIdToName = buildAppNameMap(client.applicationIds);
                return Response.ok(toDto(client, appIdToName)).build();
            })
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build());
    }

    /**
     * Get OAuth client by client_id.
     */
    @GET
    @Path("/by-client-id/{clientId}")
    @Operation(summary = "Get OAuth client by client_id")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Client details"),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response getClientByClientId(
            @PathParam("clientId") String clientId,
            @CookieParam("fc_session") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        return clientRepo.findByClientIdIncludingInactive(clientId)
            .map(client -> {
                Map<String, String> appIdToName = buildAppNameMap(client.applicationIds);
                return Response.ok(toDto(client, appIdToName)).build();
            })
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build());
    }

    /**
     * Create a new OAuth client.
     *
     * <p>For PUBLIC clients, no secret is generated.
     * <p>For CONFIDENTIAL clients, a secret is generated and returned ONCE in the response.
     * The secret is encrypted at rest using the platform's encryption key.
     */
    @POST
    @Operation(summary = "Create a new OAuth client",
        description = "For confidential clients, the secret is returned once in the response and cannot be retrieved again.")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Client created",
            content = @Content(schema = @Schema(implementation = CreateClientResponse.class))),
        @APIResponse(responseCode = "400", description = "Invalid request")
    })
    public Response createClient(
            @Valid CreateClientRequest request,
            @CookieParam("fc_session") String sessionToken,
            @HeaderParam("Authorization") String authHeader,
            @Context UriInfo uriInfo) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }
        String adminPrincipalId = principalIdOpt.get();

        // Validate application IDs if provided
        if (request.applicationIds() != null && !request.applicationIds().isEmpty()) {
            List<Application> apps = applicationRepo.findByIds(request.applicationIds());
            if (apps.size() != request.applicationIds().size()) {
                Set<String> foundIds = apps.stream().map(a -> a.id).collect(Collectors.toSet());
                Set<String> missingIds = new HashSet<>(request.applicationIds());
                missingIds.removeAll(foundIds);
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Invalid application IDs: " + missingIds))
                    .build();
            }

            // Application OAuth clients cannot use client_credentials grant
            // (client_credentials is only for service account clients)
            if (request.grantTypes() != null && request.grantTypes().contains("client_credentials")) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Application OAuth clients cannot use client_credentials grant. " +
                        "Use authorization_code or refresh_token instead."))
                    .build();
            }
        }

        // Generate unique client_id
        String clientId = generateClientId();

        // Check if client_id already exists (unlikely with TSID but check anyway)
        if (clientRepo.findByClientIdIncludingInactive(clientId).isPresent()) {
            clientId = generateClientId(); // Retry once
        }

        OAuthClient client = new OAuthClient();
        client.id = TsidGenerator.generate();
        client.clientId = clientId;
        client.clientName = request.clientName();
        client.clientType = request.clientType();
        client.redirectUris = new ArrayList<>(request.redirectUris());
        client.grantTypes = new ArrayList<>(request.grantTypes());
        client.defaultScopes = request.defaultScopes() != null ? String.join(" ", request.defaultScopes()) : null;
        client.applicationIds = request.applicationIds() != null ? new ArrayList<>(request.applicationIds()) : new ArrayList<>();
        client.active = true;

        // PKCE is always required for public clients
        client.pkceRequired = request.clientType() == ClientType.PUBLIC || request.pkceRequired();

        String plainSecret = null;
        if (request.clientType() == ClientType.CONFIDENTIAL) {
            // Generate secret and encrypt it
            plainSecret = generateClientSecret();
            client.clientSecretRef = secretService.prepareForStorage("encrypt:" + plainSecret);
        }

        clientRepo.persist(client);

        LOG.infof("OAuth client created: %s (%s) by principal %s",
            client.clientName, client.clientId, adminPrincipalId);

        // Build application name map for response
        Map<String, String> appIdToName = buildAppNameMap(client.applicationIds);

        // Return the client with the plain secret (only time it's visible)
        CreateClientResponse response = new CreateClientResponse(
            toDto(client, appIdToName),
            plainSecret // Will be null for public clients
        );

        return Response.status(Response.Status.CREATED)
            .entity(response)
            .location(uriInfo.getAbsolutePathBuilder().path(client.id).build())
            .build();
    }

    /**
     * Update an OAuth client.
     */
    @PUT
    @Path("/{id}")
    @Operation(summary = "Update OAuth client")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Client updated"),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response updateClient(
            @PathParam("id") String id,
            @Valid UpdateClientRequest request,
            @CookieParam("fc_session") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Not authenticated"))
                    .build();
        }
        String adminPrincipalId = principalIdOpt.get();

        OAuthClient client = clientRepo.findByIdOptional(id).orElse(null);
        if (client == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }

        if (request.clientName() != null) {
            client.clientName = request.clientName();
        }
        if (request.redirectUris() != null) {
            client.redirectUris = new ArrayList<>(request.redirectUris());
        }
        if (request.grantTypes() != null) {
            client.grantTypes = new ArrayList<>(request.grantTypes());
        }
        if (request.defaultScopes() != null) {
            client.defaultScopes = String.join(" ", request.defaultScopes());
        }
        if (request.pkceRequired() != null) {
            // Can't disable PKCE for public clients
            if (client.clientType == ClientType.PUBLIC && !request.pkceRequired()) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("PKCE cannot be disabled for public clients"))
                    .build();
            }
            client.pkceRequired = request.pkceRequired();
        }
        if (request.applicationIds() != null) {
            // Service account clients cannot have application associations
            if (client.serviceAccountPrincipalId != null && !request.applicationIds().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Service account OAuth clients cannot have application associations"))
                    .build();
            }

            // Validate application IDs
            if (!request.applicationIds().isEmpty()) {
                List<Application> apps = applicationRepo.findByIds(request.applicationIds());
                if (apps.size() != request.applicationIds().size()) {
                    Set<String> foundIds = apps.stream().map(a -> a.id).collect(Collectors.toSet());
                    Set<String> missingIds = new HashSet<>(request.applicationIds());
                    missingIds.removeAll(foundIds);
                    return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse("Invalid application IDs: " + missingIds))
                        .build();
                }
            }
            client.applicationIds = new ArrayList<>(request.applicationIds());
        }

        // Validate grant types
        List<String> effectiveGrantTypes = request.grantTypes() != null ? request.grantTypes() : client.grantTypes;
        List<String> effectiveAppIds = request.applicationIds() != null ? request.applicationIds() : client.applicationIds;

        // Service account clients can only use client_credentials
        if (client.serviceAccountPrincipalId != null) {
            if (effectiveGrantTypes != null && !effectiveGrantTypes.equals(List.of("client_credentials"))) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Service account OAuth clients can only use client_credentials grant"))
                    .build();
            }
        }
        // Application clients cannot use client_credentials
        else if (effectiveAppIds != null && !effectiveAppIds.isEmpty()) {
            if (effectiveGrantTypes != null && effectiveGrantTypes.contains("client_credentials")) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Application OAuth clients cannot use client_credentials grant"))
                    .build();
            }
        }

        client.updatedAt = Instant.now();
        clientRepo.update(client);
        LOG.infof("OAuth client updated: %s by principal %s", client.clientId, adminPrincipalId);

        Map<String, String> appIdToName = buildAppNameMap(client.applicationIds);
        return Response.ok(toDto(client, appIdToName)).build();
    }

    /**
     * Rotate client secret (confidential clients only).
     *
     * <p>Generates a new secret and returns it ONCE in the response.
     * The old secret is immediately invalidated.
     */
    @POST
    @Path("/{id}/rotate-secret")
    @Operation(summary = "Rotate client secret",
        description = "Generates a new secret. The new secret is returned once and cannot be retrieved again.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Secret rotated",
            content = @Content(schema = @Schema(implementation = RotateSecretResponse.class))),
        @APIResponse(responseCode = "400", description = "Cannot rotate secret for public client"),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response rotateSecret(
            @PathParam("id") String id,
            @CookieParam("fc_session") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Not authenticated"))
                    .build();
        }
        String adminPrincipalId = principalIdOpt.get();

        OAuthClient client = clientRepo.findByIdOptional(id).orElse(null);
        if (client == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }

        if (client.clientType == ClientType.PUBLIC) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("Cannot rotate secret for public clients"))
                .build();
        }

        // Generate new secret and encrypt it
        String newSecret = generateClientSecret();
        client.clientSecretRef = secretService.prepareForStorage("encrypt:" + newSecret);
        client.updatedAt = Instant.now();
        clientRepo.update(client);

        LOG.infof("OAuth client secret rotated: %s by principal %s", client.clientId, adminPrincipalId);

        return Response.ok(new RotateSecretResponse(client.clientId, newSecret)).build();
    }

    /**
     * Activate an OAuth client.
     */
    @POST
    @Path("/{id}/activate")
    @Operation(summary = "Activate OAuth client")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Client activated"),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response activateClient(
            @PathParam("id") String id,
            @CookieParam("fc_session") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Not authenticated"))
                    .build();
        }
        String adminPrincipalId = principalIdOpt.get();

        OAuthClient client = clientRepo.findByIdOptional(id).orElse(null);
        if (client == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }

        client.active = true;
        client.updatedAt = Instant.now();
        clientRepo.update(client);
        LOG.infof("OAuth client activated: %s by principal %s", client.clientId, adminPrincipalId);

        return Response.ok(new StatusResponse("Client activated")).build();
    }

    /**
     * Deactivate an OAuth client.
     */
    @POST
    @Path("/{id}/deactivate")
    @Operation(summary = "Deactivate OAuth client")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Client deactivated"),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response deactivateClient(
            @PathParam("id") String id,
            @CookieParam("fc_session") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }
        String adminPrincipalId = principalIdOpt.get();

        OAuthClient client = clientRepo.findByIdOptional(id).orElse(null);
        if (client == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }

        client.active = false;
        client.updatedAt = Instant.now();
        clientRepo.update(client);
        LOG.infof("OAuth client deactivated: %s by principal %s", client.clientId, adminPrincipalId);

        return Response.ok(new StatusResponse("Client deactivated")).build();
    }

    /**
     * Delete an OAuth client.
     */
    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete OAuth client")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Client deleted"),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response deleteClient(
            @PathParam("id") String id,
            @CookieParam("fc_session") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }
        String adminPrincipalId = principalIdOpt.get();

        OAuthClient client = clientRepo.findByIdOptional(id).orElse(null);
        if (client == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }

        clientRepo.delete(client);
        LOG.infof("OAuth client deleted: %s (%s) by principal %s", client.clientName, client.clientId, adminPrincipalId);

        return Response.noContent().build();
    }

    // ==================== Helper Methods ====================

    private String generateClientId() {
        // Format: fc_{tsid} for easy identification
        return "fc_" + TsidGenerator.generate();
    }

    private String generateClientSecret() {
        // Generate 32 bytes of random data, encode as base64 (43 chars)
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Map<String, String> buildAppNameMap(List<String> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> appIdToName = new HashMap<>();
        applicationRepo.findByIds(applicationIds)
            .forEach(app -> appIdToName.put(app.id, app.name));
        return appIdToName;
    }

    private ClientDto toDto(OAuthClient client, Map<String, String> appIdToName) {
        List<ApplicationRef> applications = new ArrayList<>();
        if (client.applicationIds != null) {
            for (String appId : client.applicationIds) {
                String appName = appIdToName.getOrDefault(appId, "Unknown");
                applications.add(new ApplicationRef(appId, appName));
            }
        }

        return new ClientDto(
            client.id,
            client.clientId,
            client.clientName,
            client.clientType,
            client.redirectUris != null ? new ArrayList<>(client.redirectUris) : List.of(),
            client.grantTypes != null ? new ArrayList<>(client.grantTypes) : List.of(),
            client.defaultScopes != null ? List.of(client.defaultScopes.split(" ")) : List.of(),
            client.pkceRequired,
            applications,
            client.serviceAccountPrincipalId,
            client.active,
            client.createdAt,
            client.updatedAt
        );
    }

    // ==================== DTOs ====================

    public record ApplicationRef(
        String id,
        String name
    ) {}

    public record ClientDto(
        String id,
        String clientId,
        String clientName,
        ClientType clientType,
        List<String> redirectUris,
        List<String> grantTypes,
        List<String> defaultScopes,
        boolean pkceRequired,
        List<ApplicationRef> applications,
        String serviceAccountPrincipalId,  // Set if this is a service account client
        boolean active,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record ClientListResponse(
        List<ClientDto> clients,
        int total
    ) {}

    public record CreateClientRequest(
        @NotBlank(message = "Client name is required")
        @Size(max = 255)
        String clientName,

        @NotNull(message = "Client type is required")
        ClientType clientType,

        @NotNull(message = "At least one redirect URI is required")
        @Size(min = 1, message = "At least one redirect URI is required")
        List<String> redirectUris,

        @NotNull(message = "At least one grant type is required")
        @Size(min = 1, message = "At least one grant type is required")
        List<String> grantTypes,

        List<String> defaultScopes,

        boolean pkceRequired,

        List<String> applicationIds
    ) {}

    public record UpdateClientRequest(
        String clientName,
        List<String> redirectUris,
        List<String> grantTypes,
        List<String> defaultScopes,
        Boolean pkceRequired,
        List<String> applicationIds
    ) {}

    public record CreateClientResponse(
        ClientDto client,
        String clientSecret
    ) {}

    public record RotateSecretResponse(
        String clientId,
        String clientSecret
    ) {}

    public record StatusResponse(
        String message
    ) {}

    public record ErrorResponse(
        String error
    ) {}
}
