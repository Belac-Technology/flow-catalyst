package tech.flowcatalyst.postbox.endpoint;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tech.flowcatalyst.postbox.dto.PostboxPayload;
import tech.flowcatalyst.postbox.entity.PostboxMessage;
import tech.flowcatalyst.postbox.service.PostboxService;

import java.util.HashMap;
import java.util.Map;

@Path("/api/v1/postbox")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PostboxIngestResource {

    @Inject
    PostboxService postboxService;

    @POST
    @Path("/ingest")
    public Response ingestMessage(@Valid PostboxPayload payload) {
        try {
            // Validate required fields
            if (payload.id == null || payload.id.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("Missing required field: id"))
                        .build();
            }
            if (payload.tenantId == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("Missing required field: tenantId"))
                        .build();
            }
            if (payload.partitionId == null || payload.partitionId.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("Missing required field: partitionId"))
                        .build();
            }
            if (payload.type == null || payload.type.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("Missing required field: type"))
                        .build();
            }
            if (payload.payload == null || payload.payload.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("Missing required field: payload"))
                        .build();
            }

            // Validate type enum
            try {
                tech.flowcatalyst.postbox.model.MessageType.valueOf(payload.type);
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(createErrorResponse("Invalid type: must be EVENT or DISPATCH_JOB"))
                        .build();
            }

            // Ingest message
            PostboxMessage message = postboxService.ingestMessage(payload);

            // Return response
            Map<String, Object> response = new HashMap<>();
            response.put("id", message.id);
            response.put("created_at", message.createdAt);
            response.put("payload_size", message.payloadSize);

            return Response.status(Response.Status.CREATED)
                    .entity(response)
                    .build();

        } catch (IllegalArgumentException e) {
            return Response.status(413)  // Payload Too Large
                    .entity(createErrorResponse(e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(createErrorResponse("Internal server error: " + e.getMessage()))
                    .build();
        }
    }

    private Map<String, String> createErrorResponse(String message) {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return error;
    }

}
