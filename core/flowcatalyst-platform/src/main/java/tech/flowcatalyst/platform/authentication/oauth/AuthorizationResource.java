package tech.flowcatalyst.platform.authentication.oauth;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import tech.flowcatalyst.platform.authentication.*;
import tech.flowcatalyst.platform.authorization.PrincipalRoleRepository;
import tech.flowcatalyst.platform.principal.PasswordService;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalRepository;
import tech.flowcatalyst.platform.principal.PrincipalType;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OAuth2 Authorization endpoint implementing:
 * - Authorization Code flow with PKCE (for SPAs/mobile)
 * - Refresh token grant (for long-lived sessions)
 *
 * This resource handles the authorization code flow which is the recommended
 * flow for browser-based applications (SPAs) and mobile apps.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6749">RFC 6749 - OAuth 2.0</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7636">RFC 7636 - PKCE</a>
 */
@Path("/oauth")
@Tag(name = "OAuth2 Authorization", description = "OAuth2 authorization code flow endpoints")
@EmbeddedModeOnly
public class AuthorizationResource {

    private static final Logger LOG = Logger.getLogger(AuthorizationResource.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Inject
    AuthConfig authConfig;

    @Inject
    OAuthClientRepository clientRepo;

    @Inject
    AuthorizationCodeRepository codeRepo;

    @Inject
    RefreshTokenRepository refreshTokenRepo;

    @Inject
    PrincipalRepository principalRepo;

    @Inject
    PrincipalRoleRepository roleRepo;

    @Inject
    JwtKeyService jwtKeyService;

    @Inject
    PkceService pkceService;

    @Inject
    PasswordService passwordService;

    @Context
    UriInfo uriInfo;

    // ==================== Authorization Endpoint ====================

    /**
     * OAuth2 Authorization endpoint.
     *
     * Initiates the authorization code flow. If the user is authenticated,
     * issues an authorization code. If not, redirects to login.
     *
     * GET /oauth/authorize?
     *   response_type=code
     *   &client_id=my-spa
     *   &redirect_uri=https://app.example.com/callback
     *   &scope=openid profile
     *   &state=xyz123
     *   &code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
     *   &code_challenge_method=S256
     */
    @GET
    @Path("/authorize")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Start authorization code flow")
    public Response authorize(
            @Parameter(description = "Must be 'code'")
            @QueryParam("response_type") String responseType,

            @Parameter(description = "OAuth client ID")
            @QueryParam("client_id") String clientId,

            @Parameter(description = "URI to redirect after authorization")
            @QueryParam("redirect_uri") String redirectUri,

            @Parameter(description = "Requested scopes (space-separated)")
            @QueryParam("scope") String scope,

            @Parameter(description = "Client state for CSRF protection")
            @QueryParam("state") String state,

            @Parameter(description = "PKCE code challenge")
            @QueryParam("code_challenge") String codeChallenge,

            @Parameter(description = "PKCE challenge method (S256 or plain)")
            @QueryParam("code_challenge_method") @DefaultValue("S256") String codeChallengeMethod,

            @Parameter(description = "OIDC nonce for replay protection")
            @QueryParam("nonce") String nonce,

            @CookieParam("FLOWCATALYST_SESSION") String sessionToken
    ) {
        // Validate response_type
        if (!"code".equals(responseType)) {
            return errorRedirect(redirectUri, "unsupported_response_type",
                "Only 'code' response type is supported", state);
        }

        // Validate client_id
        if (clientId == null || clientId.isEmpty()) {
            return errorRedirect(redirectUri, "invalid_request", "client_id is required", state);
        }

        Optional<OAuthClient> clientOpt = clientRepo.findByClientId(clientId);
        if (clientOpt.isEmpty()) {
            LOG.warnf("Authorization request with unknown client_id: %s", clientId);
            return errorRedirect(redirectUri, "invalid_client", "Unknown client_id", state);
        }

        OAuthClient client = clientOpt.get();

        // Validate redirect_uri
        if (redirectUri == null || redirectUri.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(errorJson("invalid_request", "redirect_uri is required"))
                .type(MediaType.APPLICATION_JSON)
                .build();
        }

        if (!client.isRedirectUriAllowed(redirectUri)) {
            // Don't redirect to untrusted URI - return error directly
            LOG.warnf("Authorization request with invalid redirect_uri: %s for client %s", redirectUri, clientId);
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(errorJson("invalid_request", "redirect_uri not allowed for this client"))
                .type(MediaType.APPLICATION_JSON)
                .build();
        }

        // Validate PKCE for public clients (required) or if configured
        if (client.clientType == OAuthClient.ClientType.PUBLIC || client.pkceRequired || authConfig.pkce().required()) {
            if (codeChallenge == null || codeChallenge.isEmpty()) {
                return errorRedirect(redirectUri, "invalid_request",
                    "code_challenge required for this client", state);
            }
            if (!pkceService.isValidCodeChallenge(codeChallenge)) {
                return errorRedirect(redirectUri, "invalid_request",
                    "Invalid code_challenge format", state);
            }
        }

        // Check if user is authenticated
        if (sessionToken == null || sessionToken.isEmpty()) {
            return redirectToLogin(responseType, clientId, redirectUri, scope, state,
                codeChallenge, codeChallengeMethod, nonce);
        }

        // Validate session and get principal
        String principalId = jwtKeyService.validateAndGetPrincipalId(sessionToken);
        if (principalId == null) {
            return redirectToLogin(responseType, clientId, redirectUri, scope, state,
                codeChallenge, codeChallengeMethod, nonce);
        }

        // Verify principal exists and is active
        Optional<Principal> principalOpt = principalRepo.findByIdOptional(principalId);
        if (principalOpt.isEmpty() || !principalOpt.get().active) {
            return redirectToLogin(responseType, clientId, redirectUri, scope, state,
                codeChallenge, codeChallengeMethod, nonce);
        }

        Principal principal = principalOpt.get();

        // Generate authorization code
        String code = generateAuthorizationCode();

        // Store authorization code
        AuthorizationCode authCode = new AuthorizationCode();
        authCode.code = code;
        authCode.clientId = clientId;
        authCode.principalId = principalId;
        authCode.redirectUri = redirectUri;
        authCode.scope = scope;
        authCode.codeChallenge = codeChallenge;
        authCode.codeChallengeMethod = codeChallengeMethod;
        authCode.nonce = nonce;
        authCode.state = state;
        authCode.contextClientId = principal.clientId;
        authCode.expiresAt = Instant.now().plus(authConfig.jwt().authorizationCodeExpiry());

        codeRepo.persist(authCode);

        // Build redirect with code
        StringBuilder callback = new StringBuilder(redirectUri);
        callback.append(redirectUri.contains("?") ? "&" : "?");
        callback.append("code=").append(code);
        if (state != null) {
            callback.append("&state=").append(urlEncode(state));
        }

        LOG.infof("Authorization code issued for client %s, principal %d", clientId, principalId);
        return Response.seeOther(URI.create(callback.toString())).build();
    }

    // ==================== Token Endpoint ====================

    /**
     * OAuth2 Token endpoint.
     *
     * Exchanges authorization code for tokens, or refreshes tokens.
     *
     * Supports grant types:
     * - authorization_code: Exchange code for access + refresh tokens
     * - refresh_token: Exchange refresh token for new tokens
     * - client_credentials: Service-to-service authentication
     * - password: Resource owner password credentials (for trusted apps)
     */
    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Exchange code for tokens or refresh tokens")
    public Response token(
            @HeaderParam("Authorization") String authHeader,

            @Parameter(description = "Grant type")
            @FormParam("grant_type") String grantType,

            @Parameter(description = "Authorization code (for authorization_code grant)")
            @FormParam("code") String code,

            @Parameter(description = "Redirect URI (must match authorization request)")
            @FormParam("redirect_uri") String redirectUri,

            @Parameter(description = "Client ID")
            @FormParam("client_id") String formClientId,

            @Parameter(description = "Client secret (confidential clients)")
            @FormParam("client_secret") String formClientSecret,

            @Parameter(description = "PKCE code verifier")
            @FormParam("code_verifier") String codeVerifier,

            @Parameter(description = "Refresh token (for refresh_token grant)")
            @FormParam("refresh_token") String refreshToken,

            @Parameter(description = "Username (for password grant)")
            @FormParam("username") String username,

            @Parameter(description = "Password (for password grant)")
            @FormParam("password") String password,

            @Parameter(description = "Requested scopes")
            @FormParam("scope") String scope
    ) {
        if (grantType == null || grantType.isEmpty()) {
            return tokenError("invalid_request", "grant_type is required");
        }

        return switch (grantType) {
            case "authorization_code" -> handleAuthorizationCodeGrant(
                authHeader, code, redirectUri, formClientId, formClientSecret, codeVerifier);
            case "refresh_token" -> handleRefreshTokenGrant(
                authHeader, refreshToken, formClientId, formClientSecret, scope);
            case "client_credentials" -> handleClientCredentialsGrant(
                authHeader, formClientId, formClientSecret);
            case "password" -> handlePasswordGrant(username, password);
            default -> tokenError("unsupported_grant_type", "Grant type not supported: " + grantType);
        };
    }

    // ==================== Grant Handlers ====================

    private Response handleAuthorizationCodeGrant(
            String authHeader, String code, String redirectUri,
            String formClientId, String formClientSecret, String codeVerifier) {

        if (code == null || code.isEmpty()) {
            return tokenError("invalid_request", "code is required");
        }

        // Find authorization code
        Optional<AuthorizationCode> codeOpt = codeRepo.findValidCode(code);
        if (codeOpt.isEmpty()) {
            LOG.warnf("Token request with invalid authorization code");
            return tokenError("invalid_grant", "Invalid or expired authorization code");
        }

        AuthorizationCode authCode = codeOpt.get();

        // Determine client ID from header or form
        String clientId = formClientId;
        String clientSecret = formClientSecret;
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            String[] credentials = parseBasicAuth(authHeader);
            if (credentials != null) {
                clientId = credentials[0];
                clientSecret = credentials[1];
            }
        }

        // Validate client
        if (clientId == null || !authCode.clientId.equals(clientId)) {
            return tokenError("invalid_grant", "Client mismatch");
        }

        Optional<OAuthClient> clientOpt = clientRepo.findByClientId(clientId);
        if (clientOpt.isEmpty()) {
            return tokenError("invalid_client", "Unknown client");
        }

        OAuthClient client = clientOpt.get();

        // Validate redirect_uri matches
        if (redirectUri == null || !authCode.redirectUri.equals(redirectUri)) {
            return tokenError("invalid_grant", "redirect_uri mismatch");
        }

        // Validate client secret for confidential clients
        if (client.clientType == OAuthClient.ClientType.CONFIDENTIAL) {
            if (clientSecret == null || clientSecret.isEmpty()) {
                return tokenError("invalid_client", "Client authentication required");
            }
            // TODO: Verify hashed client secret
        }

        // Validate PKCE
        if (authCode.codeChallenge != null) {
            if (codeVerifier == null || codeVerifier.isEmpty()) {
                return tokenError("invalid_grant", "code_verifier required");
            }
            if (!pkceService.verifyCodeChallenge(codeVerifier, authCode.codeChallenge,
                    authCode.codeChallengeMethod)) {
                LOG.warnf("PKCE verification failed for client %s", clientId);
                return tokenError("invalid_grant", "Invalid code_verifier");
            }
        }

        // Mark code as used (single use)
        codeRepo.markAsUsed(code);

        // Load principal and issue tokens
        Optional<Principal> principalOpt = principalRepo.findByIdOptional(authCode.principalId);
        if (principalOpt.isEmpty() || !principalOpt.get().active) {
            return tokenError("invalid_grant", "User not found or inactive");
        }

        Principal principal = principalOpt.get();
        Set<String> roles = loadRoles(principal.id);
        List<String> clients = determineAccessibleClients(principal, roles);

        String email = principal.userIdentity != null ? principal.userIdentity.email : null;
        String name = principal.name;

        // Issue access token
        String accessToken = jwtKeyService.issueSessionToken(
            principal.id,
            email,
            roles,
            clients
        );

        // Issue ID token if openid scope was requested
        String idToken = null;
        if (authCode.scope != null && authCode.scope.contains("openid")) {
            idToken = jwtKeyService.issueIdToken(
                principal.id,
                email,
                name,
                clientId,
                authCode.nonce,
                clients
            );
        }

        // Generate and store refresh token
        String refreshTokenValue = generateRefreshToken();
        String tokenFamily = generateTokenFamily();
        storeRefreshToken(refreshTokenValue, principal.id, clientId, authCode.contextClientId,
            authCode.scope, tokenFamily);

        LOG.infof("Tokens issued for principal %d via authorization_code grant", principal.id);

        return Response.ok(new TokenResponse(
            accessToken,
            "Bearer",
            authConfig.jwt().sessionTokenExpiry().toSeconds(),
            refreshTokenValue,
            authCode.scope,
            idToken
        )).build();
    }

