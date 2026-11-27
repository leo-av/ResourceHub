package app.resourcehub.policies;

import app.resourcehub.policies.api.A;
import app.resourcehub.policies.business.B;
import app.resourcehub.policies.repository.R;

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
