package app.resourcehub.availability.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredTemporalBlock(
    UUID id,
    UUID resourceId,
    String blockOrigin,
    Instant startAt,
    Instant endAt,
    boolean forcedConfirmation,
    String externalCalendarEventId,
    String reason,
    boolean active,
    UUID createdByUserId,
    Instant createdAt,
    Instant releasedAt
) {
}
