package tech.flowcatalyst.messagerouter.integration;

import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * Test webhook endpoint for integration tests.
 * This provides a real HTTP endpoint that tests can call.
 */
@Path("/test/webhook")
public class TestWebhookResource {

    private static int callCount = 0;

    @POST
    public Response webhook(String body) {
        callCount++;
        return Response.ok().build();
    }

    @POST
    @Path("/slow")
    public Response slowWebhook(String body) throws InterruptedException {
        Thread.sleep(1000);
        return Response.ok().build();
    }

    @POST
    @Path("/error")
    public Response errorWebhook(String body) {
        return Response.serverError().build();
    }

    @POST
    @Path("/bad-request")
    public Response badRequestWebhook(String body) {
        return Response.status(400).build();
    }

    public static int getCallCount() {
        return callCount;
    }

    public static void resetCallCount() {
        callCount = 0;
    }
}
