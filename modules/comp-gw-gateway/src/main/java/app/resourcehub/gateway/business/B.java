package app.resourcehub.gateway.business;

public final class B {

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        // Sin componentes business activos en el esqueleto inicial de comp-gw-gateway.
    }

    public static void terminate() {
        // Sin recursos business que liberar en el esqueleto inicial de comp-gw-gateway.
    }
}
