package app.resourcehub.access.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredPersonalData(
    UUID userId,
    String email,
    String fullName,
    String phone,
    String passwordHash,
    boolean calendarSyncConsent,
    Instant calendarSyncConsentAt,
    Instant updatedAt
) {
}
