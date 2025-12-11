package tech.flowcatalyst.eventprocessor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.flowcatalyst.eventprocessor.config.EventProcessorConfig;
import tech.flowcatalyst.eventprocessor.stream.ChangeStreamWatcher;

import java.util.logging.Logger;

/**
 * Programmatic API for starting and stopping the event processor.
 *
 * Usage in flowcatalyst-app:
 * <pre>
 * &#64;Inject
 * EventProcessorStarter eventProcessor;
 *
 * void onStart(&#64;Observes StartupEvent event) {
 *     eventProcessor.start();
 * }
 *
 * void onShutdown(&#64;Observes ShutdownEvent event) {
 *     eventProcessor.stop();
 * }
 * </pre>
 *
 * The processor will only start if event-processor.enabled=true in configuration.
 */
@ApplicationScoped
public class EventProcessorStarter {

    private static final Logger LOG = Logger.getLogger(EventProcessorStarter.class.getName());

    @Inject
    ChangeStreamWatcher watcher;

    @Inject
    EventProcessorConfig config;

    private volatile boolean started = false;

    /**
     * Start the event processor.
     *
     * This method is idempotent - calling it multiple times has no effect
     * after the first successful start.
     *
     * The processor only starts if event-processor.enabled=true.
     */
    public synchronized void start() {
        if (started) {
            LOG.warning("Event processor already started");
            return;
        }

        if (!config.enabled()) {
            LOG.info("Event processor disabled by configuration (event-processor.enabled=false)");
            return;
        }

        LOG.info("Starting event processor...");
        LOG.info("Configuration: concurrency=" + config.concurrency() +
                ", batchMaxSize=" + config.batchMaxSize() +
                ", batchMaxWaitMs=" + config.batchMaxWaitMs());
        LOG.info("Source: " + config.database() + "." + config.sourceCollection());
        LOG.info("Target: " + config.database() + "." + config.projectionCollection());

        watcher.start();
        started = true;

        LOG.info("Event processor started successfully");
    }

    /**
     * Stop the event processor gracefully.
     *
     * This method is idempotent - calling it multiple times has no effect.
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }

        LOG.info("Stopping event processor...");
        watcher.stop();
        started = false;

        LOG.info("Event processor stopped");
    }

    /**
     * Check if the event processor is currently running.
     */
    public boolean isRunning() {
        return started && watcher.isRunning();
    }

    /**
     * Check if the event processor is enabled in configuration.
     */
    public boolean isEnabled() {
        return config.enabled();
    }
}
