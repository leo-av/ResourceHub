package app.resourcehub.resources.business.structs;

import java.time.Instant;
import java.util.UUID;

public record CatalogResource(
    UUID id,
    UUID resourceTypeId,
    String code,
    String name,
    String description,
    ResourceStatus status,
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
