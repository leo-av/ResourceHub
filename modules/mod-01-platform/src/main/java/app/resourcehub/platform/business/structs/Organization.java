package app.resourcehub.platform.business.structs;

import app.resourcehub.shared.business.structs.OrganizationContext;
import java.time.Instant;
import java.util.UUID;

public record Organization(
    UUID id,
    String slug,
    String name,
    OrganizationStatus status,
    String initialTimeZoneId,
    String dbHost,
    int dbPort,
    String dbName,
    String currentSchemaVersion,
    Instant createdAt,
    Instant updatedAt,
    Instant archivedAt
) {

    public boolean isOperational() {
        return status == OrganizationStatus.ACTIVE;
    }

    public OrganizationContext toContext() {
        return new OrganizationContext(id, slug, initialTimeZoneId);
    }
}
