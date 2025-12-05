package tech.flowcatalyst.platform.admin;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
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
import tech.flowcatalyst.platform.authentication.IdpType;
import tech.flowcatalyst.platform.authentication.JwtKeyService;
import tech.flowcatalyst.platform.authorization.PrincipalRole;
import tech.flowcatalyst.platform.authorization.RoleService;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalRepository;
import tech.flowcatalyst.platform.principal.PrincipalType;
import tech.flowcatalyst.platform.principal.UserService;
import tech.flowcatalyst.platform.client.ClientAccessGrant;
import tech.flowcatalyst.platform.client.ClientAccessGrantRepository;
import tech.flowcatalyst.platform.client.ClientService;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin API for principal (user/service account) management.
 *
 * Provides CRUD operations for principals including:
 * - Create, read, update users
 * - Activate/deactivate principals
 * - Password management (reset)
 * - Role assignments
 * - Client access grants
 *
 * All operations require admin-level permissions.
 */
@Path("/api/admin/platform/principals")
@Tag(name = "Principal Admin", description = "Administrative operations for user and service account management")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@EmbeddedModeOnly
public class PrincipalAdminResource {

    private static final Logger LOG = Logger.getLogger(PrincipalAdminResource.class);

    @Inject
    UserService userService;

    @Inject
    RoleService roleService;

    @Inject
    ClientService clientService;

    @Inject
    PrincipalRepository principalRepo;

    @Inject
    ClientAccessGrantRepository grantRepo;

    @Inject
    JwtKeyService jwtKeyService;

    // ==================== CRUD Operations ====================

