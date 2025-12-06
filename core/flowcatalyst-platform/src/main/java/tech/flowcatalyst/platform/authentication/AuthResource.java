package tech.flowcatalyst.platform.authentication;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import tech.flowcatalyst.platform.authorization.PrincipalRole;
import tech.flowcatalyst.platform.authorization.PrincipalRoleRepository;
import tech.flowcatalyst.platform.principal.PasswordService;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalRepository;
import tech.flowcatalyst.platform.principal.PrincipalType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authentication endpoints for human users.
 * Handles login/logout with session cookies.
 *
 * This resource is only available in embedded mode.
 * In remote mode, auth requests are redirected to the external IdP.
 */
@Path("/auth")
@Tag(name = "Authentication", description = "User authentication endpoints")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@EmbeddedModeOnly
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);
    private static final String SESSION_COOKIE_NAME = "FLOWCATALYST_SESSION";

    @Inject
    PrincipalRepository principalRepository;

    @Inject
    PrincipalRoleRepository principalRoleRepository;

    @Inject
    PasswordService passwordService;

    @Inject
    JwtKeyService jwtKeyService;

    @ConfigProperty(name = "flowcatalyst.auth.session.secure", defaultValue = "true")
    boolean secureCookie;

    @ConfigProperty(name = "flowcatalyst.auth.session.same-site", defaultValue = "Strict")
    String sameSite;

    @Context
    UriInfo uriInfo;

    /**
     * Login with email and password.
     * Returns a session cookie on success.
     */
    @POST
    @Path("/login")
    @Operation(summary = "Login with email and password")
    @APIResponse(responseCode = "200", description = "Login successful",
            content = @Content(schema = @Schema(implementation = LoginResponse.class)))
    @APIResponse(responseCode = "401", description = "Invalid credentials")
    public Response login(LoginRequest request) {
        LOG.debugf("Login attempt for email: %s", request.email());

        // Find user by email
        Optional<Principal> principalOpt = principalRepository.findByEmail(request.email());
        if (principalOpt.isEmpty()) {
            LOG.infof("Login failed: user not found for email %s", request.email());
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid email or password"))
                    .build();
        }

        Principal principal = principalOpt.get();

        // Verify it's a user (not service account)
        if (principal.type != PrincipalType.USER) {
            LOG.warnf("Login attempt for non-user principal: %s", request.email());
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid email or password"))
                    .build();
        }

        // Verify user is active
        if (!principal.active) {
            LOG.infof("Login failed: user is inactive %s", request.email());
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Account is disabled"))
                    .build();
        }

        // Verify password
        if (principal.userIdentity == null || principal.userIdentity.passwordHash == null) {
            LOG.warnf("Login failed: no password set for user %s", request.email());
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid email or password"))
                    .build();
        }

        if (!passwordService.verifyPassword(request.password(), principal.userIdentity.passwordHash)) {
            LOG.infof("Login failed: invalid password for %s", request.email());
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid email or password"))
                    .build();
        }

        // Load roles
        List<PrincipalRole> principalRoles = principalRoleRepository.findByPrincipalId(principal.id);
        Set<String> roles = principalRoles.stream()
                .map(pr -> pr.roleName)
                .collect(Collectors.toSet());

        // Issue session token
        String token = jwtKeyService.issueSessionToken(principal.id, request.email(), roles);

        // Update last login
        principal.userIdentity.lastLoginAt = Instant.now();
        principalRepository.update(principal);

        // Create session cookie
        NewCookie sessionCookie = createSessionCookie(token);

        LOG.infof("Login successful for user: %s", request.email());

        return Response.ok(new LoginResponse(
                        principal.id,
                        principal.name,
                        request.email(),
                        roles,
                        principal.clientId
                ))
                .cookie(sessionCookie)
                .build();
    }

    /**
     * Logout - clears the session cookie.
     */
    @POST
    @Path("/logout")
    @Operation(summary = "Logout and clear session")
    @APIResponse(responseCode = "200", description = "Logout successful")
    public Response logout() {
        NewCookie expiredCookie = new NewCookie.Builder(SESSION_COOKIE_NAME)
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(NewCookie.SameSite.valueOf(sameSite.toUpperCase()))
                .build();

        return Response.ok(new MessageResponse("Logged out successfully"))
                .cookie(expiredCookie)
                .build();
    }

    /**
     * Get current user info from session.
     */
    @GET
    @Path("/me")
    @Operation(summary = "Get current authenticated user")
    @APIResponse(responseCode = "200", description = "User info",
            content = @Content(schema = @Schema(implementation = LoginResponse.class)))
    @APIResponse(responseCode = "401", description = "Not authenticated")
    public Response me(@CookieParam(SESSION_COOKIE_NAME) String sessionToken) {
        if (sessionToken == null || sessionToken.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Not authenticated"))
                    .build();
        }

        // Token will be validated by the security layer
        // This endpoint just returns user info if authenticated
        // For now, we parse the token to get user info
        try {
            // Use JwtKeyService to validate and parse the token
            Long principalId = jwtKeyService.validateAndGetPrincipalId(sessionToken);
            if (principalId == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(new ErrorResponse("Invalid session"))
                        .build();
            }

            Optional<Principal> principalOpt = principalRepository.findByIdOptional(principalId);

            if (principalOpt.isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(new ErrorResponse("User not found"))
                        .build();
            }

            Principal principal = principalOpt.get();
            List<PrincipalRole> principalRoles = principalRoleRepository.findByPrincipalId(principal.id);
            Set<String> roles = principalRoles.stream()
                    .map(pr -> pr.roleName)
                    .collect(Collectors.toSet());

            return Response.ok(new LoginResponse(
                    principal.id,
                    principal.name,
                    principal.userIdentity != null ? principal.userIdentity.email : null,
                    roles,
                    principal.clientId
            )).build();

        } catch (Exception e) {
            LOG.debug("Failed to parse session token", e);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid session"))
                    .build();
        }
    }

    private NewCookie createSessionCookie(String token) {
        long maxAgeSeconds = jwtKeyService.getSessionTokenExpiry().toSeconds();

        return new NewCookie.Builder(SESSION_COOKIE_NAME)
                .value(token)
                .path("/")
                .maxAge((int) maxAgeSeconds)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(NewCookie.SameSite.valueOf(sameSite.toUpperCase()))
                .build();
    }

    // DTOs

    public record LoginRequest(String email, String password) {}

    public record LoginResponse(
            Long principalId,
            String name,
            String email,
            Set<String> roles,
            Long clientId
    ) {}

    public record ErrorResponse(String error) {}

    public record MessageResponse(String message) {}
}
