package app.resourcehub.events;

import app.resourcehub.events.api.A;
import app.resourcehub.events.business.B;
import app.resourcehub.events.repository.R;

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
