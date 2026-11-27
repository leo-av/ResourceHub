package app.resourcehub.resources.business.structs;

import java.util.UUID;

public record CreateResourceCommand(
    UUID resourceTypeId,
    String code,
    String name,
    String description,
    int capacity,
    boolean requiresApproval,
    boolean allowProfessionalApproval,
    UUID linkedUserId
) {
}
