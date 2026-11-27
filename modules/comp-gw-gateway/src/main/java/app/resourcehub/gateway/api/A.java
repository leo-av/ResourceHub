package app.resourcehub.gateway.api;

import app.resourcehub.gateway.api.logic.services.EdgeGatewayService;
import java.time.Clock;

public final class A {

    private static EdgeGatewayService edgeGatewayService;

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        init(Clock.systemUTC());
    }

    public static void init(Clock clock) {
        edgeGatewayService = new EdgeGatewayService(clock);
    }

    public static EdgeGatewayService edgeGateway() {
        if (edgeGatewayService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return edgeGatewayService;
    }

    public static void terminate() {
        edgeGatewayService = null;
    }
}
