# Authentication Implementation Summary

## Overview

Added opt-in authentication support to the Message Router with BasicAuth and OIDC support. Authentication is disabled by default and must be explicitly enabled via configuration.

## Files Created

### Security Package (`src/main/java/tech/flowcatalyst/messagerouter/security/`)

1. **AuthenticationFilter.java**
   - JAX-RS filter that intercepts all requests
   - Uses Instance<AuthenticationConfig> pattern for optional injection
   - Checks if authentication is enabled
   - Routes to appropriate authentication handler (BasicAuth or OIDC)
   - Always allows health check endpoints (for K8s probes)
   - Protected endpoints: `/monitoring/*`, `/api/seed/*`, `/api/config`
   - Dashboard HTML (`/dashboard.html`) is always accessible (shows login modal if auth required)

2. **BasicAuthIdentityProvider.java**
   - Validates BasicAuth credentials against configured username/password
   - Extracts Base64-encoded credentials from Authorization header
   - No database required - credentials stored in properties/environment

3. **BasicAuthRequest.java**
   - Data class carrying username and password
   - Used internally for credential passing

4. **Protected.java**
   - Custom annotation marking endpoints that require authentication
   - Used on resource classes (`@Protected`)
   - Value field for documentation/logging

### Configuration

1. **AuthenticationConfig.java** (`src/main/java/tech/flowcatalyst/messagerouter/config/`)
   - Quarkus ConfigMapping interface
   - Reads from `authentication.*` properties
   - BasicAuth properties are Optional<String> (handles missing values gracefully)
   - Methods to check authentication mode and enabled state
   - Uses kebab-case for nested properties (`basic-username` not `basic.username`)

2. **application.properties** (updated)
   - Added authentication configuration section
   - Environment variable support: `AUTHENTICATION_ENABLED`, `AUTHENTICATION_MODE`
   - BasicAuth env vars: `AUTH_BASIC_USERNAME`, `AUTH_BASIC_PASSWORD`
   - OIDC configuration: `OIDC_AUTH_SERVER_URL`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`

### Resources (updated)

1. **MonitoringResource.java** (updated)
   - Added `@Protected` annotation at class level
   - Secures all monitoring endpoints when authentication is enabled

2. **MessageSeedResource.java** (updated)
   - Added `@Protected` annotation at class level
   - Secures message seeding endpoints

3. **LocalConfigResource.java** (updated)
   - Added `@Protected` annotation at class level
   - Secures configuration endpoints

### Dashboard

1. **dashboard.html** (updated)
   - Added login modal UI for authentication
   - `AuthenticationManager` class handles login flow
   - Stores credentials in browser localStorage (base64 encoded)
   - Logout button added to header
   - All fetch requests include Authorization headers when authenticated
   - Automatic logout on 401/403 responses

### Build Configuration

1. **build.gradle.kts** (updated)
   - Added Quarkus Security dependency: `io.quarkus:quarkus-security`
   - Added Quarkus OIDC dependency: `io.quarkus:quarkus-oidc`
   - Note: Removed `quarkus-security-jpa` (no database needed for BasicAuth)

### Documentation

1. **AUTHENTICATION.md**
   - Comprehensive authentication guide
   - Configuration examples for BasicAuth and OIDC
   - API usage examples
   - Docker and Kubernetes deployment examples
   - Security considerations and best practices
   - Troubleshooting guide

## Configuration Properties

### Enable/Disable Authentication
```properties
authentication.enabled=false  # Default: disabled
authentication.mode=NONE       # Values: NONE, BASIC, OIDC
```

### BasicAuth Configuration
```properties
authentication.basic-username=${AUTH_BASIC_USERNAME:}
authentication.basic-password=${AUTH_BASIC_PASSWORD:}
```

**Note**: Uses kebab-case property names (`basic-username` not `basic.username`) to comply with Quarkus ConfigMapping conventions. Properties are Optional<String> to handle missing values gracefully in test environments.

### OIDC Configuration
```properties
quarkus.oidc.enabled=false
quarkus.oidc.auth-server-url=${OIDC_AUTH_SERVER_URL:}
quarkus.oidc.client-id=${OIDC_CLIENT_ID:}
quarkus.oidc.credentials.secret=${OIDC_CLIENT_SECRET:}
```

## Protected Endpoints

When authentication is enabled, the following endpoints require authentication:

- `/monitoring/*` - All metrics and monitoring endpoints
- `/api/seed/*` - Message seeding endpoints
- `/api/config` - Configuration endpoint

## Always-Open Endpoints

These endpoints are never protected:

- `/dashboard.html` - Dashboard UI (auto-redirects to login modal if auth required)
- `/health`, `/health/live`, `/health/ready`, `/health/startup` - Kubernetes health probes
- `/q/health*` - Quarkus health endpoints

## Usage Examples

### BasicAuth with cURL
```bash
curl -H "Authorization: Basic $(echo -n 'admin:password' | base64)" \
  http://localhost:8080/monitoring/health
```

### Dashboard Login
1. Navigate to `/dashboard.html`
2. Login modal appears (if authentication enabled)
3. Enter credentials
4. Credentials stored in localStorage for session

### OIDC Flow
- Browser redirects to OIDC provider for login
- Quarkus OIDC extension handles token management
- Token automatically included in API requests

## Security Features

1. **No Database Required**
   - BasicAuth stores credentials in properties/environment
   - Suitable for internal tools and small deployments

2. **Health Checks Never Protected**
   - Kubernetes probes continue to work
   - Infrastructure health always accessible

3. **Environment Variables**
   - Credentials set via env vars, not hardcoded
   - Secure for container deployments

4. **HTTPS Only for OIDC**
   - OIDC communication enforces HTTPS
   - Token validation against provider keys

5. **Stateless**
   - No session state on server
   - Each request includes credentials or token
   - Scales horizontally

## Testing

To test the implementation:

1. **Disable Authentication (Default)**
   ```bash
   # No auth required - all endpoints open
   curl http://localhost:8080/monitoring/health
   ```

2. **Enable BasicAuth**
   ```properties
   authentication.enabled=true
   authentication.mode=BASIC
   authentication.basic.username=admin
   authentication.basic.password=secret123
   ```

   Then test:
   ```bash
   # Without auth - returns 401
   curl http://localhost:8080/monitoring/health

   # With auth - returns 200
   curl -H "Authorization: Basic $(echo -n 'admin:secret123' | base64)" \
     http://localhost:8080/monitoring/health
   ```

3. **Dashboard Login**
   - Navigate to `http://localhost:8080/dashboard.html`
   - Login modal should appear
   - Enter credentials and verify dashboard loads

## Integration Points

1. **Quarkus Security Framework**
   - Uses standard JAX-RS filters
   - Compatible with other security features

2. **OIDC Integration**
   - Leverages Quarkus OIDC extension
   - Works with major providers (Keycloak, Auth0, Okta)

3. **Dashboard Integration**
   - Client-side authentication handling
   - localStorage for credential storage
   - Logout and re-login capability

## Future Enhancements

- Role-based access control (RBAC)
- API token/key authentication
- Multi-user support with audit logging
- Integration with existing platform auth (from flowcatalyst-platform)
- Rate limiting on login attempts

## Backward Compatibility

- **Default**: Authentication disabled (`authentication.enabled=false`)
- All existing deployments work unchanged
- No breaking changes to API endpoints
- Health checks continue to work without auth
