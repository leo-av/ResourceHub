package app.resourcehub.reservations.business.structs;

import app.resourcehub.shared.business.structs.OperationalScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Reservation(
    UUID id,
    UUID requesterId,
    UUID holderId,
    OperationalScope originScope,
    ReservationStatus status,
    String pendingReason,
    Instant expiresAt,
    long totalAmountMinor,
    String currencyCode,
    String paymentStatus,
    String attendanceStatus,
    int version,
    Instant createdAt,
    Instant updatedAt,
    List<ReservationLineItem> lines
) {

    public Reservation {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
