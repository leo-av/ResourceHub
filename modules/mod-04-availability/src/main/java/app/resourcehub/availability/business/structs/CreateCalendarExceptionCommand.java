package app.resourcehub.availability.business.structs;

import java.time.LocalDate;
import java.time.LocalTime;

public record CreateCalendarExceptionCommand(
    LocalDate exceptionDate,
    ExceptionType type,
    LocalTime startLocalTime,
    LocalTime endLocalTime,
    String reason
) {
}
