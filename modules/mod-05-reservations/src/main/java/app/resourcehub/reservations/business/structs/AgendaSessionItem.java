package app.resourcehub.reservations.business.structs;

import java.time.Instant;
import java.util.UUID;

public record AgendaSessionItem(
    UUID reservationId,
    UUID lineId,
    short lineNumber,
    ReservationStatus reservationStatus,
    String attendanceStatus,
    UUID holderId,
    UUID requesterId,
    Instant startAt,
    Instant endAt,
    int requestedUnits
) {
}
