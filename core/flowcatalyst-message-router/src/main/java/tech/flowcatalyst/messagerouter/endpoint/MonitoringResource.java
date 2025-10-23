package tech.flowcatalyst.messagerouter.endpoint;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tech.flowcatalyst.messagerouter.health.HealthStatusService;
import tech.flowcatalyst.messagerouter.metrics.CircuitBreakerMetricsService;
import tech.flowcatalyst.messagerouter.metrics.PoolMetricsService;
import tech.flowcatalyst.messagerouter.metrics.QueueMetricsService;
import tech.flowcatalyst.messagerouter.metrics.QueueStats;
import tech.flowcatalyst.messagerouter.model.CircuitBreakerStats;
import tech.flowcatalyst.messagerouter.model.HealthStatus;
import tech.flowcatalyst.messagerouter.model.PoolStats;
import tech.flowcatalyst.messagerouter.model.Warning;
import tech.flowcatalyst.messagerouter.warning.WarningService;

import java.util.List;
import java.util.Map;

@Path("/monitoring")
@Tag(name = "Monitoring", description = "Monitoring and metrics endpoints for dashboard")
public class MonitoringResource {

    @Inject
    QueueMetricsService queueMetricsService;

    @Inject
    PoolMetricsService poolMetricsService;

    @Inject
    WarningService warningService;

    @Inject
    CircuitBreakerMetricsService circuitBreakerMetricsService;

    @Inject
    HealthStatusService healthStatusService;

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get system health status", description = "Returns overall system health with aggregated metrics")
    public HealthStatus getHealthStatus() {
        return healthStatusService.getHealthStatus();
    }

