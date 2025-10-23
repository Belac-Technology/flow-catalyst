package tech.flowcatalyst.dispatchjob.endpoint;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import tech.flowcatalyst.dispatchjob.dto.*;
import tech.flowcatalyst.dispatchjob.entity.DispatchJob;
import tech.flowcatalyst.dispatchjob.model.DispatchStatus;
import tech.flowcatalyst.dispatchjob.service.DispatchJobService;

import java.time.Instant;
import java.util.List;

@Path("/api/dispatch/jobs")
@Tag(name = "Dispatch Jobs", description = "Endpoints for managing dispatch jobs")
@IfBuildProperty(name = "dispatch-jobs.enabled", stringValue = "true", enableIfMissing = true)
public class DispatchJobResource {

    private static final Logger LOG = Logger.getLogger(DispatchJobResource.class);

    @Inject
    DispatchJobService dispatchJobService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create a new dispatch job", description = "Creates and queues a new dispatch job for webhook delivery")
    @APIResponses({
        @APIResponse(
            responseCode = "201",
            description = "Dispatch job created successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = DispatchJobResponse.class))
        ),
        @APIResponse(
            responseCode = "400",
            description = "Invalid request - missing or invalid fields",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))
        ),
        @APIResponse(
            responseCode = "500",
            description = "Internal server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response createDispatchJob(@Valid CreateDispatchJobRequest request) {
        LOG.infof("Creating dispatch job: type=%s, source=%s", request.type(), request.source());

        try {
            DispatchJob job = dispatchJobService.createDispatchJob(request);
            return Response.status(201).entity(DispatchJobResponse.from(job)).build();

        } catch (IllegalArgumentException e) {
            LOG.errorf("Invalid request: %s", e.getMessage());
            return Response.status(400).entity(new ErrorResponse(e.getMessage())).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error creating dispatch job");
            return Response.status(500).entity(new ErrorResponse("Internal error: " + e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get dispatch job by ID", description = "Retrieves detailed information about a specific dispatch job")
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Dispatch job found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = DispatchJobResponse.class))
        ),
        @APIResponse(
            responseCode = "404",
            description = "Dispatch job not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response getDispatchJob(@PathParam("id") Long id) {
        return dispatchJobService.findById(id)
            .map(job -> Response.ok(DispatchJobResponse.from(job)).build())
            .orElse(Response.status(404).entity(new ErrorResponse("Dispatch job not found")).build());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Search and filter dispatch jobs", description = "Search for dispatch jobs with optional filters and pagination")
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Search results returned",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = PagedDispatchJobResponse.class))
        )
    })
    public Response searchDispatchJobs(
        @QueryParam("status") DispatchStatus status,
        @QueryParam("source") String source,
        @QueryParam("type") String type,
        @QueryParam("groupId") String groupId,
        @QueryParam("createdAfter") Instant createdAfter,
        @QueryParam("createdBefore") Instant createdBefore,
        @QueryParam("page") @DefaultValue("0") Integer page,
        @QueryParam("size") @DefaultValue("20") Integer size
    ) {
        DispatchJobFilter filter = new DispatchJobFilter(
            status, source, type, groupId, createdAfter, createdBefore, page, size
        );

        List<DispatchJob> jobs = dispatchJobService.findWithFilter(filter);
        long totalCount = dispatchJobService.countWithFilter(filter);

        List<DispatchJobResponse> responses = jobs.stream()
            .map(DispatchJobResponse::from)
            .toList();

        return Response.ok(new PagedDispatchJobResponse(
            responses,
            page,
            size,
            totalCount,
            (int) Math.ceil((double) totalCount / size)
        )).build();
    }

    @GET
    @Path("/{id}/attempts")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get all attempts for a dispatch job", description = "Retrieves the full history of webhook delivery attempts for a job")
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Attempts list returned",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = DispatchAttemptResponse.class))
        ),
        @APIResponse(
            responseCode = "404",
            description = "Dispatch job not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))
        )
    })
    public Response getDispatchJobAttempts(@PathParam("id") Long id) {
        return dispatchJobService.findById(id)
            .map(job -> {
                List<DispatchAttemptResponse> responses = job.attempts.stream()
                    .map(DispatchAttemptResponse::from)
                    .toList();
                return Response.ok(responses).build();
            })
            .orElse(Response.status(404).entity(new ErrorResponse("Dispatch job not found")).build());
    }

    public record PagedDispatchJobResponse(
        List<DispatchJobResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
    ) {
    }
}
