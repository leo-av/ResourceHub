package app.resourcehub.reservations.api;

import app.resourcehub.reservations.api.logic.services.AppReservationService;
import app.resourcehub.reservations.api.logic.services.ConsoleReservationService;
import app.resourcehub.reservations.api.logic.services.WorkspaceAgendaService;

public final class A {

    private static AppReservationService appReservationService;
    private static ConsoleReservationService consoleReservationService;
    private static WorkspaceAgendaService workspaceAgendaService;

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        appReservationService = new AppReservationService();
        consoleReservationService = new ConsoleReservationService();
        workspaceAgendaService = new WorkspaceAgendaService();
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

    public static WorkspaceAgendaService workspaceAgenda() {
        if (workspaceAgendaService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return workspaceAgendaService;
    }

    public static void terminate() {
        appReservationService = null;
        consoleReservationService = null;
        workspaceAgendaService = null;
    }
}
