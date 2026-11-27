package app.resourcehub.gateway;

import app.resourcehub.gateway.api.A;
import app.resourcehub.gateway.business.B;
import app.resourcehub.gateway.business.structs.GatewaySecurityAudit;
import app.resourcehub.gateway.repository.R;
import app.resourcehub.gateway.repository.structs.CachedErrorDefinition;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import app.resourcehub.shared.business.structs.ActorContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
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
        init(catalogSupplier, clock, null, null, null, null);
    }

    public static void init(
        Supplier<List<ErrorCatalogEntry>> catalogSupplier,
        Clock clock,
        Function<String, ActorContext> tokenValidator,
        Predicate<UUID> personResourceActiveChecker,
        Predicate<String> orgActiveByHostChecker,
        Consumer<GatewaySecurityAudit> securityAuditRecorder
    ) {
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
        B.init(clock, tokenValidator, personResourceActiveChecker, orgActiveByHostChecker, securityAuditRecorder);
        A.init(clock);
    }

    public static void terminate() {
        A.terminate();
        B.terminate();
        R.terminate();
    }
}
