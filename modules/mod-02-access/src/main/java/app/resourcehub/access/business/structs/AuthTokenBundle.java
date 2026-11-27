package app.resourcehub.access.business.structs;

import app.resourcehub.shared.business.structs.OperationalScope;
import java.time.Instant;
import java.util.UUID;

public record AuthTokenBundle(
    String tokenType,
    String accessToken,
    Instant accessExpiresAt,
    String refreshToken,
    Instant refreshExpiresAt,
    OperationalScope operationalScope,
    UUID organizationId,
    UUID userId,
    UserRole role
) {
}
