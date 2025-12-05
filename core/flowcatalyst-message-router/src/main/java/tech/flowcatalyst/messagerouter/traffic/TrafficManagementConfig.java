package tech.flowcatalyst.messagerouter.traffic;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Configuration for traffic management strategies.
 *
 * Controls how instances register/deregister from load balancers
 * based on their PRIMARY/STANDBY role.
 */
@ConfigMapping(prefix = "traffic-management")
@ApplicationScoped
public interface TrafficManagementConfig {

    /**
     * Enable traffic management integration.
     * If false, no traffic management operations are performed.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Traffic management strategy to use.
     * Options: noop, aws-alb, k8s, readiness, webhook
     */
    @WithDefault("noop")
    String strategy();

    /**
     * AWS ALB specific configuration.
     */
    @WithName("aws-alb")
    AwsAlbConfig awsAlb();

    interface AwsAlbConfig {
        /**
         * ARN of the target group to register/deregister from.
         * Required when strategy=aws-alb.
         */
        Optional<String> targetGroupArn();

        /**
         * AWS region where the ALB is located.
         * Defaults to the region where the ECS task is running.
         */
        Optional<String> region();

        /**
         * Instance identifier to register.
         * Options:
         * - "auto": Auto-detect from ECS metadata
         * - IP address: Use specific IP
         * - Leave empty to auto-detect
         */
        Optional<String> instanceId();

        /**
         * Port to register with the target group.
         * Defaults to 8080 (the application port).
         */
        @WithDefault("8080")
        int port();

        /**
         * Maximum retry attempts for AWS API calls.
         */
        @WithDefault("3")
        int maxRetries();

        /**
         * Initial retry delay in milliseconds.
         */
        @WithDefault("1000")
        int retryDelayMs();
    }
}
