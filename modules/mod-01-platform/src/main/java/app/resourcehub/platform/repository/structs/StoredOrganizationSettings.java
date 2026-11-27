package app.resourcehub.platform.repository.structs;

import java.time.Instant;
import java.util.UUID;

public record StoredOrganizationSettings(
    boolean singletonKey,
    UUID organizationId,
    String timeZoneId,
    String defaultCurrencyCode,
    short maxResourceDepth,
    int seriesMaxHorizonDays,
    int seriesMaxOccurrences,
    int waitlistMaxEntries,
    int waitlistOfferWindowMinutes,
    int approvalExpirationWindowHours,
    int attendanceAutoCompleteWindowHours,
    boolean allowStandardUserSpaceBooking,
    Instant updatedAt
) {
}
