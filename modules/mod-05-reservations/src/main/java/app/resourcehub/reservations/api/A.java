package app.resourcehub.reservations.api;

import app.resourcehub.reservations.api.logic.services.AppReservationService;

public final class A {

    private static AppReservationService appReservationService;

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        appReservationService = new AppReservationService();
    }

    public static AppReservationService appReservations() {
        if (appReservationService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return appReservationService;
    }

    public static void terminate() {
        appReservationService = null;
    }
}
