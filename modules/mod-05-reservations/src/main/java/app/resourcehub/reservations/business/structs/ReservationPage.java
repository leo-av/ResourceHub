package app.resourcehub.reservations.business.structs;

import java.util.List;

public record ReservationPage(
    List<Reservation> items,
    int page,
    int size,
    long totalItems,
    int totalPages
) {

    public ReservationPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
