package tech.flowcatalyst.dispatchjob.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.flowcatalyst.dispatchjob.entity.DispatchAttempt;
import tech.flowcatalyst.dispatchjob.entity.DispatchCredentials;
import tech.flowcatalyst.dispatchjob.entity.DispatchJob;
import tech.flowcatalyst.dispatchjob.model.DispatchAttemptStatus;
import tech.flowcatalyst.dispatchjob.model.ErrorType;
import tech.flowcatalyst.dispatchjob.security.WebhookSigner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * Service for dispatching signed webhooks with HTTP/2.
 */
@ApplicationScoped
public class WebhookDispatcher {

    private static final Logger LOG = Logger.getLogger(WebhookDispatcher.class);
    private static final int MAX_RESPONSE_BODY_LENGTH = 5000;

    private final HttpClient httpClient;

    @Inject
    WebhookSigner webhookSigner;

    public WebhookDispatcher() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(30))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    }

    public DispatchAttempt sendWebhook(DispatchJob job, DispatchCredentials credentials) {
        Instant attemptStart = Instant.now();

        try {
            LOG.debugf("Sending webhook for dispatch job [%s] to [%s]", (Object) job.id, job.targetUrl);

            // Sign the webhook
            WebhookSigner.SignedWebhookRequest signed = webhookSigner.sign(job.payload, credentials);

            // Build HTTP request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(job.targetUrl))
                .header("Authorization", "Bearer " + signed.bearerToken())
                .header(WebhookSigner.SIGNATURE_HEADER, signed.signature())
                .header(WebhookSigner.TIMESTAMP_HEADER, signed.timestamp())
                .header("Content-Type", job.payloadContentType)
                .POST(HttpRequest.BodyPublishers.ofString(signed.payload()))
                .timeout(Duration.ofSeconds(30));

            // Add custom headers from job
            if (job.headers != null) {
                job.headers.forEach(requestBuilder::header);
            }

            HttpRequest request = requestBuilder.build();

            // Send request
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Build attempt record
            return buildAttempt(job, attemptStart, response, null);

        } catch (Exception e) {
            LOG.errorf(e, "Error sending webhook for dispatch job [%s]", (Object) job.id);
            return buildAttempt(job, attemptStart, null, e);
        }
    }

    private DispatchAttempt buildAttempt(
        DispatchJob job,
        Instant attemptStart,
        HttpResponse<String> response,
        Throwable error) {

        Instant completedAt = Instant.now();
        long durationMillis = Duration.between(attemptStart, completedAt).toMillis();

        DispatchAttempt attempt = new DispatchAttempt();
        attempt.attemptNumber = job.attemptCount + 1;
        attempt.attemptedAt = attemptStart;
        attempt.completedAt = completedAt;
        attempt.durationMillis = durationMillis;

        if (error != null) {
            attempt.status = DispatchAttemptStatus.FAILURE;
            attempt.errorMessage = error.getMessage();
            attempt.errorStackTrace = getStackTrace(error);
            // Network/connection errors are typically transient
            attempt.errorType = classifyException(error);
        } else {
            attempt.responseCode = response.statusCode();
            attempt.responseBody = truncate(response.body(), MAX_RESPONSE_BODY_LENGTH);

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                attempt.status = DispatchAttemptStatus.SUCCESS;
                attempt.errorType = null; // No error on success
                LOG.debugf("Webhook sent successfully for dispatch job [%s], status: %d", (Object) job.id, response.statusCode());
            } else {
                attempt.status = DispatchAttemptStatus.FAILURE;
                attempt.errorMessage = "HTTP " + response.statusCode();
                attempt.errorType = classifyHttpStatus(response.statusCode());
                LOG.warnf("Webhook failed for dispatch job [%s], status: %d", (Object) job.id, response.statusCode());
            }
        }

        return attempt;
    }

    /**
     * Classify HTTP status codes into error types.
     * - 4xx = NOT_TRANSIENT (client errors, won't succeed without changes)
     * - 5xx = TRANSIENT (server errors, may succeed on retry)
     */
    private ErrorType classifyHttpStatus(int statusCode) {
        if (statusCode >= 400 && statusCode < 500) {
            // 4xx client errors are permanent (bad request, unauthorized, forbidden, not found, etc.)
            return ErrorType.NOT_TRANSIENT;
        } else if (statusCode >= 500) {
            // 5xx server errors are transient (may succeed on retry)
            return ErrorType.TRANSIENT;
        }
        return ErrorType.UNKNOWN;
    }

    /**
     * Classify exceptions into error types.
     * Network/timeout errors are typically transient.
     */
    private ErrorType classifyException(Throwable error) {
        if (error instanceof java.net.ConnectException ||
            error instanceof java.net.SocketTimeoutException ||
            error instanceof java.net.http.HttpConnectTimeoutException ||
            error instanceof java.net.http.HttpTimeoutException) {
            return ErrorType.TRANSIENT;
        }
        // Unknown exceptions default to transient (safer to retry)
        return ErrorType.UNKNOWN;
    }

    private String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "... (truncated)";
    }

    private String getStackTrace(Throwable t) {
        if (t == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return truncate(sw.toString(), 10000);
    }
}
