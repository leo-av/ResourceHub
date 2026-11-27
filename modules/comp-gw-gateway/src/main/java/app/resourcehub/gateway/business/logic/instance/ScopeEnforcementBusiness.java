package app.resourcehub.gateway.business.logic.instance;

import app.resourcehub.gateway.business.structs.GatewayBusinessException;
import app.resourcehub.gateway.business.structs.GatewaySanitizedRequest;
import app.resourcehub.gateway.business.structs.GatewaySecurityAudit;
import app.resourcehub.gateway.business.structs.PerimeterCheckResult;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.SharedDomainException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class ScopeEnforcementBusiness {

    private static final String PLATFORM_HOST = "platform.resourcehub.app";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> PUBLIC_TENANT_PATHS = Set.of(
        "/api/v1/app/auth/login",
        "/api/v1/app/auth/refresh",
        "/api/v1/workspace/auth/login",
        "/api/v1/workspace/auth/refresh",
        "/api/v1/console/auth/login",
        "/api/v1/console/auth/refresh",
        "/api/v1/external/auth/token"
    );
    private static final Set<String> PUBLIC_PLATFORM_PATHS = Set.of(
        "/api/v1/auth/login",
        "/api/v1/error-catalog",
        "/api/v1/platform/error-catalog"
    );

    private final Function<String, ActorContext> tokenValidator;
    private final Predicate<UUID> personResourceActiveChecker;
    private final Predicate<String> orgActiveByHostChecker;
    private final Consumer<GatewaySecurityAudit> securityAuditRecorder;

    public ScopeEnforcementBusiness() {
        this(null, null, null, null);
    }

    public ScopeEnforcementBusiness(
        Function<String, ActorContext> tokenValidator,
        Predicate<UUID> personResourceActiveChecker,
        Predicate<String> orgActiveByHostChecker,
        Consumer<GatewaySecurityAudit> securityAuditRecorder
    ) {
        this.tokenValidator = tokenValidator;
        this.personResourceActiveChecker = personResourceActiveChecker != null
            ? personResourceActiveChecker
            : personId -> true;
        this.orgActiveByHostChecker = orgActiveByHostChecker != null
            ? orgActiveByHostChecker
            : host -> true;
        this.securityAuditRecorder = securityAuditRecorder != null
            ? securityAuditRecorder
            : audit -> {
            };
    }

    public PerimeterCheckResult enforce(
        GatewaySanitizedRequest sanitized,
        ActorContext preResolvedActor,
        UUID expectedOrganizationId
    ) {
        if (sanitized == null || isBlank(sanitized.host()) || isBlank(sanitized.path())) {
            throw new GatewayBusinessException(
                ErrorCode.MALFORMED_REQUEST,
                "GatewaySanitizedRequest host and path are mandatory"
            );
        }

        String host = normalizeHost(sanitized.host());
        String path = sanitized.path().trim();

        if (PLATFORM_HOST.equals(host)) {
            return enforcePlatformHost(sanitized, path, preResolvedActor);
        }

        if (isPlatformExclusivePath(path)) {
            throw auditAndReject(
                sanitized,
                expectedOrganizationId,
                preResolvedActor,
                ErrorCode.NOT_FOUND,
                "Platform route invoked from organization host"
            );
        }

        if (!orgActiveByHostChecker.test(host)) {
            throw auditAndReject(
                sanitized,
                expectedOrganizationId,
                preResolvedActor,
                ErrorCode.ORGANIZATION_NOT_ACTIVE,
                "Organization host is not operational"
            );
        }

        if (PUBLIC_TENANT_PATHS.contains(path) || path.startsWith("/api/v1/webhooks/inbound/")) {
            return new PerimeterCheckResult(sanitized, preResolvedActor);
        }

        OperationalScope channel = resolveTenantChannel(path);
        if (channel == null) {
            throw auditAndReject(
                sanitized,
                expectedOrganizationId,
                preResolvedActor,
                ErrorCode.NOT_FOUND,
                "Route not mapped to any tenant channel"
            );
        }

        ActorContext actor = resolveAuthenticatedActor(sanitized, expectedOrganizationId, preResolvedActor);
        if (expectedOrganizationId != null && !expectedOrganizationId.equals(actor.organizationId())) {
            throw auditAndReject(
                sanitized,
                expectedOrganizationId,
                actor,
                ErrorCode.NOT_FOUND,
                "JWT organizationId does not match host organization"
            );
        }

        validateTenantChannel(sanitized, expectedOrganizationId, channel, actor);
        return new PerimeterCheckResult(sanitized, actor);
    }

    private PerimeterCheckResult enforcePlatformHost(
        GatewaySanitizedRequest sanitized,
        String path,
        ActorContext preResolvedActor
    ) {
        if (PUBLIC_PLATFORM_PATHS.contains(path)) {
            return new PerimeterCheckResult(sanitized, preResolvedActor);
        }
        if (!isPlatformExclusivePath(path)) {
            throw auditAndReject(
                sanitized,
                null,
                preResolvedActor,
                ErrorCode.NOT_FOUND,
                "Tenant route cannot be invoked on platform host"
            );
        }
        ActorContext actor = resolveAuthenticatedActor(sanitized, null, preResolvedActor);
        if (actor.actorType() != ActorType.PLATFORM_ADMIN
            || actor.operationalScope() != OperationalScope.PLATFORM
            || !"PA".equals(actor.actorRole())) {
            throw auditAndReject(
                sanitized,
                null,
                actor,
                ErrorCode.SCOPE_VIOLATION,
                "Platform route requires PLATFORM_ADMIN with role PA"
            );
        }
        return new PerimeterCheckResult(sanitized, actor);
    }

    private void validateTenantChannel(
        GatewaySanitizedRequest sanitized,
        UUID expectedOrganizationId,
        OperationalScope channel,
        ActorContext actor
    ) {
        if (channel == OperationalScope.AMB_01_CLIENT) {
            if (actor.operationalScope() != OperationalScope.AMB_01_CLIENT) {
                throw auditAndReject(
                    sanitized,
                    expectedOrganizationId,
                    actor,
                    ErrorCode.SCOPE_VIOLATION,
                    "Channel /app requires AMB_01_CLIENT scope"
                );
            }
            return;
        }
        if (channel == OperationalScope.AMB_02_PROFESSIONAL) {
            if (actor.operationalScope() != OperationalScope.AMB_02_PROFESSIONAL
                || "US".equals(actor.actorRole())
                || actor.linkedPersonResourceId() == null
                || !personResourceActiveChecker.test(actor.linkedPersonResourceId())) {
                throw auditAndReject(
                    sanitized,
                    expectedOrganizationId,
                    actor,
                    ErrorCode.SCOPE_VIOLATION,
                    "Channel /workspace requires AMB_02_PROFESSIONAL with active linked PERSON resource"
                );
            }
            return;
        }
        if (channel == OperationalScope.AMB_03_MANAGEMENT) {
            if (actor.operationalScope() != OperationalScope.AMB_03_MANAGEMENT
                || (!"AD".equals(actor.actorRole()) && !"MG".equals(actor.actorRole()))) {
                throw auditAndReject(
                    sanitized,
                    expectedOrganizationId,
                    actor,
                    ErrorCode.SCOPE_VIOLATION,
                    "Channel /console requires AMB_03_MANAGEMENT with role AD or MG"
                );
            }
            return;
        }
        if (actor.actorType() != ActorType.EXTERNAL_CLIENT
            || actor.operationalScope() != OperationalScope.EXTERNAL_CLIENT) {
            throw auditAndReject(
                sanitized,
                expectedOrganizationId,
                actor,
                ErrorCode.SCOPE_VIOLATION,
                "Channel /external requires EXTERNAL_CLIENT scope"
            );
        }
    }

    private ActorContext resolveAuthenticatedActor(
        GatewaySanitizedRequest sanitized,
        UUID expectedOrganizationId,
        ActorContext preResolvedActor
    ) {
        if (preResolvedActor != null) {
            return preResolvedActor;
        }
        String bearer = extractBearerToken(sanitized.cleanHeaders());
        if (bearer == null || tokenValidator == null) {
            throw auditAndReject(
                sanitized,
                expectedOrganizationId,
                null,
                ErrorCode.UNAUTHENTICATED,
                "Missing Bearer access token"
            );
        }
        try {
            ActorContext validated = tokenValidator.apply(bearer);
            if (validated == null) {
                throw auditAndReject(
                    sanitized,
                    expectedOrganizationId,
                    null,
                    ErrorCode.UNAUTHENTICATED,
                    "Token validator returned null actor"
                );
            }
            return validated;
        } catch (SharedDomainException ex) {
            throw auditAndReject(
                sanitized,
                expectedOrganizationId,
                null,
                ex.getErrorCode() == ErrorCode.SCOPE_VIOLATION ? ErrorCode.SCOPE_VIOLATION : ErrorCode.UNAUTHENTICATED,
                ex.getMessage()
            );
        }
    }

    private GatewayBusinessException auditAndReject(
        GatewaySanitizedRequest sanitized,
        UUID organizationId,
        ActorContext actor,
        ErrorCode errorCode,
        String reason
    ) {
        securityAuditRecorder.accept(new GatewaySecurityAudit(
            sanitized.requestId(),
            sanitized.host(),
            sanitized.path(),
            sanitized.method(),
            organizationId != null ? organizationId : (actor != null ? actor.organizationId() : null),
            actor != null ? actor.actorType() : null,
            actor != null ? actor.userId() : null,
            actor != null ? actor.actorRole() : null,
            actor != null ? actor.operationalScope() : null,
            errorCode,
            reason
        ));
        return new GatewayBusinessException(errorCode, reason);
    }

    private static boolean isPlatformExclusivePath(String path) {
        return path.equals("/api/v1/organizations")
            || path.startsWith("/api/v1/organizations/")
            || path.equals("/api/v1/audit-logs")
            || path.startsWith("/api/v1/audit-logs/")
            || path.equals("/api/v1/platform")
            || path.startsWith("/api/v1/platform/")
            || path.equals("/api/v1/error-catalog")
            || path.startsWith("/api/v1/error-catalog/")
            || path.equals("/api/v1/auth")
            || path.startsWith("/api/v1/auth/");
    }

    private static OperationalScope resolveTenantChannel(String path) {
        if (path.startsWith("/api/v1/app/") || path.startsWith("/api/v2/app/")) {
            return OperationalScope.AMB_01_CLIENT;
        }
        if (path.startsWith("/api/v1/workspace/")) {
            return OperationalScope.AMB_02_PROFESSIONAL;
        }
        if (path.startsWith("/api/v1/console/") || path.startsWith("/api/v2/console/")) {
            return OperationalScope.AMB_03_MANAGEMENT;
        }
        if (path.startsWith("/api/v1/external/")) {
            return OperationalScope.EXTERNAL_CLIENT;
        }
        return null;
    }

    private static String extractBearerToken(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && "authorization".equalsIgnoreCase(entry.getKey())) {
                String val = entry.getValue();
                if (val != null && val.length() > BEARER_PREFIX.length()
                    && val.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                    String token = val.substring(BEARER_PREFIX.length()).trim();
                    return token.isEmpty() ? null : token;
                }
            }
        }
        return null;
    }

    private static String normalizeHost(String rawHost) {
        String trimmed = rawHost.trim().toLowerCase(Locale.ROOT);
        int colon = trimmed.indexOf(':');
        return colon >= 0 ? trimmed.substring(0, colon) : trimmed;
    }

    private static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }
}
