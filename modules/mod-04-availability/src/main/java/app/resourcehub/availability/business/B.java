package app.resourcehub.availability.business;

import app.resourcehub.availability.business.logic.instance.AvailabilityRulesBusiness;
import app.resourcehub.availability.business.logic.instance.ScheduleBusiness;
import app.resourcehub.availability.business.structs.AvailabilityBusinessException;
import app.resourcehub.availability.repository.R;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.shared.business.structs.ErrorCode;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;

public final class B {

    private static ScheduleBusiness scheduleBusiness;
    private static AvailabilityRulesBusiness rulesBusiness;

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        init(Clock.systemUTC());
    }

    public static void init(Clock clock) {
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        Function<UUID, CatalogResource> catalogLookup = resourceId ->
            app.resourcehub.resources.business.B.resources().findById(resourceId)
                .orElseThrow(() -> new AvailabilityBusinessException(
                    ErrorCode.NOT_FOUND,
                    "Resource not found: " + resourceId
                ));
        scheduleBusiness = new ScheduleBusiness(R.weeklySchedules(), catalogLookup, effectiveClock);
        rulesBusiness = new AvailabilityRulesBusiness(
            R.temporalBlocks(),
            R.exceptions(),
            scheduleBusiness,
            catalogLookup,
            effectiveClock
        );
    }

    public static ScheduleBusiness schedules() {
        if (scheduleBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return scheduleBusiness;
    }

    public static AvailabilityRulesBusiness rules() {
        if (rulesBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return rulesBusiness;
    }

    public static void terminate() {
        scheduleBusiness = null;
        rulesBusiness = null;
    }
}
