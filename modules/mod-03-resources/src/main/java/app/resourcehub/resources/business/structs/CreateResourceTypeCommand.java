package app.resourcehub.resources.business.structs;

import app.resourcehub.shared.business.structs.ResourceNature;

public record CreateResourceTypeCommand(
    String code,
    String name,
    String description,
    ResourceNature nature
) {
}
