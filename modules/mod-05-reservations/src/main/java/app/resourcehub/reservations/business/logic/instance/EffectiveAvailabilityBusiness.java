package app.resourcehub.reservations.business.logic.instance;

import app.resourcehub.availability.business.logic.instance.AvailabilityRulesBusiness;
import app.resourcehub.reservations.business.structs.EffectiveAvailability;
import app.resourcehub.reservations.business.structs.ReservationsBusinessException;
import app.resourcehub.reservations.repository.logic.local.ReservationRepository;
import app.resourcehub.reservations.repository.structs.StoredReservationLine;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.TimeInterval;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public class EffectiveAvailabilityBusiness {

    private final ReservationRepository reservationRepository;
    private final Supplier<AvailabilityRulesBusiness> rulesBusinessSupplier;
    private final Function<UUID, CatalogResource> resourceLookup;

    public EffectiveAvailabilityBusiness(
        ReservationRepository reservationRepository,
        Supplier<AvailabilityRulesBusiness> rulesBusinessSupplier,
        Function<UUID, CatalogResource> resourceLookup
    ) {
        if (reservationRepository == null) {
            throw new IllegalArgumentException("reservationRepository must not be null");
        }
        if (rulesBusinessSupplier == null) {
            throw new IllegalArgumentException("rulesBusinessSupplier must not be null");
        }
        if (resourceLookup == null) {
            throw new IllegalArgumentException("resourceLookup must not be null");
        }
        this.reservationRepository = reservationRepository;
        this.rulesBusinessSupplier = rulesBusinessSupplier;
        this.resourceLookup = resourceLookup;
    }

    public EffectiveAvailability calculate(
        UUID resourceId,
        Instant startAt,
        Instant endAt,
        OrganizationContext orgContext
    ) {
        return calculate(resourceId, new TimeInterval(startAt, endAt), orgContext);
    }

    public EffectiveAvailability calculate(
        UUID resourceId,
        TimeInterval range,
        OrganizationContext orgContext
    ) {
        if (resourceId == null || range == null || orgContext == null) {
            throw new ReservationsBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "resourceId, range and orgContext must not be null"
            );
        }

        CatalogResource resource = resourceLookup.apply(resourceId);
        // Nivel 1 de D-5: recurso DISABLED tiene 0 disponibilidad efectiva
        if (resource.status() != ResourceStatus.AVAILABLE) {
            return new EffectiveAvailability(
                resource.id(),
                resource.status(),
                resource.capacity(),
                range.startAt(),
                range.endAt(),
                List.of()
            );
        }

        // Niveles 4 y 2 de D-5: ventanas semanales IANA menos bloqueos temporales activos
        List<TimeInterval> openWindows = rulesBusinessSupplier.get()
            .evaluateOpenWindows(resourceId, range, orgContext);
        if (openWindows.isEmpty()) {
            return new EffectiveAvailability(
                resource.id(),
                resource.status(),
                resource.capacity(),
                range.startAt(),
                range.endAt(),
                List.of()
            );
        }

        // Nivel 5 de D-5: sustraccion de lineas de reserva ACTIVE en intervalo semiabierto [startAt, endAt)
        List<StoredReservationLine> activeLines = reservationRepository.findActiveLinesOverlapping(
            resourceId,
            range.startAt(),
            range.endAt()
        );
        for (StoredReservationLine line : activeLines) {
            openWindows = subtractInterval(openWindows, new TimeInterval(line.startAt(), line.endAt()));
            if (openWindows.isEmpty()) {
                break;
            }
        }

        return new EffectiveAvailability(
            resource.id(),
            resource.status(),
            resource.capacity(),
            range.startAt(),
            range.endAt(),
            openWindows
        );
    }

    private static List<TimeInterval> subtractInterval(List<TimeInterval> windows, TimeInterval occupied) {
        List<TimeInterval> remaining = new ArrayList<>();
        for (TimeInterval window : windows) {
            if (!window.overlaps(occupied)) {
                remaining.add(window);
                continue;
            }
            if (window.startAt().isBefore(occupied.startAt())) {
                remaining.add(new TimeInterval(window.startAt(), occupied.startAt()));
            }
            if (occupied.endAt().isBefore(window.endAt())) {
                remaining.add(new TimeInterval(occupied.endAt(), window.endAt()));
            }
        }
        return remaining;
    }
}