    private Response handleRefreshTokenGrant(
            String authHeader, String refreshToken, String clientId, String clientSecret, String scope) {

        if (refreshToken == null || refreshToken.isEmpty()) {
            return tokenError("invalid_request", "refresh_token is required");
        }

        String tokenHash = hashToken(refreshToken);
        Optional<RefreshToken> tokenOpt = refreshTokenRepo.findValidToken(tokenHash);

        if (tokenOpt.isEmpty()) {
            // Check if this is a reused token (potential theft)
            Optional<RefreshToken> reusedOpt = refreshTokenRepo.findByTokenHash(tokenHash);
            if (reusedOpt.isPresent() && reusedOpt.get().revoked) {
                // Token reuse detected - revoke entire family!
                RefreshToken reusedToken = reusedOpt.get();
                LOG.warnf("Refresh token reuse detected! Revoking token family: %s for principal %d",
                    reusedToken.tokenFamily, reusedToken.principalId);
                refreshTokenRepo.revokeTokenFamily(reusedToken.tokenFamily);
            }
            return tokenError("invalid_grant", "Invalid or expired refresh token");
        }

        RefreshToken token = tokenOpt.get();

        // Load principal
        Optional<Principal> principalOpt = principalRepo.findByIdOptional(token.principalId);
        if (principalOpt.isEmpty() || !principalOpt.get().active) {
            return tokenError("invalid_grant", "User not found or inactive");
        }

        Principal principal = principalOpt.get();
        Set<String> roles = loadRoles(principal.id);
        List<String> clients = determineAccessibleClients(principal, roles);

        String email = principal.userIdentity != null ? principal.userIdentity.email : null;
        String name = principal.name;

        // Issue new access token
        String accessToken = jwtKeyService.issueSessionToken(
            principal.id,
            email,
            roles,
            clients
        );

        // Issue ID token if openid scope was in original request
        String idToken = null;
        if (token.scope != null && token.scope.contains("openid")) {
            idToken = jwtKeyService.issueIdToken(
                principal.id,
                email,
                name,
                token.clientId,
                null,  // No nonce on refresh
                clients
            );
        }

        // Rotate refresh token (issue new one, revoke old one)
        String newRefreshToken = generateRefreshToken();
        String newTokenHash = hashToken(newRefreshToken);

        // Revoke old token and link to new one
        refreshTokenRepo.revokeToken(tokenHash, newTokenHash);

        // Store new token in same family
        storeRefreshToken(newRefreshToken, principal.id, token.clientId,
            token.contextClientId, token.scope, token.tokenFamily);

        LOG.infof("Tokens refreshed for principal %d", principal.id);

        return Response.ok(new TokenResponse(
            accessToken,
            "Bearer",
            authConfig.jwt().sessionTokenExpiry().toSeconds(),
            newRefreshToken,
            token.scope,
            idToken
        )).build();
    }

