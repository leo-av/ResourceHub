package app.resourcehub.access.business.structs;

import java.util.UUID;

public record UserSummary(
    UUID userId,
    String fullName,
    String email,
    boolean anonymized
) {
}
