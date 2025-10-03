package tech.flowcatalyst.postbox.service;

import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import tech.flowcatalyst.postbox.config.PostboxPollerConfig;
import tech.flowcatalyst.postbox.entity.PostboxMessage;
import tech.flowcatalyst.postbox.handler.MessageHandlerFactory;
import tech.flowcatalyst.postbox.metrics.PostboxMetrics;
import tech.flowcatalyst.postbox.model.MessageStatus;
import tech.flowcatalyst.postbox.repository.PostboxMessageRepository;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

@ApplicationScoped
public class PartitionPoller {

    private static final Logger log = Logger.getLogger(PartitionPoller.class);

    @Inject
    PostboxMessageRepository repository;

    @Inject
    PostboxPollerConfig config;

    @Inject
    MessageHandlerFactory handlerFactory;

    @Inject
    PostboxMetrics metrics;

    private final Long tenantId;
    private final String partitionId;
    private Client httpClient;

    public PartitionPoller() {
        this.tenantId = null;
        this.partitionId = null;
    }

    public PartitionPoller(Long tenantId, String partitionId) {
        this.tenantId = tenantId;
        this.partitionId = partitionId;
        this.httpClient = ClientBuilder.newClient();
    }

    /**
     * Poll for pending messages and process them
     * This is called on a scheduled interval
     */
    public void poll() {
        if (tenantId == null || partitionId == null) {
            log.warn("PartitionPoller not properly initialized");
            return;
        }

        try {
            List<PostboxMessage> pendingMessages = repository.findPendingMessages(
                    tenantId,
                    partitionId,
                    config.batchSize()
            );

            if (pendingMessages.isEmpty()) {
                return;
            }

            log.debugf("Processing %d pending messages for tenant=%d, partition=%s",
                    pendingMessages.size(), tenantId, partitionId);

            for (PostboxMessage message : pendingMessages) {
                processMessage(message);
            }
        } catch (Exception e) {
            log.errorf(e, "Error polling messages for tenant=%d, partition=%s", tenantId, partitionId);
        }
    }

    public void processMessage(PostboxMessage message) {
        Timer.Sample sample = metrics.startProcessingTimer();
        try {
            // Send message to consumer endpoint
            // In Phase 3, this will route to actual event handlers
            sendMessage(message);
            metrics.recordMessageProcessed(message);
        } catch (Exception e) {
            log.errorf(e, "Failed to process message %s", message.id);
            metrics.recordMessageFailed(message);
            repository.updateStatusWithError(
                    message.id,
                    MessageStatus.FAILED,
                    Instant.now(),
                    "Processing error: " + e.getMessage()
            );
        } finally {
            metrics.stopProcessingTimer(sample);
        }
    }

    private void sendMessage(PostboxMessage message) throws Exception {
        try {
            // Route to appropriate handler based on message type
            var handler = handlerFactory.getHandler(message.type);
            handler.handle(message);

            // Mark as processed after successful handling
            repository.updateStatus(
                    message.id,
                    MessageStatus.PROCESSED,
                    Instant.now()
            );
        } catch (Exception e) {
            log.errorf(e, "Error sending message %s", message.id);
            throw e;
        }
    }

    /**
     * Compress string payload with gzip
     */
    private String compressPayload(String payload) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /**
     * Handle retry logic based on HTTP response code
     */
    private void handleResponse(PostboxMessage message, Response response) {
        int status = response.getStatus();

        if (status >= 200 && status < 300) {
            // Success
            repository.updateStatus(message.id, MessageStatus.PROCESSED, Instant.now());
        } else if (status >= 400 && status < 500) {
            // Client error - increment retry count
            repository.incrementRetryCount(message.id);
            PostboxMessage updated = repository.findByMessageId(message.id);

            if (updated != null && updated.retryCount >= config.maxRetries()) {
                // Max retries exceeded
                repository.updateStatusWithError(
                        message.id,
                        MessageStatus.FAILED,
                        Instant.now(),
                        "Max retries exceeded (4xx errors)"
                );
            } else {
                // Keep trying - update error reason but keep status as PENDING
                repository.updateRetryCountAndError(
                        message.id,
                        "HTTP " + status + " - will retry"
                );
            }
        } else {
            // Server error or connection issue - don't increment counter
            // Keep retrying indefinitely
            repository.updateRetryCountAndError(
                    message.id,
                    "HTTP " + status + " - will keep retrying"
            );
        }
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getPartitionId() {
        return partitionId;
    }

    @Override
    public String toString() {
        return "PartitionPoller{" +
                "tenantId=" + tenantId +
                ", partitionId='" + partitionId + '\'' +
                '}';
    }

}
