package app.resourcehub.access.business;

import app.resourcehub.access.business.logic.instance.AuthBusiness;
import app.resourcehub.access.business.logic.instance.UserBusiness;
import app.resourcehub.access.business.utils.JwtTokenUtils;
import app.resourcehub.access.repository.R;
import java.time.Clock;

public final class B {

    private static UserBusiness userBusiness;
    private static AuthBusiness authBusiness;

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        init(Clock.systemUTC());
    }

    public static void init(Clock clock) {
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        userBusiness = new UserBusiness(R.users(), effectiveClock);
        authBusiness = new AuthBusiness(
            R.users(),
            R.localCredentials(),
            R.refreshTokens(),
            R.personalData(),
            effectiveClock,
            JwtTokenUtils.generateEd25519KeyPair()
        );
    }

    public static UserBusiness users() {
        if (userBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return userBusiness;
    }

    public static AuthBusiness auth() {
        if (authBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return authBusiness;
    }

    public static void terminate() {
        userBusiness = null;
        authBusiness = null;
    }
}
