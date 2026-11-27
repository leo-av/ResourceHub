package app.resourcehub.gateway.business;

import app.resourcehub.gateway.business.logic.instance.ErrorTranslationBusiness;
import app.resourcehub.gateway.business.logic.instance.HostRoutingBusiness;
import app.resourcehub.gateway.business.logic.instance.ScopeEnforcementBusiness;
import app.resourcehub.gateway.business.structs.GatewaySecurityAudit;
import app.resourcehub.gateway.repository.R;
import app.resourcehub.shared.business.structs.ActorContext;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class B {

    private static HostRoutingBusiness hostRoutingBusiness;
    private static ErrorTranslationBusiness errorTranslationBusiness;
    private static ScopeEnforcementBusiness scopeEnforcementBusiness;

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        init(Clock.systemUTC());
    }

    public static void init(Clock clock) {
        init(clock, null, null, null, null);
    }

    public static void init(
        Clock clock,
        Function<String, ActorContext> tokenValidator,
        Predicate<UUID> personResourceActiveChecker,
        Predicate<String> orgActiveByHostChecker,
        Consumer<GatewaySecurityAudit> securityAuditRecorder
    ) {
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        hostRoutingBusiness = new HostRoutingBusiness();
        errorTranslationBusiness = new ErrorTranslationBusiness(R.errorCatalogCache(), effectiveClock);
        scopeEnforcementBusiness = new ScopeEnforcementBusiness(
            tokenValidator,
            personResourceActiveChecker,
            orgActiveByHostChecker,
            securityAuditRecorder
        );
    }

    public static HostRoutingBusiness hostRouting() {
        if (hostRoutingBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return hostRoutingBusiness;
    }

    public static ErrorTranslationBusiness errorTranslation() {
        if (errorTranslationBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return errorTranslationBusiness;
    }

    public static ScopeEnforcementBusiness scopeEnforcement() {
        if (scopeEnforcementBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return scopeEnforcementBusiness;
    }

    public static void terminate() {
        hostRoutingBusiness = null;
        errorTranslationBusiness = null;
        scopeEnforcementBusiness = null;
    }
}
