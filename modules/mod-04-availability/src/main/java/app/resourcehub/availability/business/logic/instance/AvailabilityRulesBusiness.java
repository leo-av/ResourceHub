package app.resourcehub.availability.business.logic.instance;

import app.resourcehub.availability.business.structs.AvailabilityBusinessException;
import app.resourcehub.availability.business.structs.BlockOrigin;
import app.resourcehub.availability.business.structs.TemporalBlock;
import app.resourcehub.availability.repository.logic.local.TemporalBlockRepository;
import app.resourcehub.availability.repository.structs.AvailabilityRepositoryException;
import app.resourcehub.availability.repository.structs.StoredTemporalBlock;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.TimeInterval;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public class AvailabilityRulesBusiness {

    private static final int MAX_REASON_LENGTH = 255;

    private final TemporalBlockRepository temporalBlockRepository;
    private final ScheduleBusiness scheduleBusiness;
    private final Function<UUID, CatalogResource> resourceLookup;
    private final Clock clock;

    public AvailabilityRulesBusiness(
        TemporalBlockRepository temporalBlockRepository,
        ScheduleBusiness scheduleBusiness,
        Function<UUID, CatalogResource> resourceLookup,
        Clock clock
    ) {
        if (temporalBlockRepository == null) {
            throw new IllegalArgumentException("temporalBlockRepository must not be null");
        }
        if (scheduleBusiness == null) {
            throw new IllegalArgumentException("scheduleBusiness must not be null");
        }
        if (resourceLookup == null) {
            throw new IllegalArgumentException("resourceLookup must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.temporalBlockRepository = temporalBlockRepository;
        this.scheduleBusiness = scheduleBusiness;
        this.resourceLookup = resourceLookup;
        this.clock = clock;
    }

    public TemporalBlock registerBlock(
        UUID resourceId,
        BlockOrigin origin,
        Instant startAt,
        Instant endAt,
        boolean forcedConfirmation,
        String externalCalendarEventId,
        String reason,
        UUID createdByUserId
    ) {
        if (resourceId == null || origin == null) {
            throw new AvailabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "resourceId and block origin must not be null"
            );
        }
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new AvailabilityBusinessException(
                ErrorCode.AVAILABILITY_INVALID_INTERVAL,
                "Temporal block requires startAt < endAt"
            );
        }
        if (origin == BlockOrigin.CALENDAR && forcedConfirmation) {
            throw new AvailabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "CALENDAR blocks cannot use forcedConfirmation"
            );
        }
        String cleanReason = trimOptional(reason, MAX_REASON_LENGTH);
        String cleanExtId = trimOptional(externalCalendarEventId, MAX_REASON_LENGTH);
        resourceLookup.apply(resourceId);

        StoredTemporalBlock stored = new StoredTemporalBlock(
            UuidV7Utils.generate(),
            resourceId,
            origin.name(),
            startAt,
            endAt,
            forcedConfirmation,
            cleanExtId,
            cleanReason,
            true,
            createdByUserId,
            clock.instant(),
            null
        );
        try {
            return mapToDomain(temporalBlockRepository.insert(stored));
        } catch (AvailabilityRepositoryException ex) {
            if ("23505".equals(ex.getSqlState())) {
                throw new AvailabilityBusinessException(
                    ErrorCode.ALREADY_EXISTS,
                    "External calendar event block already registered"
                );
            }
            throw ex;
        }
    }

    public TemporalBlock releaseBlock(UUID blockId) {
        if (blockId == null) {
            throw new AvailabilityBusinessException(ErrorCode.VALIDATION_FAILED, "blockId must not be null");
        }
        StoredTemporalBlock existing = temporalBlockRepository.findById(blockId)
            .orElseThrow(() -> new AvailabilityBusinessException(ErrorCode.NOT_FOUND, "Temporal block not found"));
        if (!existing.active()) {
            throw new AvailabilityBusinessException(
                ErrorCode.TRANSITION_NOT_ALLOWED,
                "Temporal block is already released"
            );
        }
        return mapToDomain(temporalBlockRepository.release(blockId, clock.instant()));
    }

    public List<TimeInterval> evaluateOpenWindows(
        UUID resourceId,
        TimeInterval range,
        OrganizationContext orgContext
    ) {
        if (resourceId == null || range == null || orgContext == null) {
            throw new AvailabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "resourceId, range and orgContext must not be null"
            );
        }
        // Nivel 1 de D-5: recurso DISABLED tiene 0 disponibilidad efectiva
        CatalogResource resource = resourceLookup.apply(resourceId);
        if (resource.status() != ResourceStatus.AVAILABLE) {
            return List.of();
        }

        // Nivel 4 de D-5: ventanas recurrentes semanales IANA fusionadas en medianoche
        List<TimeInterval> openWindows = scheduleBusiness.evaluateWeeklyWindows(resourceId, range, orgContext);
        if (openWindows.isEmpty()) {
            return List.of();
        }

        // Nivel 2 de D-5: sustraccion de bloqueos temporales activos
        List<StoredTemporalBlock> blocks = temporalBlockRepository.findActiveOverlapping(
            resourceId,
            range.startAt(),
            range.endAt()
        );
        for (StoredTemporalBlock block : blocks) {
            openWindows = subtractBlock(openWindows, new TimeInterval(block.startAt(), block.endAt()));
            if (openWindows.isEmpty()) {
                return List.of();
            }
        }
        return openWindows;
    }

    public boolean hasActiveBlockOverlapping(UUID resourceId, TimeInterval range) {
        if (resourceId == null || range == null) {
            throw new AvailabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "resourceId and range must not be null"
            );
        }
        return !temporalBlockRepository.findActiveOverlapping(
            resourceId,
            range.startAt(),
            range.endAt()
        ).isEmpty();
    }

    private static List<TimeInterval> subtractBlock(List<TimeInterval> windows, TimeInterval block) {
        List<TimeInterval> remaining = new ArrayList<>();
        for (TimeInterval window : windows) {
            if (!window.overlaps(block)) {
                remaining.add(window);
                continue;
            }
            if (window.startAt().isBefore(block.startAt())) {
                remaining.add(new TimeInterval(window.startAt(), block.startAt()));
            }
            if (block.endAt().isBefore(window.endAt())) {
                remaining.add(new TimeInterval(block.endAt(), window.endAt()));
            }
        }
        return remaining;
    }

    private static String trimOptional(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new AvailabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Field must not exceed " + maxLength + " characters"
            );
        }
        return trimmed;
    }

    private static TemporalBlock mapToDomain(StoredTemporalBlock stored) {
        return new TemporalBlock(
            stored.id(),
            stored.resourceId(),
            BlockOrigin.valueOf(stored.blockOrigin()),
            stored.startAt(),
            stored.endAt(),
            stored.forcedConfirmation(),
            stored.externalCalendarEventId(),
            stored.reason(),
            stored.active(),
            stored.createdByUserId(),
            stored.createdAt(),
            stored.releasedAt()
        );
    }
}
