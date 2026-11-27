package app.resourcehub.traceability.business.logic.instance;

import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import app.resourcehub.traceability.business.structs.HistoryChangeType;
import app.resourcehub.traceability.business.structs.RecordHistoryCommand;
import app.resourcehub.traceability.business.structs.ReservationHistoryEntry;
import app.resourcehub.traceability.business.structs.TraceabilityBusinessException;
import app.resourcehub.traceability.repository.logic.local.ReservationHistoryRepository;
import app.resourcehub.traceability.repository.structs.StoredReservationHistory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ReservationHistoryBusiness {

    private final ReservationHistoryRepository reservationHistoryRepository;
    private final Clock clock;

    public ReservationHistoryBusiness(ReservationHistoryRepository reservationHistoryRepository, Clock clock) {
        if (reservationHistoryRepository == null || clock == null) {
            throw new IllegalArgumentException("reservationHistoryRepository and clock must not be null");
        }
        this.reservationHistoryRepository = reservationHistoryRepository;
        this.clock = clock;
    }

    public ReservationHistoryEntry appendHistoryInCurrentTx(RecordHistoryCommand command) {
        if (command == null
            || command.reservationId() == null
            || command.changeType() == null
            || isBlank(command.newStatus())
            || command.actorType() == null
            || command.actorType() == ActorType.PLATFORM_ADMIN
            || command.holderId() == null
            || command.operationalScope() == null
            || isBlank(command.snapshotAfterJson())
            || isBlank(command.correlationId())) {
            throw new TraceabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "All mandatory reservation history fields must be provided"
            );
        }
        if (command.changeType() == HistoryChangeType.MODIFIED && isBlank(command.snapshotBeforeJson())) {
            throw new TraceabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "MODIFIED reservation history entry requires snapshotBeforeJson"
            );
        }

        UUID id = UuidV7Utils.generate();
        Instant now = clock.instant();
        StoredReservationHistory stored = new StoredReservationHistory(
            id,
            command.reservationId(),
            command.changeType().name(),
            command.previousStatus(),
            command.newStatus(),
            command.actorType().name(),
            command.requesterId(),
            command.holderId(),
            command.operationalScope().name(),
            command.snapshotBeforeJson(),
            command.snapshotAfterJson(),
            command.correlationId().trim(),
            now
        );
        StoredReservationHistory inserted = reservationHistoryRepository.insert(stored);
        return toDomain(inserted);
    }

    public List<ReservationHistoryEntry> listReservationHistory(ActorContext actor, UUID reservationId) {
        if (actor == null) {
            throw new TraceabilityBusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        OperationalScope scope = actor.operationalScope();
        if (scope != OperationalScope.AMB_01_CLIENT && scope != OperationalScope.AMB_03_MANAGEMENT) {
            throw new TraceabilityBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "Reservation history requires AMB_01_CLIENT or AMB_03_MANAGEMENT scope"
            );
        }
        if (reservationId == null) {
            throw new TraceabilityBusinessException(ErrorCode.VALIDATION_FAILED, "reservationId must not be null");
        }

        List<StoredReservationHistory> stored = reservationHistoryRepository.findByReservationId(reservationId);
        if (stored.isEmpty()) {
            throw new TraceabilityBusinessException(
                ErrorCode.NOT_FOUND,
                "No history entries found for reservation: " + reservationId
            );
        }
        if (scope == OperationalScope.AMB_01_CLIENT
            && stored.stream().anyMatch(entry -> !entry.holderId().equals(actor.userId()))) {
            throw new TraceabilityBusinessException(
                ErrorCode.NO_PERMISSION,
                "Reservation history belongs to another holder"
            );
        }
        return stored.stream().map(ReservationHistoryBusiness::toDomain).toList();
    }

    private static ReservationHistoryEntry toDomain(StoredReservationHistory stored) {
        return new ReservationHistoryEntry(
            stored.id(),
            stored.reservationId(),
            HistoryChangeType.valueOf(stored.changeType()),
            stored.previousStatus(),
            stored.newStatus(),
            ActorType.valueOf(stored.actorType()),
            stored.requesterId(),
            stored.holderId(),
            OperationalScope.valueOf(stored.operationalScope()),
            stored.snapshotBeforeJson(),
            stored.snapshotAfterJson(),
            stored.correlationId(),
            stored.occurredAt()
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
