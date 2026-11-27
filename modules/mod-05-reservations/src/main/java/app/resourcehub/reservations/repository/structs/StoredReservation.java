package app.resourcehub.reservations.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredReservation(
    UUID id,
    UUID requesterId,
    UUID holderId,
    String originScope,
    String status,
    String pendingReason,
    Instant expiresAt,
    UUID seriesId,
    Integer seriesOccurrenceIndex,
    long totalAmountMinor,
    String currencyCode,
    String paymentStatus,
    String attendanceStatus,
    UUID attendanceMarkedByUserId,
    Instant attendanceMarkedAt,
    String cancellationSource,
    UUID causedByBlockId,
    int version,
    Instant createdAt,
    Instant updatedAt
) {
}
