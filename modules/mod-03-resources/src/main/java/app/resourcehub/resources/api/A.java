package app.resourcehub.resources.api;

import app.resourcehub.resources.api.logic.services.AppCatalogService;
import app.resourcehub.resources.api.logic.services.ConsoleCatalogService;

public final class A {

    private static ConsoleCatalogService consoleCatalogService;
    private static AppCatalogService appCatalogService;

    private A() {
        throw new UnsupportedOperationException("Static XF api injector");
    }

    public static void init() {
        consoleCatalogService = new ConsoleCatalogService();
        appCatalogService = new AppCatalogService();
    }

    public static ConsoleCatalogService consoleCatalog() {
        if (consoleCatalogService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return consoleCatalogService;
    }

    public static AppCatalogService appCatalog() {
        if (appCatalogService == null) {
            throw new IllegalStateException("A.init() has not been called");
        }
        return appCatalogService;
    }

    public static void terminate() {
        consoleCatalogService = null;
        appCatalogService = null;
    }
}
