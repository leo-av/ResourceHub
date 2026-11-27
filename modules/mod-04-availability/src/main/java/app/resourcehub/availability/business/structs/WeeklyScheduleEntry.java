package app.resourcehub.availability.business.structs;

import java.time.LocalDate;
import java.time.LocalTime;

public record WeeklyScheduleEntry(
    int dayOfWeek,
    LocalTime startLocalTime,
    LocalTime endLocalTime,
    LocalDate validFrom,
    LocalDate validUntil
) {
}
