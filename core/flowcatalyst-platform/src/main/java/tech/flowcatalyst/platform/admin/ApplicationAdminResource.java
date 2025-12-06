package tech.flowcatalyst.platform.admin;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.application.Application;
import tech.flowcatalyst.platform.application.ApplicationRepository;
import tech.flowcatalyst.platform.application.ApplicationService;
import tech.flowcatalyst.platform.application.ApplicationClientConfig;
import tech.flowcatalyst.platform.authentication.EmbeddedModeOnly;
import tech.flowcatalyst.platform.authentication.JwtKeyService;
import tech.flowcatalyst.platform.authorization.PermissionRegistry;
import tech.flowcatalyst.platform.client.Client;
import tech.flowcatalyst.platform.client.ClientRepository;

import java.util.List;
import java.util.Map;

/**
 * Admin API for managing applications in the platform ecosystem.
 *
 * Applications are the software products that users access. Each application
 * has a unique code that serves as the prefix for roles.
 */
@Path("/api/admin/platform/applications")
@Tag(name = "Application Admin", description = "Application management endpoints")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@EmbeddedModeOnly
public class ApplicationAdminResource {

    @Inject
    ApplicationService applicationService;

    @Inject
    ApplicationRepository applicationRepo;

    @Inject
    ClientRepository clientRepo;

    @Inject
    JwtKeyService jwtKeyService;

    // ========================================================================
    // Application CRUD
    // ========================================================================

