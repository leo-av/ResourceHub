package app.resourcehub.traceability.api;

import app.resourcehub.traceability.api.logic.services.AppHistoryService;
import app.resourcehub.traceability.api.logic.services.ConsoleAuditService;

public final class A {

    private static AppHistoryService appHistoryService;
    private static ConsoleAuditService consoleAuditService;

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        appHistoryService = new AppHistoryService();
        consoleAuditService = new ConsoleAuditService();
    }

    public static AppHistoryService appHistory() {
        if (appHistoryService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return appHistoryService;
    }

    public static ConsoleAuditService consoleAudit() {
        if (consoleAuditService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return consoleAuditService;
    }

    public static void terminate() {
        appHistoryService = null;
        consoleAuditService = null;
    }
}
