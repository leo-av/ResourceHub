package app.resourcehub.shared.business;

public final class B {

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        // MOD-11 núcleo compartido expone primitivas y tipos de transferencia puros.
    }

    public static void terminate() {
        // Sin estado de negocio que liberar en MOD-11.
    }
}
