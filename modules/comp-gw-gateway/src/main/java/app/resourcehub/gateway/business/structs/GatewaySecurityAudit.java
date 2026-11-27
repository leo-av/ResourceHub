package app.resourcehub.gateway.business.structs;

import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.util.UUID;

public record GatewaySecurityAudit(
    String requestId,
    String host,
    String path,
    String method,
    UUID organizationId,
    ActorType actorType,
    UUID actorId,
    String actorRole,
    OperationalScope operationalScope,
    ErrorCode errorCode,
    String reason
) {
}
