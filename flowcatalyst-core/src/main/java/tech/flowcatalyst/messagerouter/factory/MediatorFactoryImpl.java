package tech.flowcatalyst.messagerouter.factory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.flowcatalyst.dispatchjob.mediator.DispatchJobMediator;
import tech.flowcatalyst.messagerouter.mediator.HttpMediator;
import tech.flowcatalyst.messagerouter.mediator.Mediator;

@ApplicationScoped
public class MediatorFactoryImpl implements MediatorFactory {

    private static final Logger LOG = Logger.getLogger(MediatorFactoryImpl.class);

    @Inject
    HttpMediator httpMediator;

    @Inject
    DispatchJobMediator dispatchJobMediator;

    @Override
    public Mediator createMediator(String mediationType) {
        LOG.debugf("Creating mediator for type: %s", mediationType);

        return switch (mediationType.toUpperCase()) {
            case "HTTP" -> httpMediator;
            case "DISPATCH_JOB" -> dispatchJobMediator;
            default -> throw new IllegalArgumentException("Unsupported mediator type: " + mediationType);
        };
    }
}
