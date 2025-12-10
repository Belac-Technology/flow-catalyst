package tech.flowcatalyst.platform.admin;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import tech.flowcatalyst.platform.client.Client;
import tech.flowcatalyst.platform.client.ClientService;
import tech.flowcatalyst.platform.client.ClientStatus;

import java.time.Instant;
import java.util.List;

/**
 * Admin API for client management.
 *
 * Provides CRUD operations for clients including:
 * - Create, read, update clients
 * - Status management (activate, suspend, deactivate)
 * - Audit notes
 *
 * All operations require admin-level permissions.
 */
@Path("/api/admin/platform/clients")
@Tag(name = "Client Admin", description = "Administrative operations for client management")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@EmbeddedModeOnly
public class ClientAdminResource {

    private static final Logger LOG = Logger.getLogger(ClientAdminResource.class);

    @Inject
    ClientService clientService;

    @Inject
    JwtKeyService jwtKeyService;

    // ==================== CRUD Operations ====================

    /**
     * List all clients.
     */
    @GET
    @Operation(summary = "List all clients", description = "Returns all clients regardless of status")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "List of clients",
            content = @Content(schema = @Schema(implementation = ClientListResponse.class))),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Insufficient permissions")
    })
    public Response listClients(
            @QueryParam("status") @Parameter(description = "Filter by status") ClientStatus status,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        // TODO: Add permission check for platform:client:view
        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        List<Client> clients;
        if (status != null) {
            clients = clientService.findAll().stream()
                .filter(c -> c.status == status)
                .toList();
        } else {
            clients = clientService.findAll();
        }

        List<ClientDto> dtos = clients.stream()
            .map(this::toDto)
            .toList();

        return Response.ok(new ClientListResponse(dtos, dtos.size())).build();
    }

    /**
     * Get a specific client by ID.
     */
    @GET
    @Path("/{id}")
    @Operation(summary = "Get client by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Client details",
            content = @Content(schema = @Schema(implementation = ClientDto.class))),
        @APIResponse(responseCode = "404", description = "Client not found"),
        @APIResponse(responseCode = "401", description = "Not authenticated")
    })
    public Response getClient(
            @PathParam("id") String id,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        return clientService.findById(id)
            .map(client -> Response.ok(toDto(client)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build());
    }

    /**
     * Get a client by identifier/slug.
     */
    @GET
    @Path("/by-identifier/{identifier}")
    @Operation(summary = "Get client by identifier")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Client details"),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response getClientByIdentifier(
            @PathParam("identifier") String identifier,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        return clientService.findByIdentifier(identifier)
            .map(client -> Response.ok(toDto(client)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build());
    }

    /**
     * Create a new client.
     */
    @POST
    @Operation(summary = "Create a new client")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Client created",
            content = @Content(schema = @Schema(implementation = ClientDto.class))),
        @APIResponse(responseCode = "400", description = "Invalid request or identifier already exists"),
        @APIResponse(responseCode = "401", description = "Not authenticated")
    })
    public Response createClient(
            @Valid CreateClientRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader,
            @Context UriInfo uriInfo) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }
        String principalId = principalIdOpt.get();

        try {
            Client client = clientService.createClient(request.name(), request.identifier());
            LOG.infof("Client created: %s (%s) by principal %s",
                client.name, client.identifier, principalId);

            return Response.status(Response.Status.CREATED)
                .entity(toDto(client))
                .location(uriInfo.getAbsolutePathBuilder().path(String.valueOf(client.id)).build())
                .build();
        } catch (BadRequestException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(e.getMessage()))
                .build();
        }
    }

    /**
     * Update client details.
     */
    @PUT
    @Path("/{id}")
    @Operation(summary = "Update client details")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Client updated"),
        @APIResponse(responseCode = "404", description = "Client not found"),
        @APIResponse(responseCode = "400", description = "Invalid request")
    })
    public Response updateClient(
            @PathParam("id") String id,
            @Valid UpdateClientRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }
        String principalId = principalIdOpt.get();

        try {
            Client client = clientService.updateClient(id, request.name());
            LOG.infof("Client updated: %s by principal %s", id, principalId);
            return Response.ok(toDto(client)).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }
    }

    // ==================== Status Management ====================

    /**
     * Activate a client.
     */
    @POST
    @Path("/{id}/activate")
    @Operation(summary = "Activate a client")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Client activated"),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response activateClient(
            @PathParam("id") String id,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }
        String principalId = principalIdOpt.get();

        try {
            clientService.activateClient(id, principalId);
            LOG.infof("Client %s activated by principal %s", id, principalId);
            return Response.ok(new StatusChangeResponse("Client activated")).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }
    }

    /**
     * Suspend a client.
     */
    @POST
    @Path("/{id}/suspend")
    @Operation(summary = "Suspend a client")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Client suspended"),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response suspendClient(
            @PathParam("id") String id,
            @Valid StatusChangeRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }
        String principalId = principalIdOpt.get();

        try {
            clientService.suspendClient(id, request.reason(), principalId);
            LOG.infof("Client %s suspended by principal %s: %s", id, principalId, request.reason());
            return Response.ok(new StatusChangeResponse("Client suspended")).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }
    }

    /**
     * Deactivate a client (soft delete).
     */
    @POST
    @Path("/{id}/deactivate")
    @Operation(summary = "Deactivate a client")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Client deactivated"),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response deactivateClient(
            @PathParam("id") String id,
            @Valid StatusChangeRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }
        String principalId = principalIdOpt.get();

        try {
            clientService.deactivateClient(id, request.reason(), principalId);
            LOG.infof("Client %s deactivated by principal %s: %s", id, principalId, request.reason());
            return Response.ok(new StatusChangeResponse("Client deactivated")).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }
    }

    // ==================== Audit Notes ====================

    /**
     * Add a note to a client's audit trail.
     */
    @POST
    @Path("/{id}/notes")
    @Operation(summary = "Add audit note to client")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Note added"),
        @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response addNote(
            @PathParam("id") String id,
            @Valid AddNoteRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        var principalIdOpt = jwtKeyService.extractAndValidatePrincipalId(sessionToken, authHeader);
        if (principalIdOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }
        String principalId = principalIdOpt.get();

        try {
            clientService.addNote(id, request.category(), request.text(), principalId);
            return Response.status(Response.Status.CREATED)
                .entity(new StatusChangeResponse("Note added"))
                .build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Client not found"))
                .build();
        }
    }

    // ==================== Helper Methods ====================

    private ClientDto toDto(Client client) {
        return new ClientDto(
            client.id != null ? client.id : null,
            client.name,
            client.identifier,
            client.status,
            client.statusReason,
            client.statusChangedAt,
            client.createdAt,
            client.updatedAt
        );
    }

    // ==================== DTOs ====================

    public record ClientDto(
        String id,
        String name,
        String identifier,
        ClientStatus status,
        String statusReason,
        Instant statusChangedAt,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record ClientListResponse(
        List<ClientDto> clients,
        int total
    ) {}

    public record CreateClientRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be less than 255 characters")
        String name,

        @NotBlank(message = "Identifier is required")
        @Size(min = 2, max = 100, message = "Identifier must be 2-100 characters")
        String identifier
    ) {}

    public record UpdateClientRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be less than 255 characters")
        String name
    ) {}

    public record StatusChangeRequest(
        @NotBlank(message = "Reason is required")
        String reason
    ) {}

    public record StatusChangeResponse(
        String message
    ) {}

    public record AddNoteRequest(
        @NotBlank(message = "Category is required")
        String category,

        @NotBlank(message = "Text is required")
        String text
    ) {}

    public record ErrorResponse(
        String error
    ) {}
}
