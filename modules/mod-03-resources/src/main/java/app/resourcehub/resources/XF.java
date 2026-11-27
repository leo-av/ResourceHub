package app.resourcehub.resources;

import app.resourcehub.resources.api.A;
import app.resourcehub.resources.business.B;
import app.resourcehub.resources.repository.R;
import java.time.Clock;
import java.util.UUID;
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
        init(dataSource, clock, userId -> true);
    }

    public static void init(DataSource dataSource, Clock clock, Predicate<UUID> linkedUserActiveChecker) {
        R.init(dataSource);
        B.init(clock, linkedUserActiveChecker);
        A.init();
    }

    public static void terminate() {
        A.terminate();
        B.terminate();
        R.terminate();
    }
}
