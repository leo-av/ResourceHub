package app.resourcehub.gateway.business;

import app.resourcehub.gateway.business.logic.instance.ErrorTranslationBusiness;
import app.resourcehub.gateway.business.logic.instance.HostRoutingBusiness;
import app.resourcehub.gateway.repository.R;
import java.time.Clock;

public final class B {

    private static HostRoutingBusiness hostRoutingBusiness;
    private static ErrorTranslationBusiness errorTranslationBusiness;

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        init(Clock.systemUTC());
    }

    public static void init(Clock clock) {
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        hostRoutingBusiness = new HostRoutingBusiness();
        errorTranslationBusiness = new ErrorTranslationBusiness(R.errorCatalogCache(), effectiveClock);
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

    public static void terminate() {
        hostRoutingBusiness = null;
        errorTranslationBusiness = null;
    }
}
