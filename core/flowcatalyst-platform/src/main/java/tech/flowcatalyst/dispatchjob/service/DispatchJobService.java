package tech.flowcatalyst.dispatchjob.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tech.flowcatalyst.dispatchjob.dto.CreateDispatchJobRequest;
import tech.flowcatalyst.dispatchjob.dto.DispatchJobFilter;
import tech.flowcatalyst.dispatchjob.entity.DispatchAttempt;
import tech.flowcatalyst.dispatchjob.entity.DispatchCredentials;
import tech.flowcatalyst.dispatchjob.entity.DispatchJob;
import tech.flowcatalyst.dispatchjob.model.DispatchAttemptStatus;
import tech.flowcatalyst.dispatchjob.model.DispatchStatus;
import tech.flowcatalyst.dispatchjob.model.ErrorType;
import tech.flowcatalyst.dispatchjob.model.MediationType;
import tech.flowcatalyst.dispatchjob.model.MessagePointer;
import tech.flowcatalyst.dispatchjob.repository.DispatchJobRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing and processing dispatch jobs.
 */
@ApplicationScoped
public class DispatchJobService {

    private static final Logger LOG = Logger.getLogger(DispatchJobService.class);
    private static final String DISPATCH_POOL_CODE = "DISPATCH-POOL";
    private static final MediationType MEDIATION_TYPE = MediationType.HTTP;
    private static final String PROCESSING_ENDPOINT = "http://localhost:8080/api/dispatch/process";

    @Inject
    DispatchJobRepository dispatchJobRepository;

    @Inject
    CredentialsService credentialsService;

    @Inject
    WebhookDispatcher webhookDispatcher;

    @Inject
    SqsClient sqsClient;

    @Inject
    ObjectMapper objectMapper;

    public DispatchJob createDispatchJob(CreateDispatchJobRequest request) {
        // Validate credentials exist
        credentialsService.findById(request.credentialsId())
            .orElseThrow(() -> new IllegalArgumentException("Credentials not found: " + request.credentialsId()));

        // Create via repository (handles TSID generation and metadata conversion)
        DispatchJob job = dispatchJobRepository.create(request);

        LOG.infof("Created dispatch job [%s] of type [%s] from source [%s]", job.id, job.type, job.source);

        // Send to SQS queue
        sendToQueue(job, request.queueUrl());

        return job;
    }

    private void sendToQueue(DispatchJob job, String queueUrl) {
        try {
            // Create MessagePointer for the dispatch job
            MessagePointer messagePointer = new MessagePointer(
                job.id.toString(),
                DISPATCH_POOL_CODE,
                "dispatch-auth-token", // Auth token for internal processing endpoint
                MEDIATION_TYPE,
                PROCESSING_ENDPOINT,
                null,  // No message group ordering needed for dispatch jobs (each job is independent)
                null   // batchId is populated by message router during routing
            );

            String messageBody = objectMapper.writeValueAsString(messagePointer);

            SendMessageRequest sendRequest = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .messageGroupId("dispatch-" + job.type) // Group by type
                .messageDeduplicationId(job.id.toString())
                .build();

            sqsClient.sendMessage(sendRequest);
            LOG.infof("Sent dispatch job [%s] to queue [%s]", job.id, queueUrl);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to send dispatch job [%s] to queue", job.id);
            // Don't fail the create operation, job is persisted and can be retried
        }
    }

    public DispatchJobProcessResult processDispatchJob(String dispatchJobId) {
        // Load the dispatch job (single document read - includes metadata and attempts)
        DispatchJob job = dispatchJobRepository.findByIdOptional(dispatchJobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + dispatchJobId));

        LOG.infof("Processing dispatch job [%s], attempt %d/%d", job.id, job.attemptCount + 1, job.maxRetries);

        // Update status to IN_PROGRESS
        dispatchJobRepository.updateStatus(job.id, DispatchStatus.IN_PROGRESS, null, null, null);

        // Load credentials (separate collection)
        DispatchCredentials credentials = credentialsService.findById(job.credentialsId)
            .orElseThrow(() -> new IllegalArgumentException("Credentials not found: " + job.credentialsId));