    /**
     * List all principals with optional filters.
     */
    @GET
    @Operation(summary = "List principals", description = "List users and service accounts with optional filters")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "List of principals",
            content = @Content(schema = @Schema(implementation = PrincipalListResponse.class))),
        @APIResponse(responseCode = "401", description = "Not authenticated")
    })
    public Response listPrincipals(
            @QueryParam("clientId") @Parameter(description = "Filter by client ID") Long clientId,
            @QueryParam("type") @Parameter(description = "Filter by type (USER/SERVICE)") PrincipalType type,
            @QueryParam("active") @Parameter(description = "Filter by active status") Boolean active,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        List<Principal> principals;

        // Build query based on filters
        if (clientId != null && type != null && active != null) {
            principals = principalRepo.find("clientId = ?1 AND type = ?2 AND active = ?3",
                clientId, type, active).list();
        } else if (clientId != null && type != null) {
            principals = principalRepo.find("clientId = ?1 AND type = ?2", clientId, type).list();
        } else if (clientId != null && active != null) {
            principals = principalRepo.find("clientId = ?1 AND active = ?2", clientId, active).list();
        } else if (clientId != null) {
            principals = principalRepo.find("clientId", clientId).list();
        } else if (type != null) {
            principals = principalRepo.find("type", type).list();
        } else if (active != null) {
            principals = principalRepo.find("active", active).list();
        } else {
            principals = principalRepo.listAll();
        }

        List<PrincipalDto> dtos = principals.stream()
            .map(this::toDto)
            .toList();

        return Response.ok(new PrincipalListResponse(dtos, dtos.size())).build();
    }

    /**
     * Get a specific principal by ID.
     */
    @GET
    @Path("/{id}")
    @Operation(summary = "Get principal by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Principal details",
            content = @Content(schema = @Schema(implementation = PrincipalDetailDto.class))),
        @APIResponse(responseCode = "404", description = "Principal not found")
    })
    public Response getPrincipal(
            @PathParam("id") Long id,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long principalId = extractPrincipalId(sessionToken, authHeader);
        if (principalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        return principalRepo.findByIdOptional(id)
            .map(principal -> {
                // Include roles and client access grants
                Set<String> roles = roleService.findRoleNamesByPrincipal(id);
                List<ClientAccessGrant> grants = grantRepo.findByPrincipalId(id);
                Set<Long> grantedClientIds = grants.stream()
                    .map(g -> g.clientId)
                    .collect(Collectors.toSet());

                return Response.ok(toDetailDto(principal, roles, grantedClientIds)).build();
            })
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Principal not found"))
                .build());
    }

    /**
     * Create a new internal user.
     */
    @POST
    @Path("/users")
    @Operation(summary = "Create a new internal user")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "User created",
            content = @Content(schema = @Schema(implementation = PrincipalDto.class))),
        @APIResponse(responseCode = "400", description = "Invalid request or email already exists")
    })
    public Response createUser(
            @Valid CreateUserRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader,
            @Context UriInfo uriInfo) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        try {
            Principal principal = userService.createInternalUser(
                request.email(),
                request.password(),
                request.name(),
                request.clientId()
            );

            LOG.infof("User created: %s by principal %d", request.email(), adminPrincipalId);

            return Response.status(Response.Status.CREATED)
                .entity(toDto(principal))
                .location(uriInfo.getBaseUriBuilder()
                    .path(PrincipalAdminResource.class)
                    .path(String.valueOf(principal.id))
                    .build())
                .build();
        } catch (BadRequestException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(e.getMessage()))
                .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(e.getMessage()))
                .build();
        }
    }

    /**
     * Update a principal's name.
     */
    @PUT
    @Path("/{id}")
    @Operation(summary = "Update principal details")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Principal updated"),
        @APIResponse(responseCode = "404", description = "Principal not found")
    })
    public Response updatePrincipal(
            @PathParam("id") Long id,
            @Valid UpdatePrincipalRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        try {
            Principal principal = userService.updateUser(id, request.name());
            LOG.infof("Principal %d updated by principal %d", id, adminPrincipalId);
            return Response.ok(toDto(principal)).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Principal not found"))
                .build();
        }
    }

    // ==================== Status Management ====================

    /**
     * Activate a principal.
     */
    @POST
    @Path("/{id}/activate")
    @Operation(summary = "Activate a principal")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Principal activated"),
        @APIResponse(responseCode = "404", description = "Principal not found")
    })
    public Response activatePrincipal(
            @PathParam("id") Long id,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        try {
            userService.activateUser(id);
            LOG.infof("Principal %d activated by principal %d", id, adminPrincipalId);
            return Response.ok(new StatusResponse("Principal activated")).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Principal not found"))
                .build();
        }
    }

    /**
     * Deactivate a principal.
     */
    @POST
    @Path("/{id}/deactivate")
    @Operation(summary = "Deactivate a principal")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Principal deactivated"),
        @APIResponse(responseCode = "404", description = "Principal not found")
    })
    public Response deactivatePrincipal(
            @PathParam("id") Long id,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        try {
            userService.deactivateUser(id);
            LOG.infof("Principal %d deactivated by principal %d", id, adminPrincipalId);
            return Response.ok(new StatusResponse("Principal deactivated")).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Principal not found"))
                .build();
        }
    }

    // ==================== Password Management ====================

    /**
     * Reset a user's password (admin action).
     */
    @POST
    @Path("/{id}/reset-password")
    @Operation(summary = "Reset user password")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Password reset"),
        @APIResponse(responseCode = "400", description = "User is not internal auth or password doesn't meet requirements"),
        @APIResponse(responseCode = "404", description = "User not found")
    })
    public Response resetPassword(
            @PathParam("id") Long id,
            @Valid ResetPasswordRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        try {
            userService.resetPassword(id, request.newPassword());
            LOG.infof("Password reset for principal %d by principal %d", id, adminPrincipalId);
            return Response.ok(new StatusResponse("Password reset successfully")).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("User not found"))
                .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(e.getMessage()))
                .build();
        }
    }

    // ==================== Role Management ====================

    /**
     * Get roles assigned to a principal.
     */
    @GET
    @Path("/{id}/roles")
    @Operation(summary = "Get principal's roles")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "List of roles"),
        @APIResponse(responseCode = "404", description = "Principal not found")
    })
    public Response getPrincipalRoles(
            @PathParam("id") Long id,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        if (!principalRepo.findByIdOptional(id).isPresent()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Principal not found"))
                .build();
        }

        List<PrincipalRole> assignments = roleService.findAssignmentsByPrincipal(id);
        List<RoleAssignmentDto> dtos = assignments.stream()
            .map(pr -> new RoleAssignmentDto(pr.id, pr.roleName, pr.assignmentSource, pr.assignedAt))
            .toList();

        return Response.ok(new RoleListResponse(dtos)).build();
    }

    /**
     * Assign a role to a principal.
     */
    @POST
    @Path("/{id}/roles")
    @Operation(summary = "Assign role to principal")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Role assigned"),
        @APIResponse(responseCode = "400", description = "Role already assigned or not defined"),
        @APIResponse(responseCode = "404", description = "Principal not found")
    })
    public Response assignRole(
            @PathParam("id") Long id,
            @Valid AssignRoleRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        try {
            PrincipalRole assignment = roleService.assignRole(id, request.roleName(), "MANUAL");
            LOG.infof("Role %s assigned to principal %d by principal %d",
                request.roleName(), id, adminPrincipalId);

            return Response.status(Response.Status.CREATED)
                .entity(new RoleAssignmentDto(
                    assignment.id,
                    assignment.roleName,
                    assignment.assignmentSource,
                    assignment.assignedAt
                ))
                .build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Principal not found"))
                .build();
        } catch (BadRequestException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(e.getMessage()))
                .build();
        }
    }

    /**
     * Remove a role from a principal.
     */
    @DELETE
    @Path("/{id}/roles/{roleName}")
    @Operation(summary = "Remove role from principal")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Role removed"),
        @APIResponse(responseCode = "404", description = "Principal or role assignment not found")
    })
    public Response removeRole(
            @PathParam("id") Long id,
            @PathParam("roleName") String roleName,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        try {
            roleService.removeRole(id, roleName);
            LOG.infof("Role %s removed from principal %d by principal %d",
                roleName, id, adminPrincipalId);
            return Response.noContent().build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Role assignment not found"))
                .build();
        }
    }

    // ==================== Client Access Grants ====================

    /**
     * Get client access grants for a principal.
     */
    @GET
    @Path("/{id}/client-access")
    @Operation(summary = "Get principal's client access grants")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "List of client access grants"),
        @APIResponse(responseCode = "404", description = "Principal not found")
    })
    public Response getClientAccessGrants(
            @PathParam("id") Long id,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        if (!principalRepo.findByIdOptional(id).isPresent()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Principal not found"))
                .build();
        }

        List<ClientAccessGrant> grants = grantRepo.findByPrincipalId(id);
        List<ClientAccessGrantDto> dtos = grants.stream()
            .map(g -> new ClientAccessGrantDto(g.id, g.clientId, g.grantedAt, g.expiresAt))
            .toList();

        return Response.ok(new ClientAccessListResponse(dtos)).build();
    }

    /**
     * Grant client access to a principal.
     */
    @POST
    @Path("/{id}/client-access")
    @Operation(summary = "Grant client access to principal")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Access granted"),
        @APIResponse(responseCode = "400", description = "Grant already exists or principal belongs to client"),
        @APIResponse(responseCode = "404", description = "Principal or client not found")
    })
    public Response grantClientAccess(
            @PathParam("id") Long id,
            @Valid GrantClientAccessRequest request,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        try {
            ClientAccessGrant grant = clientService.grantClientAccess(id, request.clientId());
            LOG.infof("Client access to %d granted to principal %d by principal %d",
                request.clientId(), id, adminPrincipalId);

            return Response.status(Response.Status.CREATED)
                .entity(new ClientAccessGrantDto(
                    grant.id,
                    grant.clientId,
                    grant.grantedAt,
                    grant.expiresAt
                ))
                .build();
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

    /**
     * Revoke client access from a principal.
     */
    @DELETE
    @Path("/{id}/client-access/{clientId}")
    @Operation(summary = "Revoke client access from principal")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Access revoked"),
        @APIResponse(responseCode = "404", description = "Grant not found")
    })
    public Response revokeClientAccess(
            @PathParam("id") Long id,
            @PathParam("clientId") Long clientId,
            @CookieParam("FLOWCATALYST_SESSION") String sessionToken,
            @HeaderParam("Authorization") String authHeader) {

        Long adminPrincipalId = extractPrincipalId(sessionToken, authHeader);
        if (adminPrincipalId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Not authenticated"))
                .build();
        }

        try {
            clientService.revokeClientAccess(id, clientId);
            LOG.infof("Client access to %d revoked from principal %d by principal %d",
                clientId, id, adminPrincipalId);
            return Response.noContent().build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Grant not found"))
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

    private PrincipalDto toDto(Principal principal) {
        String email = null;
        IdpType idpType = null;
        if (principal.userIdentity != null) {
            email = principal.userIdentity.email;
            idpType = principal.userIdentity.idpType;
        }

        return new PrincipalDto(
            principal.id,
            principal.type,
            principal.clientId,
            principal.name,
            principal.active,
            email,
            idpType,
            principal.createdAt,
            principal.updatedAt
        );
    }

    private PrincipalDetailDto toDetailDto(Principal principal, Set<String> roles, Set<Long> grantedClientIds) {
        String email = null;
        IdpType idpType = null;
        Instant lastLoginAt = null;
        if (principal.userIdentity != null) {
            email = principal.userIdentity.email;
            idpType = principal.userIdentity.idpType;
            lastLoginAt = principal.userIdentity.lastLoginAt;
        }

        return new PrincipalDetailDto(
            principal.id,
            principal.type,
            principal.clientId,
            principal.name,
            principal.active,
            email,
            idpType,
            lastLoginAt,
            roles,
            grantedClientIds,
            principal.createdAt,
            principal.updatedAt
        );
    }

    // ==================== DTOs ====================

    public record PrincipalDto(
        Long id,
        PrincipalType type,
        Long clientId,
        String name,
        boolean active,
        String email,
        IdpType idpType,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record PrincipalDetailDto(
        Long id,
        PrincipalType type,
        Long clientId,
        String name,
        boolean active,
        String email,
        IdpType idpType,
        Instant lastLoginAt,
        Set<String> roles,
        Set<Long> grantedClientIds,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record PrincipalListResponse(
        List<PrincipalDto> principals,
        int total
    ) {}

    public record CreateUserRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        @NotBlank(message = "Name is required")
        String name,

        Long clientId
    ) {}

    public record UpdatePrincipalRequest(
        @NotBlank(message = "Name is required")
        String name
    ) {}

    public record ResetPasswordRequest(
        @NotBlank(message = "New password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String newPassword
    ) {}

    public record AssignRoleRequest(
        @NotBlank(message = "Role name is required")
        String roleName
    ) {}

    public record GrantClientAccessRequest(
        @NotNull(message = "Client ID is required")
        Long clientId
    ) {}

    public record RoleAssignmentDto(
        Long id,
        String roleName,
        String assignmentSource,
        Instant assignedAt
    ) {}

    public record RoleListResponse(
        List<RoleAssignmentDto> roles
    ) {}

    public record ClientAccessGrantDto(
        Long id,
        Long clientId,
        Instant grantedAt,
        Instant expiresAt
    ) {}

    public record ClientAccessListResponse(
        List<ClientAccessGrantDto> grants
    ) {}

    public record StatusResponse(
        String message
    ) {}

    public record ErrorResponse(
        String error
    ) {}
}
