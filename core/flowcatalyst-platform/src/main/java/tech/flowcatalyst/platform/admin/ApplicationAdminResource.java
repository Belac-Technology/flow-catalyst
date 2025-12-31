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
import tech.flowcatalyst.platform.application.events.ApplicationActivated;
import tech.flowcatalyst.platform.application.events.ApplicationDeactivated;
import tech.flowcatalyst.platform.application.events.ApplicationDeleted;
import tech.flowcatalyst.platform.application.events.ApplicationDisabledForClient;
import tech.flowcatalyst.platform.application.events.ApplicationEnabledForClient;
import tech.flowcatalyst.platform.application.events.ApplicationUpdated;
import tech.flowcatalyst.platform.application.operations.DisableApplicationForClientCommand;
import tech.flowcatalyst.platform.application.operations.EnableApplicationForClientCommand;
import tech.flowcatalyst.platform.application.operations.activateapplication.ActivateApplicationCommand;
import tech.flowcatalyst.platform.application.operations.activateapplication.ActivateApplicationUseCase;
import tech.flowcatalyst.platform.application.operations.deactivateapplication.DeactivateApplicationCommand;
import tech.flowcatalyst.platform.application.operations.deactivateapplication.DeactivateApplicationUseCase;
import tech.flowcatalyst.platform.application.operations.deleteapplication.DeleteApplicationCommand;
import tech.flowcatalyst.platform.application.operations.deleteapplication.DeleteApplicationUseCase;
import tech.flowcatalyst.platform.application.operations.createapplication.CreateApplicationCommand;
import tech.flowcatalyst.platform.application.operations.createapplication.CreateApplicationUseCase;
import tech.flowcatalyst.platform.application.operations.provisionserviceaccount.ProvisionServiceAccountCommand;
import tech.flowcatalyst.platform.application.operations.provisionserviceaccount.ProvisionServiceAccountUseCase;
import tech.flowcatalyst.platform.application.operations.updateapplication.UpdateApplicationCommand;
import tech.flowcatalyst.platform.application.operations.updateapplication.UpdateApplicationUseCase;
import tech.flowcatalyst.platform.application.events.ApplicationCreated;
import tech.flowcatalyst.platform.audit.AuditContext;
import tech.flowcatalyst.platform.authentication.EmbeddedModeOnly;
import tech.flowcatalyst.platform.authentication.JwtKeyService;
import tech.flowcatalyst.platform.authorization.PermissionRegistry;
import tech.flowcatalyst.platform.client.Client;
import tech.flowcatalyst.platform.client.ClientRepository;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.common.Result;
import tech.flowcatalyst.platform.common.errors.UseCaseError;

import java.util.List;
import java.util.Map;

/**
 * Admin API for managing applications in the platform ecosystem.
 *
 * Applications are the software products that users access. Each application
 * has a unique code that serves as the prefix for roles.
 */
