package tech.flowcatalyst.messagerouter.endpoint;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
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
    public Map<String, QueueStats> getQueueStats() {
        return queueMetricsService.getAllQueueStats();
    }

    @GET
    @Path("/pool-stats")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get pool statistics", description = "Returns statistics for all processing pools")
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
