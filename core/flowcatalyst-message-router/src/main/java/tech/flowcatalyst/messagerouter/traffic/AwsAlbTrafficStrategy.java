package tech.flowcatalyst.messagerouter.traffic;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.Collections;
import java.util.logging.Logger;

/**
 * AWS Application Load Balancer traffic management strategy.
 *
 * Registers/deregisters this ECS task with an ALB target group based on
 * PRIMARY/STANDBY role. Only the PRIMARY instance receives traffic.
 *
 * Requirements:
 * - Running in AWS ECS/Fargate with ECS metadata endpoint available
 * - IAM role with elasticloadbalancing:RegisterTargets and DeregisterTargets
 * - Target group ARN configured
 *
 * The strategy automatically detects the task's IP address from ECS metadata
 * and registers/deregisters it from the specified target group.
 */
@ApplicationScoped
public class AwsAlbTrafficStrategy implements TrafficManagementStrategy {

    private static final Logger LOG = Logger.getLogger(AwsAlbTrafficStrategy.class.getName());
    private static final String ECS_METADATA_URI_ENV = "ECS_CONTAINER_METADATA_URI_V4";

    @Inject
    TrafficManagementConfig config;

    private ElasticLoadBalancingV2Client elbClient;
    private volatile boolean registered = false;
    private volatile String lastOperation = "none";
    private volatile String lastError = null;
    private volatile Instant lastOperationTime = null;

    private String cachedInstanceIp = null;

    /**
     * Initialize the ELB client.
     * Called lazily on first use to avoid creating client when strategy is not used.
     */
    private synchronized void initializeClient() {
        if (elbClient != null) {
            return;
        }

        try {
            Region region = determineRegion();
            elbClient = ElasticLoadBalancingV2Client.builder()
                    .region(region)
                    .build();
            LOG.info("Initialized AWS ELB client for region: " + region);
        } catch (Exception e) {
            LOG.severe("Failed to initialize AWS ELB client: " + e.getMessage());
            throw new RuntimeException("Cannot initialize AWS ELB client", e);
        }
    }

