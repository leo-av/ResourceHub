package app.resourcehub.access.business.structs;

import java.time.Instant;
import java.util.UUID;

public record UserManagement(
    UUID id,
    UserRole role,
    IdentityStatus status,
    UUID linkedPersonResourceId,
    String email,
    String fullName,
    String phone,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt
) {
}
