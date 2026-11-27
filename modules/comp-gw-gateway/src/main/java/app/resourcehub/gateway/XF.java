package app.resourcehub.gateway;

import app.resourcehub.gateway.api.A;
import app.resourcehub.gateway.business.B;
import app.resourcehub.gateway.repository.R;
import app.resourcehub.gateway.repository.structs.CachedErrorDefinition;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

public final class XF {

    private XF() {
        throw new UnsupportedOperationException("Utility XF architecture entry point");
    }

    public static void init() {
        init(() -> app.resourcehub.platform.business.B.errorCatalog().loadErrorCatalog(), Clock.systemUTC());
    }

    public static void init(Clock clock) {
        init(() -> app.resourcehub.platform.business.B.errorCatalog().loadErrorCatalog(), clock);
    }

    public static void init(Supplier<List<ErrorCatalogEntry>> catalogSupplier, Clock clock) {
        Supplier<List<CachedErrorDefinition>> mappedSupplier = () -> catalogSupplier.get().stream()
            .filter(ErrorCatalogEntry::active)
            .map(entry -> new CachedErrorDefinition(
                entry.code().name(),
                entry.numericValue(),
                entry.httpStatus(),
                entry.defaultMessage()
            ))
            .toList();
        R.init(mappedSupplier);
        B.init(clock);
        A.init(clock);
    }

    public static void terminate() {
        A.terminate();
        B.terminate();
        R.terminate();
    }
}
