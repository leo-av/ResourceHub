package app.resourcehub.access.api.logic.services;

import app.resourcehub.access.business.B;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.CreateConsoleUserCommand;
import app.resourcehub.access.business.structs.PatchConsoleUserCommand;
import app.resourcehub.access.business.structs.UserManagement;
import app.resourcehub.access.business.structs.UserManagementPage;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.PageResponse;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public class ConsoleUserService {

    public ResponseWrapper<UserManagement> createUser(
        ActorContext actor,
        CreateConsoleUserCommand command,
        String requestId,
        Clock clock
    ) {
        requireAdminWrite(actor);
        UserManagement created = B.users().createConsoleUser(actor.organizationId(), command);
        return ResponseWrapper.ok("OK", created, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<PageResponse<UserManagement>> listUsers(
        ActorContext actor,
        int page,
        int size,
        String requestId,
        Clock clock
    ) {
        requireConsoleRead(actor);
        UserManagementPage result = B.users().listUsersPage(page, size);
        PageResponse<UserManagement> payload = new PageResponse<>(
            result.items(),
            result.page(),
            result.size(),
            result.totalItems(),
            result.totalPages()
        );
        return ResponseWrapper.ok("OK", payload, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<UserManagement> getUser(
        ActorContext actor,
        UUID userId,
        String requestId,
        Clock clock
    ) {
        requireConsoleRead(actor);
        UserManagement user = B.users().getUserManagement(userId);
        return ResponseWrapper.ok("OK", user, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<UserManagement> patchUser(
        ActorContext actor,
        UUID userId,
        PatchConsoleUserCommand command,
        String requestId,
        Clock clock
    ) {
        requireAdminWrite(actor);
        UserManagement updated = B.users().updateConsoleUser(userId, command);
        return ResponseWrapper.ok("OK", updated, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public InternalProblem toInternalProblem(AccessBusinessException ex, String instancePath, String requestId) {
        ErrorCode code = ex.getErrorCode();
        int httpStatus = mapHttpStatus(code);
        return new InternalProblem(
            URI.create("https://internal.resourcehub/problems/" + code.name().toLowerCase().replace('_', '-')),
            code.name(),
            httpStatus,
            ex.getMessage(),
            instancePath != null ? instancePath : "/api/v1/console/users",
            code,
            effectiveRequestId(requestId),
            null,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static void requireConsoleRead(ActorContext actor) {
        B.authorization().assertConsoleOperation(actor, false);
    }

    private static void requireAdminWrite(ActorContext actor) {
        B.authorization().assertConsoleOperation(actor, true);
    }

    private static int mapHttpStatus(ErrorCode code) {
        if (code == ErrorCode.VALIDATION_FAILED || code == ErrorCode.MALFORMED_REQUEST) {
            return 400;
        }
        if (code == ErrorCode.UNAUTHENTICATED || code == ErrorCode.INVALID_CREDENTIALS) {
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
        return 500;
    }

    private static String effectiveRequestId(String requestId) {
        return (requestId == null || requestId.isBlank()) ? UUID.randomUUID().toString() : requestId;
    }

    private static Clock effectiveClock(Clock clock) {
        return clock != null ? clock : Clock.systemUTC();
    }
}
