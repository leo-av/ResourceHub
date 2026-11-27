package app.resourcehub.resources.api.logic.services;

import app.resourcehub.resources.business.B;
import app.resourcehub.resources.business.structs.CatalogPage;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.CreateResourceCommand;
import app.resourcehub.resources.business.structs.CreateResourceTypeCommand;
import app.resourcehub.resources.business.structs.PatchResourceStatusCommand;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.resources.business.structs.ResourcesBusinessException;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.PageResponse;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public class ConsoleCatalogService {

    public ResponseWrapper<ResourceType> createResourceType(
        ActorContext actor,
        CreateResourceTypeCommand command,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        if (command == null) {
            throw new ResourcesBusinessException(ErrorCode.VALIDATION_FAILED, "Request body must not be null");
        }
        ResourceType created = B.resourceTypes().createResourceType(
            command.code(),
            command.name(),
            command.description(),
            command.nature()
        );
        return ResponseWrapper.ok("OK", created, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<List<ResourceType>> listResourceTypes(
        ActorContext actor,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        List<ResourceType> items = B.resourceTypes().listResourceTypes();
        return ResponseWrapper.ok("OK", items, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<CatalogResource> createResource(
        ActorContext actor,
        CreateResourceCommand command,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        if (command == null) {
            throw new ResourcesBusinessException(ErrorCode.VALIDATION_FAILED, "Request body must not be null");
        }
        CatalogResource created = B.resources().createSimpleResource(
            command.resourceTypeId(),
            command.code(),
            command.name(),
            command.description(),
            command.capacity(),
            command.requiresApproval(),
            command.allowProfessionalApproval(),
            command.linkedUserId()
        );
        return ResponseWrapper.ok("OK", created, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<CatalogResource> patchResourceStatus(
        ActorContext actor,
        UUID resourceId,
        PatchResourceStatusCommand command,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        if (command == null) {
            throw new ResourcesBusinessException(ErrorCode.VALIDATION_FAILED, "Request body must not be null");
        }
        CatalogResource updated = B.resources().transitionStatus(resourceId, command.status());
        return ResponseWrapper.ok("OK", updated, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<PageResponse<CatalogResource>> listResources(
        ActorContext actor,
        int page,
        int size,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        CatalogPage result = B.resources().listPage(page, size);
        PageResponse<CatalogResource> payload = new PageResponse<>(
            result.items(),
            result.page(),
            result.size(),
            result.totalItems(),
            result.totalPages()
        );
        return ResponseWrapper.ok("OK", payload, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public InternalProblem toInternalProblem(ResourcesBusinessException ex, String instancePath, String requestId) {
        ErrorCode code = ex.getErrorCode();
        int httpStatus = mapHttpStatus(code);
        return new InternalProblem(
            URI.create("https://internal.resourcehub/problems/" + code.name().toLowerCase().replace('_', '-')),
            code.name(),
            httpStatus,
            ex.getMessage(),
            instancePath != null ? instancePath : "/api/v1/console/resources",
            code,
            effectiveRequestId(requestId),
            null,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static void requireConsoleManagement(ActorContext actor) {
        if (actor == null) {
            throw new ResourcesBusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        if (actor.operationalScope() != OperationalScope.AMB_03_MANAGEMENT
            || (!"AD".equals(actor.actorRole()) && !"MG".equals(actor.actorRole()))) {
            throw new ResourcesBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "Console catalog requires AMB_03_MANAGEMENT with role AD or MG"
            );
        }
    }

    private static int mapHttpStatus(ErrorCode code) {
        if (code == ErrorCode.VALIDATION_FAILED || code == ErrorCode.MALFORMED_REQUEST) {
            return 400;
        }
        if (code == ErrorCode.UNAUTHENTICATED || code == ErrorCode.INVALID_CREDENTIALS) {
            return 401;
        }
        if (code == ErrorCode.SCOPE_VIOLATION || code == ErrorCode.NO_PERMISSION) {
            return 403;
        }
        if (code == ErrorCode.NOT_FOUND) {
            return 404;
        }
        if (code == ErrorCode.ALREADY_EXISTS || code == ErrorCode.TRANSITION_NOT_ALLOWED) {
            return 409;
        }
        if (code == ErrorCode.CATALOG_RESOURCE_NOT_BOOKABLE) {
            return 422;
        }
        return 500;
    }

    private static String effectiveRequestId(String requestId) {
        return (requestId == null || requestId.isBlank()) ? UUID.randomUUID().toString() : requestId;
    }

    private static Clock effectiveClock(Clock clock) {
        return clock != null ? clock : Clock.systemUTC();
    }
}
