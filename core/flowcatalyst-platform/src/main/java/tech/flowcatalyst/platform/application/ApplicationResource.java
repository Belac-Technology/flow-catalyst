package tech.flowcatalyst.platform.application;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.application.operations.*;
import tech.flowcatalyst.platform.application.Application;

import java.util.List;

@Path("/api/applications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Applications", description = "Manage platform applications")
public class ApplicationResource {

    @Inject
    ApplicationAdminService applicationService;

    @GET
    @Operation(summary = "List all applications", description = "Returns all applications")
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "List of applications",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = ApplicationListResponse.class))
        )
    })
    public Response listApplications(@QueryParam("activeOnly") @DefaultValue("false") boolean activeOnly) {
        List<Application> applications = activeOnly
            ? applicationService.findAllActive()
            : applicationService.findAll();

        List<ApplicationResponse> responses = applications.stream()
            .map(ApplicationResponse::from)
            .toList();

        return Response.ok(new ApplicationListResponse(responses)).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get application by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Application found"),
        @APIResponse(responseCode = "404", description = "Application not found")
    })
    public Response getApplication(@PathParam("id") Long id) {
        return applicationService.findById(id)
            .map(app -> Response.ok(ApplicationResponse.from(app)).build())
            .orElse(Response.status(404)
                .entity(new ErrorResponse("Application not found: " + id))
                .build());
    }

    @GET
    @Path("/code/{code}")
    @Operation(summary = "Get application by code")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Application found"),
        @APIResponse(responseCode = "404", description = "Application not found")
    })
    public Response getApplicationByCode(@PathParam("code") String code) {
        return applicationService.findByCode(code)
            .map(app -> Response.ok(ApplicationResponse.from(app)).build())
            .orElse(Response.status(404)
                .entity(new ErrorResponse("Application not found: " + code))
                .build());
    }

    @POST
    @Operation(summary = "Create a new application")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Application created"),
        @APIResponse(responseCode = "400", description = "Invalid request")
    })
    public Response createApplication(CreateApplicationRequest request) {
        Application app = applicationService.execute(new CreateApplication(
            request.code(),
            request.name(),
            request.description(),
            request.defaultBaseUrl(),
            request.iconUrl()
        ));
        return Response.status(201).entity(ApplicationResponse.from(app)).build();
    }

    @PUT
    @Path("/{id}")
    @Operation(summary = "Update an application")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Application updated"),
        @APIResponse(responseCode = "400", description = "Invalid request"),
        @APIResponse(responseCode = "404", description = "Application not found")
    })
    public Response updateApplication(@PathParam("id") Long id, UpdateApplicationRequest request) {
        Application app = applicationService.execute(new UpdateApplication(
            id,
            request.name(),
            request.description(),
            request.defaultBaseUrl(),
            request.iconUrl()
        ));
        return Response.ok(ApplicationResponse.from(app)).build();
    }

    @POST
    @Path("/{id}/activate")
    @Operation(summary = "Activate an application")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Application activated"),
        @APIResponse(responseCode = "400", description = "Application already active"),
        @APIResponse(responseCode = "404", description = "Application not found")
    })
    public Response activateApplication(@PathParam("id") Long id) {
        Application app = applicationService.execute(new ActivateApplication(id));
        return Response.ok(ApplicationResponse.from(app)).build();
    }

    @POST
    @Path("/{id}/deactivate")
    @Operation(summary = "Deactivate an application")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Application deactivated"),
        @APIResponse(responseCode = "400", description = "Application already deactivated"),
        @APIResponse(responseCode = "404", description = "Application not found")
    })
    public Response deactivateApplication(@PathParam("id") Long id) {
        Application app = applicationService.execute(new DeactivateApplication(id));
        return Response.ok(ApplicationResponse.from(app)).build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete an application")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Application deleted"),
        @APIResponse(responseCode = "400", description = "Cannot delete active application or application with configurations"),
        @APIResponse(responseCode = "404", description = "Application not found")
    })
    public Response deleteApplication(@PathParam("id") Long id) {
        applicationService.execute(new DeleteApplication(id));
        return Response.noContent().build();
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    public record ApplicationListResponse(List<ApplicationResponse> items) {}

    public record ApplicationResponse(
        String id,
        String code,
        String name,
        String description,
        String defaultBaseUrl,
        String iconUrl,
        boolean active,
        String createdAt,
        String updatedAt
    ) {
        public static ApplicationResponse from(Application app) {
            return new ApplicationResponse(
                app.id != null ? app.id.toString() : null,
                app.code,
                app.name,
                app.description,
                app.defaultBaseUrl,
                app.iconUrl,
                app.active,
                app.createdAt != null ? app.createdAt.toString() : null,
                app.updatedAt != null ? app.updatedAt.toString() : null
            );
        }
    }

    public record CreateApplicationRequest(
        String code,
        String name,
        String description,
        String defaultBaseUrl,
        String iconUrl
    ) {}

    public record UpdateApplicationRequest(
        String name,
        String description,
        String defaultBaseUrl,
        String iconUrl
    ) {}

    public record ErrorResponse(String error) {}
}
