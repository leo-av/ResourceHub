package app.resourcehub.availability.business.logic.instance;

import app.resourcehub.availability.business.structs.AvailabilityBusinessException;
import app.resourcehub.availability.business.structs.RecurringScheduleSlot;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.availability.repository.logic.local.WeeklyScheduleRepository;
import app.resourcehub.availability.repository.structs.StoredRecurringSchedule;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.TimeInterval;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public class ScheduleBusiness {

    private static final String MIDNIGHT_END = "24:00:00";
    private static final String MIDNIGHT_START = "00:00:00";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final WeeklyScheduleRepository scheduleRepository;
    private final Function<UUID, CatalogResource> resourceLookup;
    private final Clock clock;

    public ScheduleBusiness(
        WeeklyScheduleRepository scheduleRepository,
        Function<UUID, CatalogResource> resourceLookup,
        Clock clock
    ) {
        if (scheduleRepository == null) {
            throw new IllegalArgumentException("scheduleRepository must not be null");
        }
        if (resourceLookup == null) {
            throw new IllegalArgumentException("resourceLookup must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.scheduleRepository = scheduleRepository;
        this.resourceLookup = resourceLookup;
        this.clock = clock;
    }

    public List<RecurringScheduleSlot> replaceWeeklySchedules(
        UUID resourceId,
        List<WeeklyScheduleEntry> entries
    ) {
        if (resourceId == null) {
            throw new AvailabilityBusinessException(ErrorCode.VALIDATION_FAILED, "resourceId must not be null");
        }
        resourceLookup.apply(resourceId);

        List<WeeklyScheduleEntry> safeEntries = entries == null ? List.of() : entries;
        Instant now = clock.instant();
        List<StoredRecurringSchedule> expanded = new ArrayList<>();

        for (WeeklyScheduleEntry entry : safeEntries) {
            expandEntry(resourceId, entry, now, expanded);
        }

        List<StoredRecurringSchedule> persisted = scheduleRepository.replaceForResource(resourceId, expanded);
        return persisted.stream().map(ScheduleBusiness::mapToDomain).toList();
    }

    public List<RecurringScheduleSlot> listWeeklySchedules(UUID resourceId) {
        if (resourceId == null) {
            throw new AvailabilityBusinessException(ErrorCode.VALIDATION_FAILED, "resourceId must not be null");
        }
        return scheduleRepository.findByResourceId(resourceId).stream()
            .map(ScheduleBusiness::mapToDomain)
            .toList();
    }

    public List<TimeInterval> evaluateWeeklyWindows(
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
        List<StoredRecurringSchedule> slots = scheduleRepository.findByResourceId(resourceId);
        if (slots.isEmpty()) {
            return List.of();
        }

        ZoneId zone = orgContext.zoneId();
        LocalDate startDate = range.startAt().atZone(zone).toLocalDate().minusDays(1);
        LocalDate endDate = range.endAt().atZone(zone).toLocalDate().plusDays(1);

        List<TimeInterval> rawIntervals = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            int dow = date.getDayOfWeek().getValue();
            for (StoredRecurringSchedule slot : slots) {
                if (slot.dayOfWeek() != dow) {
                    continue;
                }
                if (slot.validFrom() != null && date.isBefore(slot.validFrom())) {
                    continue;
                }
                if (slot.validUntil() != null && date.isAfter(slot.validUntil())) {
                    continue;
                }
                Instant slotStart = date.atTime(LocalTime.parse(slot.startLocalTime())).atZone(zone).toInstant();
                Instant slotEnd = MIDNIGHT_END.equals(slot.endLocalTime())
                    ? date.plusDays(1).atStartOfDay(zone).toInstant()
                    : date.atTime(LocalTime.parse(slot.endLocalTime())).atZone(zone).toInstant();

                Instant clampedStart = slotStart.isAfter(range.startAt()) ? slotStart : range.startAt();
                Instant clampedEnd = slotEnd.isBefore(range.endAt()) ? slotEnd : range.endAt();
                if (clampedStart.isBefore(clampedEnd)) {
                    rawIntervals.add(new TimeInterval(clampedStart, clampedEnd));
                }
            }
        }

        return mergeContiguousIntervals(rawIntervals);
    }

    private static void expandEntry(
        UUID resourceId,
        WeeklyScheduleEntry entry,
        Instant now,
        List<StoredRecurringSchedule> out
    ) {
        if (entry == null || entry.startLocalTime() == null || entry.endLocalTime() == null) {
            throw new AvailabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Schedule entry and local times must not be null"
            );
        }
        if (entry.dayOfWeek() < 1 || entry.dayOfWeek() > 7) {
            throw new AvailabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "dayOfWeek must be between 1 and 7"
            );
        }
        if (entry.validFrom() != null
            && entry.validUntil() != null
            && entry.validFrom().isAfter(entry.validUntil())) {
            throw new AvailabilityBusinessException(
                ErrorCode.AVAILABILITY_INVALID_INTERVAL,
                "validFrom must not be after validUntil"
            );
        }

        LocalTime start = entry.startLocalTime();
        LocalTime end = entry.endLocalTime();
        if (start.equals(end)) {
            throw new AvailabilityBusinessException(
                ErrorCode.AVAILABILITY_INVALID_INTERVAL,
                "Schedule slot startLocalTime must differ from endLocalTime"
            );
        }

        if (end.equals(LocalTime.MIDNIGHT)) {
            out.add(new StoredRecurringSchedule(
                UuidV7Utils.generate(),
                resourceId,
                (short) entry.dayOfWeek(),
                formatTime(start),
                MIDNIGHT_END,
                entry.validFrom(),
                entry.validUntil(),
                now
            ));
            return;
        }

        if (start.isBefore(end)) {
            out.add(new StoredRecurringSchedule(
                UuidV7Utils.generate(),
                resourceId,
                (short) entry.dayOfWeek(),
                formatTime(start),
                formatTime(end),
                entry.validFrom(),
                entry.validUntil(),
                now
            ));
            return;
        }

        // Cruce de medianoche MDD-008: desdoblar [start, 24:00:00) en D y [00:00:00, end) en (D % 7) + 1
        short currentDay = (short) entry.dayOfWeek();
        short nextDay = (short) ((entry.dayOfWeek() % 7) + 1);
        out.add(new StoredRecurringSchedule(
            UuidV7Utils.generate(),
            resourceId,
            currentDay,
            formatTime(start),
            MIDNIGHT_END,
            entry.validFrom(),
            entry.validUntil(),
            now
        ));
        out.add(new StoredRecurringSchedule(
            UuidV7Utils.generate(),
            resourceId,
            nextDay,
            MIDNIGHT_START,
            formatTime(end),
            entry.validFrom(),
            entry.validUntil(),
            now
        ));
    }

    private static List<TimeInterval> mergeContiguousIntervals(List<TimeInterval> intervals) {
        if (intervals.isEmpty()) {
            return List.of();
        }
        List<TimeInterval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparing(TimeInterval::startAt).thenComparing(TimeInterval::endAt));

        List<TimeInterval> merged = new ArrayList<>();
        TimeInterval current = sorted.getFirst();
        for (int i = 1; i < sorted.size(); i++) {
            TimeInterval next = sorted.get(i);
            if (!next.startAt().isAfter(current.endAt())) {
                Instant maxEnd = next.endAt().isAfter(current.endAt()) ? next.endAt() : current.endAt();
                current = new TimeInterval(current.startAt(), maxEnd);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return List.copyOf(merged);
    }

    private static String formatTime(LocalTime time) {
        return time.format(TIME_FORMATTER);
    }

    private static RecurringScheduleSlot mapToDomain(StoredRecurringSchedule stored) {
        return new RecurringScheduleSlot(
            stored.id(),
            stored.resourceId(),
            stored.dayOfWeek(),
            stored.startLocalTime(),
            stored.endLocalTime(),
            stored.validFrom(),
            stored.validUntil(),
            stored.createdAt()
        );
    }
}
