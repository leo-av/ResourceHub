package app.resourcehub.access.business;

import app.resourcehub.access.business.logic.instance.AuthBusiness;
import app.resourcehub.access.business.logic.instance.AuthorizationBusiness;
import app.resourcehub.access.business.logic.instance.UserBusiness;
import app.resourcehub.access.business.structs.UserPersonLinkDisabledEvent;
import app.resourcehub.access.business.utils.JwtTokenUtils;
import app.resourcehub.access.repository.R;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public final class B {

    private static UserBusiness userBusiness;
    private static AuthBusiness authBusiness;
    private static AuthorizationBusiness authorizationBusiness;

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        init(Clock.systemUTC(), orgId -> Boolean.FALSE, ev -> {
        });
    }

    public static void init(Clock clock) {
        init(clock, orgId -> Boolean.FALSE, ev -> {
        });
    }

    public static void init(Clock clock, Function<UUID, Boolean> allowStandardUserSpaceBookingResolver) {
        init(clock, allowStandardUserSpaceBookingResolver, ev -> {
        });
    }

    public static void init(
        Clock clock,
        Function<UUID, Boolean> allowStandardUserSpaceBookingResolver,
        Consumer<UserPersonLinkDisabledEvent> personLinkDisabledListener
    ) {
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        userBusiness = new UserBusiness(
            R.users(),
            effectiveClock,
            R.personalData(),
            R.localCredentials(),
            R.refreshTokens(),
            personLinkDisabledListener
        );
        authBusiness = new AuthBusiness(
            R.users(),
            R.localCredentials(),
            R.refreshTokens(),
            R.personalData(),
            effectiveClock,
            JwtTokenUtils.generateEd25519KeyPair()
        );
        authorizationBusiness = new AuthorizationBusiness(allowStandardUserSpaceBookingResolver);
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

    public static AuthorizationBusiness authorization() {
        if (authorizationBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return authorizationBusiness;
    }

    public static void terminate() {
        userBusiness = null;
        authBusiness = null;
        authorizationBusiness = null;
    }
}
