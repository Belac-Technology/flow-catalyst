package tech.flowcatalyst.eventprocessor.warning;

import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.standby.StandbyWarningService;

import java.util.logging.Logger;

/**
 * Warning service adapter for the event processor.
 *
 * For now, this just logs warnings. In the future, this could integrate
 * with a proper warning/alerting system.
 */
@ApplicationScoped
public class EventProcessorWarningAdapter implements StandbyWarningService {

    private static final Logger LOG = Logger.getLogger(EventProcessorWarningAdapter.class.getName());

    @Override
    public void addWarning(String id, String severity, String title, String message) {
        // Log the warning - in future, integrate with proper alerting
        String logMessage = String.format("[%s] %s: %s (ID: %s)", severity, title, message, id);

        switch (severity.toUpperCase()) {
            case "CRITICAL", "ERROR" -> LOG.severe(logMessage);
            case "WARN", "WARNING" -> LOG.warning(logMessage);
            default -> LOG.info(logMessage);
        }
    }

    @Override
    public void acknowledgeWarning(String id) {
        LOG.fine("Warning acknowledged: " + id);
    }
}
