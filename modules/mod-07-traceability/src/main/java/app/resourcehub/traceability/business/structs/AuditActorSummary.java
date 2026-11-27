package app.resourcehub.traceability.business.structs;

import java.util.UUID;

public record AuditActorSummary(
    UUID userId,
    String displayName,
    boolean anonymized
) {

    public static final String ANONYMIZED_LABEL = "Usuario eliminado (RGPD)";
}
