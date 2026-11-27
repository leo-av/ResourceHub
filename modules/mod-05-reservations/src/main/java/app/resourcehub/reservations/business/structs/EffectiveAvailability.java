package app.resourcehub.reservations.business.structs;

import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.shared.business.structs.TimeInterval;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EffectiveAvailability(
    UUID resourceId,
    ResourceStatus resourceStatus,
    int capacity,
    Instant rangeStart,
    Instant rangeEnd,
    List<TimeInterval> availableWindows
) {

    public EffectiveAvailability {
        availableWindows = availableWindows == null ? List.of() : List.copyOf(availableWindows);
    }
}
