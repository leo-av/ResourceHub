package app.resourcehub.access.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredLocalCredential(
    UUID organizationId,
    UUID userId,
    String normalizedEmail,
    String passwordHash,
    Instant updatedAt
) {
}