    @Override
    public void registerAsActive() throws TrafficManagementException {
        if (!config.enabled()) {
            LOG.fine("Traffic management disabled - skipping registration");
            return;
        }

        initializeClient();

        String targetGroupArn = config.awsAlb().targetGroupArn()
                .orElseThrow(() -> new TrafficManagementException(
                        "Target group ARN not configured - cannot register with ALB"));

        String instanceIp = getInstanceIp();
        int port = config.awsAlb().port();

        int maxRetries = config.awsAlb().maxRetries();
        int retryDelay = config.awsAlb().retryDelayMs();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                TargetDescription target = TargetDescription.builder()
                        .id(instanceIp)
                        .port(port)
                        .build();

                RegisterTargetsRequest request = RegisterTargetsRequest.builder()
                        .targetGroupArn(targetGroupArn)
                        .targets(target)
                        .build();

                elbClient.registerTargets(request);

                this.registered = true;
                this.lastOperation = "register";
                this.lastOperationTime = Instant.now();
                this.lastError = null;

                LOG.info("Successfully registered " + instanceIp + ":" + port +
                        " with target group " + targetGroupArn);
                return;

            } catch (SdkException e) {
                this.lastError = e.getMessage();
                if (attempt < maxRetries) {
                    LOG.warning("Failed to register with ALB (attempt " + attempt + "/" + maxRetries + "): " +
                            e.getMessage() + " - retrying in " + retryDelay + "ms");
                    try {
                        Thread.sleep(retryDelay);
                        retryDelay *= 2; // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new TrafficManagementException("Interrupted during retry", ie);
                    }
                } else {
                    LOG.severe("Failed to register with ALB after " + maxRetries + " attempts: " + e.getMessage());
                    throw new TrafficManagementException("Failed to register with ALB", e);
                }
            }
        }
    }

    @Override
    public void deregisterFromActive() throws TrafficManagementException {
        if (!config.enabled()) {
            LOG.fine("Traffic management disabled - skipping deregistration");
            return;
        }

        initializeClient();

        String targetGroupArn = config.awsAlb().targetGroupArn()
                .orElseThrow(() -> new TrafficManagementException(
                        "Target group ARN not configured - cannot deregister from ALB"));

        String instanceIp = getInstanceIp();
        int port = config.awsAlb().port();

        int maxRetries = config.awsAlb().maxRetries();
        int retryDelay = config.awsAlb().retryDelayMs();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                TargetDescription target = TargetDescription.builder()
                        .id(instanceIp)
                        .port(port)
                        .build();

                DeregisterTargetsRequest request = DeregisterTargetsRequest.builder()
                        .targetGroupArn(targetGroupArn)
                        .targets(target)
                        .build();

                elbClient.deregisterTargets(request);

                this.registered = false;
                this.lastOperation = "deregister";
                this.lastOperationTime = Instant.now();
                this.lastError = null;

                LOG.info("Successfully deregistered " + instanceIp + ":" + port +
                        " from target group " + targetGroupArn);
                return;

            } catch (SdkException e) {
                this.lastError = e.getMessage();
                if (attempt < maxRetries) {
                    LOG.warning("Failed to deregister from ALB (attempt " + attempt + "/" + maxRetries + "): " +
                            e.getMessage() + " - retrying in " + retryDelay + "ms");
                    try {
                        Thread.sleep(retryDelay);
                        retryDelay *= 2; // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new TrafficManagementException("Interrupted during retry", ie);
                    }
                } else {
                    LOG.severe("Failed to deregister from ALB after " + maxRetries + " attempts: " + e.getMessage());
                    throw new TrafficManagementException("Failed to deregister from ALB", e);
                }
            }
        }
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }

    @Override
    public TrafficStatus getStatus() {
        String targetInfo = null;
        if (config.awsAlb().targetGroupArn().isPresent()) {
            try {
                String ip = getInstanceIp();
                int port = config.awsAlb().port();
                targetInfo = ip + ":" + port + " -> " + config.awsAlb().targetGroupArn().get();
            } catch (Exception e) {
                targetInfo = "Error: " + e.getMessage();
            }
        }

        return new TrafficStatus(
                "aws-alb",
                registered,
                targetInfo,
                lastOperation + (lastOperationTime != null ? " at " + lastOperationTime : ""),
                lastError
        );
    }

    /**
     * Determine the AWS region to use for the ELB client.
     * Uses configured region if available, otherwise detects from ECS metadata.
     */
    private Region determineRegion() {
        return config.awsAlb().region()
                .map(Region::of)
                .orElseGet(() -> {
                    // Try to detect region from ECS metadata
                    try {
                        String metadataUri = System.getenv(ECS_METADATA_URI_ENV);
                        if (metadataUri != null) {
                            String taskMetadata = fetchUrl(metadataUri + "/task");
                            // Parse region from task ARN: arn:aws:ecs:REGION:...
                            if (taskMetadata.contains("\"TaskARN\"")) {
                                String arn = taskMetadata.split("\"TaskARN\"\\s*:\\s*\"")[1].split("\"")[0];
                                String region = arn.split(":")[3];
                                LOG.info("Detected AWS region from ECS metadata: " + region);
                                return Region.of(region);
                            }
                        }
                    } catch (Exception e) {
                        LOG.warning("Failed to detect region from ECS metadata: " + e.getMessage());
                    }

                    // Default to us-east-1
                    LOG.warning("Could not detect AWS region - defaulting to us-east-1");
                    return Region.US_EAST_1;
                });
    }

    /**
     * Get the instance IP address to register with the target group.
     * Uses configured instance ID if available, otherwise auto-detects from ECS metadata.
     */
    private String getInstanceIp() throws TrafficManagementException {
        // Use cached value if available
        if (cachedInstanceIp != null) {
            return cachedInstanceIp;
        }

        // Check if instance ID is configured
        if (config.awsAlb().instanceId().isPresent()) {
            String configuredId = config.awsAlb().instanceId().get();
            if (!"auto".equalsIgnoreCase(configuredId)) {
                LOG.info("Using configured instance IP: " + configuredId);
                cachedInstanceIp = configuredId;
                return cachedInstanceIp;
            }
        }

        // Auto-detect from ECS metadata
        try {
            String metadataUri = System.getenv(ECS_METADATA_URI_ENV);
            if (metadataUri == null) {
                throw new TrafficManagementException(
                        "ECS metadata endpoint not available - cannot auto-detect instance IP. " +
                        "Please configure traffic-management.aws-alb.instance-id manually.");
            }

            String taskMetadata = fetchUrl(metadataUri + "/task");

            // Parse IP from Containers[0].Networks[0].IPv4Addresses[0]
            // This is a simplified parse - in production you might want a JSON library
            if (taskMetadata.contains("\"IPv4Addresses\"")) {
                String ipSection = taskMetadata.split("\"IPv4Addresses\"\\s*:\\s*\\[")[1];
                String ip = ipSection.split("\"")[1];
                LOG.info("Detected instance IP from ECS metadata: " + ip);
                cachedInstanceIp = ip;
                return cachedInstanceIp;
            }

            throw new TrafficManagementException("Could not parse IP address from ECS metadata");

        } catch (Exception e) {
            throw new TrafficManagementException("Failed to detect instance IP from ECS metadata", e);
        }
    }

    /**
     * Fetch content from a URL.
     */
    private String fetchUrl(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } finally {
            conn.disconnect();
        }
    }
}
