package app.resourcehub.platform.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredErrorCatalogItem(
    UUID id,
    String code,
    int numericValue,
    short familyCode,
    short moduleCode,
    short httpStatus,
    String defaultMessage,
    boolean active,
    Instant createdAt,
    Instant updatedAt
) {
}
