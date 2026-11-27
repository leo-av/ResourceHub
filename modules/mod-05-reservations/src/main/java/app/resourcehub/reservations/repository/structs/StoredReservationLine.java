package app.resourcehub.reservations.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredReservationLine(
    UUID id,
    UUID reservationId,
    short lineNumber,
    UUID resourceId,
    UUID resourceRootId,
    Instant startAt,
    Instant endAt,
    int requestedUnits,
    boolean exclusive,
    String status,
    long lineAmountMinor,
    String currencyCode
) {
}
