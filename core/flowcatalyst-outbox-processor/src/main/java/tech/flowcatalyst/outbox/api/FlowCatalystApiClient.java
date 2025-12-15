package tech.flowcatalyst.outbox.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.flowcatalyst.outbox.config.OutboxProcessorConfig;
import tech.flowcatalyst.outbox.model.OutboxItem;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * HTTP client for FlowCatalyst batch APIs.
 * Uses Java 21 HttpClient with virtual threads for efficient concurrency.
 */
@ApplicationScoped
public class FlowCatalystApiClient {

    private static final Logger LOG = Logger.getLogger(FlowCatalystApiClient.class);

    @Inject
    OutboxProcessorConfig config;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public FlowCatalystApiClient() {
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Send a batch of events to FlowCatalyst.
     *
     * @param items List of outbox items containing event payloads
     * @throws ApiException if the API call fails
     */
    public void createEventsBatch(List<OutboxItem> items) throws ApiException {
        List<JsonNode> payloads = items.stream()
            .map(this::parsePayload)
            .toList();

        post("/api/events/batch", payloads);
    }

    /**
     * Send a batch of dispatch jobs to FlowCatalyst.
     *
     * @param items List of outbox items containing dispatch job payloads
     * @throws ApiException if the API call fails
     */
    public void createDispatchJobsBatch(List<OutboxItem> items) throws ApiException {
        List<JsonNode> payloads = items.stream()
            .map(this::parsePayload)
            .toList();

        post("/api/dispatch/jobs/batch", payloads);
    }

    private void post(String path, Object body) throws ApiException {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ApiException("Failed to serialize request body", e);
        }

        String url = config.apiBaseUrl() + path;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(json));

        // Add authorization header if token is configured
        config.apiToken().ifPresent(token ->
            requestBuilder.header("Authorization", "Bearer " + token));

        HttpRequest request = requestBuilder.build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                LOG.errorf("API error: POST %s returned %d: %s", path, response.statusCode(), response.body());
                throw new ApiException("API error: " + response.statusCode() + " - " + response.body());
            }

            LOG.debugf("POST %s returned %d", path, response.statusCode());

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to call API: POST %s", path);
            throw new ApiException("Failed to call API: " + e.getMessage(), e);
        }
    }

    private JsonNode parsePayload(OutboxItem item) {
        try {
            return objectMapper.readTree(item.payload());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Invalid JSON payload for item " + item.id(), e);
        }
    }

    /**
     * Exception thrown when FlowCatalyst API call fails.
     */
    public static class ApiException extends Exception {
        public ApiException(String message) {
            super(message);
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
