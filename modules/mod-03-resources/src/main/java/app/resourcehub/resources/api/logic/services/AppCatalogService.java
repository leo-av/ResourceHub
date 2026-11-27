package app.resourcehub.resources.api.logic.services;

import app.resourcehub.resources.business.B;
import app.resourcehub.resources.business.structs.CatalogPage;
import app.resourcehub.resources.business.structs.CatalogResource;
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

public class AppCatalogService {

    public ResponseWrapper<List<ResourceType>> listResourceTypes(
        ActorContext actor,
        String requestId,
        Clock clock
    ) {
        requireAppScope(actor);
        List<ResourceType> activeTypes = B.resourceTypes().listResourceTypes().stream()
            .filter(ResourceType::active)
            .toList();
        return ResponseWrapper.ok("OK", activeTypes, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<PageResponse<CatalogResource>> listResources(
        ActorContext actor,
        int page,
        int size,
        String requestId,
        Clock clock
    ) {
        requireAppScope(actor);
        CatalogPage result = B.resources().listAvailablePage(page, size);
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
            instancePath != null ? instancePath : "/api/v1/app/resources",
            code,
            effectiveRequestId(requestId),
            null,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static void requireAppScope(ActorContext actor) {
        if (actor == null) {
            throw new ResourcesBusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        if (actor.operationalScope() != OperationalScope.AMB_01_CLIENT) {
            throw new ResourcesBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "App catalog endpoint requires AMB_01_CLIENT operational scope"
            );
        }
    }

    private static int mapHttpStatus(ErrorCode code) {
        if (code == ErrorCode.VALIDATION_FAILED || code == ErrorCode.MALFORMED_REQUEST) {
            return 400;
        }
        if (code == ErrorCode.UNAUTHENTICATED) {
            return 401;
        }
        if (code == ErrorCode.SCOPE_VIOLATION || code == ErrorCode.NO_PERMISSION) {
            return 403;
        }
        if (code == ErrorCode.NOT_FOUND) {
            return 404;
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
