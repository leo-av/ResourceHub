package app.resourcehub.shared.business.structs;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;

public record OrganizationContext(UUID organizationId, String slug, String timeZoneId) {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{1,63}$");

    public OrganizationContext {
        if (organizationId == null) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "organizationId must not be null");
        }
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "slug must match ^[a-z0-9-]{1,63}$");
        }
        if (timeZoneId == null || timeZoneId.isBlank()) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "timeZoneId must not be blank");
        }
        try {
            ZoneId.of(timeZoneId);
        } catch (DateTimeException ex) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Invalid IANA timeZoneId: " + timeZoneId);
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(timeZoneId);
    }
}
