package app.resourcehub.access.business.structs;

import java.time.Instant;
import java.util.UUID;

public record UserPersonLinkDisabledEvent(
    UUID userId,
    UUID linkedPersonResourceId,
    Instant occurredAt
) {
}