@Path("/api/admin/applications")
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

    @Inject
    AuditContext auditContext;

    @Inject
    ActivateApplicationUseCase activateApplicationUseCase;

    @Inject
    DeactivateApplicationUseCase deactivateApplicationUseCase;

    @Inject
    DeleteApplicationUseCase deleteApplicationUseCase;

    @Inject
    UpdateApplicationUseCase updateApplicationUseCase;

    @Inject
    CreateApplicationUseCase createApplicationUseCase;

    @Inject
    ProvisionServiceAccountUseCase provisionServiceAccountUseCase;

    // ========================================================================
    // Application CRUD
    // ========================================================================

    @GET
    @Operation(summary = "List all applications")
    public Response listApplications(
            @QueryParam("activeOnly") @DefaultValue("false") boolean activeOnly,
            @QueryParam("type") String type,
            @CookieParam("fc_session") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        if (!isAuthenticated(sessionToken, authHeader)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Not authenticated"))
                .build();
        }

        List<Application> apps;
        if (type != null && !type.isBlank()) {
            try {
                Application.ApplicationType appType = Application.ApplicationType.valueOf(type.toUpperCase());
                apps = applicationRepo.findByType(appType, activeOnly);
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid type. Must be APPLICATION or INTEGRATION"))
                    .build();
            }
        } else {
            apps = activeOnly
                ? applicationService.findAllActive()
                : applicationService.findAll();
        }

        var response = apps.stream().map(this::toApplicationResponse).toList();

        return Response.ok(Map.of(
            "applications", response,
            "total", apps.size()
        )).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get application by ID")
    public Response getApplication(@PathParam("id") String id) {
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
        String principalId = auditContext.requirePrincipalId();
        ExecutionContext ctx = ExecutionContext.create(principalId);

        // Parse application type
        Application.ApplicationType appType = Application.ApplicationType.APPLICATION;
        if (request.type != null && !request.type.isBlank()) {
            try {
                appType = Application.ApplicationType.valueOf(request.type.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid type. Must be APPLICATION or INTEGRATION"))
                    .build();
            }
        }

        // Create application via UseCase
        var command = new CreateApplicationCommand(
            request.code,
            request.name,
            request.description,
            request.defaultBaseUrl,
            request.iconUrl,
            appType,
            true  // provisionServiceAccount
        );
        Result<ApplicationCreated> result = createApplicationUseCase.execute(command, ctx);

        return switch (result) {
            case Result.Success<ApplicationCreated> s -> {
                // Fetch the created application
                Application app = applicationRepo.findByIdOptional(s.value().applicationId()).orElse(null);

                // Provision service account using UseCase
                var provisionCommand = new ProvisionServiceAccountCommand(app.id);
                var provisionResult = provisionServiceAccountUseCase.execute(provisionCommand, ctx);

                if (provisionResult.isSuccess()) {
                    // Build response including service account credentials
                    // Re-fetch app since provisioning updated it
                    app = applicationRepo.findByIdOptional(s.value().applicationId()).orElse(app);
                    var response = toApplicationDetailResponse(app);
                    response.put("serviceAccount", Map.of(
                        "principalId", provisionResult.principal().id,
                        "name", provisionResult.principal().name,
                        "oauthClient", Map.of(
                            "id", provisionResult.oauthClient().id,
                            "clientId", provisionResult.oauthClient().clientId,
                            "clientSecret", provisionResult.clientSecret()  // Only available at creation time!
                        )
                    ));

                    yield Response.status(Response.Status.CREATED)
                        .entity(response)
                        .build();
                } else {
                    // Service account provisioning failed, but app was created
                    var response = toApplicationDetailResponse(app);
                    response.put("warning", "Application created but service account provisioning failed: " + provisionResult.error().message());
                    yield Response.status(Response.Status.CREATED)
                        .entity(response)
                        .build();
                }
            }
            case Result.Failure<ApplicationCreated> f -> {
                yield Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", f.error().message()))
                    .build();
            }
        };
    }

    @PUT
    @Path("/{id}")
    @Operation(summary = "Update an application")
    public Response updateApplication(@PathParam("id") String id, UpdateApplicationRequest request) {
        String principalId = auditContext.requirePrincipalId();
        ExecutionContext ctx = ExecutionContext.create(principalId);
        var command = new UpdateApplicationCommand(
            id,
            request.name,
            request.description,
            request.defaultBaseUrl,
            request.iconUrl
        );
        Result<ApplicationUpdated> result = updateApplicationUseCase.execute(command, ctx);

        return switch (result) {
            case Result.Success<ApplicationUpdated> s -> {
                // Fetch the updated application to return full details
                Application app = applicationRepo.findByIdOptional(id).orElse(null);
                yield Response.ok(toApplicationDetailResponse(app)).build();
            }
            case Result.Failure<ApplicationUpdated> f -> {
                if (f.error() instanceof UseCaseError.NotFoundError) {
                    yield Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", f.error().message()))
                        .build();
                }
                yield Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", f.error().message()))
                    .build();
            }
        };
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete an application",
        description = "Permanently deletes an application. The application must be deactivated first.")
    public Response deleteApplication(@PathParam("id") String id) {
        String principalId = auditContext.requirePrincipalId();
        ExecutionContext ctx = ExecutionContext.create(principalId);
        var command = new DeleteApplicationCommand(id);
        Result<ApplicationDeleted> result = deleteApplicationUseCase.execute(command, ctx);

        return switch (result) {
            case Result.Success<ApplicationDeleted> s ->
                Response.ok(Map.of("message", "Application deleted")).build();
            case Result.Failure<ApplicationDeleted> f -> {
                if (f.error() instanceof UseCaseError.NotFoundError) {
                    yield Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", f.error().message()))
                        .build();
                }
                yield Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", f.error().message()))
                    .build();
            }
        };
    }

    @POST
    @Path("/{id}/activate")
    @Operation(summary = "Activate an application")
    public Response activateApplication(@PathParam("id") String id) {
        String principalId = auditContext.requirePrincipalId();
        ExecutionContext ctx = ExecutionContext.create(principalId);
        var command = new ActivateApplicationCommand(id);
        Result<ApplicationActivated> result = activateApplicationUseCase.execute(command, ctx);

        return switch (result) {
            case Result.Success<ApplicationActivated> s ->
                Response.ok(Map.of("message", "Application activated")).build();
            case Result.Failure<ApplicationActivated> f -> {
                if (f.error() instanceof UseCaseError.NotFoundError) {
                    yield Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", f.error().message()))
                        .build();
                }
                yield Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", f.error().message()))
                    .build();
            }
        };
    }

    @POST
    @Path("/{id}/deactivate")
    @Operation(summary = "Deactivate an application")
    public Response deactivateApplication(@PathParam("id") String id) {
        String principalId = auditContext.requirePrincipalId();
        ExecutionContext ctx = ExecutionContext.create(principalId);
        var command = new DeactivateApplicationCommand(id);
        Result<ApplicationDeactivated> result = deactivateApplicationUseCase.execute(command, ctx);

        return switch (result) {
            case Result.Success<ApplicationDeactivated> s ->
                Response.ok(Map.of("message", "Application deactivated")).build();
            case Result.Failure<ApplicationDeactivated> f -> {
                if (f.error() instanceof UseCaseError.NotFoundError) {
                    yield Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", f.error().message()))
                        .build();
                }
                yield Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", f.error().message()))
                    .build();
            }
        };
    }

    @POST
    @Path("/{id}/provision-service-account")
    @Operation(summary = "Provision a service account for an existing application",
        description = "Creates a service account and OAuth client for an application that doesn't have one. " +
            "The client secret is only returned once and cannot be retrieved later.")
    public Response provisionServiceAccount(@PathParam("id") String id) {
        String principalId = auditContext.requirePrincipalId();
        ExecutionContext ctx = ExecutionContext.create(principalId);
        var command = new ProvisionServiceAccountCommand(id);
        var provisionResult = provisionServiceAccountUseCase.execute(command, ctx);

        if (provisionResult.isSuccess()) {
            return Response.ok(Map.of(
                "message", "Service account provisioned",
                "serviceAccount", Map.of(
                    "principalId", provisionResult.principal().id,
                    "name", provisionResult.principal().name,
                    "oauthClient", Map.of(
                        "id", provisionResult.oauthClient().id,
                        "clientId", provisionResult.oauthClient().clientId,
                        "clientSecret", provisionResult.clientSecret()  // Only available now!
                    )
                )
            )).build();
        } else {
            var error = provisionResult.error();
            if (error instanceof UseCaseError.NotFoundError) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", error.message()))
                    .build();
            }
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", error.message()))
                .build();
        }
    }

    // ========================================================================
    // Client Configuration
    // ========================================================================

    @GET
    @Path("/{id}/clients")
    @Operation(summary = "Get client configurations for an application")
    public Response getClientConfigs(@PathParam("id") String id) {
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
            @PathParam("id") String applicationId,
            @PathParam("clientId") String clientId,
            ClientConfigRequest request) {

        String principalId = auditContext.requirePrincipalId();
        ExecutionContext ctx = ExecutionContext.create(principalId);

        boolean enabled = request.enabled != null ? request.enabled : true;

        if (enabled) {
            var command = new EnableApplicationForClientCommand(
                applicationId,
                clientId,
                request.baseUrlOverride,
                request.config
            );
            Result<ApplicationEnabledForClient> result = applicationService.enableForClient(ctx, command);

            return switch (result) {
                case Result.Success<ApplicationEnabledForClient> s -> {
                    ApplicationClientConfig config = applicationService.getConfigsForApplication(applicationId)
                        .stream()
                        .filter(c -> c.clientId.equals(clientId))
                        .findFirst()
                        .orElse(null);
                    yield Response.ok(toClientConfigResponse(config)).build();
                }
                case Result.Failure<ApplicationEnabledForClient> f -> {
                    if (f.error() instanceof UseCaseError.NotFoundError) {
                        yield Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", f.error().message()))
                            .build();
                    }
                    yield Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", f.error().message()))
                        .build();
                }
            };
        } else {
            var command = new DisableApplicationForClientCommand(applicationId, clientId);
            Result<ApplicationDisabledForClient> result = applicationService.disableForClient(ctx, command);

            return switch (result) {
                case Result.Success<ApplicationDisabledForClient> s -> {
                    ApplicationClientConfig config = applicationService.getConfigsForApplication(applicationId)
                        .stream()
                        .filter(c -> c.clientId.equals(clientId))
                        .findFirst()
                        .orElse(null);
                    yield Response.ok(toClientConfigResponse(config)).build();
                }
                case Result.Failure<ApplicationDisabledForClient> f -> {
                    if (f.error() instanceof UseCaseError.NotFoundError) {
                        yield Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", f.error().message()))
                            .build();
                    }
                    yield Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", f.error().message()))
                        .build();
                }
            };
        }
    }

    @POST
    @Path("/{id}/clients/{clientId}/enable")
    @Operation(summary = "Enable application for a client")
    public Response enableForClient(
            @PathParam("id") String applicationId,
            @PathParam("clientId") String clientId) {

        String principalId = auditContext.requirePrincipalId();
        ExecutionContext ctx = ExecutionContext.create(principalId);
        var command = new EnableApplicationForClientCommand(applicationId, clientId, null);
        Result<ApplicationEnabledForClient> result = applicationService.enableForClient(ctx, command);

        return switch (result) {
            case Result.Success<ApplicationEnabledForClient> s ->
                Response.ok(Map.of("message", "Application enabled for client")).build();
            case Result.Failure<ApplicationEnabledForClient> f -> {
                if (f.error() instanceof UseCaseError.NotFoundError) {
                    yield Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", f.error().message()))
                        .build();
                }
                yield Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", f.error().message()))
                    .build();
            }
        };
    }

    @POST
    @Path("/{id}/clients/{clientId}/disable")
    @Operation(summary = "Disable application for a client")
    public Response disableForClient(
            @PathParam("id") String applicationId,
            @PathParam("clientId") String clientId) {

        String principalId = auditContext.requirePrincipalId();
        ExecutionContext ctx = ExecutionContext.create(principalId);
        var command = new DisableApplicationForClientCommand(applicationId, clientId);
        Result<ApplicationDisabledForClient> result = applicationService.disableForClient(ctx, command);

        return switch (result) {
            case Result.Success<ApplicationDisabledForClient> s ->
                Response.ok(Map.of("message", "Application disabled for client")).build();
            case Result.Failure<ApplicationDisabledForClient> f -> {
                if (f.error() instanceof UseCaseError.NotFoundError) {
                    yield Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", f.error().message()))
                        .build();
                }
                yield Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", f.error().message()))
                    .build();
            }
        };
    }

    // ========================================================================
    // Roles for Application
    // ========================================================================

    @GET
    @Path("/{id}/roles")
    @Operation(summary = "Get all roles defined for this application")
    public Response getApplicationRoles(@PathParam("id") String id) {
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
        public String type;  // "APPLICATION" or "INTEGRATION", defaults to APPLICATION
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
        var result = new java.util.HashMap<String, Object>();
        result.put("id", app.id);
        result.put("type", app.type != null ? app.type.name() : "APPLICATION");
        result.put("code", app.code);
        result.put("name", app.name);
        result.put("description", app.description);
        result.put("defaultBaseUrl", app.defaultBaseUrl);
        result.put("iconUrl", app.iconUrl);
        result.put("serviceAccountId", app.serviceAccountId);
        result.put("serviceAccountPrincipalId", app.serviceAccountPrincipalId);
        result.put("active", app.active);
        result.put("createdAt", app.createdAt);
        result.put("updatedAt", app.updatedAt);
        return result;
    }

    private Map<String, Object> toApplicationDetailResponse(Application app) {
        var result = new java.util.HashMap<String, Object>();
        result.put("id", app.id);
        result.put("type", app.type != null ? app.type.name() : "APPLICATION");
        result.put("code", app.code);
        result.put("name", app.name);
        result.put("description", app.description);
        result.put("iconUrl", app.iconUrl);
        result.put("defaultBaseUrl", app.defaultBaseUrl);
        result.put("serviceAccountId", app.serviceAccountId);
        result.put("serviceAccountPrincipalId", app.serviceAccountPrincipalId);
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