    @GET
    @Path("/queue-stats")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get queue statistics", description = "Returns statistics for all queues")
    @APIResponse(
        responseCode = "200",
        description = "Queue statistics",
        content = @Content(
            mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = QueueStats.class),
            examples = @ExampleObject(
                name = "Queue Statistics Example",
                value = """
                {
                  "flow-catalyst-high-priority.fifo": {
                    "name": "flow-catalyst-high-priority.fifo",
                    "totalMessages": 150000,
                    "totalConsumed": 149950,
                    "totalFailed": 50,
                    "successRate": 0.9996666666666667,
                    "currentSize": 500,
                    "throughput": 25.5,
                    "pendingMessages": 500,
                    "messagesNotVisible": 10
                  },
                  "flow-catalyst-medium-priority.fifo": {
                    "name": "flow-catalyst-medium-priority.fifo",
                    "totalMessages": 85000,
                    "totalConsumed": 84980,
                    "totalFailed": 20,
                    "successRate": 0.9997647058823529,
                    "currentSize": 150,
                    "throughput": 15.2,
                    "pendingMessages": 150,
                    "messagesNotVisible": 5
                  },
                  "flow-catalyst-low-priority.fifo": {
                    "name": "flow-catalyst-low-priority.fifo",
                    "totalMessages": 45000,
                    "totalConsumed": 44995,
                    "totalFailed": 5,
                    "successRate": 0.9998888888888889,
                    "currentSize": 50,
                    "throughput": 8.1,
                    "pendingMessages": 50,
                    "messagesNotVisible": 2
                  }
                }
                """
            )
        )
    )
    public Map<String, QueueStats> getQueueStats() {
        return queueMetricsService.getAllQueueStats();
    }

    @GET
    @Path("/pool-stats")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get pool statistics", description = "Returns statistics for all processing pools")
    @APIResponse(
        responseCode = "200",
        description = "Pool statistics",
        content = @Content(
            mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = PoolStats.class),
            examples = @ExampleObject(
                name = "Pool Statistics Example",
                value = """
                {
                  "POOL-HIGH": {
                    "poolCode": "POOL-HIGH",
                    "totalProcessed": 128647,
                    "totalSucceeded": 128637,
                    "totalFailed": 10,
                    "totalRateLimited": 0,
                    "successRate": 0.9999222679114165,
                    "activeWorkers": 10,
                    "availablePermits": 0,
                    "maxConcurrency": 10,
                    "queueSize": 500,
                    "maxQueueCapacity": 500,
                    "averageProcessingTimeMs": 103.82261537385249
                  },
                  "POOL-MEDIUM": {
                    "poolCode": "POOL-MEDIUM",
                    "totalProcessed": 65432,
                    "totalSucceeded": 65425,
                    "totalFailed": 7,
                    "totalRateLimited": 0,
                    "successRate": 0.9998930481283422,
                    "activeWorkers": 5,
                    "availablePermits": 0,
                    "maxConcurrency": 5,
                    "queueSize": 250,
                    "maxQueueCapacity": 500,
                    "averageProcessingTimeMs": 125.5
                  },
                  "POOL-LOW": {
                    "poolCode": "POOL-LOW",
                    "totalProcessed": 32100,
                    "totalSucceeded": 32098,
                    "totalFailed": 2,
                    "totalRateLimited": 0,
                    "successRate": 0.9999376947040498,
                    "activeWorkers": 2,
                    "availablePermits": 0,
                    "maxConcurrency": 2,
                    "queueSize": 75,
                    "maxQueueCapacity": 500,
                    "averageProcessingTimeMs": 95.3
                  }
                }
                """
            )
        )
    )
    public Map<String, PoolStats> getPoolStats() {
        return poolMetricsService.getAllPoolStats();
    }

    @GET
    @Path("/warnings")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get all warnings", description = "Returns all system warnings")
    public List<Warning> getAllWarnings() {
        return warningService.getAllWarnings();
    }

    @GET
    @Path("/warnings/unacknowledged")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get unacknowledged warnings", description = "Returns all unacknowledged warnings")
    public List<Warning> getUnacknowledgedWarnings() {
        return warningService.getUnacknowledgedWarnings();
    }

    @GET
    @Path("/warnings/severity/{severity}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get warnings by severity", description = "Returns warnings filtered by severity")
    public List<Warning> getWarningsBySeverity(
            @PathParam("severity") @Parameter(description = "Severity level (e.g., ERROR, WARN, INFO)") String severity) {
        return warningService.getWarningsBySeverity(severity);
    }

    @POST
    @Path("/warnings/{warningId}/acknowledge")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Acknowledge a warning", description = "Mark a warning as acknowledged")
    public Response acknowledgeWarning(
            @PathParam("warningId") @Parameter(description = "Warning ID") String warningId) {
        boolean acknowledged = warningService.acknowledgeWarning(warningId);
        if (acknowledged) {
            return Response.ok("{\"status\":\"success\"}").build();
        } else {
            return Response.status(404).entity("{\"status\":\"error\",\"message\":\"Warning not found\"}").build();
        }
    }

    @DELETE
    @Path("/warnings")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Clear all warnings", description = "Delete all warnings from the system")
    public Response clearAllWarnings() {
        warningService.clearAllWarnings();
        return Response.ok("{\"status\":\"success\"}").build();
    }

    @DELETE
    @Path("/warnings/old")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Clear old warnings", description = "Delete warnings older than specified hours")
    public Response clearOldWarnings(
            @QueryParam("hours") @DefaultValue("24") @Parameter(description = "Age in hours") int hours) {
        warningService.clearOldWarnings(hours);
        return Response.ok("{\"status\":\"success\"}").build();
    }

    @GET
    @Path("/circuit-breakers")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get circuit breaker statistics", description = "Returns statistics for all circuit breakers")
    @APIResponse(
        responseCode = "200",
        description = "Circuit breaker statistics",
        content = @Content(
            mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = CircuitBreakerStats.class),
            examples = @ExampleObject(
                name = "Circuit Breaker Statistics Example",
                value = """
                {
                  "http://localhost:8081/api/slow": {
                    "name": "http://localhost:8081/api/slow",
                    "state": "CLOSED",
                    "successfulCalls": 10000,
                    "failedCalls": 0,
                    "rejectedCalls": 0,
                    "failureRate": 0.0,
                    "bufferedCalls": 100,
                    "bufferSize": 100
                  },
                  "http://localhost:8081/api/faulty": {
                    "name": "http://localhost:8081/api/faulty",
                    "state": "HALF_OPEN",
                    "successfulCalls": 8500,
                    "failedCalls": 150,
                    "rejectedCalls": 25,
                    "failureRate": 0.017341040462428,
                    "bufferedCalls": 100,
                    "bufferSize": 100
                  },
                  "https://api.example.com/endpoint": {
                    "name": "https://api.example.com/endpoint",
                    "state": "OPEN",
                    "successfulCalls": 5000,
                    "failedCalls": 500,
                    "rejectedCalls": 1500,
                    "failureRate": 0.09090909090909091,
                    "bufferedCalls": 100,
                    "bufferSize": 100
                  }
                }
                """
            )
        )
    )
    public Map<String, CircuitBreakerStats> getCircuitBreakerStats() {
        return circuitBreakerMetricsService.getAllCircuitBreakerStats();
    }

    @GET
    @Path("/circuit-breakers/{name}/state")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get circuit breaker state", description = "Returns the state of a specific circuit breaker")
    public Response getCircuitBreakerState(
            @PathParam("name") @Parameter(description = "Circuit breaker name") String name) {
        String state = circuitBreakerMetricsService.getCircuitBreakerState(name);
        return Response.ok("{\"name\":\"" + name + "\",\"state\":\"" + state + "\"}").build();
    }

    @POST
    @Path("/circuit-breakers/{name}/reset")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Reset circuit breaker", description = "Reset a specific circuit breaker")
    public Response resetCircuitBreaker(
            @PathParam("name") @Parameter(description = "Circuit breaker name") String name) {
        boolean reset = circuitBreakerMetricsService.resetCircuitBreaker(name);
        if (reset) {
            return Response.ok("{\"status\":\"success\"}").build();
        } else {
            return Response.status(500).entity("{\"status\":\"error\",\"message\":\"Failed to reset circuit breaker\"}").build();
        }
    }

    @POST
    @Path("/circuit-breakers/reset-all")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Reset all circuit breakers", description = "Reset all circuit breakers")
    public Response resetAllCircuitBreakers() {
        circuitBreakerMetricsService.resetAllCircuitBreakers();
        return Response.ok("{\"status\":\"success\"}").build();
    }
}
