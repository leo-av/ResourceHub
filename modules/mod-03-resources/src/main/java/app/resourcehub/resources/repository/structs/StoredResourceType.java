package app.resourcehub.resources.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredResourceType(
    UUID id,
    String code,
    String name,
    String description,
    String natureCode,
    boolean active,
    Instant createdAt,
    Instant updatedAt
) {
}
