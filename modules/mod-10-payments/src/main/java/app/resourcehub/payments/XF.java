package app.resourcehub.payments;

import app.resourcehub.payments.api.A;
import app.resourcehub.payments.business.B;
import app.resourcehub.payments.repository.R;

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
