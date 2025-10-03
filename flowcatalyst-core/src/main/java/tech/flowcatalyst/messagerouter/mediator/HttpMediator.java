package tech.flowcatalyst.messagerouter.mediator;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import tech.flowcatalyst.messagerouter.model.MediationResult;
import tech.flowcatalyst.messagerouter.model.MessagePointer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

@ApplicationScoped
public class HttpMediator implements Mediator {

    private static final Logger LOG = Logger.getLogger(HttpMediator.class);
    private static final int CONNECTION_POOL_SIZE = 200;
    private static final String MEDIATION_TYPE = "HTTP";

    private final HttpClient httpClient;

    public HttpMediator() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(30))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    }

    @Override
    @Retry(
        maxRetries = 3,
        delay = 1000,
        jitter = 500,
        retryOn = {java.net.http.HttpTimeoutException.class, java.io.IOException.class}
    )
    @CircuitBreaker(
        requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 5000,
        successThreshold = 3
    )
    @CircuitBreakerName("http-mediator")
    @Timeout(value = 900000)
    public MediationResult process(MessagePointer message) {
        try {
            LOG.debugf("Processing message [%s] via HTTP to [%s]", message.id(), message.mediationTarget());

            // Build HTTP request
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(message.mediationTarget()))
                .header("Authorization", "Bearer " + message.authToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    String.format("{\"messageId\":\"%s\"}", message.id())
                ))
                .build();

            // Send request
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Evaluate response
            int statusCode = response.statusCode();

            if (statusCode == 200) {
                LOG.debugf("Message [%s] processed successfully", message.id());
                return MediationResult.SUCCESS;
            } else if (statusCode >= 500) {
                LOG.warnf("Message [%s] failed with server error: %d", message.id(), statusCode);
                return MediationResult.ERROR_SERVER;
            } else if (statusCode >= 400) {
                LOG.warnf("Message [%s] failed with process error: %d", message.id(), statusCode);
                return MediationResult.ERROR_PROCESS;
            } else {
                LOG.warnf("Message [%s] received unexpected status: %d", message.id(), statusCode);
                return MediationResult.ERROR_PROCESS;
            }

        } catch (java.net.http.HttpConnectTimeoutException | java.net.ConnectException e) {
            LOG.error("Connection error processing message: " + message.id(), e);
            return MediationResult.ERROR_CONNECTION;
        } catch (java.net.http.HttpTimeoutException e) {
            LOG.error("Timeout processing message: " + message.id(), e);
            return MediationResult.ERROR_CONNECTION;
        } catch (Exception e) {
            LOG.error("Error processing message: " + message.id(), e);
            return MediationResult.ERROR_SERVER;
        }
    }

    @Override
    public String getMediationType() {
        return MEDIATION_TYPE;
    }
}
