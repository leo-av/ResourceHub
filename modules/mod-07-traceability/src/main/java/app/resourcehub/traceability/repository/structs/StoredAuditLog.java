package app.resourcehub.traceability.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredAuditLog(
    UUID id,
    String correlationId,
    String operationCode,
    String targetEntityType,
    UUID targetEntityId,
    String actorType,
    UUID requesterId,
    UUID holderId,
    String actorRole,
    String operationalScope,
    String outcome,
    String rejectionCategory,
    String violatedRuleOrPolicyId,
    String detailsJson,
    Instant occurredAt
) {
}
