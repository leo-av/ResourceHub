package app.resourcehub.reservations.business.structs;

import java.time.Instant;
import java.util.UUID;

public record ModifyReservationCommand(
    UUID resourceId,
    Instant startAt,
    Instant endAt,
    Integer requestedUnits
) {
}
