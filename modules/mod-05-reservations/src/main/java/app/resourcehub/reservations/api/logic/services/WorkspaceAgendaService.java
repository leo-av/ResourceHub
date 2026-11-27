package app.resourcehub.reservations.api.logic.services;

import app.resourcehub.reservations.business.B;
import app.resourcehub.reservations.business.structs.ProfessionalAgenda;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class WorkspaceAgendaService {

    public ResponseWrapper<ProfessionalAgenda> getAgenda(
        ActorContext actor,
        Instant from,
        Instant to,
        String requestId,
        Clock clock
    ) {
        String correlationId = effectiveRequestId(requestId);
        ProfessionalAgenda agenda = B.reservations().getOwnProfessionalAgenda(actor, from, to);
        return ResponseWrapper.ok("OK", agenda, correlationId, effectiveClock(clock).instant());
    }

    public InternalProblem toInternalProblem(SharedDomainException ex, String instancePath, String requestId) {
        ErrorCode code = ex.getErrorCode();
        int httpStatus = mapHttpStatus(code);
        return new InternalProblem(
            URI.create("https://internal.resourcehub/problems/" + code.name().toLowerCase().replace('_', '-')),
            code.name(),
            httpStatus,
            ex.getMessage(),
            instancePath != null ? instancePath : "/api/v1/workspace/agenda",
            code,
            effectiveRequestId(requestId),
            null,
            List.of(),
            List.of(),
            List.of()
        );
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
        if (code == ErrorCode.BOOKING_CONFLICT
            || code == ErrorCode.ALREADY_EXISTS
            || code == ErrorCode.TRANSITION_NOT_ALLOWED) {
            return 409;
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
