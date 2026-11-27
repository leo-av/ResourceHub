package app.resourcehub.traceability;

import app.resourcehub.traceability.repository.R;
import app.resourcehub.traceability.repository.logic.local.AuditLogRepository;
import app.resourcehub.traceability.repository.logic.local.ReservationHistoryRepository;
import app.resourcehub.traceability.repository.structs.AuditLogQueryFilter;
import app.resourcehub.traceability.repository.structs.StoredAuditLog;
import app.resourcehub.traceability.repository.structs.StoredReservationHistory;
import app.resourcehub.traceability.repository.structs.TraceabilityRepositoryException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-020 [PRU-01] — Contratos append-only e inmutabilidad en repositorios MOD-07")
class TraceabilityRepositoryContractTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T18:35:00Z");

    @Test
    @DisplayName("Garantiza cero metodos update/delete e inicializacion segura de R/XF en MOD-07")
    void shouldExposeAppendOnlyRepositoriesWithoutUpdateOrDeleteMethods() {
        List<String> forbiddenHistory = Arrays.stream(ReservationHistoryRepository.class.getDeclaredMethods())
            .map(Method::getName)
            .filter(name -> name.toLowerCase().startsWith("update") || name.toLowerCase().startsWith("delete"))
            .toList();
        assertThat(forbiddenHistory).isEmpty();

        List<String> forbiddenAudit = Arrays.stream(AuditLogRepository.class.getDeclaredMethods())
            .map(Method::getName)
            .filter(name -> name.toLowerCase().startsWith("update") || name.toLowerCase().startsWith("delete"))
            .toList();
        assertThat(forbiddenAudit).isEmpty();

        assertThatThrownBy(() -> new ReservationHistoryRepository((DataSource) null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditLogRepository((DataSource) null))
            .isInstanceOf(IllegalArgumentException.class);

        XF.terminate();
        assertThatThrownBy(R::reservationHistory).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(R::auditLogs).isInstanceOf(IllegalStateException.class);

        XF.init();
        assertThat(R.reservationHistory().isConfigured()).isFalse();
        assertThat(R.auditLogs().isConfigured()).isFalse();

        UUID id = UUID.fromString("01936d00-0001-7000-8000-000000000001");
        StoredReservationHistory historyEntry = new StoredReservationHistory(
            id, id, "CREATED", null, "CONFIRMED", "USER", id, id,
            "AMB_01_CLIENT", null, "{}", "req-1", FIXED_NOW
        );
        StoredAuditLog auditEntry = new StoredAuditLog(
            id, "req-1", "RESERVATION_CREATE", "RESERVATION", id, "USER",
            id, id, "US", "AMB_01_CLIENT", "ACCEPTED", null, null, "{}", FIXED_NOW
        );

        assertThatThrownBy(() -> R.reservationHistory().insert(historyEntry))
            .isInstanceOf(TraceabilityRepositoryException.class);
        assertThatThrownBy(() -> R.auditLogs().insertAutonomous(auditEntry))
            .isInstanceOf(TraceabilityRepositoryException.class);
        assertThatThrownBy(() -> R.auditLogs().countByFilter(
            new AuditLogQueryFilter(id, "RESERVATION_CREATE", "ACCEPTED", "req-1", FIXED_NOW, FIXED_NOW)
        )).isInstanceOf(TraceabilityRepositoryException.class);

        XF.terminate();
    }
}