    private Response handleClientCredentialsGrant(String authHeader, String formClientId, String formClientSecret) {
        String clientId;
        String clientSecret;

        // Try Basic auth header first
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            String[] credentials = parseBasicAuth(authHeader);
            if (credentials == null) {
                return tokenError("invalid_client", "Invalid Authorization header");
            }
            clientId = credentials[0];
            clientSecret = credentials[1];
        } else if (formClientId != null && formClientSecret != null) {
            // Fall back to form parameters
            clientId = formClientId;
            clientSecret = formClientSecret;
        } else {
            return tokenError("invalid_client", "Client credentials required");
        }

        // Find service account by client_id
        Optional<Principal> principalOpt = principalRepo.findByServiceAccountClientId(clientId);
        if (principalOpt.isEmpty()) {
            LOG.infof("Token request failed: client_id not found: %s", clientId);
            return tokenError("invalid_client", "Invalid client credentials");
        }

        Principal principal = principalOpt.get();

        // Verify it's a service account
        if (principal.type != PrincipalType.SERVICE) {
            LOG.warnf("Token request for non-service principal: %s", clientId);
            return tokenError("invalid_client", "Invalid client credentials");
        }

        // Verify service account is active
        if (!principal.active) {
            LOG.infof("Token request failed: service account is inactive: %s", clientId);
            return tokenError("invalid_client", "Client is disabled");
        }

