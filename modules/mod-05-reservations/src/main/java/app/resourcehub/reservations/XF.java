package app.resourcehub.reservations;

import app.resourcehub.reservations.api.A;
import app.resourcehub.reservations.business.B;
import app.resourcehub.reservations.repository.R;
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

    public static void init(DataSource dataSource) {
        R.init(dataSource);
        B.init();
        A.init();
    }

    public static void terminate() {
        A.terminate();
        B.terminate();
        R.terminate();
    }
}
