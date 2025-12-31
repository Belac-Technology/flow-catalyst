package tech.flowcatalyst.platform.audit;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.ext.Provider;
import tech.flowcatalyst.platform.authentication.JwtKeyService;

/**
 * JAX-RS filter that auto-populates AuditContext from session cookie or Bearer token.
 *
 * Runs after authentication filters to extract the principal ID and make it
 * available for audit logging throughout the request.
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 10)
public class AuditContextFilter implements ContainerRequestFilter {

    private static final String SESSION_COOKIE = "fc_session";
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(AuditContextFilter.class);

    @Inject
    AuditContext auditContext;

    @Inject
    JwtKeyService jwtKeyService;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String principalId = null;

        // Try session cookie first
        Cookie sessionCookie = ctx.getCookies().get(SESSION_COOKIE);
        if (sessionCookie != null && sessionCookie.getValue() != null) {
            principalId = jwtKeyService.validateAndGetPrincipalId(sessionCookie.getValue());
            if (principalId == null) {
                LOG.debugf("Session cookie present but invalid for path: %s", ctx.getUriInfo().getPath());
            }
        } else {
            LOG.debugf("No session cookie for path: %s, cookies: %s", ctx.getUriInfo().getPath(), ctx.getCookies().keySet());
        }

        // Fall back to Bearer token
        if (principalId == null) {
            String authHeader = ctx.getHeaderString("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring("Bearer ".length());
                principalId = jwtKeyService.validateAndGetPrincipalId(token);
            }
        }

        if (principalId != null) {
            auditContext.setPrincipalId(principalId);
            LOG.debugf("Audit context set for principal: %d on path: %s", principalId, ctx.getUriInfo().getPath());
        }
    }
}
