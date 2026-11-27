package app.resourcehub.access;

import app.resourcehub.access.api.A;
import app.resourcehub.access.business.B;
import app.resourcehub.access.repository.R;

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
