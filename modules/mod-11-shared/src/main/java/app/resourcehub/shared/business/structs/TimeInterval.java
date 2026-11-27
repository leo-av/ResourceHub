package app.resourcehub.shared.business.structs;

import java.time.Duration;
import java.time.Instant;

public record TimeInterval(Instant startAt, Instant endAt) {

    public TimeInterval {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new SharedDomainException(
                    ErrorCode.AVAILABILITY_INVALID_INTERVAL,
                    "TimeInterval requires non-null UTC instants with startAt < endAt");
        }
    }

    public Duration duration() {
        return Duration.between(startAt, endAt);
    }

    public boolean contains(Instant instant) {
        return instant != null && !instant.isBefore(startAt) && instant.isBefore(endAt);
    }

    public boolean overlaps(TimeInterval other) {
        return other != null && startAt.isBefore(other.endAt) && other.startAt.isBefore(endAt);
    }

    public boolean isAdjacentTo(TimeInterval other) {
        return other != null && (endAt.equals(other.startAt) || other.endAt.equals(startAt));
    }
}
