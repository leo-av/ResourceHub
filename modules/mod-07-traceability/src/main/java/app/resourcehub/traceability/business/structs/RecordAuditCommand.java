package app.resourcehub.traceability.business.structs;

import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.util.UUID;

public record RecordAuditCommand(
    String correlationId,
    String operationCode,
    String targetEntityType,
    UUID targetEntityId,
    ActorType actorType,
    UUID requesterId,
    UUID holderId,
    String actorRole,
    OperationalScope operationalScope,
    AuditOutcome outcome,
    String rejectionCategory,
    String violatedRuleOrPolicyId,
    String detailsJson
) {
}
