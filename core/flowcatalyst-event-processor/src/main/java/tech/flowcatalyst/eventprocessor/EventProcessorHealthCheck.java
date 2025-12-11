package tech.flowcatalyst.eventprocessor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import tech.flowcatalyst.eventprocessor.config.EventProcessorConfig;
import tech.flowcatalyst.eventprocessor.dispatch.BatchDispatcher;
import tech.flowcatalyst.eventprocessor.dispatch.CheckpointTracker;

/**
 * Health check for the event processor.
 *
 * Reports DOWN if:
 * - Event processor is enabled but not running
 * - A fatal error has occurred in batch processing
 */
@ApplicationScoped
@Readiness
public class EventProcessorHealthCheck implements HealthCheck {

    @Inject
    EventProcessorConfig config;

    @Inject
    EventProcessorStarter starter;

    @Inject
    CheckpointTracker checkpointTracker;

    @Inject
    BatchDispatcher dispatcher;

    @Override
    public HealthCheckResponse call() {
        // If disabled, always report UP (not our concern)
        if (!config.enabled()) {
            return HealthCheckResponse.builder()
                    .name("EventProcessor")
                    .up()
                    .withData("enabled", false)
                    .build();
        }

        // Check for fatal errors
        if (checkpointTracker.hasFatalError()) {
            Exception error = checkpointTracker.getFatalError();
            return HealthCheckResponse.builder()
                    .name("EventProcessor")
                    .down()
                    .withData("enabled", true)
                    .withData("running", starter.isRunning())
                    .withData("error", error.getMessage())
                    .build();
        }

        // Check if running
        if (!starter.isRunning()) {
            return HealthCheckResponse.builder()
                    .name("EventProcessor")
                    .down()
                    .withData("enabled", true)
                    .withData("running", false)
                    .withData("reason", "Event processor is not running")
                    .build();
        }

        // All good
        return HealthCheckResponse.builder()
                .name("EventProcessor")
                .up()
                .withData("enabled", true)
                .withData("running", true)
                .withData("batchesProcessed", dispatcher.getCurrentSequence())
                .withData("lastCheckpointedBatch", checkpointTracker.getLastCheckpointedSeq())
                .withData("inFlightBatches", checkpointTracker.getInFlightCount())
                .withData("availableConcurrencySlots", dispatcher.getAvailableSlots())
                .build();
    }
}
