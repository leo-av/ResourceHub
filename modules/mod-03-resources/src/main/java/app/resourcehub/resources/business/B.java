package app.resourcehub.resources.business;

import app.resourcehub.resources.business.logic.instance.ResourceBusiness;
import app.resourcehub.resources.business.logic.instance.ResourceTypeBusiness;
import app.resourcehub.resources.repository.R;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Predicate;

public final class B {

    private static ResourceTypeBusiness resourceTypeBusiness;
    private static ResourceBusiness resourceBusiness;

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        init(Clock.systemUTC(), userId -> true);
    }

    public static void init(Clock clock) {
        init(clock, userId -> true);
    }

    public static void init(Clock clock, Predicate<UUID> linkedUserActiveChecker) {
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        resourceTypeBusiness = new ResourceTypeBusiness(R.resourceTypes(), effectiveClock);
        resourceBusiness = new ResourceBusiness(
            R.resources(),
            R.resourceTypes(),
            effectiveClock,
            linkedUserActiveChecker
        );
    }

    public static ResourceTypeBusiness resourceTypes() {
        if (resourceTypeBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return resourceTypeBusiness;
    }

    public static ResourceBusiness resources() {
        if (resourceBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return resourceBusiness;
    }

    public static void terminate() {
        resourceTypeBusiness = null;
        resourceBusiness = null;
    }
}
