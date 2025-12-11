package tech.flowcatalyst.eventprocessor;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.logging.Logger;

/**
 * Auto-starts the event processor when running standalone (not embedded in flowcatalyst-app).
 *
 * When embedded in flowcatalyst-app, AppStartup handles the lifecycle instead.
 */
@ApplicationScoped
public class EventProcessorAutoStart {

    private static final Logger LOG = Logger.getLogger(EventProcessorAutoStart.class.getName());

    @Inject
    EventProcessorStarter starter;

    void onStart(@Observes StartupEvent event) {
        LOG.info("EventProcessorAutoStart: triggering start");
        starter.start();
    }

    void onShutdown(@Observes ShutdownEvent event) {
        starter.stop();
    }
}
