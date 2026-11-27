package app.resourcehub.access.business.structs;

import java.util.UUID;

public record RefreshTokenCommand(
    UUID organizationId,
    String refreshToken
) {
}
