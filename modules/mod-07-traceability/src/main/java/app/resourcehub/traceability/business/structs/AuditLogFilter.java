package app.resourcehub.traceability.business.structs;

import java.time.Instant;
import java.util.UUID;

public record AuditLogFilter(
    UUID actorId,
    String operationCode,
    AuditOutcome outcome,
    String correlationId,
    Instant fromOccurredAt,
    Instant toOccurredAt
) {
}
