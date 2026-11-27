package app.resourcehub.access.business.structs;

import java.util.UUID;

public record PatchConsoleUserCommand(
    UserRole role,
    IdentityStatus status,
    UUID linkedPersonResourceId,
    boolean clearPersonLink
) {
}
