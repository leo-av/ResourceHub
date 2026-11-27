package app.resourcehub.reservations.business.logic.instance;

import app.resourcehub.availability.business.logic.instance.AvailabilityRulesBusiness;
import app.resourcehub.availability.business.logic.instance.ScheduleBusiness;
import app.resourcehub.reservations.business.structs.CreateReservationCommand;
import app.resourcehub.reservations.business.structs.ModifyReservationCommand;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationLineItem;
import app.resourcehub.reservations.business.structs.ReservationPage;
import app.resourcehub.reservations.business.structs.ReservationStatus;
import app.resourcehub.reservations.business.structs.ReservationsBusinessException;
import app.resourcehub.reservations.repository.logic.local.ReservationRepository;
import app.resourcehub.reservations.repository.structs.ReservationsRepositoryException;
import app.resourcehub.reservations.repository.structs.StoredReservation;
import app.resourcehub.reservations.repository.structs.StoredReservationAggregate;
import app.resourcehub.reservations.repository.structs.StoredReservationLine;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.TimeInterval;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public class ReservationBusiness {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_CURRENCY = "EUR";
    private static final String LINE_STATUS_ACTIVE = "ACTIVE";
    private static final String PAYMENT_SIN_CARGO = "SIN_CARGO";
    private static final String CANCELLATION_HOLDER = "HOLDER";
    private static final String CANCELLATION_MANAGEMENT = "MANAGEMENT";

    private final ReservationRepository reservationRepository;
    private final Supplier<ScheduleBusiness> scheduleBusinessSupplier;
    private final Supplier<AvailabilityRulesBusiness> rulesBusinessSupplier;
    private final Function<UUID, CatalogResource> resourceLookup;
    private final Clock clock;

    public ReservationBusiness(
        ReservationRepository reservationRepository,
        Supplier<ScheduleBusiness> scheduleBusinessSupplier,
        Supplier<AvailabilityRulesBusiness> rulesBusinessSupplier,
        Function<UUID, CatalogResource> resourceLookup,
        Clock clock
    ) {
        if (reservationRepository == null) {
            throw new IllegalArgumentException("reservationRepository must not be null");
        }
        if (scheduleBusinessSupplier == null) {
            throw new IllegalArgumentException("scheduleBusinessSupplier must not be null");
        }
        if (rulesBusinessSupplier == null) {
            throw new IllegalArgumentException("rulesBusinessSupplier must not be null");
        }
        if (resourceLookup == null) {
            throw new IllegalArgumentException("resourceLookup must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.reservationRepository = reservationRepository;
        this.scheduleBusinessSupplier = scheduleBusinessSupplier;
        this.rulesBusinessSupplier = rulesBusinessSupplier;
        this.resourceLookup = resourceLookup;
        this.clock = clock;
    }

    public Reservation createReservation(
        ActorContext actor,
        CreateReservationCommand command,
        OrganizationContext orgContext
    ) {
        requireClientScope(actor);
        if (command == null || command.resourceId() == null || orgContext == null) {
            throw new ReservationsBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "command, resourceId and orgContext must not be null"
            );
        }
        if (command.requestedHolderId() != null && !command.requestedHolderId().equals(actor.userId())) {
            throw new ReservationsBusinessException(
                ErrorCode.NO_PERMISSION,
                "Client scope must match requesterId and holderId"
            );
        }
        int units = command.requestedUnits() == null ? 1 : command.requestedUnits();
        if (units != 1) {
            throw new ReservationsBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "MVP-0 simple reservation supports requestedUnits = 1"
            );
        }

        TimeInterval slot = new TimeInterval(command.startAt(), command.endAt());
        CatalogResource resource = resourceLookup.apply(command.resourceId());
        validateSlotBookable(resource, slot, orgContext);

        // Nivel 5 D-5 / AC-003: ausencia de reservas ACTIVE solapadas en [startAt, endAt)
        List<StoredReservationLine> conflictingLines = reservationRepository.findActiveLinesOverlapping(
            resource.id(),
            slot.startAt(),
            slot.endAt()
        );
        if (!conflictingLines.isEmpty()) {
            throw new ReservationsBusinessException(
                ErrorCode.BOOKING_CONFLICT,
                "Requested interval conflicts with an existing active reservation"
            );
        }

        UUID reservationId = UuidV7Utils.generate();
        UUID lineId = UuidV7Utils.generate();
        Instant now = clock.instant();

        StoredReservation header = new StoredReservation(
            reservationId,
            actor.userId(),
            actor.userId(),
            OperationalScope.AMB_01_CLIENT.name(),
            ReservationStatus.CONFIRMED.name(),
            null,
            null,
            null,
            null,
            0L,
            DEFAULT_CURRENCY,
            PAYMENT_SIN_CARGO,
            null,
            null,
            null,
            null,
            null,
            1,
            now,
            now
        );

        StoredReservationLine line = new StoredReservationLine(
            lineId,
            reservationId,
            (short) 1,
            resource.id(),
            resource.rootId(),
            slot.startAt(),
            slot.endAt(),
            1,
            true,
            LINE_STATUS_ACTIVE,
            0L,
            DEFAULT_CURRENCY
        );

        StoredReservationAggregate persisted;
        try {
            persisted = reservationRepository.insertAggregateWithCanonicalLocks(
                List.of(resource.rootId()),
                header,
                List.of(line)
            );
        } catch (ReservationsRepositoryException ex) {
            if ("23P01".equals(ex.getSqlState())) {
                throw new ReservationsBusinessException(
                    ErrorCode.BOOKING_CONFLICT,
                    "Requested interval conflicts with an existing active reservation"
                );
            }
            throw ex;
        }
        return mapToDomain(persisted);
    }

    public Reservation modifyReservation(
        ActorContext actor,
        UUID reservationId,
        ModifyReservationCommand command,
        OrganizationContext orgContext
    ) {
        requireClientScope(actor);
        if (reservationId == null || command == null || command.resourceId() == null || orgContext == null) {
            throw new ReservationsBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "reservationId, command, resourceId and orgContext must not be null"
            );
        }
        int units = command.requestedUnits() == null ? 1 : command.requestedUnits();
        if (units != 1) {
            throw new ReservationsBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Modification currently supports requestedUnits = 1"
            );
        }

        StoredReservationAggregate existing = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationsBusinessException(ErrorCode.NOT_FOUND, "Reservation not found"));
        if (!existing.reservation().holderId().equals(actor.userId())) {
            throw new ReservationsBusinessException(ErrorCode.NOT_FOUND, "Reservation not found");
        }
        ReservationStatus currentStatus = ReservationStatus.valueOf(existing.reservation().status());
        if (!isModifiableStatus(currentStatus)) {
            throw new ReservationsBusinessException(
                ErrorCode.TRANSITION_NOT_ALLOWED,
                "Reservation status does not allow modification: " + currentStatus
            );
        }

        TimeInterval newSlot = new TimeInterval(command.startAt(), command.endAt());
        CatalogResource newResource = resourceLookup.apply(command.resourceId());
        validateSlotBookable(newResource, newSlot, orgContext);

        List<StoredReservationLine> conflicts = reservationRepository.findActiveLinesOverlappingExcludingReservation(
            newResource.id(),
            newSlot.startAt(),
            newSlot.endAt(),
            reservationId
        );
        if (!conflicts.isEmpty()) {
            throw new ReservationsBusinessException(
                ErrorCode.BOOKING_CONFLICT,
                "Target interval conflicts with another active reservation"
            );
        }

        UUID oldRootId = existing.lines().isEmpty()
            ? newResource.rootId()
            : existing.lines().getFirst().resourceRootId();
        StoredReservationLine replacementLine = new StoredReservationLine(
            UuidV7Utils.generate(),
            reservationId,
            (short) 1,
            newResource.id(),
            newResource.rootId(),
            newSlot.startAt(),
            newSlot.endAt(),
            1,
            true,
            LINE_STATUS_ACTIVE,
            0L,
            DEFAULT_CURRENCY
        );

        try {
            StoredReservationAggregate updated = reservationRepository.modifyAggregateWithCanonicalLocks(
                List.of(oldRootId, newResource.rootId()),
                reservationId,
                existing.reservation().version(),
                clock.instant(),
                replacementLine
            ).orElseThrow(() -> new ReservationsBusinessException(
                ErrorCode.TRANSITION_NOT_ALLOWED,
                "Concurrent modification detected on reservation version"
            ));
            return mapToDomain(updated);
        } catch (ReservationsRepositoryException ex) {
            if ("23P01".equals(ex.getSqlState())) {
                throw new ReservationsBusinessException(
                    ErrorCode.BOOKING_CONFLICT,
                    "Target interval conflicts with another active reservation"
                );
            }
            throw ex;
        }
    }

    public Reservation cancelReservation(ActorContext actor, UUID reservationId) {
        if (actor == null) {
            throw new ReservationsBusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        String cancellationSource = resolveCancellationSource(actor);
        if (reservationId == null) {
            throw new ReservationsBusinessException(ErrorCode.VALIDATION_FAILED, "reservationId must not be null");
        }

        StoredReservationAggregate existing = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationsBusinessException(ErrorCode.NOT_FOUND, "Reservation not found"));
        if (actor.operationalScope() == OperationalScope.AMB_01_CLIENT
            && !existing.reservation().holderId().equals(actor.userId())) {
            throw new ReservationsBusinessException(ErrorCode.NOT_FOUND, "Reservation not found");
        }
        ReservationStatus currentStatus = ReservationStatus.valueOf(existing.reservation().status());
        if (!isModifiableStatus(currentStatus)) {
            throw new ReservationsBusinessException(
                ErrorCode.TRANSITION_NOT_ALLOWED,
                "Reservation status does not allow cancellation: " + currentStatus
            );
        }

        reservationRepository.updateStatus(
            reservationId,
            ReservationStatus.CANCELLED.name(),
            null,
            null,
            cancellationSource,
            null,
            existing.reservation().version(),
            clock.instant()
        ).orElseThrow(() -> new ReservationsBusinessException(
            ErrorCode.TRANSITION_NOT_ALLOWED,
            "Concurrent modification detected while cancelling reservation"
        ));

        StoredReservationAggregate cancelled = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationsBusinessException(ErrorCode.NOT_FOUND, "Reservation not found"));
        return mapToDomain(cancelled);
    }

    public ReservationPage listOwnReservations(ActorContext actor, int page, int size) {
        requireClientScope(actor);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ReservationsBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Pagination parameters must satisfy page >= 1 and 1 <= size <= 100"
            );
        }
        int offset = (page - 1) * size;
        long totalItems = reservationRepository.countByHolderId(actor.userId());
        List<Reservation> items = totalItems == 0
            ? List.of()
            : reservationRepository.findAggregatesByHolderId(actor.userId(), size, offset).stream()
                .map(ReservationBusiness::mapToDomain)
                .toList();
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + size - 1) / size);
        return new ReservationPage(items, page, size, totalItems, totalPages);
    }

    public Reservation getOwnReservation(ActorContext actor, UUID reservationId) {
        requireClientScope(actor);
        if (reservationId == null) {
            throw new ReservationsBusinessException(ErrorCode.VALIDATION_FAILED, "reservationId must not be null");
        }
        StoredReservationAggregate aggregate = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationsBusinessException(ErrorCode.NOT_FOUND, "Reservation not found"));
        if (!aggregate.reservation().holderId().equals(actor.userId())) {
            throw new ReservationsBusinessException(ErrorCode.NOT_FOUND, "Reservation not found");
        }
        return mapToDomain(aggregate);
    }

    private void validateSlotBookable(CatalogResource resource, TimeInterval slot, OrganizationContext orgContext) {
        // Nivel 1 D-5: recurso habilitado
        if (resource.status() != ResourceStatus.AVAILABLE) {
            throw new ReservationsBusinessException(
                ErrorCode.CATALOG_RESOURCE_NOT_BOOKABLE,
                "Resource is not AVAILABLE for booking: " + resource.id()
            );
        }

        // Niveles 4 y 3 D-5: cobertura completa dentro del horario recurrente + excepciones IANA
        List<TimeInterval> scheduleWindows = rulesBusinessSupplier.get()
            .evaluateScheduleWithExceptions(resource.id(), slot, orgContext);
        if (!isCoveredByWindows(scheduleWindows, slot)) {
            throw new ReservationsBusinessException(
                ErrorCode.AVAILABILITY_OUTSIDE_HOURS,
                "Requested interval falls outside recurring weekly schedules"
            );
        }

        // Nivel 2 D-5: ausencia de bloqueos temporales activos solapados
        if (rulesBusinessSupplier.get().hasActiveBlockOverlapping(resource.id(), slot)) {
            throw new ReservationsBusinessException(
                ErrorCode.AVAILABILITY_RESOURCE_BLOCKED,
                "Resource has an active temporal block in the requested interval"
            );
        }
    }

    private static String resolveCancellationSource(ActorContext actor) {
        if (actor.operationalScope() == OperationalScope.AMB_01_CLIENT) {
            return CANCELLATION_HOLDER;
        }
        if (actor.operationalScope() == OperationalScope.AMB_03_MANAGEMENT
            && ("AD".equals(actor.actorRole()) || "MG".equals(actor.actorRole()))) {
            return CANCELLATION_MANAGEMENT;
        }
        throw new ReservationsBusinessException(
            ErrorCode.SCOPE_VIOLATION,
            "Operational scope not authorized to cancel reservations"
        );
    }

    private static boolean isModifiableStatus(ReservationStatus status) {
        return status == ReservationStatus.CONFIRMED || status == ReservationStatus.PENDING;
    }

    private static void requireClientScope(ActorContext actor) {
        if (actor == null) {
            throw new ReservationsBusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        if (actor.operationalScope() != OperationalScope.AMB_01_CLIENT) {
            throw new ReservationsBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "App reservations endpoint requires AMB_01_CLIENT operational scope"
            );
        }
    }

    private static boolean isCoveredByWindows(List<TimeInterval> windows, TimeInterval target) {
        for (TimeInterval window : windows) {
            if (!window.startAt().isAfter(target.startAt()) && !window.endAt().isBefore(target.endAt())) {
                return true;
            }
        }
        return false;
    }

    private static Reservation mapToDomain(StoredReservationAggregate aggregate) {
        StoredReservation header = aggregate.reservation();
        List<ReservationLineItem> lines = aggregate.lines().stream()
            .map(ReservationBusiness::mapLineToDomain)
            .toList();
        return new Reservation(
            header.id(),
            header.requesterId(),
            header.holderId(),
            OperationalScope.valueOf(header.originScope()),
            ReservationStatus.valueOf(header.status()),
            header.pendingReason(),
            header.expiresAt(),
            header.totalAmountMinor(),
            header.currencyCode(),
            header.paymentStatus(),
            header.attendanceStatus(),
            header.version(),
            header.createdAt(),
            header.updatedAt(),
            lines
        );
    }

    private static ReservationLineItem mapLineToDomain(StoredReservationLine line) {
        return new ReservationLineItem(
            line.id(),
            line.reservationId(),
            line.lineNumber(),
            line.resourceId(),
            line.resourceRootId(),
            line.startAt(),
            line.endAt(),
            line.requestedUnits(),
            line.exclusive(),
            line.status(),
            line.lineAmountMinor(),
            line.currencyCode()
        );
    }
}
