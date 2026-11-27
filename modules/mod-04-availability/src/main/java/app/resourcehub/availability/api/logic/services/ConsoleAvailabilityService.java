package app.resourcehub.availability.api.logic.services;

import app.resourcehub.access.business.logic.instance.AuthorizationBusiness;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.availability.business.B;
import app.resourcehub.availability.business.structs.AvailabilityBusinessException;
import app.resourcehub.availability.business.structs.CalendarException;
import app.resourcehub.availability.business.structs.CreateCalendarExceptionCommand;
import app.resourcehub.availability.business.structs.CreateTemporalBlockCommand;
import app.resourcehub.availability.business.structs.RecurringScheduleSlot;
import app.resourcehub.availability.business.structs.TemporalBlock;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
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

public class ConsoleAvailabilityService {

    private static final AuthorizationBusiness DEFAULT_AUTHZ = new AuthorizationBusiness();

    public ResponseWrapper<List<RecurringScheduleSlot>> replaceWeeklySchedule(
        ActorContext actor,
        UUID resourceId,
        List<WeeklyScheduleEntry> entries,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        List<RecurringScheduleSlot> result = B.schedules().replaceWeeklySchedules(resourceId, entries);
        return ResponseWrapper.ok("OK", result, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<List<RecurringScheduleSlot>> listWeeklySchedules(
        ActorContext actor,
        UUID resourceId,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        List<RecurringScheduleSlot> result = B.schedules().listWeeklySchedules(resourceId);
        return ResponseWrapper.ok("OK", result, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<CalendarException> createException(
        ActorContext actor,
        UUID resourceId,
        CreateCalendarExceptionCommand command,
        OrganizationContext orgContext,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        if (command == null) {
            throw new AvailabilityBusinessException(ErrorCode.VALIDATION_FAILED, "Request body must not be null");
        }
        CalendarException created = B.rules().registerException(
            resourceId,
            command.exceptionDate(),
            command.type(),
            command.startLocalTime(),
            command.endLocalTime(),
            command.reason(),
            actor.userId(),
            orgContext
        );
        return ResponseWrapper.ok("OK", created, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<List<CalendarException>> listExceptions(
        ActorContext actor,
        UUID resourceId,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        List<CalendarException> exceptions = B.rules().listExceptions(resourceId);
        return ResponseWrapper.ok("OK", exceptions, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<TemporalBlock> createBlock(
        ActorContext actor,
        UUID resourceId,
        CreateTemporalBlockCommand command,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        if (command == null) {
            throw new AvailabilityBusinessException(ErrorCode.VALIDATION_FAILED, "Request body must not be null");
        }
        TemporalBlock created = B.rules().registerBlock(
            resourceId,
            command.origin(),
            command.startAt(),
            command.endAt(),
            command.forcedConfirmation(),
            command.externalCalendarEventId(),
            command.reason(),
            actor.userId()
        );
        return ResponseWrapper.ok("OK", created, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<TemporalBlock> releaseBlock(
        ActorContext actor,
        UUID blockId,
        String requestId,
        Clock clock
    ) {
        requireConsoleManagement(actor);
        TemporalBlock released = B.rules().releaseBlock(blockId);
        return ResponseWrapper.ok("OK", released, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<TemporalBlock> registerWorkspaceSelfBlock(
        ActorContext actor,
        UUID resourceId,
        CreateTemporalBlockCommand command,
        String requestId,
        Clock clock
    ) {
        if (command == null) {
            throw new AvailabilityBusinessException(ErrorCode.VALIDATION_FAILED, "Request body must not be null");
        }
        TemporalBlock created = B.rules().registerWorkspaceSelfBlock(
            actor,
            resourceId,
            command.startAt(),
            command.endAt(),
            command.reason()
        );
        return ResponseWrapper.ok("OK", created, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public InternalProblem toInternalProblem(SharedDomainException ex, String instancePath, String requestId) {
        ErrorCode code = ex.getErrorCode();
        int httpStatus = mapHttpStatus(code);
        return new InternalProblem(
            URI.create("https://internal.resourcehub/problems/" + code.name().toLowerCase().replace('_', '-')),
            code.name(),
            httpStatus,
            ex.getMessage(),
            instancePath != null ? instancePath : "/api/v1/console/availability",
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
            throw new AvailabilityBusinessException(ex.getErrorCode(), ex.getMessage());
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
        if (code == ErrorCode.ALREADY_EXISTS || code == ErrorCode.TRANSITION_NOT_ALLOWED) {
            return 409;
        }
        if (code == ErrorCode.AVAILABILITY_INVALID_INTERVAL) {
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
