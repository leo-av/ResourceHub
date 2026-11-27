package app.resourcehub.policies.api.logic.services;

import app.resourcehub.access.business.B;
import app.resourcehub.access.business.logic.instance.AuthorizationBusiness;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.policies.business.structs.PoliciesBusinessException;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public class ConsolePolicyService {

    private static final AuthorizationBusiness DEFAULT_AUTHZ = new AuthorizationBusiness();

    public ResponseWrapper<String> assertPolicyReadAccess(ActorContext actor, String requestId, Clock clock) {
        requireConsoleOperation(actor, false);
        return ResponseWrapper.ok(
            "OK",
            "AUTHORIZED_READ",
            effectiveRequestId(requestId),
            effectiveClock(clock).instant()
        );
    }

    public ResponseWrapper<String> assertPolicyMutationAccess(ActorContext actor, String requestId, Clock clock) {
        requireConsoleOperation(actor, true);
        return ResponseWrapper.ok(
            "OK",
            "AUTHORIZED_ADMIN_WRITE",
            effectiveRequestId(requestId),
            effectiveClock(clock).instant()
        );
    }

    public InternalProblem toInternalProblem(SharedDomainException ex, String instancePath, String requestId) {
        ErrorCode code = ex.getErrorCode();
        int httpStatus = mapHttpStatus(code);
        return new InternalProblem(
            URI.create("https://internal.resourcehub/problems/" + code.name().toLowerCase().replace('_', '-')),
            code.name(),
            httpStatus,
            ex.getMessage(),
            instancePath != null ? instancePath : "/api/v1/console/policies",
            code,
            effectiveRequestId(requestId),
            null,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static void requireConsoleOperation(ActorContext actor, boolean requiresAdminOnly) {
        AuthorizationBusiness authz = B.isInitialized() ? B.authorization() : DEFAULT_AUTHZ;
        try {
            authz.assertConsoleOperation(actor, requiresAdminOnly);
        } catch (AccessBusinessException ex) {
            throw new PoliciesBusinessException(ex.getErrorCode(), ex.getMessage());
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
        return 500;
    }

    private static String effectiveRequestId(String requestId) {
        return (requestId == null || requestId.isBlank()) ? UUID.randomUUID().toString() : requestId;
    }

    private static Clock effectiveClock(Clock clock) {
        return clock != null ? clock : Clock.systemUTC();
    }
}
