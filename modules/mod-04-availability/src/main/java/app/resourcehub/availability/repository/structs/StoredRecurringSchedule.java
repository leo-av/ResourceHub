package app.resourcehub.availability.repository.structs;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StoredRecurringSchedule(
    UUID id,
    UUID resourceId,
    short dayOfWeek,
    String startLocalTime,
    String endLocalTime,
    LocalDate validFrom,
    LocalDate validUntil,
    Instant createdAt
) {
}
