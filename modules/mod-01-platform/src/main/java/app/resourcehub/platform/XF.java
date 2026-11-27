package app.resourcehub.platform;

import app.resourcehub.platform.api.A;
import app.resourcehub.platform.business.B;
import app.resourcehub.platform.repository.R;

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
