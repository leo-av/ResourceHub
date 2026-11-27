package app.resourcehub.availability.business.structs;

import java.time.Instant;

public record CreateTemporalBlockCommand(
    BlockOrigin origin,
    Instant startAt,
    Instant endAt,
    boolean forcedConfirmation,
    String externalCalendarEventId,
    String reason
) {
}
