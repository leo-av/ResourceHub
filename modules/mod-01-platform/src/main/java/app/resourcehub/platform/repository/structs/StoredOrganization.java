package app.resourcehub.platform.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredOrganization(
    UUID id,
    String slug,
    String name,
    String status,
    String initialTimeZoneId,
    String dbHost,
    int dbPort,
    String dbName,
    String currentSchemaVersion,
    Instant createdAt,
    Instant updatedAt,
    Instant archivedAt
) {
}
