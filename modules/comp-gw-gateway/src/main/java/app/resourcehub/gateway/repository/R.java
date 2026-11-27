package app.resourcehub.gateway.repository;

import app.resourcehub.gateway.repository.logic.local.ErrorCatalogCacheRepository;
import app.resourcehub.gateway.repository.structs.CachedErrorDefinition;
import java.util.List;
import java.util.function.Supplier;

public final class R {

    private static ErrorCatalogCacheRepository errorCatalogCacheRepository;

    private R() {
        throw new UnsupportedOperationException("Static XF repository injector");
    }

    public static void init() {
        errorCatalogCacheRepository = new ErrorCatalogCacheRepository();
    }

    public static void init(Supplier<List<CachedErrorDefinition>> catalogSupplier) {
        errorCatalogCacheRepository = new ErrorCatalogCacheRepository(catalogSupplier);
        errorCatalogCacheRepository.refresh();
    }

    public static ErrorCatalogCacheRepository errorCatalogCache() {
        if (errorCatalogCacheRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return errorCatalogCacheRepository;
    }

    public static void terminate() {
        errorCatalogCacheRepository = null;
    }
}
