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
import tech.flowcatalyst.platform.authentication.EmbeddedModeOnly;
import tech.flowcatalyst.platform.authentication.JwtKeyService;
import tech.flowcatalyst.platform.authentication.oauth.OAuthClient;
import tech.flowcatalyst.platform.authentication.oauth.OAuthClient.ClientType;
import tech.flowcatalyst.platform.authentication.oauth.OAuthClientRepository;
import tech.flowcatalyst.platform.principal.PasswordService;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Admin API for OAuth2 client management.
 *
 * Provides CRUD operations for OAuth clients including:
 * - Create public clients (SPAs, mobile apps)
 * - Create confidential clients (server-side apps)
 * - Manage client secrets
 * - Configure redirect URIs and grant types
 *
 * All operations require admin-level permissions.
 */
@Path("/api/admin/platform/oauth-clients")
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
    PasswordService passwordService;

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
            @QueryParam("ownerClientId") @Parameter(description = "Filter by owner client") Long ownerClientId,
            @QueryParam("active") @Parameter(description = "Filter by active status") Boolean active,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        List<OAuthClient> clients;
        if (ownerClientId != null && active != null) {
            clients = clientRepo.find("ownerClientId = ?1 AND active = ?2", ownerClientId, active).list();
        } else if (ownerClientId != null) {
            clients = clientRepo.find("ownerClientId", ownerClientId).list();
        } else if (active != null) {
            clients = clientRepo.find("active", active).list();
        } else {
            clients = clientRepo.listAll();
        }

        List<ClientDto> dtos = clients.stream()
            .map(this::toDto)
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
            @PathParam("id") Long id,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        return clientRepo.findByIdOptional(id)
            .map(client -> Response.ok(toDto(client)).build())
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
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        return clientRepo.findByClientIdIncludingInactive(clientId)
            .map(client -> Response.ok(toDto(client)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build());
    }

    /**
     * Create a new OAuth client.
     *
     * For PUBLIC clients, no secret is generated.
     * For CONFIDENTIAL clients, a secret is generated and returned ONCE in the response.
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
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader,
            @Context UriInfo uriInfo) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
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
        client.redirectUris = String.join(",", request.redirectUris());
        client.grantTypes = String.join(",", request.grantTypes());
        client.defaultScopes = request.defaultScopes() != null ? String.join(",", request.defaultScopes()) : null;
        client.ownerClientId = request.ownerClientId();
        client.active = true;

        // PKCE is always required for public clients
        client.pkceRequired = request.clientType() == ClientType.PUBLIC || request.pkceRequired();

        String plainSecret = null;
        if (request.clientType() == ClientType.CONFIDENTIAL) {
            // Generate and hash the secret
            plainSecret = generateClientSecret();
            client.clientSecretHash = passwordService.hashPassword(plainSecret);
        }

        clientRepo.persist(client);

        LOG.infof("OAuth client created: %s (%s) by principal %d",
            client.clientName, client.clientId, adminPrincipalId);

        // Return the client with the plain secret (only time it's visible)
        CreateClientResponse response = new CreateClientResponse(
            toDto(client),
            plainSecret // Will be null for public clients
        );

        return Response.status(Response.Status.CREATED)
            .entity(response)
            .location(uriInfo.getAbsolutePathBuilder().path(String.valueOf(client.id)).build())
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
            @PathParam("id") Long id,
            @Valid UpdateClientRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

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
            client.redirectUris = String.join(",", request.redirectUris());
        }
        if (request.grantTypes() != null) {
            client.grantTypes = String.join(",", request.grantTypes());
        }
        if (request.defaultScopes() != null) {
            client.defaultScopes = String.join(",", request.defaultScopes());
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

        clientRepo.update(client);
        LOG.infof("OAuth client updated: %s by principal %d", client.clientId, adminPrincipalId);

        return Response.ok(toDto(client)).build();
    }

    /**
     * Rotate client secret (confidential clients only).
     *
     * Generates a new secret and returns it ONCE in the response.
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
            @PathParam("id") Long id,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

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

        // Generate new secret
        String newSecret = generateClientSecret();
        client.clientSecretHash = passwordService.hashPassword(newSecret);
        clientRepo.update(client);

        LOG.infof("OAuth client secret rotated: %s by principal %d", client.clientId, adminPrincipalId);

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
            @PathParam("id") Long id,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        OAuthClient client = clientRepo.findByIdOptional(id).orElse(null);
        if (client == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }

        client.active = true;
        clientRepo.update(client);
        LOG.infof("OAuth client activated: %s by principal %d", client.clientId, adminPrincipalId);

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
            @PathParam("id") Long id,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        OAuthClient client = clientRepo.findByIdOptional(id).orElse(null);
        if (client == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }

        client.active = false;
        clientRepo.update(client);
        LOG.infof("OAuth client deactivated: %s by principal %d", client.clientId, adminPrincipalId);

        return Response.ok(new StatusResponse("Client deactivated")).build();
    }

    // ==================== Helper Methods ====================

    private Long extractPrincipalId(String sessionToken, String authHeader) {
        String token = sessionToken;
        if (token == null && authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring("Bearer ".length());
        }
        if (token == null) {
            return null;
        }
        return jwtKeyService.validateAndGetPrincipalId(token);
    }

    private String generateClientId() {
        // Format: fc_{tsid} for easy identification
        return "fc_" + Long.toString(TsidGenerator.generate(), 36);
    }

    private String generateClientSecret() {
        // Generate 32 bytes of random data, encode as base64 (43 chars)
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ClientDto toDto(OAuthClient client) {
        Set<String> redirectUris = client.redirectUris != null
            ? Set.of(client.redirectUris.split(","))
            : Set.of();
        Set<String> grantTypes = client.grantTypes != null
            ? Set.of(client.grantTypes.split(","))
            : Set.of();
        Set<String> defaultScopes = client.defaultScopes != null
            ? Set.of(client.defaultScopes.split(","))
            : Set.of();

        return new ClientDto(
            client.id,
            client.clientId,
            client.clientName,
            client.clientType,
            redirectUris,
            grantTypes,
            defaultScopes,
            client.pkceRequired,
            client.ownerClientId,
            client.active,
            client.createdAt,
            client.updatedAt
        );
    }

    // ==================== DTOs ====================

    public record ClientDto(
        Long id,
        String clientId,
        String clientName,
        ClientType clientType,
        Set<String> redirectUris,
        Set<String> grantTypes,
        Set<String> defaultScopes,
        boolean pkceRequired,
        Long ownerClientId,
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
        Set<String> redirectUris,

        @NotNull(message = "At least one grant type is required")
        @Size(min = 1, message = "At least one grant type is required")
        Set<String> grantTypes,

        Set<String> defaultScopes,

        boolean pkceRequired,

        Long ownerClientId
    ) {}

    public record UpdateClientRequest(
        String clientName,
        Set<String> redirectUris,
        Set<String> grantTypes,
        Set<String> defaultScopes,
        Boolean pkceRequired
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
