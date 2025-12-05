package tech.flowcatalyst.dispatchjob.admin;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import tech.flowcatalyst.dispatchjob.dto.*;
import tech.flowcatalyst.dispatchjob.entity.DispatchCredentials;
import tech.flowcatalyst.dispatchjob.service.CredentialsService;

@Path("/api/admin/dispatch/credentials")
@Tag(name = "Credentials Admin", description = "Admin endpoints for managing dispatch credentials")
public class CredentialsAdminResource {

    private static final Logger LOG = Logger.getLogger(CredentialsAdminResource.class);

    @Inject
    CredentialsService credentialsService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create new dispatch credentials", description = "Creates new webhook credentials with bearer token and signing secret")
    @APIResponses({
        @APIResponse(
            responseCode = "201",
            description = "Credentials created successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = CredentialsResponse.class))
        ),
        @APIResponse(
            responseCode = "400",
            description = "Invalid request - missing required fields",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response createCredentials(@Valid CreateCredentialsRequest request) {
        LOG.infof("Creating new credentials");

        DispatchCredentials credentials = credentialsService.create(request);

        return Response.status(201)
            .entity(CredentialsResponse.from(credentials))
            .build();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get credentials by ID (without sensitive data)", description = "Retrieves credential metadata without exposing bearer token or signing secret")
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Credentials found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = CredentialsResponse.class))
        ),
        @APIResponse(
            responseCode = "404",
            description = "Credentials not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response getCredentials(@PathParam("id") Long id) {
        return credentialsService.findById(id)
            .map(credentials -> Response.ok(CredentialsResponse.from(credentials)).build())
            .orElse(Response.status(404).entity(new ErrorResponse("Credentials not found")).build());
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Delete credentials", description = "Permanently deletes credentials and invalidates cache")
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Credentials deleted successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SuccessResponse.class))
        ),
        @APIResponse(
            responseCode = "404",
            description = "Credentials not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response deleteCredentials(@PathParam("id") Long id) {
        boolean deleted = credentialsService.delete(id);

        if (deleted) {
            return Response.ok(new SuccessResponse("Credentials deleted")).build();
        } else {
            return Response.status(404).entity(new ErrorResponse("Credentials not found")).build();
        }
    }
}
