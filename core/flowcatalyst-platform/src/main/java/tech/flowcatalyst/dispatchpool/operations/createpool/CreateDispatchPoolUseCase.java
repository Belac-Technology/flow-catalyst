package tech.flowcatalyst.dispatchpool.operations.createpool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.flowcatalyst.dispatchpool.DispatchPool;
import tech.flowcatalyst.dispatchpool.DispatchPoolRepository;
import tech.flowcatalyst.dispatchpool.DispatchPoolStatus;
import tech.flowcatalyst.dispatchpool.events.DispatchPoolCreated;
import tech.flowcatalyst.platform.application.Application;
import tech.flowcatalyst.platform.application.ApplicationRepository;
import tech.flowcatalyst.platform.client.Client;
import tech.flowcatalyst.platform.client.ClientRepository;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.common.Result;
import tech.flowcatalyst.platform.common.UnitOfWork;
import tech.flowcatalyst.platform.common.errors.UseCaseError;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Use case for creating a new dispatch pool.
 */
@ApplicationScoped
public class CreateDispatchPoolUseCase {

    @Inject
    DispatchPoolRepository poolRepo;

    @Inject
    ApplicationRepository applicationRepo;

    @Inject
    ClientRepository clientRepo;

    @Inject
    UnitOfWork unitOfWork;

    public Result<DispatchPoolCreated> execute(CreateDispatchPoolCommand command, ExecutionContext context) {
        // Validate code
        if (command.code() == null || command.code().isBlank()) {
            return Result.failure(new UseCaseError.ValidationError(
                "CODE_REQUIRED",
                "Code is required",
                Map.of()
            ));
        }

        // Validate code format (lowercase alphanumeric with hyphens)
        if (!isValidCode(command.code())) {
            return Result.failure(new UseCaseError.ValidationError(
                "INVALID_CODE_FORMAT",
                "Code must be lowercase alphanumeric with hyphens, starting with a letter",
                Map.of("code", command.code())
            ));
        }

        // Validate name
        if (command.name() == null || command.name().isBlank()) {
            return Result.failure(new UseCaseError.ValidationError(
                "NAME_REQUIRED",
                "Name is required",
                Map.of()
            ));
        }

        // Validate application
        if (command.applicationId() == null || command.applicationId().isBlank()) {
            return Result.failure(new UseCaseError.ValidationError(
                "APPLICATION_ID_REQUIRED",
                "Application ID is required",
                Map.of()
            ));
        }

        Optional<Application> applicationOpt = applicationRepo.findByIdOptional(command.applicationId());
        if (applicationOpt.isEmpty()) {
            return Result.failure(new UseCaseError.NotFoundError(
                "APPLICATION_NOT_FOUND",
                "Application not found",
                Map.of("applicationId", command.applicationId())
            ));
        }
        Application application = applicationOpt.get();

        // Validate client (if provided)
        String clientIdentifier = null;
        if (command.clientId() != null && !command.clientId().isBlank()) {
            Optional<Client> clientOpt = clientRepo.findByIdOptional(command.clientId());
            if (clientOpt.isEmpty()) {
                return Result.failure(new UseCaseError.NotFoundError(
                    "CLIENT_NOT_FOUND",
                    "Client not found",
                    Map.of("clientId", command.clientId())
                ));
            }
            clientIdentifier = clientOpt.get().identifier;
        }

        // Validate rate limit
        if (command.rateLimit() < 1) {
            return Result.failure(new UseCaseError.ValidationError(
                "INVALID_RATE_LIMIT",
                "Rate limit must be at least 1",
                Map.of("rateLimit", String.valueOf(command.rateLimit()))
            ));
        }

        // Validate concurrency
        if (command.concurrency() < 1) {
            return Result.failure(new UseCaseError.ValidationError(
                "INVALID_CONCURRENCY",
                "Concurrency must be at least 1",
                Map.of("concurrency", String.valueOf(command.concurrency()))
            ));
        }

        // Check code uniqueness within scope
        if (poolRepo.existsByCodeAndScope(command.code(), command.clientId(), command.applicationId())) {
            return Result.failure(new UseCaseError.BusinessRuleViolation(
                "CODE_EXISTS",
                "A pool with this code already exists in this scope",
                Map.of("code", command.code(), "applicationId", command.applicationId())
            ));
        }

        // Create pool
        Instant now = Instant.now();
        DispatchPool pool = new DispatchPool(
            TsidGenerator.generate(),
            command.code().toLowerCase(),
            command.name(),
            command.description(),
            command.rateLimit(),
            command.concurrency(),
            application.id,
            application.code,
            command.clientId(),
            clientIdentifier,
            DispatchPoolStatus.ACTIVE,
            now,
            now
        );

        // Create domain event
        DispatchPoolCreated event = DispatchPoolCreated.builder()
            .from(context)
            .poolId(pool.id())
            .code(pool.code())
            .name(pool.name())
            .description(pool.description())
            .rateLimit(pool.rateLimit())
            .concurrency(pool.concurrency())
            .applicationId(pool.applicationId())
            .applicationCode(pool.applicationCode())
            .clientId(pool.clientId())
            .clientIdentifier(pool.clientIdentifier())
            .status(pool.status())
            .build();

        // Commit atomically
        return unitOfWork.commit(pool, event, command);
    }

    private boolean isValidCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        // Lowercase alphanumeric with hyphens, must start with letter
        return code.matches("^[a-z][a-z0-9-]*$");
    }
}
