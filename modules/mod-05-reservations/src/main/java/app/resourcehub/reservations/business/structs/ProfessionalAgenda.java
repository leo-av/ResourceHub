package app.resourcehub.reservations.business.structs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProfessionalAgenda(
    UUID personResourceId,
    String resourceCode,
    String resourceName,
    Instant from,
    Instant to,
    List<AgendaSessionItem> items
) {
}
