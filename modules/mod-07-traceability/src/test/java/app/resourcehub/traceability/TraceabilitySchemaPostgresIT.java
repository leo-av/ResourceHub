package app.resourcehub.traceability;

import app.resourcehub.traceability.repository.R;
import app.resourcehub.traceability.repository.structs.AuditLogQueryFilter;
import app.resourcehub.traceability.repository.structs.StoredAuditLog;
import app.resourcehub.traceability.repository.structs.StoredReservationHistory;
import app.resourcehub.traceability.repository.structs.TraceabilityRepositoryException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-020 [PRU-03 / PRU-SEG-08] — Flyway V10 traceability (13 + 15 cols), Triggers Append-Only y ADR-012")
class TraceabilitySchemaPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final Instant T0 = Instant.parse("2026-11-27T18:35:00Z");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("resourcehub")
        .withUsername("postgres")
        .withPassword("postgres");

    private static DataSource dbaApiDataSource;
    private static MigrateResult migrateResult;

    @BeforeAll
    static void startPostgres() throws SQLException {
        POSTGRES.start();
        provisionRoles();
        migrateResult = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASS)
            .locations("filesystem:" + resolveMigrationsDirectory().toAbsolutePath())
            .load()
            .migrate();

        dbaApiDataSource = new SimpleDriverDataSource(POSTGRES.getJdbcUrl(), DBA_API_USER, DBA_API_PASS);
        app.resourcehub.platform.XF.init(dbaApiDataSource);
        XF.init(dbaApiDataSource);
    }

    @AfterAll
    static void stopPostgres() {
        XF.terminate();
        app.resourcehub.platform.XF.terminate();
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    @DisplayName("PRU-03/PRU-SEG-08: Crea reservation_history (13) y audit_log (15) sin PII, triggers anti-mutacion y tx autonoma")
    void shouldEnforceTraceabilitySchemaAppendOnlyTriggersAndAutonomousPersistence() throws SQLException {
        assertThat(migrateResult.migrationsExecuted).isGreaterThanOrEqualTo(8);

        try (Connection conn = dbaApiDataSource.getConnection()) {
            assertThat(countTableColumns(conn, "traceability", "reservation_history")).isEqualTo(13);
            assertThat(countTableColumns(conn, "traceability", "audit_log")).isEqualTo(15);
            assertThat(countPiiColumns(conn, "traceability", "reservation_history")).isZero();
            assertThat(countPiiColumns(conn, "traceability", "audit_log")).isZero();
        }

        UUID reservationId = UUID.fromString("01936d00-0010-7000-8000-000000000001");
        UUID requesterId = UUID.fromString("01936d00-0020-7000-8000-000000000001");
        UUID holderId = UUID.fromString("01936d00-0020-7000-8000-000000000002");

        // 1. Insercion valida y lectura cronologica en reservation_history
        UUID histId1 = UUID.fromString("01936d00-0030-7000-8000-000000000001");
        UUID histId2 = UUID.fromString("01936d00-0030-7000-8000-000000000002");
        R.reservationHistory().insert(new StoredReservationHistory(
            histId1, reservationId, "CREATED", null, "CONFIRMED", "USER", requesterId, holderId,
            "AMB_01_CLIENT", null, "{\"status\":\"CONFIRMED\"}", "corr-01", T0
        ));
        R.reservationHistory().insert(new StoredReservationHistory(
            histId2, reservationId, "CANCELLED", "CONFIRMED", "CANCELLED", "USER", requesterId, holderId,
            "AMB_01_CLIENT", "{\"status\":\"CONFIRMED\"}", "{\"status\":\"CANCELLED\"}", "corr-02", T0.plusSeconds(30)
        ));

        List<StoredReservationHistory> history = R.reservationHistory().findByReservationId(reservationId);
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().changeType()).isEqualTo("CREATED");
        assertThat(history.get(1).changeType()).isEqualTo("CANCELLED");

        // 2. ADR-012: insertAutonomous sobrevive a rollback en la transaccion de negocio externa
        UUID rejectedAuditId = UUID.fromString("01936d00-0040-7000-8000-000000000001");
        try (Connection businessConn = dbaApiDataSource.getConnection()) {
            businessConn.setAutoCommit(false);
            R.auditLogs().insertAutonomous(new StoredAuditLog(
                rejectedAuditId,
                "corr-rej-01",
                "RESERVATION_MODIFY",
                "RESERVATION",
                reservationId,
                "USER",
                requesterId,
                holderId,
                "US",
                "AMB_01_CLIENT",
                "REJECTED",
                "CONFLICT",
                "RN-RES-003",
                "{\"errorCode\":\"BOOKING_CONFLICT\"}",
                T0.plusSeconds(60)
            ));
            businessConn.rollback();
        }

        AuditLogQueryFilter filter = new AuditLogQueryFilter(
            holderId, "RESERVATION_MODIFY", "REJECTED", "corr-rej-01", T0, T0.plusSeconds(120)
        );
        assertThat(R.auditLogs().countByFilter(filter)).isEqualTo(1L);
        List<StoredAuditLog> auditLogs = R.auditLogs().findByFilter(filter, 10, 0);
        assertThat(auditLogs).hasSize(1);
        assertThat(auditLogs.getFirst().id()).isEqualTo(rejectedAuditId);
        assertThat(auditLogs.getFirst().rejectionCategory()).isEqualTo("CONFLICT");

        // 3. Validacion check constraint chk_aud_rej_cat -> 23514
        assertThatThrownBy(() -> R.auditLogs().insertAutonomous(new StoredAuditLog(
            UUID.fromString("01936d00-0040-7000-8000-000000000099"),
            "corr-bad", "RESERVATION_CREATE", "RESERVATION", reservationId,
            "USER", requesterId, holderId, "US", "AMB_01_CLIENT",
            "REJECTED", null, null, "{}", T0
        )))
            .isInstanceOf(TraceabilityRepositoryException.class)
            .extracting(ex -> ((TraceabilityRepositoryException) ex).getSqlState())
            .isEqualTo("23514");

        // 4. PRU-SEG-08: Trigger trg_prevent_append_only_mutation bloquea UPDATE y DELETE en ambas tablas
        try (Connection conn = dbaApiDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            assertThatThrownBy(() -> stmt.executeUpdate(
                "UPDATE traceability.reservation_history SET new_status = 'EXPIRED' WHERE id = '" + histId1 + "'"
            ))
                .isInstanceOf(SQLException.class)
                .extracting(ex -> ((SQLException) ex).getSQLState())
                .isEqualTo("P0001");

            assertThatThrownBy(() -> stmt.executeUpdate(
                "DELETE FROM traceability.reservation_history WHERE id = '" + histId1 + "'"
            ))
                .isInstanceOf(SQLException.class)
                .extracting(ex -> ((SQLException) ex).getSQLState())
                .isEqualTo("P0001");

            assertThatThrownBy(() -> stmt.executeUpdate(
                "UPDATE traceability.audit_log SET outcome = 'ACCEPTED' WHERE id = '" + rejectedAuditId + "'"
            ))
                .isInstanceOf(SQLException.class)
                .extracting(ex -> ((SQLException) ex).getSQLState())
                .isEqualTo("P0001");

            assertThatThrownBy(() -> stmt.executeUpdate(
                "DELETE FROM traceability.audit_log WHERE id = '" + rejectedAuditId + "'"
            ))
                .isInstanceOf(SQLException.class)
                .extracting(ex -> ((SQLException) ex).getSQLState())
                .isEqualTo("P0001");
        }
    }

    private static int countTableColumns(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int countPiiColumns(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
              AND LOWER(column_name) IN ('email', 'full_name', 'phone', 'dni', 'tax_id', 'password_hash')
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void provisionRoles() throws SQLException {
        try (Connection superConn = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
             Statement stmt = superConn.createStatement()) {
            stmt.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'admin') THEN
                        CREATE ROLE admin WITH LOGIN SUPERUSER PASSWORD 'admin_test_pass';
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'dba_api') THEN
                        CREATE ROLE dba_api WITH LOGIN PASSWORD 'dba_api_test_pass'
                            NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOREPLICATION NOBYPASSRLS;
                    END IF;
                END
                $$;
                """);
            stmt.execute("ALTER DATABASE resourcehub OWNER TO admin");
        }
    }

    private static Path resolveMigrationsDirectory() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("db/migration");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate db/migration");
    }

    private record SimpleDriverDataSource(String jdbcUrl, String username, String password) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(jdbcUrl, username, password);
        }

        @Override
        public Connection getConnection(String user, String pass) throws SQLException {
            return DriverManager.getConnection(jdbcUrl, user, pass);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("Not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
