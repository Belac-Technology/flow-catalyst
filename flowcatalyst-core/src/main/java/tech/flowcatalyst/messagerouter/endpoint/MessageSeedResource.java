package tech.flowcatalyst.messagerouter.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tech.flowcatalyst.messagerouter.model.MessagePointer;

import java.util.Random;
import java.util.UUID;

@Path("/api/seed")
@Tag(name = "Message Seeding", description = "Endpoints for seeding test messages to queues")
public class MessageSeedResource {

    private static final Logger LOG = Logger.getLogger(MessageSeedResource.class);
    private static final Random RANDOM = new Random();

    private static final String[] QUEUES = {
        "http://localhost:9324/000000000000/flow-catalyst-high-priority.fifo",
        "http://localhost:9324/000000000000/flow-catalyst-medium-priority.fifo",
        "http://localhost:9324/000000000000/flow-catalyst-low-priority.fifo"
    };

    private static final String[] POOL_CODES = {"POOL-HIGH", "POOL-MEDIUM", "POOL-LOW"};

    private static final String[] ENDPOINTS = {
        "http://localhost:8080/api/test/fast",
        "http://localhost:8080/api/test/slow",
        "http://localhost:8080/api/test/faulty",
        "http://localhost:8080/api/test/fail"
    };

    @Inject
    SqsClient sqsClient;

    @Inject
    ObjectMapper objectMapper;

    @POST
    @Path("/messages")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Seed messages to queues")
    public Response seedMessages(
            @QueryParam("count") @DefaultValue("10") @Parameter(description = "Number of messages to send") int count,
            @QueryParam("queue") @DefaultValue("random") @Parameter(description = "Target queue: high, medium, low, or random") String queueParam,
            @QueryParam("endpoint") @DefaultValue("random") @Parameter(description = "Target endpoint: fast, slow, faulty, fail, or random") String endpointParam) {

        try {
            int successCount = 0;

            for (int i = 0; i < count; i++) {
                String queueUrl = selectQueue(queueParam);
                String poolCode = getPoolCodeForQueue(queueUrl);
                String targetEndpoint = selectEndpoint(endpointParam);

                MessagePointer message = new MessagePointer(
                    UUID.randomUUID().toString(),
                    poolCode,
                    "test-token-" + UUID.randomUUID(),
                    tech.flowcatalyst.messagerouter.model.MediationType.HTTP,
                    targetEndpoint
                );

                String messageBody = objectMapper.writeValueAsString(message);
                String messageGroupId = "test-group-" + RANDOM.nextInt(10); // 10 different message groups

                SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .messageGroupId(messageGroupId)
                    .messageDeduplicationId(message.id())
                    .build();

                sqsClient.sendMessage(request);
                successCount++;

                LOG.infof("Sent message %d/%d - ID: %s, Queue: %s, Pool: %s, Endpoint: %s",
                    i + 1, count, message.id(), getQueueName(queueUrl), poolCode, targetEndpoint);
            }

            return Response.ok()
                .entity(String.format("{\"status\":\"success\",\"messagesSent\":%d,\"totalRequested\":%d}", successCount, count))
                .build();

        } catch (Exception e) {
            LOG.error("Error seeding messages", e);
            return Response.status(500)
                .entity("{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}")
                .build();
        }
    }

    private String selectQueue(String queueParam) {
        return switch (queueParam.toLowerCase()) {
            case "high" -> QUEUES[0];
            case "medium" -> QUEUES[1];
            case "low" -> QUEUES[2];
            default -> QUEUES[RANDOM.nextInt(QUEUES.length)];
        };
    }

    private String getPoolCodeForQueue(String queueUrl) {
        if (queueUrl.contains("high-priority")) return POOL_CODES[0];
        if (queueUrl.contains("medium-priority")) return POOL_CODES[1];
        if (queueUrl.contains("low-priority")) return POOL_CODES[2];
        return POOL_CODES[0];
    }

    private String selectEndpoint(String endpointParam) {
        return switch (endpointParam.toLowerCase()) {
            case "fast" -> ENDPOINTS[0];
            case "slow" -> ENDPOINTS[1];
            case "faulty" -> ENDPOINTS[2];
            case "fail" -> ENDPOINTS[3];
            default -> ENDPOINTS[RANDOM.nextInt(ENDPOINTS.length)];
        };
    }

    private String getQueueName(String queueUrl) {
        String[] parts = queueUrl.split("/");
        return parts[parts.length - 1];
    }
}
