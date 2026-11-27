package app.resourcehub.resources;

import app.resourcehub.resources.api.A;
import app.resourcehub.resources.business.B;
import app.resourcehub.resources.repository.R;

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
