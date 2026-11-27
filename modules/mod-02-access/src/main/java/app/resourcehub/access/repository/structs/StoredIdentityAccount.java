package app.resourcehub.access.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredIdentityAccount(
    UUID id,
    String roleCode,
    String status,
    UUID linkedPersonResourceId,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {
}
