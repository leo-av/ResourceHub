package app.resourcehub.access.api;

import app.resourcehub.access.api.logic.services.AppAuthService;
import app.resourcehub.access.api.logic.services.ConsoleAuthService;
import app.resourcehub.access.api.logic.services.ConsoleUserService;
import app.resourcehub.access.api.logic.services.WorkspaceAuthService;

public final class A {

    private static AppAuthService appAuthService;
    private static ConsoleAuthService consoleAuthService;
    private static ConsoleUserService consoleUserService;
    private static WorkspaceAuthService workspaceAuthService;

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        appAuthService = new AppAuthService();
        consoleAuthService = new ConsoleAuthService();
        consoleUserService = new ConsoleUserService();
        workspaceAuthService = new WorkspaceAuthService();
    }

    public static AppAuthService appAuth() {
        if (appAuthService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return appAuthService;
    }

    public static ConsoleAuthService consoleAuth() {
        if (consoleAuthService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return consoleAuthService;
    }

    public static ConsoleUserService consoleUsers() {
        if (consoleUserService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return consoleUserService;
    }

    public static WorkspaceAuthService workspaceAuth() {
        if (workspaceAuthService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return workspaceAuthService;
    }

    public static void terminate() {
        appAuthService = null;
        consoleAuthService = null;
        consoleUserService = null;
        workspaceAuthService = null;
    }
}