    @GET
    @Operation(summary = "List all applications")
    public Response listApplications(
            @QueryParam("activeOnly") @DefaultValue("false") boolean activeOnly,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        if (!isAuthenticated(sessionToken, authHeader)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Not authenticated"))
                .build();
        }

        List<Application> apps = activeOnly
            ? applicationService.findAllActive()
            : applicationService.findAll();

        var response = apps.stream().map(this::toApplicationResponse).toList();

        return Response.ok(Map.of(
            "applications", response,
            "total", apps.size()
        )).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get application by ID")
    public Response getApplication(@PathParam("id") Long id) {
        return applicationService.findById(id)
            .map(app -> Response.ok(toApplicationDetailResponse(app)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Application not found"))
                .build());
    }

    @GET
    @Path("/by-code/{code}")
    @Operation(summary = "Get application by code")
    public Response getApplicationByCode(@PathParam("code") String code) {
        return applicationService.findByCode(code)
            .map(app -> Response.ok(toApplicationDetailResponse(app)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Application not found"))
                .build());
    }

    @POST
    @Operation(summary = "Create a new application")
    public Response createApplication(CreateApplicationRequest request) {
        if (request.code == null || request.code.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Application code is required"))
                .build();
        }
        if (request.name == null || request.name.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Application name is required"))
                .build();
        }

        try {
            Application app = applicationService.createApplication(
                request.code,
                request.name,
                request.description,
                request.defaultBaseUrl
            );

            if (request.iconUrl != null) {
                app.iconUrl = request.iconUrl;
            }

            return Response.status(Response.Status.CREATED)
                .entity(toApplicationDetailResponse(app))
                .build();
        } catch (BadRequestException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @PUT
    @Path("/{id}")
    @Operation(summary = "Update an application")
    public Response updateApplication(@PathParam("id") Long id, UpdateApplicationRequest request) {
        try {
            Application app = applicationService.updateApplication(
                id,
                request.name,
                request.description,
                request.defaultBaseUrl,
                request.iconUrl
            );
            return Response.ok(toApplicationDetailResponse(app)).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Application not found"))
                .build();
        }
    }

    @POST
    @Path("/{id}/activate")
    @Operation(summary = "Activate an application")
    public Response activateApplication(@PathParam("id") Long id) {
        try {
            applicationService.activateApplication(id);
            return Response.ok(Map.of("message", "Application activated")).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Application not found"))
                .build();
        }
    }

    @POST
    @Path("/{id}/deactivate")
    @Operation(summary = "Deactivate an application")
    public Response deactivateApplication(@PathParam("id") Long id) {
        try {
            applicationService.deactivateApplication(id);
            return Response.ok(Map.of("message", "Application deactivated")).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Application not found"))
                .build();
        }
    }

    // ========================================================================
    // Client Configuration
    // ========================================================================

    @GET
    @Path("/{id}/clients")
    @Operation(summary = "Get client configurations for an application")
    public Response getClientConfigs(@PathParam("id") Long id) {
        if (applicationService.findById(id).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Application not found"))
                .build();
        }

        List<ApplicationClientConfig> configs = applicationService.getConfigsForApplication(id);
        var response = configs.stream().map(this::toClientConfigResponse).toList();

        return Response.ok(Map.of(
            "clientConfigs", response,
            "total", configs.size()
        )).build();
    }

    @PUT
    @Path("/{id}/clients/{clientId}")
    @Operation(summary = "Configure application for a specific client")
    public Response configureClient(
            @PathParam("id") Long applicationId,
            @PathParam("clientId") Long clientId,
            ClientConfigRequest request) {

        try {
            ApplicationClientConfig config = applicationService.configureForClient(
                applicationId,
                clientId,
                request.enabled != null ? request.enabled : true,
                request.baseUrlOverride,
                request.config
            );
            return Response.ok(toClientConfigResponse(config)).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/{id}/clients/{clientId}/enable")
    @Operation(summary = "Enable application for a client")
    public Response enableForClient(
            @PathParam("id") Long applicationId,
            @PathParam("clientId") Long clientId) {

        try {
            applicationService.enableForClient(applicationId, clientId);
            return Response.ok(Map.of("message", "Application enabled for client")).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/{id}/clients/{clientId}/disable")
    @Operation(summary = "Disable application for a client")
    public Response disableForClient(
            @PathParam("id") Long applicationId,
            @PathParam("clientId") Long clientId) {

        try {
            applicationService.disableForClient(applicationId, clientId);
            return Response.ok(Map.of("message", "Application disabled for client")).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    // ========================================================================
    // Roles for Application
    // ========================================================================

    @GET
    @Path("/{id}/roles")
    @Operation(summary = "Get all roles defined for this application")
    public Response getApplicationRoles(@PathParam("id") Long id) {
        return applicationService.findById(id)
            .map(app -> {
                var roles = PermissionRegistry.extractApplicationCodes(List.of(app.code));
                // This would need PermissionRegistry injection to get actual roles
                // For now, return a placeholder
                return Response.ok(Map.of(
                    "applicationCode", app.code,
                    "message", "Use GET /api/admin/platform/roles?application=" + app.code + " to get roles"
                )).build();
            })
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Application not found"))
                .build());
    }

    // ========================================================================
    // DTOs and Response Mapping
    // ========================================================================

    public static class CreateApplicationRequest {
        public String code;
        public String name;
        public String description;
        public String defaultBaseUrl;
        public String iconUrl;
    }

    public static class UpdateApplicationRequest {
        public String name;
        public String description;
        public String defaultBaseUrl;
        public String iconUrl;
    }

    public static class ClientConfigRequest {
        public Boolean enabled;
        public String baseUrlOverride;
        public Map<String, Object> config;
    }

    private Map<String, Object> toApplicationResponse(Application app) {
        return Map.of(
            "id", app.id,
            "code", app.code,
            "name", app.name,
            "active", app.active
        );
    }

    private Map<String, Object> toApplicationDetailResponse(Application app) {
        var result = new java.util.HashMap<String, Object>();
        result.put("id", app.id);
        result.put("code", app.code);
        result.put("name", app.name);
        result.put("description", app.description);
        result.put("iconUrl", app.iconUrl);
        result.put("defaultBaseUrl", app.defaultBaseUrl);
        result.put("active", app.active);
        result.put("createdAt", app.createdAt);
        result.put("updatedAt", app.updatedAt);
        return result;
    }

    private Map<String, Object> toClientConfigResponse(ApplicationClientConfig config) {
        var result = new java.util.HashMap<String, Object>();
        result.put("id", config.id);
        result.put("applicationId", config.applicationId);
        result.put("clientId", config.clientId);

        // Look up client details
        Client client = clientRepo.findByIdOptional(config.clientId).orElse(null);
        if (client != null) {
            result.put("clientName", client.name);
            result.put("clientIdentifier", client.identifier);
        } else {
            result.put("clientName", null);
            result.put("clientIdentifier", null);
        }

        result.put("enabled", config.enabled);
        result.put("baseUrlOverride", config.baseUrlOverride);

        // Compute effective base URL
        Application app = applicationRepo.findByIdOptional(config.applicationId).orElse(null);
        String effectiveBaseUrl = (config.baseUrlOverride != null && !config.baseUrlOverride.isBlank())
            ? config.baseUrlOverride
            : (app != null ? app.defaultBaseUrl : null);
        result.put("effectiveBaseUrl", effectiveBaseUrl);
        result.put("config", config.configJson);
        return result;
    }

    private boolean isAuthenticated(String sessionToken, String authHeader) {
        String token = sessionToken;
        if (token == null && authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring("Bearer ".length());
        }
        if (token == null) {
            return false;
        }
        return jwtKeyService.validateAndGetPrincipalId(token) != null;
    }
}
