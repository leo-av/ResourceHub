package app.resourcehub.reservations.api.logic.services;

import app.resourcehub.reservations.business.B;
import app.resourcehub.reservations.business.structs.CreateReservationCommand;
import app.resourcehub.reservations.business.structs.EffectiveAvailability;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationPage;
import app.resourcehub.reservations.business.structs.ReservationsBusinessException;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.PageResponse;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.SharedDomainException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class AppReservationService {

    public ResponseWrapper<EffectiveAvailability> getEffectiveAvailability(
        ActorContext actor,
        UUID resourceId,
        Instant startAt,
        Instant endAt,
        OrganizationContext orgContext,
        String requestId,
        Clock clock
    ) {
        requireReadableScope(actor);
        EffectiveAvailability result = B.availability().calculate(resourceId, startAt, endAt, orgContext);
        return ResponseWrapper.ok("OK", result, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<Reservation> createReservation(
        ActorContext actor,
        CreateReservationCommand command,
        OrganizationContext orgContext,
        String requestId,
        Clock clock
    ) {
        Reservation created = B.reservations().createReservation(actor, command, orgContext);
        return ResponseWrapper.ok("OK", created, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<PageResponse<Reservation>> listOwnReservations(
        ActorContext actor,
        int page,
        int size,
        String requestId,
        Clock clock
    ) {
        ReservationPage result = B.reservations().listOwnReservations(actor, page, size);
        PageResponse<Reservation> payload = new PageResponse<>(
            result.items(),
            result.page(),
            result.size(),
            result.totalItems(),
            result.totalPages()
        );
        return ResponseWrapper.ok("OK", payload, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<Reservation> getOwnReservation(
        ActorContext actor,
        UUID reservationId,
        String requestId,
        Clock clock
    ) {
        Reservation found = B.reservations().getOwnReservation(actor, reservationId);
        return ResponseWrapper.ok("OK", found, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public InternalProblem toInternalProblem(SharedDomainException ex, String instancePath, String requestId) {
        ErrorCode code = ex.getErrorCode();
        int httpStatus = mapHttpStatus(code);
        return new InternalProblem(
            URI.create("https://internal.resourcehub/problems/" + code.name().toLowerCase().replace('_', '-')),
            code.name(),
            httpStatus,
            ex.getMessage(),
            instancePath != null ? instancePath : "/api/v1/app/reservations",
            code,
            effectiveRequestId(requestId),
            null,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static void requireReadableScope(ActorContext actor) {
        if (actor == null) {
            throw new ReservationsBusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        OperationalScope scope = actor.operationalScope();
        if (scope != OperationalScope.AMB_01_CLIENT
            && scope != OperationalScope.AMB_02_PROFESSIONAL
            && scope != OperationalScope.AMB_03_MANAGEMENT) {
            throw new ReservationsBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "Operational scope not authorized to inspect resource availability"
            );
        }
    }

    private static int mapHttpStatus(ErrorCode code) {
        if (code == ErrorCode.VALIDATION_FAILED || code == ErrorCode.MALFORMED_REQUEST) {
            return 400;
        }
        if (code == ErrorCode.UNAUTHENTICATED) {
            return 401;
        }
        if (code == ErrorCode.SCOPE_VIOLATION || code == ErrorCode.NO_PERMISSION) {
            return 403;
        }
        if (code == ErrorCode.NOT_FOUND) {
            return 404;
        }
        if (code == ErrorCode.BOOKING_CONFLICT
            || code == ErrorCode.ALREADY_EXISTS
            || code == ErrorCode.TRANSITION_NOT_ALLOWED) {
            return 409;
        }
        if (code == ErrorCode.CATALOG_RESOURCE_NOT_BOOKABLE
            || code == ErrorCode.AVAILABILITY_INVALID_INTERVAL
            || code == ErrorCode.AVAILABILITY_OUTSIDE_HOURS
            || code == ErrorCode.AVAILABILITY_RESOURCE_BLOCKED) {
            return 422;
        }
        return 500;
    }

    private static String effectiveRequestId(String requestId) {
        return (requestId == null || requestId.isBlank()) ? UUID.randomUUID().toString() : requestId;
    }

    private static Clock effectiveClock(Clock clock) {
        return clock != null ? clock : Clock.systemUTC();
    }
}
