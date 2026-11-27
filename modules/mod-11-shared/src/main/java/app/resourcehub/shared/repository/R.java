package app.resourcehub.shared.repository;

public final class R {

    private R() {
        throw new UnsupportedOperationException("Static XF repository injector");
    }

    public static void init() {
        // MOD-11 núcleo compartido no declara repositorios físicos.
    }

    public static void terminate() {
        // Sin recursos de acceso que liberar en MOD-11.
    }
}