        // Verify client secret
        if (principal.serviceAccount == null || principal.serviceAccount.clientSecretHash == null) {
            LOG.warnf("Token request failed: no client secret set for: %s", clientId);
            return tokenError("invalid_client", "Invalid client credentials");
        }

        if (!passwordService.verifyPassword(clientSecret, principal.serviceAccount.clientSecretHash)) {
            LOG.infof("Token request failed: invalid client secret for: %s", clientId);
            return tokenError("invalid_client", "Invalid client credentials");
        }

        // Load roles
        Set<String> roles = loadRoles(principal.id);

        // Issue access token
        String token = jwtKeyService.issueAccessToken(principal.id, clientId, roles);

        // Update last used
        principal.serviceAccount.lastUsedAt = Instant.now();
        principalRepo.update(principal);

        LOG.infof("Access token issued for service account: %s", clientId);

        return Response.ok(new TokenResponse(
            token,
            "Bearer",
            jwtKeyService.getAccessTokenExpiry().toSeconds(),
            null, // No refresh token for client_credentials
            null,
            null  // No ID token for client_credentials
        )).build();
    }

    private Response handlePasswordGrant(String username, String password) {
        if (username == null || password == null) {
            return tokenError("invalid_request", "username and password required");
        }

        // Find user by email
        Optional<Principal> principalOpt = principalRepo.findByEmail(username);
        if (principalOpt.isEmpty()) {
            LOG.infof("Password grant failed: user not found: %s", username);
            return tokenError("invalid_grant", "Invalid username or password");
        }

        Principal principal = principalOpt.get();

        // Verify it's a user
        if (principal.type != PrincipalType.USER) {
            LOG.warnf("Password grant for non-user principal: %s", username);
            return tokenError("invalid_grant", "Invalid username or password");
        }

        // Verify user is active
        if (!principal.active) {
            LOG.infof("Password grant failed: user is inactive: %s", username);
            return tokenError("invalid_grant", "Account is disabled");
        }

        // Verify password
        if (principal.userIdentity == null || principal.userIdentity.passwordHash == null) {
            LOG.warnf("Password grant failed: no password set for: %s", username);
            return tokenError("invalid_grant", "Invalid username or password");
        }

        if (!passwordService.verifyPassword(password, principal.userIdentity.passwordHash)) {
            LOG.infof("Password grant failed: invalid password for: %s", username);
            return tokenError("invalid_grant", "Invalid username or password");
        }

        // Load roles
        Set<String> roles = loadRoles(principal.id);
        List<String> clients = determineAccessibleClients(principal, roles);

        // Issue session token
        String token = jwtKeyService.issueSessionToken(principal.id, username, roles, clients);

        // Update last login
        principal.userIdentity.lastLoginAt = Instant.now();
        principalRepo.update(principal);

        LOG.infof("Access token issued via password grant for: %s", username);

        return Response.ok(new TokenResponse(
            token,
            "Bearer",
            jwtKeyService.getSessionTokenExpiry().toSeconds(),
            null,
            null,
            null  // No ID token for password grant
        )).build();
    }

    // ==================== Helper Methods ====================

    private String generateAuthorizationCode() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateTokenFamily() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash token", e);
        }
    }

    private void storeRefreshToken(String token, String principalId, String clientId,
            String contextClientId, String scope, String tokenFamily) {
        RefreshToken rt = new RefreshToken();
        rt.tokenHash = hashToken(token);
        rt.principalId = principalId;
        rt.clientId = clientId;
        rt.contextClientId = contextClientId;
        rt.scope = scope;
        rt.tokenFamily = tokenFamily;
        rt.expiresAt = Instant.now().plus(authConfig.jwt().refreshTokenExpiry());
        refreshTokenRepo.persist(rt);
    }

    private Set<String> loadRoles(String principalId) {
        return roleRepo.findByPrincipalId(principalId).stream()
            .map(pr -> pr.roleName)
            .collect(Collectors.toSet());
    }

    private String[] parseBasicAuth(String authHeader) {
        try {
            String base64 = authHeader.substring("Basic ".length()).trim();
            String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            int colonIdx = decoded.indexOf(':');
            if (colonIdx < 0) return null;
            return new String[] {
                decoded.substring(0, colonIdx),
                decoded.substring(colonIdx + 1)
            };
        } catch (Exception e) {
            return null;
        }
    }

    private Response redirectToLogin(String responseType, String clientId, String redirectUri,
            String scope, String state, String codeChallenge, String codeChallengeMethod, String nonce) {
        StringBuilder loginUrl = new StringBuilder("/auth/login?oauth=true");
        loginUrl.append("&response_type=").append(urlEncode(responseType));
        loginUrl.append("&client_id=").append(urlEncode(clientId));
        loginUrl.append("&redirect_uri=").append(urlEncode(redirectUri));
        if (scope != null) loginUrl.append("&scope=").append(urlEncode(scope));
        if (state != null) loginUrl.append("&state=").append(urlEncode(state));
        if (codeChallenge != null) loginUrl.append("&code_challenge=").append(urlEncode(codeChallenge));
        if (codeChallengeMethod != null) loginUrl.append("&code_challenge_method=").append(urlEncode(codeChallengeMethod));
        if (nonce != null) loginUrl.append("&nonce=").append(urlEncode(nonce));

        return Response.seeOther(URI.create(loginUrl.toString())).build();
    }

    private Response errorRedirect(String redirectUri, String error, String description, String state) {
        if (redirectUri == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(errorJson(error, description))
                .type(MediaType.APPLICATION_JSON)
                .build();
        }
        StringBuilder url = new StringBuilder(redirectUri);
        url.append(redirectUri.contains("?") ? "&" : "?");
        url.append("error=").append(urlEncode(error));
        url.append("&error_description=").append(urlEncode(description));
        if (state != null) {
            url.append("&state=").append(urlEncode(state));
        }
        return Response.seeOther(URI.create(url.toString())).build();
    }

    private Response tokenError(String error, String description) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(errorJson(error, description))
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    private Map<String, String> errorJson(String error, String description) {
        return Map.of("error", error, "error_description", description);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Determine which clients the user can access based on their scope.
     *
     * @return List of client IDs as strings, or ["*"] for anchor users
     */
    private List<String> determineAccessibleClients(Principal principal, Set<String> roles) {
        // Check explicit scope first
        if (principal.scope != null) {
            switch (principal.scope) {
                case ANCHOR:
                    return List.of("*");
                case CLIENT:
                    if (principal.clientId != null) {
                        return List.of(String.valueOf(principal.clientId));
                    }
                    return List.of();
                case PARTNER:
                    if (principal.clientId != null) {
                        return List.of(String.valueOf(principal.clientId));
                    }
                    return List.of();
            }
        }

        // Fallback: check roles for platform admins
        if (roles.stream().anyMatch(r -> r.contains("platform:admin") || r.contains("super-admin"))) {
            return List.of("*");
        }

        // User is bound to a specific client
        if (principal.clientId != null) {
            return List.of(String.valueOf(principal.clientId));
        }

        return List.of();
    }

    // ==================== DTOs ====================

    public record TokenResponse(
        String access_token,
        String token_type,
        long expires_in,
        String refresh_token,
        String scope,
        String id_token
    ) {}
}
