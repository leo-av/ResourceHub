package app.resourcehub.reservations.repository.structs;

public record StoredAgendaEntry(
    StoredReservation reservation,
    StoredReservationLine line
) {
}
