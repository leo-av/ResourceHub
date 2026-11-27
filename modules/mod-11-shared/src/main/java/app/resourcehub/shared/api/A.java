package app.resourcehub.shared.api;

public final class A {

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        // MOD-11 núcleo compartido no expone endpoints HTTP propios.
    }

    public static void terminate() {
        // Sin recursos de interacción que liberar en MOD-11.
    }
}
