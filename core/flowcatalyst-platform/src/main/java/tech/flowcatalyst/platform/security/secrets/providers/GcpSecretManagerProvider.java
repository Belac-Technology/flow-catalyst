package tech.flowcatalyst.platform.security.secrets.providers;

import com.google.cloud.secretmanager.v1.*;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.flowcatalyst.platform.security.secrets.SecretProvider;
import tech.flowcatalyst.platform.security.secrets.SecretResolutionException;

import java.io.IOException;

/**
 * Secret provider that uses Google Cloud Secret Manager.
 *
 * Reference format: gcp-sm://projects/PROJECT_ID/secrets/SECRET_NAME
 * Or short form:    gcp-sm://SECRET_NAME (uses configured project)
 *
 * Configuration:
 * - GCP credentials via standard GCP SDK chain (GOOGLE_APPLICATION_CREDENTIALS, etc.)
 * - flowcatalyst.secrets.gcp.project-id: GCP project ID (required for short form references)
 * - flowcatalyst.secrets.gcp-sm.enabled: Must be true to enable this provider
 */
@ApplicationScoped
@LookupIfProperty(name = "flowcatalyst.secrets.gcp-sm.enabled", stringValue = "true", lookupIfMissing = false)
public class GcpSecretManagerProvider implements SecretProvider {

    private static final Logger LOG = Logger.getLogger(GcpSecretManagerProvider.class);

    private static final String PREFIX = "gcp-sm://";
    private static final String PROJECTS_PREFIX = "projects/";

    @ConfigProperty(name = "flowcatalyst.secrets.gcp.project-id")
    java.util.Optional<String> projectId;

    private SecretManagerServiceClient client;

    @PostConstruct
    void init() {
        try {
            client = SecretManagerServiceClient.create();
            LOG.info("GCP Secret Manager provider initialized");
        } catch (IOException e) {
            LOG.error("Failed to initialize GCP Secret Manager client", e);
            throw new RuntimeException("Failed to initialize GCP Secret Manager", e);
        }
    }

    @PreDestroy
    void cleanup() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public String resolve(String reference) throws SecretResolutionException {
        if (!canHandle(reference)) {
            throw new SecretResolutionException("Invalid reference format for GCP Secret Manager provider");
        }

        String secretPath = extractSecretPath(reference);

        try {
            // Add /versions/latest if not specified
            String versionPath = secretPath.contains("/versions/")
                ? secretPath
                : secretPath + "/versions/latest";

            SecretVersionName versionName = SecretVersionName.parse(versionPath);
            AccessSecretVersionResponse response = client.accessSecretVersion(versionName);

            return response.getPayload().getData().toStringUtf8();
        } catch (com.google.api.gax.rpc.NotFoundException e) {
            throw new SecretResolutionException("Secret not found: " + secretPath, e);
        } catch (Exception e) {
            throw new SecretResolutionException("Failed to retrieve secret from GCP Secret Manager: " + secretPath, e);
        }
    }

    @Override
    public ValidationResult validate(String reference) {
        if (!canHandle(reference)) {
            return ValidationResult.failure("Invalid reference format for GCP Secret Manager");
        }

        String secretPath = extractSecretPath(reference);

        try {
            // Get secret metadata without retrieving the value
            SecretName secretName = SecretName.parse(secretPath);
            Secret secret = client.getSecret(secretName);

            // Check replication status
            String replicationInfo = secret.hasReplication() ? "configured" : "unknown";

            return ValidationResult.success("Secret exists in GCP Secret Manager (replication: " + replicationInfo + ")");
        } catch (com.google.api.gax.rpc.NotFoundException e) {
            return ValidationResult.failure("Secret not found: " + secretPath);
        } catch (com.google.api.gax.rpc.PermissionDeniedException e) {
            return ValidationResult.failure("Permission denied accessing secret: " + secretPath);
        } catch (Exception e) {
            LOG.debugf(e, "Failed to validate secret: %s", secretPath);
            return ValidationResult.failure("Failed to access secret: " + e.getMessage());
        }
    }

    @Override
    public boolean canHandle(String reference) {
        return reference != null && reference.startsWith(PREFIX);
    }

    @Override
    public String getType() {
        return "gcp-sm";
    }

    private String extractSecretPath(String reference) {
        String path = reference.substring(PREFIX.length());

        // If it's a short form (just secret name), expand to full path
        if (!path.startsWith(PROJECTS_PREFIX)) {
            if (projectId.isEmpty() || projectId.get().isBlank()) {
                throw new SecretResolutionException(
                    "GCP project ID not configured. Set flowcatalyst.secrets.gcp.project-id or use full path");
            }
            path = String.format("projects/%s/secrets/%s", projectId.get(), path);
        }

        return path;
    }
}
