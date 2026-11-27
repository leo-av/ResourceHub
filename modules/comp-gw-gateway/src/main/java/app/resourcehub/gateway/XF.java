package app.resourcehub.gateway;

import app.resourcehub.gateway.api.A;
import app.resourcehub.gateway.business.B;
import app.resourcehub.gateway.repository.R;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

public final class XF {

    private XF() {
        throw new UnsupportedOperationException("Utility XF architecture entry point");
    }

    public static void init() {
        R.init();
        B.init();
        A.init();
    }

    public static void init(Clock clock) {
        R.init();
        B.init(clock);
        A.init(clock);
    }

    public static void init(Supplier<List<ErrorCatalogEntry>> catalogSupplier, Clock clock) {
        R.init(catalogSupplier);
        B.init(clock);
        A.init(clock);
    }

    public static void terminate() {
        A.terminate();
        B.terminate();
        R.terminate();
    }
}
