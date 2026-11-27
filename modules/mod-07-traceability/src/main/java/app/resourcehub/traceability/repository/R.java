package app.resourcehub.traceability.repository;

import app.resourcehub.traceability.repository.logic.local.AuditLogRepository;
import app.resourcehub.traceability.repository.logic.local.ReservationHistoryRepository;
import javax.sql.DataSource;

public final class R {

    private static ReservationHistoryRepository reservationHistoryRepository;
    private static AuditLogRepository auditLogRepository;

    private R() {
        throw new UnsupportedOperationException("Static XF repository injector");
    }

    public static void init() {
        reservationHistoryRepository = new ReservationHistoryRepository();
        auditLogRepository = new AuditLogRepository();
    }

    public static void init(DataSource dataSource) {
        reservationHistoryRepository = new ReservationHistoryRepository(dataSource);
        auditLogRepository = new AuditLogRepository(dataSource);
    }

    public static ReservationHistoryRepository reservationHistory() {
        if (reservationHistoryRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return reservationHistoryRepository;
    }

    public static AuditLogRepository auditLogs() {
        if (auditLogRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return auditLogRepository;
    }

    public static void terminate() {
        reservationHistoryRepository = null;
        auditLogRepository = null;
    }
}
