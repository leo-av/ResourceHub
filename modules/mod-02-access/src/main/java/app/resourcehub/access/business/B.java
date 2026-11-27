package app.resourcehub.access.business;

import app.resourcehub.access.business.logic.instance.UserBusiness;
import app.resourcehub.access.repository.R;
import java.time.Clock;

public final class B {

    private static UserBusiness userBusiness;

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        init(Clock.systemUTC());
    }

    public static void init(Clock clock) {
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        userBusiness = new UserBusiness(R.users(), effectiveClock);
    }

    public static UserBusiness users() {
        if (userBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return userBusiness;
    }

    public static void terminate() {
        userBusiness = null;
    }
}
