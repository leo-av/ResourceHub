package app.resourcehub.traceability.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredReservationHistory(
    UUID id,
    UUID reservationId,
    String changeType,
    String previousStatus,
    String newStatus,
    String actorType,
    UUID requesterId,
    UUID holderId,
    String operationalScope,
    String snapshotBeforeJson,
    String snapshotAfterJson,
    String correlationId,
    Instant occurredAt
) {
}
