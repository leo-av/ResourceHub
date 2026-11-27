package app.resourcehub.access.api.logic.services;

import app.resourcehub.access.business.B;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.LocalAuthCommand;
import app.resourcehub.access.business.structs.LogoutCommand;
import app.resourcehub.access.business.structs.RefreshTokenCommand;
import app.resourcehub.access.business.structs.UserProfile;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public class ConsoleAuthService {

    public ResponseWrapper<AuthTokenBundle> login(LocalAuthCommand command, String requestId, Clock clock) {
        if (command == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "Request body must not be null");
        }
        AuthTokenBundle bundle = B.auth().authenticateLocal(
            command.organizationId(),
            OperationalScope.AMB_03_MANAGEMENT,
            command.email(),
            command.password()
        );
        return ResponseWrapper.ok("OK", bundle, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<AuthTokenBundle> refresh(RefreshTokenCommand command, String requestId, Clock clock) {
        if (command == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "Request body must not be null");
        }
        AuthTokenBundle bundle = B.auth().refresh(
            command.organizationId(),
            OperationalScope.AMB_03_MANAGEMENT,
            command.refreshToken()
        );
        return ResponseWrapper.ok("OK", bundle, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<Void> logout(LogoutCommand command, String requestId, Clock clock) {
        if (command == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "Request body must not be null");
        }
        B.auth().logout(OperationalScope.AMB_03_MANAGEMENT, command.accessToken(), command.refreshToken());
        return ResponseWrapper.ok("OK", effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public ResponseWrapper<UserProfile> me(String accessToken, String requestId, Clock clock) {
        UserProfile profile = B.auth().getCurrentProfile(OperationalScope.AMB_03_MANAGEMENT, accessToken);
        return ResponseWrapper.ok("OK", profile, effectiveRequestId(requestId), effectiveClock(clock).instant());
    }

    public InternalProblem toInternalProblem(AccessBusinessException ex, String instancePath, String requestId) {
        ErrorCode code = ex.getErrorCode();
        int httpStatus = mapHttpStatus(code);
        return new InternalProblem(
            URI.create("https://internal.resourcehub/problems/" + code.name().toLowerCase().replace('_', '-')),
            code.name(),
            httpStatus,
            ex.getMessage(),
            instancePath != null ? instancePath : "/api/v1/console/auth",
            code,
            effectiveRequestId(requestId),
            null,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static int mapHttpStatus(ErrorCode code) {
        return switch (code) {
            case VALIDATION_FAILED, MALFORMED_REQUEST -> 400;
            case UNAUTHENTICATED, INVALID_CREDENTIALS, SECURITY_SIGNATURE_INVALID -> 401;
            case SCOPE_VIOLATION, NO_PERMISSION, ORGANIZATION_NOT_ACTIVE -> 403;
            case NOT_FOUND -> 404;
            case ALREADY_EXISTS, TRANSITION_NOT_ALLOWED -> 409;
            default -> 500;
        };
    }

    private static String effectiveRequestId(String requestId) {
        return (requestId == null || requestId.isBlank()) ? UUID.randomUUID().toString() : requestId;
    }

    private static Clock effectiveClock(Clock clock) {
        return clock != null ? clock : Clock.systemUTC();
    }
}
