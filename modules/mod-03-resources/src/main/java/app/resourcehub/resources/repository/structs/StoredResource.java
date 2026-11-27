package app.resourcehub.resources.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredResource(
    UUID id,
    UUID resourceTypeId,
    String code,
    String name,
    String description,
    String status,
    int capacity,
    boolean requiresApproval,
    boolean allowProfessionalApproval,
    UUID parentId,
    UUID rootId,
    String hierarchyPath,
    short depth,
    UUID linkedUserId,
    Instant createdAt,
    Instant updatedAt
) {
}
