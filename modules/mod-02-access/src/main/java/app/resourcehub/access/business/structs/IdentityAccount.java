package app.resourcehub.access.business.structs;

import java.time.Instant;
import java.util.UUID;

public record IdentityAccount(
    UUID id,
    UserRole role,
    IdentityStatus status,
    UUID linkedPersonResourceId,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {
}
