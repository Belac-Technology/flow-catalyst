package tech.flowcatalyst.app;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import tech.flowcatalyst.eventprocessor.EventProcessorStarter;

import java.util.logging.Logger;

/**
 * FlowCatalyst App startup handler.
 *
 * This is the all-in-one deployment that includes:
 * - flowcatalyst-platform (auth, admin, dispatch, events)
 * - flowcatalyst-message-router (message pointer routing)
 * - flowcatalyst-event-processor (change stream processing)
 *
 * The event processor is started programmatically here so that it can be
 * controlled independently in split deployments.
 */
@ApplicationScoped
public class AppStartup {

    private static final Logger LOG = Logger.getLogger(AppStartup.class.getName());

    @Inject
    EventProcessorStarter eventProcessor;

    void onStart(@Observes StartupEvent event) {
        LOG.info("FlowCatalyst App starting...");

        // Start the event processor (if enabled)
        eventProcessor.start();

        LOG.info("FlowCatalyst App started successfully");
    }

    void onShutdown(@Observes ShutdownEvent event) {
        LOG.info("FlowCatalyst App shutting down...");

        // Stop the event processor gracefully
        eventProcessor.stop();

        LOG.info("FlowCatalyst App shutdown complete");
    }
}
