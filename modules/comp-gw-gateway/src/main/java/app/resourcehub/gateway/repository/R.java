package app.resourcehub.gateway.repository;

public final class R {

    private R() {
        throw new UnsupportedOperationException("Static XF repository injector");
    }

    public static void init() {
        // Sin componentes repository activos en el esqueleto inicial de comp-gw-gateway.
    }

    public static void terminate() {
        // Sin recursos repository que liberar en el esqueleto inicial de comp-gw-gateway.
    }
}
