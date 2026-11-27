package app.resourcehub.access.business.structs;

import app.resourcehub.shared.business.structs.OperationalScope;
import java.util.UUID;

public record UserProfile(
    UUID userId,
    UUID organizationId,
    UserRole role,
    IdentityStatus status,
    OperationalScope operationalScope,
    UUID linkedPersonResourceId
) {
}
