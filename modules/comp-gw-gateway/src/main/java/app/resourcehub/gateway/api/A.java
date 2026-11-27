package app.resourcehub.gateway.api;

public final class A {

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        // Sin componentes api activos en el esqueleto inicial de comp-gw-gateway.
    }

    public static void terminate() {
        // Sin recursos api que liberar en el esqueleto inicial de comp-gw-gateway.
    }
}
