package tech.flowcatalyst.streamprocessor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import tech.flowcatalyst.streamprocessor.config.StreamProcessorConfig;
import tech.flowcatalyst.streamprocessor.stream.StreamContext;

import java.util.Map;

/**
 * Health check for the stream processor.
 *
 * <p>Aggregates health status across all configured streams.</p>
 *
 * <p>Reports DOWN if:</p>
 * <ul>
 *   <li>Stream processor is enabled but no streams are running</li>
 *   <li>Any stream has encountered a fatal error</li>
 * </ul>
 */
@ApplicationScoped
@Readiness
public class StreamProcessorHealthCheck implements HealthCheck {

    @Inject
    StreamProcessorConfig config;

    @Inject
    StreamProcessorStarter starter;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.builder()
                .name("StreamProcessor");

        // If disabled, always report UP (not our concern)
        if (!config.enabled()) {
            return builder
                    .up()
                    .withData("enabled", false)
                    .build();
        }

        Map<String, StreamContext> streams = starter.getStreams();

        // Check if any streams are running
        if (streams.isEmpty()) {
            return builder
                    .down()
                    .withData("enabled", true)
                    .withData("running", false)
                    .withData("reason", "No streams configured or started")
                    .build();
        }

        // Check for fatal errors in any stream
        for (Map.Entry<String, StreamContext> entry : streams.entrySet()) {
            StreamContext context = entry.getValue();
            if (context.hasFatalError()) {
                Exception error = context.getFatalError();
                return builder
                        .down()
                        .withData("enabled", true)
                        .withData("running", starter.isRunning())
                        .withData("failedStream", entry.getKey())
                        .withData("error", error.getMessage())
                        .build();
            }
        }

        // Check if all streams are running
        long runningCount = streams.values().stream().filter(StreamContext::isRunning).count();
        if (runningCount == 0) {
            return builder
                    .down()
                    .withData("enabled", true)
                    .withData("running", false)
                    .withData("totalStreams", streams.size())
                    .withData("reason", "No streams are running")
                    .build();
        }

        // Aggregate metrics across all streams
        long totalBatches = 0;
        long totalCheckpointed = 0;
        int totalInFlight = 0;
        int totalAvailableSlots = 0;

        for (StreamContext context : streams.values()) {
            totalBatches += context.getCurrentBatchSequence();
            totalCheckpointed += context.getLastCheckpointedSequence();
            totalInFlight += context.getInFlightBatchCount();
            totalAvailableSlots += context.getAvailableConcurrencySlots();
        }

        // All good
        return builder
                .up()
                .withData("enabled", true)
                .withData("running", true)
                .withData("totalStreams", streams.size())
                .withData("runningStreams", runningCount)
                .withData("totalBatchesProcessed", totalBatches)
                .withData("totalCheckpointedBatches", totalCheckpointed)
                .withData("totalInFlightBatches", totalInFlight)
                .withData("totalAvailableConcurrencySlots", totalAvailableSlots)
                .build();
    }
}
