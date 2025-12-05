package tech.flowcatalyst.platform.bff;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.application.Application;
import tech.flowcatalyst.platform.application.ApplicationRepository;
import tech.flowcatalyst.platform.audit.AuditContext;
import tech.flowcatalyst.platform.authentication.JwtKeyService;
import tech.flowcatalyst.platform.authorization.AuthRole;
import tech.flowcatalyst.platform.authorization.RoleAdminService;
import tech.flowcatalyst.platform.authorization.operations.CreateRole;
import tech.flowcatalyst.platform.authorization.operations.DeleteRole;
import tech.flowcatalyst.platform.authorization.operations.UpdateRole;

import java.util.List;
import java.util.Set;

/**
 * BFF (Backend For Frontend) endpoints for Roles.
 * Returns IDs as strings to preserve precision for JavaScript clients.
 */
@Path("/bff/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "BFF - Roles", description = "Web-optimized role endpoints with string IDs")
public class RoleBffResource {

    @Inject
    RoleAdminService roleAdminService;

    @Inject
    ApplicationRepository applicationRepository;

    @Inject
    JwtKeyService jwtKeyService;

    @Inject
    AuditContext auditContext;

    @GET
    @Operation(summary = "List all roles (BFF)")
    public Response listRoles(
        @QueryParam("application") String applicationCode,
        @QueryParam("source") String source,
        @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
        @HeaderParam("Authorization") String authHeader
    ) {
        List<AuthRole> roles;

        if (applicationCode != null && !applicationCode.isBlank()) {
            roles = roleAdminService.findByApplicationCode(applicationCode);
        } else {
            roles = roleAdminService.findAll();
        }

        // Filter by source if provided
        if (source != null && !source.isBlank()) {
            try {
                AuthRole.RoleSource sourceEnum = AuthRole.RoleSource.valueOf(source.toUpperCase());
                roles = roles.stream()
                    .filter(r -> r.source == sourceEnum)
                    .toList();
            } catch (IllegalArgumentException e) {
                return Response.status(400)
                    .entity(new ErrorResponse("Invalid source. Must be CODE, DATABASE, or SDK"))
                    .build();
            }
        }

        List<BffRoleResponse> responses = roles.stream()
            .map(BffRoleResponse::from)
            .toList();

        return Response.ok(new BffRoleListResponse(responses, responses.size())).build();
    }

    @GET
    @Path("/{roleName}")
    @Operation(summary = "Get role by name (BFF)")
    public Response getRole(@PathParam("roleName") String roleName) {
        return roleAdminService.findByName(roleName)
            .map(role -> Response.ok(BffRoleResponse.from(role)).build())
            .orElse(Response.status(404)
                .entity(new ErrorResponse("Role not found: " + roleName))
                .build());
    }

    @GET
    @Path("/filters/applications")
    @Operation(summary = "Get applications for role filter")
    public Response getApplications() {
        List<Application> apps = applicationRepository.listAll();
        List<BffApplicationOption> options = apps.stream()
            .filter(a -> a.active)
            .map(a -> new BffApplicationOption(a.id.toString(), a.code, a.name))
            .toList();
        return Response.ok(new BffApplicationOptionsResponse(options)).build();
    }

    @POST
    @Operation(summary = "Create a new role (BFF)")
    public Response createRole(
        CreateRoleRequest request,
        @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
        @HeaderParam("Authorization") String authHeader
    ) {
        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(401).entity(new ErrorResponse("Not authenticated")).build();
        }

        if (request.applicationCode() == null || request.applicationCode().isBlank()) {
            return Response.status(400).entity(new ErrorResponse("applicationCode is required")).build();
        }
        if (request.name() == null || request.name().isBlank()) {
            return Response.status(400).entity(new ErrorResponse("name is required")).build();
        }

        Application app = applicationRepository.findByCode(request.applicationCode()).orElse(null);
        if (app == null) {
            return Response.status(404)
                .entity(new ErrorResponse("Application not found: " + request.applicationCode()))
                .build();
        }

        try {
            auditContext.setPrincipalId(principalId);

            AuthRole role = roleAdminService.execute(new CreateRole(
                app.id,
                request.name(),
                request.displayName(),
                request.description(),
                request.permissions(),
                AuthRole.RoleSource.DATABASE,
                request.clientManaged() != null ? request.clientManaged() : false
            ));

            return Response.status(201).entity(BffRoleResponse.from(role)).build();
        } catch (BadRequestException e) {
            return Response.status(409).entity(new ErrorResponse(e.getMessage())).build();
        } catch (NotFoundException e) {
            return Response.status(404).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{roleName}")
    @Operation(summary = "Update a role (BFF)")
    public Response updateRole(
        @PathParam("roleName") String roleName,
        UpdateRoleRequest request,
        @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
        @HeaderParam("Authorization") String authHeader
    ) {
        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(401).entity(new ErrorResponse("Not authenticated")).build();
        }

        try {
            auditContext.setPrincipalId(principalId);

            AuthRole role = roleAdminService.execute(new UpdateRole(
                roleName,
                request.displayName(),
                request.description(),
                request.permissions(),
                request.clientManaged()
            ));

            return Response.ok(BffRoleResponse.from(role)).build();
        } catch (NotFoundException e) {
            return Response.status(404).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{roleName}")
    @Operation(summary = "Delete a role (BFF)")
    public Response deleteRole(
        @PathParam("roleName") String roleName,
        @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
        @HeaderParam("Authorization") String authHeader
    ) {
        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(401).entity(new ErrorResponse("Not authenticated")).build();
        }

        try {
            auditContext.setPrincipalId(principalId);
            roleAdminService.execute(new DeleteRole(roleName));
            return Response.noContent().build();
        } catch (NotFoundException e) {
            return Response.status(404).entity(new ErrorResponse(e.getMessage())).build();
        } catch (BadRequestException e) {
            return Response.status(400).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

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

    // ========================================================================
    // BFF DTOs - IDs as Strings for JavaScript precision
    // ========================================================================

    public record BffRoleListResponse(List<BffRoleResponse> items, int total) {}

    public record BffRoleResponse(
        String id,
        String name,
        String shortName,
        String displayName,
        String description,
        Set<String> permissions,
        String applicationCode,
        String source,
        boolean clientManaged,
        String createdAt,
        String updatedAt
    ) {
        public static BffRoleResponse from(AuthRole role) {
            return new BffRoleResponse(
                role.id != null ? role.id.toString() : null,
                role.name,
                role.getShortName(),
                role.displayName,
                role.description,
                role.permissions,
                role.application != null ? role.application.code : null,
                role.source != null ? role.source.name() : null,
                role.clientManaged,
                role.createdAt != null ? role.createdAt.toString() : null,
                role.updatedAt != null ? role.updatedAt.toString() : null
            );
        }
    }

    public record BffApplicationOption(String id, String code, String name) {}
    public record BffApplicationOptionsResponse(List<BffApplicationOption> options) {}

    public record CreateRoleRequest(
        String applicationCode,
        String name,
        String displayName,
        String description,
        Set<String> permissions,
        Boolean clientManaged
    ) {}

    public record UpdateRoleRequest(
        String displayName,
        String description,
        Set<String> permissions,
        Boolean clientManaged
    ) {}

    public record ErrorResponse(String error) {}
}
