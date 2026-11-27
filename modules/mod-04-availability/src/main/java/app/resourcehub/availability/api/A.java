package app.resourcehub.availability.api;

import app.resourcehub.availability.api.logic.services.ConsoleAvailabilityService;

public final class A {

    private static ConsoleAvailabilityService consoleAvailabilityService;

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        consoleAvailabilityService = new ConsoleAvailabilityService();
    }

    public static ConsoleAvailabilityService consoleAvailability() {
        if (consoleAvailabilityService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return consoleAvailabilityService;
    }

    public static void terminate() {
        consoleAvailabilityService = null;
    }
}
