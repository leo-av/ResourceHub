package app.resourcehub.reservations.repository;

import app.resourcehub.reservations.repository.logic.local.ReservationRepository;
import javax.sql.DataSource;

public final class R {

    private static ReservationRepository reservationRepository;

    private R() {
        throw new UnsupportedOperationException("Static XF repository injector");
    }

    public static void init() {
        reservationRepository = new ReservationRepository();
    }

    public static void init(DataSource dataSource) {
        reservationRepository = new ReservationRepository(dataSource);
    }

    public static ReservationRepository reservations() {
        if (reservationRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return reservationRepository;
    }

    public static void terminate() {
        reservationRepository = null;
    }
}
