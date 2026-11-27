package app.resourcehub.traceability.business;

import app.resourcehub.traceability.business.logic.instance.AuditLogBusiness;
import app.resourcehub.traceability.business.logic.instance.ReservationHistoryBusiness;
import app.resourcehub.traceability.business.structs.AuditActorSummary;
import app.resourcehub.traceability.repository.R;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class B {

    private static ReservationHistoryBusiness reservationHistoryBusiness;
    private static AuditLogBusiness auditLogBusiness;

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        init(Clock.systemUTC(), ids -> Map.of());
    }

    public static void init(Clock clock) {
        init(clock, ids -> Map.of());
    }

    public static void init(
        Clock clock,
        Function<Set<UUID>, Map<UUID, AuditActorSummary>> actorSummaryResolver
    ) {
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        reservationHistoryBusiness = new ReservationHistoryBusiness(R.reservationHistory(), effectiveClock);
        auditLogBusiness = new AuditLogBusiness(R.auditLogs(), effectiveClock, actorSummaryResolver);
    }

    public static boolean isInitialized() {
        return reservationHistoryBusiness != null && auditLogBusiness != null;
    }

    public static ReservationHistoryBusiness history() {
        if (reservationHistoryBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return reservationHistoryBusiness;
    }

    public static AuditLogBusiness audit() {
        if (auditLogBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return auditLogBusiness;
    }

    public static void terminate() {
        reservationHistoryBusiness = null;
        auditLogBusiness = null;
    }
}
