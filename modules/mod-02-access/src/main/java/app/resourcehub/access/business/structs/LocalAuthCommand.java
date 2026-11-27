package app.resourcehub.access.business.structs;

import java.util.UUID;

public record LocalAuthCommand(
    UUID organizationId,
    String email,
    char[] password
) {
}
