package tech.flowcatalyst.messagerouter.mediator;

import tech.flowcatalyst.messagerouter.model.MediationResult;
import tech.flowcatalyst.messagerouter.model.MediationType;
import tech.flowcatalyst.messagerouter.model.MessagePointer;

public interface Mediator {

    /**
     * Processes a message pointer by mediating to the downstream system
     *
     * @param message the message pointer to process
     * @return the result of the mediation
     */
    MediationResult process(MessagePointer message);

    /**
     * Returns the mediation type this mediator handles
     */
    MediationType getMediationType();
}
