package app.resourcehub.access.business.structs;

import java.util.UUID;

public record CreateConsoleUserCommand(
    UserRole role,
    String email,
    String fullName,
    String phone,
    char[] rawPassword,
    UUID linkedPersonResourceId
) {
}
