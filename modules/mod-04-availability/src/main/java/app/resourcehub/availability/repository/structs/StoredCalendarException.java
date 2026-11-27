package app.resourcehub.availability.repository.structs;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StoredCalendarException(
    UUID id,
    UUID resourceId,
    LocalDate exceptionDate,
    String exceptionType,
    Instant startAt,
    Instant endAt,
    String reason,
    UUID createdByUserId,
    Instant createdAt
) {
}
