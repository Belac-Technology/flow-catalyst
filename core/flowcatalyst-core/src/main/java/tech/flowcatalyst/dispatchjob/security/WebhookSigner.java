package tech.flowcatalyst.dispatchjob.security;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.dispatchjob.entity.DispatchCredentials;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

@ApplicationScoped
@IfBuildProperty(name = "dispatch-jobs.enabled", stringValue = "true", enableIfMissing = true)
public class WebhookSigner {

    public static final String SIGNATURE_HEADER = "X-FLOWCATALYST-SIGNATURE";
    public static final String TIMESTAMP_HEADER = "X-FLOWCATALYST-TIMESTAMP";
    private static final String ALGORITHM = "HmacSHA256";

    public SignedWebhookRequest sign(String payload, DispatchCredentials credentials) {
        // Generate ISO8601 timestamp with millisecond precision
        String timestamp = Instant.now()
            .truncatedTo(ChronoUnit.MILLIS)
            .toString();

        // Create signature payload: timestamp + body
        String signaturePayload = timestamp + payload;

        // Generate HMAC SHA-256 signature
        String signature = generateHmacSha256(signaturePayload, credentials.signingSecret);

        return new SignedWebhookRequest(
            payload,
            signature,
            timestamp,
            credentials.bearerToken
        );
    }

    private String generateHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                ALGORITHM
            );
            mac.init(secretKeySpec);

            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            // Return as hex string (lowercase)
            return HexFormat.of().formatHex(hash);

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate webhook signature", e);
        }
    }

    public record SignedWebhookRequest(
        String payload,
        String signature,
        String timestamp,
        String bearerToken
    ) {
    }
}
