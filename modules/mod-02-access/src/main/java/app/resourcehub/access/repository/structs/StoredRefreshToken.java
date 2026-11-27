package app.resourcehub.access.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredRefreshToken(
    String tokenHash,
    UUID familyId,
    UUID userId,
    UUID organizationId,
    String operationalScope,
    Instant issuedAt,
    Instant expiresAt,
    boolean used,
    boolean revoked
) {
}
