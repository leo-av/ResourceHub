package app.resourcehub.reservations.repository.structs;

import java.util.List;

public record StoredReservationAggregate(
    StoredReservation reservation,
    List<StoredReservationLine> lines
) {

    public StoredReservationAggregate {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
