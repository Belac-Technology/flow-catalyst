package tech.flowcatalyst.platform.admin;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.platform.application.Application;
import tech.flowcatalyst.platform.application.ApplicationRepository;
import tech.flowcatalyst.platform.audit.AuditContext;
import tech.flowcatalyst.platform.authentication.EmbeddedModeOnly;
import tech.flowcatalyst.platform.authentication.JwtKeyService;
import tech.flowcatalyst.platform.authorization.*;
import tech.flowcatalyst.platform.authorization.operations.CreateRole;
import tech.flowcatalyst.platform.authorization.operations.DeleteRole;
import tech.flowcatalyst.platform.authorization.operations.UpdateRole;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Admin API for managing roles and viewing permissions.
 *
 * Roles can come from three sources:
 * - CODE: Defined in Java @Role classes (read-only, synced to DB at startup)
 * - DATABASE: Created by administrators through this API
 * - SDK: Registered by external applications via the SDK API
 *
 * Permissions are code-first (defined in Java code) and cannot be created via API.
 * External applications can register their own permissions via SDK.
 */
@Path("/api/admin/platform/roles")
@Tag(name = "Role Admin", description = "Manage roles and view permissions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@EmbeddedModeOnly
public class RoleAdminResource {

    @Inject
    PermissionRegistry permissionRegistry;

    @Inject
    RoleService roleService;

    @Inject
    RoleAdminService roleAdminService;

    @Inject
    ApplicationRepository applicationRepository;

    @Inject
    JwtKeyService jwtKeyService;

    @Inject
    AuditContext auditContext;

    // ==================== Roles ====================

    /**
     * List all available roles from the database.
     */
    @GET
    @Operation(summary = "List all available roles",
        description = "Returns all roles from the database. Filter by application code or source.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "List of roles",
            content = @Content(schema = @Schema(implementation = RoleListResponse.class))),
        @APIResponse(responseCode = "401", description = "Not authenticated")
    })
    public Response listRoles(
            @QueryParam("application") String application,
            @QueryParam("source") String source,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        List<AuthRole> roles;
        if (application != null && !application.isBlank()) {
            roles = roleService.getRolesForApplication(application);
        } else {
            roles = roleService.getAllRoles();
        }

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
            .sorted((a, b) -> a.name().compareTo(b.name()))
            .toList();

        return Response.ok(new RoleListResponse(dtos, dtos.size())).build();
    }

    /**
     * Get a specific role by name.
     */
    @GET
    @Path("/{roleName}")
    @Operation(summary = "Get role details by name")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Role details with permissions",
            content = @Content(schema = @Schema(implementation = RoleDto.class))),
        @APIResponse(responseCode = "404", description = "Role not found")
    })
    public Response getRole(
            @PathParam("roleName") String roleName,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        return roleService.getRoleByName(roleName)
            .map(role -> Response.ok(toRoleDto(role)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Role not found: " + roleName))
                .build());
    }

    /**
     * Create a new role for an application.
     * Role name will be auto-prefixed with the application code.
     */
    @POST
    @Operation(summary = "Create a new role",
        description = "Creates a new role with source=DATABASE. Role name is auto-prefixed with application code.")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Role created",
            content = @Content(schema = @Schema(implementation = RoleDto.class))),
        @APIResponse(responseCode = "400", description = "Invalid request"),
        @APIResponse(responseCode = "404", description = "Application not found"),
        @APIResponse(responseCode = "409", description = "Role already exists")
    })
    public Response createRole(
            CreateRoleRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        // Validate request
        if (request.applicationCode() == null || request.applicationCode().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("applicationCode is required"))
                .build();
        }
        if (request.name() == null || request.name().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("name is required"))
                .build();
        }

        // Find application
        Application app = applicationRepository.findByCode(request.applicationCode())
            .orElse(null);
        if (app == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Application not found: " + request.applicationCode()))
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
                AuthRole.RoleSource.DATABASE,
                request.clientManaged() != null ? request.clientManaged() : false
            ));

            return Response.status(Response.Status.CREATED)
                .entity(toRoleDto(role))
                .build();
        } catch (BadRequestException e) {
            return Response.status(Response.Status.CONFLICT)
                .entity(new ErrorResponse(e.getMessage()))
                .build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse(e.getMessage()))
                .build();
        }
    }

    /**
     * Update an existing role.
     * CODE-sourced roles can only have clientManaged flag updated.
     */
    @PUT
    @Path("/{roleName}")
    @Operation(summary = "Update a role",
        description = "Updates a role. CODE-sourced roles can only have clientManaged updated.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Role updated",
            content = @Content(schema = @Schema(implementation = RoleDto.class))),
        @APIResponse(responseCode = "404", description = "Role not found")
    })
    public Response updateRole(
            @PathParam("roleName") String roleName,
            UpdateRoleRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        try {
            // Set audit context before executing operation
            auditContext.setPrincipalId(principalId);

            AuthRole role = roleAdminService.execute(new UpdateRole(
                roleName,
                request.displayName(),
                request.description(),
                request.permissions(),
                request.clientManaged()
            ));

            return Response.ok(toRoleDto(role)).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse(e.getMessage()))
                .build();
        }
    }

    /**
     * Delete a role. Only DATABASE and SDK sourced roles can be deleted.
     */
    @DELETE
    @Path("/{roleName}")
    @Operation(summary = "Delete a role",
        description = "Deletes a role. Only DATABASE and SDK sourced roles can be deleted.")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Role deleted"),
        @APIResponse(responseCode = "400", description = "Cannot delete CODE-defined role"),
        @APIResponse(responseCode = "404", description = "Role not found")
    })
    public Response deleteRole(
            @PathParam("roleName") String roleName,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        try {
            // Set audit context before executing operation
            auditContext.setPrincipalId(principalId);

            roleAdminService.execute(new DeleteRole(roleName));
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

    // ==================== Permissions ====================

    /**
     * List all available permissions.
     * Includes both code-defined and database permissions.
     */
    @GET
    @Path("/permissions")
    @Operation(summary = "List all available permissions",
        description = "Returns all permissions from code and database.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "List of permissions",
            content = @Content(schema = @Schema(implementation = PermissionListResponse.class))),
        @APIResponse(responseCode = "401", description = "Not authenticated")
    })
    public Response listPermissions(
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        Collection<PermissionDefinition> permissions = permissionRegistry.getAllPermissions();

        List<PermissionDto> dtos = permissions.stream()
            .map(this::toPermissionDto)
            .sorted((a, b) -> a.permission().compareTo(b.permission()))
            .toList();

        return Response.ok(new PermissionListResponse(dtos, dtos.size())).build();
    }

    /**
     * Get a specific permission by string.
     */
    @GET
    @Path("/permissions/{permission}")
    @Operation(summary = "Get permission details")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Permission details"),
        @APIResponse(responseCode = "404", description = "Permission not found")
    })
    public Response getPermission(
            @PathParam("permission") String permission,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        return permissionRegistry.getPermission(permission)
            .map(perm -> Response.ok(toPermissionDto(perm)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Permission not found: " + permission))
                .build());
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
            role.name,
            role.applicationCode,
            role.displayName,
            role.getShortName(),
            role.description,
            role.permissions,
            role.source.name(),
            role.clientManaged,
            role.createdAt,
            role.updatedAt
        );
    }

    private PermissionDto toPermissionDto(PermissionDefinition perm) {
        return new PermissionDto(
            perm.toPermissionString(),
            perm.subdomain(),
            perm.context(),
            perm.aggregate(),
            perm.action(),
            perm.description()
        );
    }

    // ==================== DTOs ====================

    public record RoleDto(
        String name,
        String applicationCode,
        String displayName,
        String shortName,
        String description,
        Set<String> permissions,
        String source,
        boolean clientManaged,
        java.time.Instant createdAt,
        java.time.Instant updatedAt
    ) {}

    public record RoleListResponse(
        List<RoleDto> roles,
        int total
    ) {}

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

    public record PermissionDto(
        String permission,
        String subdomain,
        String context,
        String aggregate,
        String action,
        String description
    ) {}

    public record PermissionListResponse(
        List<PermissionDto> permissions,
        int total
    ) {}

    public record ErrorResponse(
        String error
    ) {}
}
