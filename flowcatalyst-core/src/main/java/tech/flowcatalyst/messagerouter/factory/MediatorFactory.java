package tech.flowcatalyst.messagerouter.factory;

import tech.flowcatalyst.messagerouter.mediator.Mediator;

public interface MediatorFactory {

    /**
     * Creates a mediator based on the mediation type
     *
     * @param mediationType the type of mediator to create
     * @return a mediator instance
     */
    Mediator createMediator(String mediationType);
}
