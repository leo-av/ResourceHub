package app.resourcehub.policies.api;

import app.resourcehub.policies.api.logic.services.ConsolePolicyService;

public final class A {

    private static ConsolePolicyService consolePolicyService;

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        consolePolicyService = new ConsolePolicyService();
    }

    public static ConsolePolicyService consolePolicy() {
        if (consolePolicyService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return consolePolicyService;
    }

    public static void terminate() {
        consolePolicyService = null;
    }
}
