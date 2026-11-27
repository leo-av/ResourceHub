package app.resourcehub.traceability.api.logic.services;

import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.PageResponse;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;
import app.resourcehub.traceability.business.B;
import app.resourcehub.traceability.business.structs.AuditLogEntry;
import app.resourcehub.traceability.business.structs.AuditLogFilter;
import app.resourcehub.traceability.business.structs.AuditLogPage;
import app.resourcehub.traceability.business.structs.AuditOutcome;
import app.resourcehub.traceability.business.structs.TraceabilityBusinessException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ConsoleAuditService {

    public ResponseWrapper<PageResponse<AuditLogEntry>> listAuditLogs(
        ActorContext actor,
        UUID actorId,
        String operationCode,
        String outcome,
        String correlationId,
        Instant fromOccurredAt,
        Instant toOccurredAt,
        int page,
        int size,
        String requestId,
        Clock clock
    ) {
        AuditOutcome normalizedOutcome = normalizeOutcome(outcome);
        AuditLogFilter filter = new AuditLogFilter(
            actorId,
            trimToNull(operationCode),
            normalizedOutcome,
            trimToNull(correlationId),
            fromOccurredAt,
            toOccurredAt
        );
        AuditLogPage result = B.audit().listAuditLogs(actor, filter, page, size);
        PageResponse<AuditLogEntry> payload = new PageResponse<>(
            result.items(),
            result.page(),
            result.size(),
            result.totalItems(),
            result.totalPages()
        );
        return ResponseWrapper.ok("OK", payload, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public InternalProblem toInternalProblem(SharedDomainException ex, String instancePath, String requestId) {
        ErrorCode code = ex.getErrorCode();
        int httpStatus = mapHttpStatus(code);
        return new InternalProblem(
            URI.create("https://internal.resourcehub/problems/" + code.name().toLowerCase().replace('_', '-')),
            code.name(),
            httpStatus,
            ex.getMessage(),
            instancePath != null ? instancePath : "/api/v1/console/audit-logs",
            code,
            effectiveRequestId(requestId),
            null,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static AuditOutcome normalizeOutcome(String outcome) {
        String trimmed = trimToNull(outcome);
        if (trimmed == null) {
            return null;
        }
        try {
            return AuditOutcome.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new TraceabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Unsupported audit outcome filter: " + trimmed
            );
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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
        return 500;
    }

    private static String effectiveRequestId(String requestId) {
        return (requestId == null || requestId.isBlank()) ? UUID.randomUUID().toString() : requestId;
    }

    private static Clock effectiveClock(Clock clock) {
        return clock != null ? clock : Clock.systemUTC();
    }
}
