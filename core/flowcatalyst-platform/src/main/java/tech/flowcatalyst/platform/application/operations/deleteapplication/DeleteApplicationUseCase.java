package tech.flowcatalyst.platform.application.operations.deleteapplication;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.flowcatalyst.platform.application.Application;
import tech.flowcatalyst.platform.application.ApplicationClientConfigRepository;
import tech.flowcatalyst.platform.application.ApplicationRepository;
import tech.flowcatalyst.platform.application.events.ApplicationDeleted;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.common.Result;
import tech.flowcatalyst.platform.common.UnitOfWork;
import tech.flowcatalyst.platform.common.errors.UseCaseError;

import java.util.Map;

/**
 * Use case for deleting an Application.
 */
@ApplicationScoped
public class DeleteApplicationUseCase {

    @Inject
    ApplicationRepository repo;

    @Inject
    ApplicationClientConfigRepository configRepo;

    @Inject
    UnitOfWork unitOfWork;

    public Result<ApplicationDeleted> execute(
            DeleteApplicationCommand command,
            ExecutionContext context
    ) {
        // Load aggregate
        Application app = repo.findByIdOptional(command.applicationId())
            .orElse(null);

        if (app == null) {
            return Result.failure(new UseCaseError.NotFoundError(
                "APPLICATION_NOT_FOUND",
                "Application not found",
                Map.of("applicationId", command.applicationId())
            ));
        }

        // Can only delete deactivated applications
        if (app.active) {
            return Result.failure(new UseCaseError.BusinessRuleViolation(
                "CANNOT_DELETE_ACTIVE",
                "Cannot delete active application. Deactivate it first.",
                Map.of("applicationId", command.applicationId())
            ));
        }

        // Check for client configurations
        long configCount = configRepo.countByApplication(app.id);
        if (configCount > 0) {
            return Result.failure(new UseCaseError.BusinessRuleViolation(
                "HAS_CLIENT_CONFIGURATIONS",
                "Cannot delete application with client configurations. Remove all configurations first.",
                Map.of("applicationId", command.applicationId(), "configCount", configCount)
            ));
        }

        // Create domain event (before deletion so we have access to the entity)
        ApplicationDeleted event = ApplicationDeleted.builder()
            .from(context)
            .applicationId(app.id)
            .code(app.code)
            .build();

        // Delete via commitDelete
        return unitOfWork.commitDelete(app, event, command);
    }
}
