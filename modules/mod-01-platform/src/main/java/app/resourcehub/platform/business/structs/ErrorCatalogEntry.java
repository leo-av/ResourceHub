package app.resourcehub.platform.business.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import java.time.Instant;
import java.util.UUID;

public record ErrorCatalogEntry(
    UUID id,
    ErrorCode code,
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
