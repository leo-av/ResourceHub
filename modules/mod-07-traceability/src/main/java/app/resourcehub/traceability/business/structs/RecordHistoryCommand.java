package app.resourcehub.traceability.business.structs;

import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.util.UUID;

public record RecordHistoryCommand(
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
    String correlationId
) {
}
