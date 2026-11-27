package app.resourcehub.availability.business.structs;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CalendarException(
    UUID id,
    UUID resourceId,
    LocalDate exceptionDate,
    ExceptionType type,
    Instant startAt,
    Instant endAt,
    String reason,
    UUID createdByUserId,
    Instant createdAt
) {
}
