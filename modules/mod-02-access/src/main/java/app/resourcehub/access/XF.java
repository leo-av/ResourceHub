package app.resourcehub.access;

import app.resourcehub.access.api.A;
import app.resourcehub.access.business.B;
import app.resourcehub.access.business.structs.UserPersonLinkDisabledEvent;
import app.resourcehub.access.repository.R;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.sql.DataSource;

public final class XF {

    private XF() {
        throw new UnsupportedOperationException("Utility XF architecture entry point");
    }

    public static void init() {
        R.init();
        B.init();
        A.init();
    }

    public static void init(DataSource dataSource, Clock clock) {
        init(dataSource, clock, orgId -> Boolean.FALSE, ev -> {
        }, personId -> Boolean.TRUE);
    }

    public static void init(
        DataSource dataSource,
        Clock clock,
        Function<UUID, Boolean> allowStandardUserSpaceBookingResolver
    ) {
        init(dataSource, clock, allowStandardUserSpaceBookingResolver, ev -> {
        }, personId -> Boolean.TRUE);
    }

    public static void init(
        DataSource dataSource,
        Clock clock,
        Function<UUID, Boolean> allowStandardUserSpaceBookingResolver,
        Consumer<UserPersonLinkDisabledEvent> personLinkDisabledListener
    ) {
        init(
            dataSource,
            clock,
            allowStandardUserSpaceBookingResolver,
            personLinkDisabledListener,
            personId -> Boolean.TRUE
        );
    }

    public static void init(
        DataSource dataSource,
        Clock clock,
        Function<UUID, Boolean> allowStandardUserSpaceBookingResolver,
        Consumer<UserPersonLinkDisabledEvent> personLinkDisabledListener,
        Predicate<UUID> personResourceActiveChecker
    ) {
        R.init(dataSource);
        B.init(clock, allowStandardUserSpaceBookingResolver, personLinkDisabledListener, personResourceActiveChecker);
        A.init();
    }

    public static void terminate() {
        A.terminate();
        B.terminate();
        R.terminate();
    }
}
