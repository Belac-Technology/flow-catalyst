package tech.flowcatalyst.platform.application;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.audit.AuditContext;
import tech.flowcatalyst.platform.authentication.JwtKeyService;
import tech.flowcatalyst.platform.authorization.AuthRole;
import tech.flowcatalyst.platform.authorization.RoleAdminService;
import tech.flowcatalyst.platform.authorization.RoleService;
import tech.flowcatalyst.platform.authorization.operations.CreateRole;
import tech.flowcatalyst.platform.authorization.operations.DeleteRole;
import tech.flowcatalyst.platform.authorization.operations.SyncRoles;

import java.util.List;
import java.util.Set;

/**
 * SDK API for external applications to manage their roles.
 *
 * External applications using the FlowCatalyst SDK can:
 * - Register roles for assignment to users
 * - Sync roles (bulk create/update/delete)
 * - Remove roles
 *
 * Role names are auto-prefixed with the application code.
 * For example, if app code is "myapp" and role name is "admin",
 * the full role name will be "myapp:admin".
 */
@Path("/api/applications/{appCode}/roles")
@Tag(name = "Application Roles SDK", description = "SDK API for external applications to manage their roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApplicationRoleResource {

    @Inject
    ApplicationRepository applicationRepository;

    @Inject
    RoleService roleService;

    @Inject
    RoleAdminService roleAdminService;

    @Inject
    JwtKeyService jwtKeyService;

    @Inject
    AuditContext auditContext;

    /**
     * List all roles for an application.
     */
    @GET
    @Operation(summary = "List application roles",
        description = "Returns all roles registered for this application.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "List of roles",
            content = @Content(schema = @Schema(implementation = RoleListResponse.class))),
        @APIResponse(responseCode = "404", description = "Application not found")
    })
    public Response listRoles(
            @PathParam("appCode") String appCode,
            @QueryParam("source") String source,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        Application app = applicationRepository.findByCode(appCode).orElse(null);
        if (app == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Application not found: " + appCode))
                .build();
        }

        List<AuthRole> roles = roleService.getRolesForApplication(appCode);

        // Filter by source if provided
        if (source != null && !source.isBlank()) {
            try {
                AuthRole.RoleSource sourceEnum = AuthRole.RoleSource.valueOf(source.toUpperCase());
                roles = roles.stream()
                    .filter(r -> r.source == sourceEnum)
                    .toList();
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Invalid source. Must be CODE, DATABASE, or SDK"))
                    .build();
            }
        }

        List<RoleDto> dtos = roles.stream()
            .map(this::toRoleDto)
            .toList();

        return Response.ok(new RoleListResponse(dtos, dtos.size())).build();
    }

    /**
     * Sync roles from an external application.
     * Creates new roles, updates existing SDK roles, and optionally removes unlisted SDK roles.
     *
     * This is the primary method for SDK integration - applications call this endpoint
     * on startup or when their role definitions change.
     */
    @POST
    @Path("/sync")
    @Operation(summary = "Sync application roles",
        description = "Bulk sync roles from an external application. " +
                      "Creates new roles, updates existing SDK roles. " +
                      "Set removeUnlisted=true to remove SDK roles not in the sync list.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Sync complete",
            content = @Content(schema = @Schema(implementation = SyncResponse.class))),
        @APIResponse(responseCode = "404", description = "Application not found")
    })
    public Response syncRoles(
            @PathParam("appCode") String appCode,
            @QueryParam("removeUnlisted") @DefaultValue("false") boolean removeUnlisted,
            SyncRolesRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        Application app = applicationRepository.findByCode(appCode).orElse(null);
        if (app == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Application not found: " + appCode))
                .build();
        }

        if (request.roles() == null || request.roles().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("roles list is required"))
                .build();
        }

        // Set audit context before executing operation
        auditContext.setPrincipalId(principalId);

        // Convert request to internal format
        List<SyncRoles.SyncRoleItem> roleItems = request.roles().stream()
            .map(r -> new SyncRoles.SyncRoleItem(
                r.name(),
                r.displayName(),
                r.description(),
                r.permissions(),
                r.clientManaged() != null ? r.clientManaged() : false
            ))
            .toList();

        roleAdminService.execute(new SyncRoles(app.id, roleItems, removeUnlisted));

        // Return updated role list
        List<AuthRole> roles = roleService.getRolesForApplication(appCode);
        List<RoleDto> dtos = roles.stream()
            .filter(r -> r.source == AuthRole.RoleSource.SDK)
            .map(this::toRoleDto)
            .toList();

        return Response.ok(new SyncResponse(dtos.size(), dtos)).build();
    }

    /**
     * Create a single role for an application.
     */
    @POST
    @Operation(summary = "Create application role",
        description = "Creates a single role for the application with source=SDK.")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Role created",
            content = @Content(schema = @Schema(implementation = RoleDto.class))),
        @APIResponse(responseCode = "404", description = "Application not found"),
        @APIResponse(responseCode = "409", description = "Role already exists")
    })
    public Response createRole(
            @PathParam("appCode") String appCode,
            CreateRoleRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        Application app = applicationRepository.findByCode(appCode).orElse(null);
        if (app == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Application not found: " + appCode))
                .build();
        }

        if (request.name() == null || request.name().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("name is required"))
                .build();
        }

        try {
            // Set audit context before executing operation
            auditContext.setPrincipalId(principalId);

            AuthRole role = roleAdminService.execute(new CreateRole(
                app.id,
                request.name(),
                request.displayName(),
                request.description(),
                request.permissions(),
                AuthRole.RoleSource.SDK,
                request.clientManaged() != null ? request.clientManaged() : false
            ));

            return Response.status(Response.Status.CREATED)
                .entity(toRoleDto(role))
                .build();
        } catch (BadRequestException e) {
            return Response.status(Response.Status.CONFLICT)
                .entity(new ErrorResponse(e.getMessage()))
                .build();
        }
    }

    /**
     * Delete an SDK role.
     * Only SDK-sourced roles can be deleted via this endpoint.
     */
    @DELETE
    @Path("/{roleName}")
    @Operation(summary = "Delete application role",
        description = "Deletes an SDK-sourced role. Cannot delete CODE or DATABASE sourced roles.")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Role deleted"),
        @APIResponse(responseCode = "400", description = "Cannot delete non-SDK role"),
        @APIResponse(responseCode = "404", description = "Role not found")
    })
    public Response deleteRole(
            @PathParam("appCode") String appCode,
            @PathParam("roleName") String roleName,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        // Construct full role name
        String fullRoleName = appCode + ":" + roleName;

        // Verify the role belongs to this application
        var roleOpt = roleService.getRoleByName(fullRoleName);
        if (roleOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Role not found: " + fullRoleName))
                .build();
        }

        AuthRole role = roleOpt.get();
        if (role.source != AuthRole.RoleSource.SDK) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("Cannot delete non-SDK role via SDK API. Source: " + role.source))
                .build();
        }

        try {
            // Set audit context before executing operation
            auditContext.setPrincipalId(principalId);

            roleAdminService.execute(new DeleteRole(fullRoleName));
            return Response.noContent().build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse(e.getMessage()))
                .build();
        } catch (BadRequestException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(e.getMessage()))
                .build();
        }
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

    private RoleDto toRoleDto(AuthRole role) {
        return new RoleDto(
            role.getShortName(),
            role.name,
            role.displayName,
            role.description,
            role.permissions,
            role.source.name(),
            role.clientManaged
        );
    }

    // ==================== DTOs ====================

    public record RoleDto(
        String name,
        String fullName,
        String displayName,
        String description,
        Set<String> permissions,
        String source,
        boolean clientManaged
    ) {}

    public record RoleListResponse(
        List<RoleDto> roles,
        int total
    ) {}

    public record CreateRoleRequest(
        String name,
        String displayName,
        String description,
        Set<String> permissions,
        Boolean clientManaged
    ) {}

    public record SyncRolesRequest(
        List<SyncRoleItem> roles
    ) {}

    public record SyncRoleItem(
        String name,
        String displayName,
        String description,
        Set<String> permissions,
        Boolean clientManaged
    ) {}

    public record SyncResponse(
        int syncedCount,
        List<RoleDto> roles
    ) {}

    public record ErrorResponse(
        String error
    ) {}
}
