package tech.flowcatalyst.messagerouter.mediator;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import tech.flowcatalyst.messagerouter.model.MediationResult;
import tech.flowcatalyst.messagerouter.model.MediationType;
import tech.flowcatalyst.messagerouter.model.MessagePointer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class HttpMediator implements Mediator {

    private static final Logger LOG = Logger.getLogger(HttpMediator.class);

    private final HttpClient httpClient;
    private final ExecutorService executorService;

    public HttpMediator(@org.eclipse.microprofile.config.inject.ConfigProperty(name = "mediator.http.version", defaultValue = "HTTP_2") String httpVersion) {
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();

        HttpClient.Version version = "HTTP_1_1".equalsIgnoreCase(httpVersion)
            ? HttpClient.Version.HTTP_1_1
            : HttpClient.Version.HTTP_2;

        LOG.infof("Initializing HttpMediator with HTTP version: %s", version);

        this.httpClient = HttpClient.newBuilder()
            .version(version)
            .connectTimeout(Duration.ofSeconds(30))
            .executor(executorService)
            .build();
    }

    @PreDestroy
    void cleanup() {
        LOG.info("Shutting down HttpMediator executor service");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warn("HttpMediator executor did not terminate within 10 seconds, forcing shutdown");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while shutting down HttpMediator executor");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    @CircuitBreaker(
        requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 5000,
        successThreshold = 3,
        failOn = {java.net.http.HttpTimeoutException.class, java.io.IOException.class, ServerErrorException.class}
    )
    @CircuitBreakerName("http-mediator")
    @Retry(
        maxRetries = 3,
        delay = 1000,
        jitter = 500,
        retryOn = {java.net.http.HttpTimeoutException.class, java.io.IOException.class}
    )
    @Timeout(value = 900000)
    public MediationResult process(MessagePointer message) {
        try {
            String payload = String.format("{\"messageId\":\"%s\"}", message.id());
            LOG.debugf("Processing message [%s] via HTTP to [%s] with payload: %s",
                message.id(), message.mediationTarget(), payload);

            // Build HTTP request
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(message.mediationTarget()))
                .header("Authorization", "Bearer " + message.authToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

            // Send request
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Evaluate response
            int statusCode = response.statusCode();

            if (statusCode == 200) {
                LOG.debugf("Message [%s] processed successfully", message.id());
                return MediationResult.SUCCESS;
            } else if (statusCode == 501) {
                // 501 Not Implemented - endpoint doesn't support this operation, should ACK to prevent retry
                LOG.errorf("Message [%s] failed with 501 Not Implemented - operation not supported at endpoint: %s",
                    message.id(), message.mediationTarget());
                return MediationResult.ERROR_CONFIG;
            } else if (statusCode >= 500) {
                LOG.warnf("Message [%s] failed with server error: %d", message.id(), statusCode);
                return MediationResult.ERROR_SERVER;
            } else if (statusCode == 400) {
                // 400 Bad Request - could be transient (malformed by message router), should retry
                LOG.warnf("Message [%s] failed with 400 Bad Request", message.id());
                return MediationResult.ERROR_PROCESS;
            } else if (statusCode >= 401 && statusCode < 500) {
                // All other 4xx errors indicate configuration problems:
                // 401 Unauthorized, 403 Forbidden, 404 Not Found, 405 Method Not Allowed, etc.
                LOG.errorf("Message [%s] failed with %d %s - configuration error at endpoint: %s",
                    message.id(), statusCode, getStatusDescription(statusCode), message.mediationTarget());
                return MediationResult.ERROR_CONFIG;
            } else {
                LOG.warnf("Message [%s] received unexpected status: %d", message.id(), statusCode);
                return MediationResult.ERROR_PROCESS;
            }

        } catch (java.net.http.HttpConnectTimeoutException | java.net.ConnectException e) {
            LOG.errorf(e, "Connection error processing message: %s", message.id());
            return MediationResult.ERROR_CONNECTION;
        } catch (java.net.http.HttpTimeoutException e) {
            LOG.errorf(e, "Timeout processing message: %s", message.id());
            return MediationResult.ERROR_CONNECTION;
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error processing message: %s", message.id());
            return MediationResult.ERROR_SERVER;
        }
    }

    @Override
    public MediationType getMediationType() {
        return MediationType.HTTP;
    }

    /**
     * Get human-readable description for HTTP status codes
     */
    private String getStatusDescription(int statusCode) {
        return switch (statusCode) {
            case 401 -> "Unauthorized";
            case 402 -> "Payment Required";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 407 -> "Proxy Authentication Required";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case 413 -> "Payload Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 416 -> "Range Not Satisfiable";
            case 417 -> "Expectation Failed";
            case 418 -> "I'm a teapot";
            case 421 -> "Misdirected Request";
            case 422 -> "Unprocessable Entity";
            case 423 -> "Locked";
            case 424 -> "Failed Dependency";
            case 425 -> "Too Early";
            case 426 -> "Upgrade Required";
            case 428 -> "Precondition Required";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 451 -> "Unavailable For Legal Reasons";
            default -> "Client Error";
        };
    }
}
