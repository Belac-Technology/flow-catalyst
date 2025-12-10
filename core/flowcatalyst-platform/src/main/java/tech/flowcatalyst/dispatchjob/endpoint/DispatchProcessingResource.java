package tech.flowcatalyst.dispatchjob.endpoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
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
import tech.flowcatalyst.dispatchjob.service.DispatchJobService;

@Path("/api/dispatch/process")
@Tag(name = "Dispatch Processing", description = "Internal endpoint for processing dispatch jobs via message router")
public class DispatchProcessingResource {

    private static final Logger LOG = Logger.getLogger(DispatchProcessingResource.class);

    @Inject
    DispatchJobService dispatchJobService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Process a dispatch job (internal endpoint called by message router)", description = "Internal endpoint that executes webhook dispatch and records attempts")
    @APIResponses({
        @APIResponse(
            responseCode = "200",
            description = "Job processed successfully or max attempts exhausted",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ProcessResponse.class))
        ),
        @APIResponse(
            responseCode = "400",
            description = "Job failed, will retry",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ProcessResponse.class))
        ),
        @APIResponse(
            responseCode = "500",
            description = "Internal error during processing",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ProcessResponse.class))
        )
    })
    public Response processDispatchJob(ProcessRequest request) {
        LOG.infof("Received dispatch job processing request: %s", request.messageId());

        try {
            String dispatchJobId = request.messageId();

            DispatchJobService.DispatchJobProcessResult result = dispatchJobService.processDispatchJob(dispatchJobId);

            return Response
                .status(result.httpStatusCode())
                .entity(new ProcessResponse(result.success(), result.message()))
                .build();

        } catch (IllegalArgumentException e) {
            LOG.errorf("Invalid dispatch job ID: %s", request.messageId());
            return Response
                .status(400)
                .entity(new ProcessResponse(false, "Invalid job ID"))
                .build();

        } catch (Exception e) {
            LOG.errorf(e, "Error processing dispatch job: %s", request.messageId());
            return Response
                .status(500)
                .entity(new ProcessResponse(false, "Internal error: " + e.getMessage()))
                .build();
        }
    }

    public record ProcessRequest(
        @JsonProperty("messageId") String messageId
    ) {
    }

    public record ProcessResponse(
        @JsonProperty("success") boolean success,
        @JsonProperty("message") String message
    ) {
    }
}
