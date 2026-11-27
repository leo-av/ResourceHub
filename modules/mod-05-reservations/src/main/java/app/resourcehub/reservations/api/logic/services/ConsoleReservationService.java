package app.resourcehub.reservations.api.logic.services;

import app.resourcehub.access.business.logic.instance.AuthorizationBusiness;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.reservations.business.B;
import app.resourcehub.reservations.business.structs.ModifyReservationCommand;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationsBusinessException;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.SharedDomainException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public class ConsoleReservationService {

    private static final AuthorizationBusiness DEFAULT_AUTHZ = new AuthorizationBusiness();

    public ResponseWrapper<Reservation> modifyReservation(
        ActorContext actor,
        UUID reservationId,
        ModifyReservationCommand command,
        OrganizationContext orgContext,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        String correlationId = effectiveRequestId(requestId);
        Reservation modified = B.reservations().modifyReservation(
            actor, reservationId, command, orgContext, correlationId
        );
        return ResponseWrapper.ok("OK", modified, correlationId, effectiveClock(clock).instant());
    }

    public ResponseWrapper<Reservation> cancelReservation(
        ActorContext actor,
        UUID reservationId,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        String correlationId = effectiveRequestId(requestId);
        Reservation cancelled = B.reservations().cancelReservation(actor, reservationId, correlationId);
        return ResponseWrapper.ok("OK", cancelled, correlationId, effectiveClock(clock).instant());
    }

    public InternalProblem toInternalProblem(SharedDomainException ex, String instancePath, String requestId) {
        ErrorCode code = ex.getErrorCode();
        int httpStatus = mapHttpStatus(code);
        return new InternalProblem(
            URI.create("https://internal.resourcehub/problems/" + code.name().toLowerCase().replace('_', '-')),
            code.name(),
            httpStatus,
            ex.getMessage(),
            instancePath != null ? instancePath : "/api/v1/console/reservations",
            code,
            effectiveRequestId(requestId),
            null,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static void requireConsoleManagement(ActorContext actor) {
        AuthorizationBusiness authz = app.resourcehub.access.business.B.isInitialized()
            ? app.resourcehub.access.business.B.authorization()
            : DEFAULT_AUTHZ;
        try {
            authz.assertConsoleOperation(actor, false);
        } catch (AccessBusinessException ex) {
            throw new ReservationsBusinessException(ex.getErrorCode(), ex.getMessage());
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
        return 500;
    }

    private static String effectiveRequestId(String requestId) {
        return (requestId == null || requestId.isBlank()) ? UUID.randomUUID().toString() : requestId;
    }

    private static Clock effectiveClock(Clock clock) {
        return clock != null ? clock : Clock.systemUTC();
    }
}
