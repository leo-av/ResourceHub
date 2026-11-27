package app.resourcehub.traceability;

import app.resourcehub.traceability.api.A;
import app.resourcehub.traceability.business.B;
import app.resourcehub.traceability.business.structs.AuditActorSummary;
import app.resourcehub.traceability.repository.R;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;

public final class XF {

    private XF() {
        throw new UnsupportedOperationException("Utility XF architecture entry point");
    }

    public static void init() {
        R.init();
        B.init();
        A.init();
    }

    public static void init(DataSource dataSource) {
        R.init(dataSource);
        B.init();
        A.init();
    }

    public static void init(
        DataSource dataSource,
        Clock clock,
        Function<Set<UUID>, Map<UUID, AuditActorSummary>> actorSummaryResolver
    ) {
        R.init(dataSource);
        B.init(clock, actorSummaryResolver);
        A.init();
    }

    public static void terminate() {
        A.terminate();
        B.terminate();
        R.terminate();
    }
}
