package app.resourcehub.availability;

import app.resourcehub.availability.api.A;
import app.resourcehub.availability.business.B;
import app.resourcehub.availability.repository.R;
import java.time.Clock;
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
        R.init(dataSource);
        B.init(clock);
        A.init();
    }

    public static void terminate() {
        A.terminate();
        B.terminate();
        R.terminate();
    }
}
