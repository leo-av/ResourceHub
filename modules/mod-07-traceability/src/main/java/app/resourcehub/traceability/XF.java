package app.resourcehub.traceability;

import app.resourcehub.traceability.api.A;
import app.resourcehub.traceability.business.B;
import app.resourcehub.traceability.repository.R;

public final class XF {

    private XF() {
        throw new UnsupportedOperationException("Utility XF architecture entry point");
    }

    public static void init() {
        R.init();
        B.init();
        A.init();
    }

    public static void terminate() {
        A.terminate();
        B.terminate();
        R.terminate();
    }
}
