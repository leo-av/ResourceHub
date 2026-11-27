package app.resourcehub.traceability.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record AuditLogQueryFilter(
    UUID actorId,
    String operationCode,
    String outcome,
    String correlationId,
    Instant fromOccurredAt,
    Instant toOccurredAt
) {
}
