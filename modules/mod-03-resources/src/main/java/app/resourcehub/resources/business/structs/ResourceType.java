package app.resourcehub.resources.business.structs;

import app.resourcehub.shared.business.structs.ResourceNature;
import java.time.Instant;
import java.util.UUID;

public record ResourceType(
    UUID id,
    String code,
    String name,
    String description,
    ResourceNature nature,
    boolean active,
    Instant createdAt,
    Instant updatedAt
) {
}
