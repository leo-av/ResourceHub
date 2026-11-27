package app.resourcehub.gateway.business.structs;

import app.resourcehub.shared.business.structs.ActorContext;

public record PerimeterCheckResult(
    GatewaySanitizedRequest sanitizedRequest,
    ActorContext actor
) {
}
