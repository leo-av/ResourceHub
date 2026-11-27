package app.resourcehub.reservations.api;

import app.resourcehub.reservations.api.logic.services.AppReservationService;
import app.resourcehub.reservations.api.logic.services.ConsoleReservationService;

public final class A {

    private static AppReservationService appReservationService;
    private static ConsoleReservationService consoleReservationService;

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        appReservationService = new AppReservationService();
        consoleReservationService = new ConsoleReservationService();
    }

    public static AppReservationService appReservations() {
        if (appReservationService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return appReservationService;
    }

    public static ConsoleReservationService consoleReservations() {
        if (consoleReservationService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return consoleReservationService;
    }

    public static void terminate() {
        appReservationService = null;
        consoleReservationService = null;
    }
}
