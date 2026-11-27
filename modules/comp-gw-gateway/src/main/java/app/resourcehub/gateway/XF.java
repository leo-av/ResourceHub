package app.resourcehub.gateway;

import app.resourcehub.gateway.api.A;
import app.resourcehub.gateway.business.B;
import app.resourcehub.gateway.repository.R;

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
