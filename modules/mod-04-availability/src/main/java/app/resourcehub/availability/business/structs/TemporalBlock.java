package app.resourcehub.availability.business.structs;

import java.time.Instant;
import java.util.UUID;

public record TemporalBlock(
    UUID id,
    UUID resourceId,
    BlockOrigin blockOrigin,
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
