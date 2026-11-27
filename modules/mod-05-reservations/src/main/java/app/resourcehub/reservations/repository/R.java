package app.resourcehub.reservations.repository;

public final class R {

    private R() {
        throw new UnsupportedOperationException("Static XF repository injector");
    }

    public static void init() {
        // Sin componentes repository activos en el esqueleto inicial de mod-05-reservations.
    }

    public static void terminate() {
        // Sin recursos repository que liberar en el esqueleto inicial de mod-05-reservations.
    }
}
