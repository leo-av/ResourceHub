package app.resourcehub.traceability.business.structs;

import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.time.Instant;
import java.util.UUID;

public record AuditLogEntry(
    UUID id,
    String correlationId,
    String operationCode,
    String targetEntityType,
    UUID targetEntityId,
    ActorType actorType,
    UUID requesterId,
    AuditActorSummary requesterSummary,
    UUID holderId,
    AuditActorSummary holderSummary,
    String actorRole,
    OperationalScope operationalScope,
    AuditOutcome outcome,
    String rejectionCategory,
    String violatedRuleOrPolicyId,
    String detailsJson,
    Instant occurredAt
) {
}
