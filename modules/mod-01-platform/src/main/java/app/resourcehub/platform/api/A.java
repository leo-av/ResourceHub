package app.resourcehub.platform.api;

import app.resourcehub.platform.api.logic.services.ErrorCatalogService;

public final class A {

    private static ErrorCatalogService errorCatalogService;

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        errorCatalogService = new ErrorCatalogService();
    }

    public static ErrorCatalogService errorCatalog() {
        if (errorCatalogService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return errorCatalogService;
    }

    public static void terminate() {
        errorCatalogService = null;
    }
}
