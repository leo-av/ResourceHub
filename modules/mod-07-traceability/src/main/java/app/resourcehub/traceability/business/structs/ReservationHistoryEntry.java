package app.resourcehub.traceability.business.structs;

import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.time.Instant;
import java.util.UUID;

public record ReservationHistoryEntry(
    UUID id,
    UUID reservationId,
    HistoryChangeType changeType,
    String previousStatus,
    String newStatus,
    ActorType actorType,
    UUID requesterId,
    UUID holderId,
    OperationalScope operationalScope,
    String snapshotBeforeJson,
    String snapshotAfterJson,
    String correlationId,
    Instant occurredAt
) {
}
