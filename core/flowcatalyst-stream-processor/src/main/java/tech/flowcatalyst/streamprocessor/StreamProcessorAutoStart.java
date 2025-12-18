package tech.flowcatalyst.streamprocessor;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.logging.Logger;

/**
 * Auto-starts the stream processor when running standalone.
 *
 * <p>When embedded in flowcatalyst-app, AppStartup handles the lifecycle instead.</p>
 */
@ApplicationScoped
public class StreamProcessorAutoStart {

    private static final Logger LOG = Logger.getLogger(StreamProcessorAutoStart.class.getName());

    @Inject
    StreamProcessorStarter starter;

    void onStart(@Observes StartupEvent event) {
        LOG.info("StreamProcessorAutoStart: triggering start");
        starter.start();
    }

    void onShutdown(@Observes ShutdownEvent event) {
        starter.stop();
    }
}
