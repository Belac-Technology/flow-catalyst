package tech.flowcatalyst.postbox.health;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import tech.flowcatalyst.postbox.service.PollerDiscovery;

/**
 * Health check for postbox poller system
 * Reports the status of partition pollers and discovery mechanism
 */
@Unremovable
@Liveness
@ApplicationScoped
public class PostboxHealthCheck implements HealthCheck {

    @Inject
    PollerDiscovery pollerDiscovery;

    @Override
    public HealthCheckResponse call() {
        try {
            int pollerCount = pollerDiscovery.getPollerCount();

            return HealthCheckResponse.builder()
                    .name("Postbox Pollers")
                    .up()
                    .withData("active_pollers", pollerCount)
                    .withData("status", "healthy")
                    .build();

        } catch (Exception e) {
            return HealthCheckResponse.builder()
                    .name("Postbox Pollers")
                    .down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }

}
