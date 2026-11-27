package app.resourcehub.shared;

import app.resourcehub.shared.api.A;
import app.resourcehub.shared.business.B;
import app.resourcehub.shared.repository.R;

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
