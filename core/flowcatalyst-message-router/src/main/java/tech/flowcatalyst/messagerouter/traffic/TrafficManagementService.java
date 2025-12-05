package tech.flowcatalyst.messagerouter.traffic;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.logging.Logger;

/**
 * Orchestration service for traffic management strategies.
 *
 * Selects the appropriate strategy based on configuration and
 * provides a unified interface for registering/deregistering
 * this instance with load balancers.
 *
 * Handles errors gracefully - traffic management failures are logged
 * but don't crash the application or affect standby mode operation.
 */
@ApplicationScoped
public class TrafficManagementService {

    private static final Logger LOG = Logger.getLogger(TrafficManagementService.class.getName());

    @Inject
    TrafficManagementConfig config;

    @Inject
    NoOpTrafficStrategy noOpStrategy;

    @Inject
    jakarta.enterprise.inject.Instance<AwsAlbTrafficStrategy> awsAlbStrategyInstance;

    private TrafficManagementStrategy activeStrategy;

    /**
     * Initialize and select the appropriate strategy on startup.
     */
    void onStartup(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.info("Traffic management disabled - using no-op strategy");
            activeStrategy = noOpStrategy;
            return;
        }

        String strategyType = config.strategy().toLowerCase();
        LOG.info("Traffic management enabled with strategy: " + strategyType);

        switch (strategyType) {
            case "noop":
                activeStrategy = noOpStrategy;
                LOG.info("Using no-op traffic strategy");
                break;

            case "aws-alb":
                if (awsAlbStrategyInstance.isResolvable()) {
                    activeStrategy = awsAlbStrategyInstance.get();
                    LOG.info("Using AWS ALB traffic strategy");
                    validateAwsAlbConfig();
                } else {
                    LOG.warning("AWS ALB strategy requested but not available - falling back to no-op");
                    activeStrategy = noOpStrategy;
                }
                break;

            default:
                LOG.warning("Unknown traffic management strategy: " + strategyType + " - using no-op");
                activeStrategy = noOpStrategy;
                break;
        }
    }

    /**
     * Register this instance as active with the load balancer.
     * Should be called when instance becomes PRIMARY.
     *
     * Failures are logged but don't throw exceptions - graceful degradation.
     */
    public void registerAsActive() {
        if (activeStrategy == null) {
            LOG.warning("Traffic management strategy not initialized - skipping registration");
            return;
        }

        try {
            LOG.info("Registering instance as active with load balancer");
            activeStrategy.registerAsActive();
        } catch (TrafficManagementStrategy.TrafficManagementException e) {
            LOG.severe("Failed to register instance with load balancer: " + e.getMessage() +
                    " - Instance may receive traffic despite being STANDBY");
            // Don't throw - allow standby mode to continue working
        } catch (Exception e) {
            LOG.severe("Unexpected error during traffic registration: " + e.getMessage());
        }
    }

    /**
     * Deregister this instance from the load balancer.
     * Should be called when instance becomes STANDBY or shuts down.
     *
     * Failures are logged but don't throw exceptions - graceful degradation.
     */
    public void deregisterFromActive() {
        if (activeStrategy == null) {
            LOG.warning("Traffic management strategy not initialized - skipping deregistration");
            return;
        }

        try {
            LOG.info("Deregistering instance from load balancer");
            activeStrategy.deregisterFromActive();
        } catch (TrafficManagementStrategy.TrafficManagementException e) {
            LOG.severe("Failed to deregister instance from load balancer: " + e.getMessage() +
                    " - Instance may continue receiving traffic despite being STANDBY");
            // Don't throw - allow standby mode to continue working
        } catch (Exception e) {
            LOG.severe("Unexpected error during traffic deregistration: " + e.getMessage());
        }
    }

    /**
     * Check if this instance is currently registered with the load balancer.
     *
     * @return true if registered, false otherwise
     */
    public boolean isRegistered() {
        if (activeStrategy == null) {
            return false;
        }
        return activeStrategy.isRegistered();
    }

    /**
     * Get the current traffic management status for monitoring.
     *
     * @return status information
     */
    public TrafficManagementStrategy.TrafficStatus getStatus() {
        if (activeStrategy == null) {
            return new TrafficManagementStrategy.TrafficStatus(
                    "uninitialized",
                    false,
                    "Strategy not initialized",
                    "none",
                    "Strategy not initialized"
            );
        }
        return activeStrategy.getStatus();
    }

    /**
     * Validate AWS ALB configuration.
     */
    private void validateAwsAlbConfig() {
        if (config.awsAlb().targetGroupArn().isEmpty()) {
            LOG.severe("AWS ALB strategy enabled but target group ARN not configured! " +
                    "Set traffic-management.aws-alb.target-group-arn in application.properties");
        }

        if (config.awsAlb().port() <= 0 || config.awsAlb().port() > 65535) {
            LOG.warning("Invalid port configured: " + config.awsAlb().port() + " - using default 8080");
        }

        // Check if running in ECS
        String metadataUri = System.getenv("ECS_CONTAINER_METADATA_URI_V4");
        if (metadataUri == null && config.awsAlb().instanceId().isEmpty()) {
            LOG.warning("Not running in ECS and instance ID not configured - " +
                    "traffic management may fail. Set traffic-management.aws-alb.instance-id");
        }
    }
}
