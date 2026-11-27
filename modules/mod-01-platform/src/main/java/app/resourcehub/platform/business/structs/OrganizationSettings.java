package app.resourcehub.platform.business.structs;

import java.time.Instant;
import java.util.UUID;

public record OrganizationSettings(
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
