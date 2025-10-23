package tech.flowcatalyst.dispatchjob.mediator;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import tech.flowcatalyst.messagerouter.mediator.Mediator;
import tech.flowcatalyst.messagerouter.model.MediationResult;
import tech.flowcatalyst.messagerouter.model.MediationType;
import tech.flowcatalyst.messagerouter.model.MessagePointer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

@ApplicationScoped
@IfBuildProperty(name = "dispatch-jobs.enabled", stringValue = "true", enableIfMissing = true)
public class DispatchJobMediator implements Mediator {

    private static final Logger LOG = Logger.getLogger(DispatchJobMediator.class);

    private final HttpClient httpClient;

    public DispatchJobMediator() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(30))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    }

    @Override
    public MediationResult process(MessagePointer message) {
        try {
            LOG.debugf("Processing dispatch job message [%s] via endpoint [%s]", message.id(), message.mediationTarget());

            // Build HTTP request to internal processing endpoint
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(message.mediationTarget()))
                .header("Authorization", "Bearer " + message.authToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    String.format("{\"messageId\":\"%s\"}", message.id())
                ))
                .timeout(Duration.ofSeconds(30))
                .build();

            // Send request
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Evaluate response
            int statusCode = response.statusCode();

            if (statusCode == 200) {
                LOG.debugf("Dispatch job [%s] processed successfully", message.id());
                return MediationResult.SUCCESS;
            } else if (statusCode == 400) {
                LOG.warnf("Dispatch job [%s] failed but can retry (400)", message.id());
                return MediationResult.ERROR_PROCESS;
            } else if (statusCode >= 500) {
                LOG.warnf("Dispatch job [%s] failed with server error: %d", message.id(), statusCode);
                return MediationResult.ERROR_SERVER;
            } else {
                LOG.warnf("Dispatch job [%s] received unexpected status: %d", message.id(), statusCode);
                return MediationResult.ERROR_PROCESS;
            }

        } catch (java.net.http.HttpConnectTimeoutException | java.net.ConnectException e) {
            LOG.errorf(e, "Connection error processing dispatch job: %s", message.id());
            return MediationResult.ERROR_CONNECTION;
        } catch (java.net.http.HttpTimeoutException e) {
            LOG.errorf(e, "Timeout processing dispatch job: %s", message.id());
            return MediationResult.ERROR_CONNECTION;
        } catch (Exception e) {
            LOG.errorf(e, "Error processing dispatch job: %s", message.id());
            return MediationResult.ERROR_SERVER;
        }
    }

    @Override
    public MediationType getMediationType() {
        return MediationType.HTTP;
    }
}
