package app.resourcehub.shared.business.structs;

import java.util.Set;
import java.util.UUID;

public record ActorContext(
        UUID userId,
        UUID organizationId,
        ActorType actorType,
        String actorRole,
        OperationalScope operationalScope,
        UUID linkedPersonResourceId,
        Set<String> scopes) {

    public ActorContext {
        if (actorType == null) {
            throw new SharedDomainException(ErrorCode.UNAUTHENTICATED, "actorType must not be null");
        }
        if (actorRole == null || actorRole.isBlank()) {
            throw new SharedDomainException(ErrorCode.UNAUTHENTICATED, "actorRole must not be blank");
        }
        if (operationalScope == null) {
            throw new SharedDomainException(ErrorCode.SCOPE_VIOLATION, "operationalScope must not be null");
        }
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
