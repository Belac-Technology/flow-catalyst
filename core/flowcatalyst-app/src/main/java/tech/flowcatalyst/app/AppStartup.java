package tech.flowcatalyst.app;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import tech.flowcatalyst.streamprocessor.StreamProcessorStarter;

import java.util.logging.Logger;

/**
 * FlowCatalyst App startup handler.
 *
 * This is the all-in-one deployment that includes:
 * - flowcatalyst-platform (auth, admin, dispatch, events)
 * - flowcatalyst-message-router (message pointer routing)
 * - flowcatalyst-stream-processor (change stream processing)
 *
 * The stream processor is started programmatically here so that it can be
 * controlled independently in split deployments.
 */
@ApplicationScoped
public class AppStartup {

    private static final Logger LOG = Logger.getLogger(AppStartup.class.getName());

    @Inject
    StreamProcessorStarter streamProcessor;

    void onStart(@Observes StartupEvent event) {
        LOG.info("FlowCatalyst App starting...");

        // Start the stream processor (if enabled)
        streamProcessor.start();

        LOG.info("FlowCatalyst App started successfully");
    }

    void onShutdown(@Observes ShutdownEvent event) {
        LOG.info("FlowCatalyst App shutting down...");

        // Stop the stream processor gracefully
        streamProcessor.stop();

        LOG.info("FlowCatalyst App shutdown complete");
    }
}