        // Dispatch webhook
        DispatchAttempt attempt = webhookDispatcher.sendWebhook(job, credentials);

        // Add attempt atomically (single MongoDB operation)
        dispatchJobRepository.addAttempt(job.id, attempt);

        // Update job based on attempt result
        int newAttemptCount = job.attemptCount + 1;

        if (attempt.status == DispatchAttemptStatus.SUCCESS) {
            // Success - mark as completed
            Instant completedAt = Instant.now();
            Long duration = Duration.between(job.createdAt, completedAt).toMillis();

            dispatchJobRepository.updateStatus(
                job.id, DispatchStatus.COMPLETED, completedAt, duration, null);

            LOG.infof("Dispatch job [%s] completed successfully", job.id);
            return DispatchJobProcessResult.success("Completed successfully");

        } else {
            // Failure - check if we should retry based on error type and retry count
            boolean isNotTransient = attempt.errorType == ErrorType.NOT_TRANSIENT;
            boolean retriesExhausted = newAttemptCount >= job.maxRetries;

            if (isNotTransient || retriesExhausted) {
                // Permanent error - either non-transient or max attempts exhausted
                Instant completedAt = Instant.now();
                Long duration = Duration.between(job.createdAt, completedAt).toMillis();

                dispatchJobRepository.updateStatus(
                    job.id, DispatchStatus.ERROR, completedAt, duration, attempt.errorMessage);

                if (isNotTransient) {
                    LOG.warnf("Dispatch job [%s] failed with non-transient error, marking as ERROR", job.id);
                    return DispatchJobProcessResult.permanentError("Non-transient error", attempt.errorMessage);
                } else {
                    LOG.warnf("Dispatch job [%s] failed after %d attempts, marking as ERROR", job.id, newAttemptCount);
                    return DispatchJobProcessResult.permanentError("Max attempts exhausted", attempt.errorMessage);
                }

            } else {
                // More attempts available and error is transient - queue for retry
                dispatchJobRepository.updateStatus(
                    job.id, DispatchStatus.QUEUED, null, null, attempt.errorMessage);

                LOG.warnf("Dispatch job [%s] failed, attempt %d/%d, re-queued for retry", job.id, newAttemptCount, job.maxRetries);
                return DispatchJobProcessResult.transientError("Failed, re-queued for retry", attempt.errorMessage);
            }
        }
    }

    public Optional<DispatchJob> findById(String id) {
        return dispatchJobRepository.findByIdOptional(id);
    }

    public List<DispatchJob> findWithFilter(DispatchJobFilter filter) {
        return dispatchJobRepository.findWithFilter(filter);
    }

    public long countWithFilter(DispatchJobFilter filter) {
        return dispatchJobRepository.countWithFilter(filter);
    }

    /**
     * Result of processing a dispatch job.
     *
     * @param ack Whether to acknowledge (true) or nack (false) the message
     * @param message Human-readable status message
     * @param details Optional error details (stack trace, response body, etc.)
     * @param errorType Classification of error for retry decisions (null if ack=true)
     * @param httpStatusCode HTTP status code to return to message router
     */
    public record DispatchJobProcessResult(
        boolean ack,
        String message,
        String details,
        ErrorType errorType,
        int httpStatusCode
    ) {
        /** Success result - ack the message */
        public static DispatchJobProcessResult success(String message) {
            return new DispatchJobProcessResult(true, message, null, null, 200);
        }

        /** Transient error - nack for retry */
        public static DispatchJobProcessResult transientError(String message, String details) {
            return new DispatchJobProcessResult(false, message, details, ErrorType.TRANSIENT, 400);
        }

        /** Permanent error - ack to prevent retry, goes to ERROR status */
        public static DispatchJobProcessResult permanentError(String message, String details) {
            return new DispatchJobProcessResult(true, message, details, ErrorType.NOT_TRANSIENT, 200);
        }

        /** Unknown error - nack, treat as transient */
        public static DispatchJobProcessResult unknownError(String message, String details) {
            return new DispatchJobProcessResult(false, message, details, ErrorType.UNKNOWN, 500);
        }
    }
}
