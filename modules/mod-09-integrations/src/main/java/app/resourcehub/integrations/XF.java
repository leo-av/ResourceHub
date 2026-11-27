package app.resourcehub.integrations;

import app.resourcehub.integrations.api.A;
import app.resourcehub.integrations.business.B;
import app.resourcehub.integrations.repository.R;

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
