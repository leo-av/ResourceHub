package app.resourcehub.reservations;

import app.resourcehub.reservations.repository.R;
import app.resourcehub.reservations.repository.logic.local.ReservationRepository;
import app.resourcehub.reservations.repository.structs.ReservationsRepositoryException;
import app.resourcehub.reservations.repository.structs.StoredReservation;
import app.resourcehub.reservations.repository.structs.StoredReservationAggregate;
import app.resourcehub.reservations.repository.structs.StoredReservationLine;
import java.io.PrintWriter;
import java.lang.reflect.Method;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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

@DisplayName("TT-011 [PRU-03] — Flyway V6 reservations (20 + 12 cols), GiST [) y atomicidad ACID")
class ReservationsRepositoryPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T16:15:00Z");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("resourcehub")
        .withUsername("postgres")
        .withPassword("postgres");

    private static DataSource dbaApiDataSource;
    private static MigrateResult migrateResult;

    @BeforeAll
    static void startPostgresAndApplyMigrations() throws SQLException {
        POSTGRES.start();
        provisionRoles();
        migrateResult = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASS)
            .locations("filesystem:" + resolveMigrationsDirectory().toAbsolutePath())
            .load()
            .migrate();

        dbaApiDataSource = new SimpleDriverDataSource(POSTGRES.getJdbcUrl(), DBA_API_USER, DBA_API_PASS);
        XF.init(dbaApiDataSource);
    }

    @AfterAll
    static void stopPostgres() {
        XF.terminate();
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    @DisplayName("PRU-03: Crea reservations.reservation (20 cols) y reservation_line (12 cols) y aplica constraints")
    void shouldCreateReservationsTablesAndEnforceSqlConstraints() throws SQLException {
        assertThat(migrateResult.migrationsExecuted).isGreaterThanOrEqualTo(5);

        try (Connection conn = dbaApiDataSource.getConnection()) {
            assertThat(countTableColumns(conn, "reservations", "reservation")).isEqualTo(20);
            assertThat(countTableColumns(conn, "reservations", "reservation_line")).isEqualTo(12);

            try (Statement stmt = conn.createStatement()) {
                // chk_res_client_scope_holder: AMB_01_CLIENT exige holder_id = requester_id
                assertThatThrownBy(() -> stmt.executeUpdate("""
                    INSERT INTO reservations.reservation (
                        id, requester_id, holder_id, origin_scope, status, created_at, updated_at
                    ) VALUES (
                        '01936b00-0100-7000-8000-000000000001',
                        '01936b00-0100-7000-8000-000000000010',
                        '01936b00-0100-7000-8000-000000000020',
                        'AMB_01_CLIENT',
                        'CONFIRMED',
                        now(),
                        now()
                    )
                    """))
                    .isInstanceOf(SQLException.class)
                    .extracting(ex -> ((SQLException) ex).getSQLState())
                    .isEqualTo("23514");

                // chk_res_pending_expires: PENDING exige expires_at NOT NULL
                assertThatThrownBy(() -> stmt.executeUpdate("""
                    INSERT INTO reservations.reservation (
                        id, requester_id, holder_id, origin_scope, status, pending_reason,
                        expires_at, created_at, updated_at
                    ) VALUES (
                        '01936b00-0100-7000-8000-000000000002',
                        '01936b00-0100-7000-8000-000000000010',
                        '01936b00-0100-7000-8000-000000000010',
                        'AMB_01_CLIENT',
                        'PENDING',
                        'APPROVAL',
                        NULL,
                        now(),
                        now()
                    )
                    """))
                    .isInstanceOf(SQLException.class)
                    .extracting(ex -> ((SQLException) ex).getSQLState())
                    .isEqualTo("23514");
            }
        }
    }

    @Test
    @DisplayName("PRU-03: Rollback atomico cabecera+linea, rangos [) GiST adyacentes, versionado y 0 deletes")
    void shouldPersistAggregateAtomicallyCheckGistAdjacencyAndEnforceOptimisticLock() {
        UUID requesterId = UUID.fromString("01936b00-0200-7000-8000-000000000001");
        UUID holderId = UUID.fromString("01936b00-0200-7000-8000-000000000002");
        UUID resourceA = UUID.fromString("01936b00-0200-7000-8000-000000000011");
        UUID resourceB = UUID.fromString("01936b00-0200-7000-8000-000000000012");

        // 1. Rollback atomico si linea #2 viola chk_line_units (0)
        UUID rolledBackResId = UUID.fromString("01936b00-0200-7000-8000-000000000100");
        StoredReservation badHeader = sampleReservation(rolledBackResId, requesterId, holderId, "CONFIRMED", null);
        StoredReservationLine validLine1 = sampleLine(
            UUID.fromString("01936b00-0200-7000-8000-000000000101"),
            rolledBackResId,
            (short) 1,
            resourceA,
            Instant.parse("2026-12-01T10:00:00Z"),
            Instant.parse("2026-12-01T11:00:00Z"),
            1
        );
        StoredReservationLine invalidLine2 = sampleLine(
            UUID.fromString("01936b00-0200-7000-8000-000000000102"),
            rolledBackResId,
            (short) 2,
            resourceB,
            Instant.parse("2026-12-01T10:00:00Z"),
            Instant.parse("2026-12-01T11:00:00Z"),
            0
        );

        assertThatThrownBy(() -> R.reservations().insertAggregate(badHeader, List.of(validLine1, invalidLine2)))
            .isInstanceOf(ReservationsRepositoryException.class)
            .extracting(ex -> ((ReservationsRepositoryException) ex).getSqlState())
            .isEqualTo("23514");
        assertThat(R.reservations().findById(rolledBackResId)).isEmpty();
        assertThat(R.reservations().countByHolderId(holderId)).isZero();

        // 2. Insercion atomica valida con 2 lineas
        UUID resId = UUID.fromString("01936b00-0200-7000-8000-000000000200");
        StoredReservation header = sampleReservation(resId, requesterId, holderId, "CONFIRMED", null);
        StoredReservationLine line1 = sampleLine(
            UUID.fromString("01936b00-0200-7000-8000-000000000201"),
            resId,
            (short) 1,
            resourceA,
            Instant.parse("2026-12-01T10:00:00Z"),
            Instant.parse("2026-12-01T11:00:00Z"),
            1
        );
        StoredReservationLine line2 = sampleLine(
            UUID.fromString("01936b00-0200-7000-8000-000000000202"),
            resId,
            (short) 2,
            resourceB,
            Instant.parse("2026-12-01T10:00:00Z"),
            Instant.parse("2026-12-01T11:00:00Z"),
            2
        );

        StoredReservationAggregate saved = R.reservations().insertAggregate(header, List.of(line1, line2));
        assertThat(saved.lines()).hasSize(2);
        assertThat(R.reservations().countByHolderId(holderId)).isEqualTo(1L);
        assertThat(R.reservations().findByHolderId(holderId, 10, 0)).hasSize(1);
        assertThat(R.reservations().findById(resId))
            .isPresent()
            .get()
            .satisfies(agg -> assertThat(agg.lines()).hasSize(2));

        // 3. Adyacencia semiabierta [10:00, 11:00) vs [11:00, 12:00) -> 0 solapes; [10:30, 11:30) -> 1 solape
        assertThat(R.reservations().findActiveLinesOverlapping(
            resourceA,
            Instant.parse("2026-12-01T11:00:00Z"),
            Instant.parse("2026-12-01T12:00:00Z")
        )).isEmpty();
        assertThat(R.reservations().findActiveLinesOverlapping(
            resourceA,
            Instant.parse("2026-12-01T10:30:00Z"),
            Instant.parse("2026-12-01T11:30:00Z")
        )).hasSize(1);

        // 4. Bloqueo optimista + liberacion logica de lineas al cancelar (0 DELETE)
        assertThat(R.reservations().updateStatus(
            resId,
            "CANCELLED",
            null,
            null,
            "HOLDER",
            null,
            99,
            FIXED_NOW.plusSeconds(60)
        )).isEmpty();

        Optional<StoredReservation> cancelled = R.reservations().updateStatus(
            resId,
            "CANCELLED",
            null,
            null,
            "HOLDER",
            null,
            1,
            FIXED_NOW.plusSeconds(60)
        );
        assertThat(cancelled).isPresent();
        assertThat(cancelled.get().status()).isEqualTo("CANCELLED");
        assertThat(cancelled.get().version()).isEqualTo(2);
        assertThat(R.reservations().findActiveLinesOverlapping(
            resourceA,
            Instant.parse("2026-12-01T10:00:00Z"),
            Instant.parse("2026-12-01T11:00:00Z")
        )).isEmpty();

        // 5. TT-014 (V7 PRU-03): excl_res_line_active_overlap aborta solape directo con SQLState 23P01
        UUID resGistA = UUID.fromString("01936b00-0300-7000-8000-000000000001");
        UUID resGistB = UUID.fromString("01936b00-0300-7000-8000-000000000002");
        R.reservations().insertAggregate(
            sampleReservation(resGistA, requesterId, holderId, "CONFIRMED", null),
            List.of(sampleLine(
                UUID.fromString("01936b00-0300-7000-8000-000000000011"),
                resGistA, (short) 1, resourceA,
                Instant.parse("2026-12-02T10:00:00Z"),
                Instant.parse("2026-12-02T11:00:00Z"),
                1
            ))
        );
        assertThatThrownBy(() -> R.reservations().insertAggregate(
            sampleReservation(resGistB, requesterId, holderId, "CONFIRMED", null),
            List.of(sampleLine(
                UUID.fromString("01936b00-0300-7000-8000-000000000012"),
                resGistB, (short) 1, resourceA,
                Instant.parse("2026-12-02T10:30:00Z"),
                Instant.parse("2026-12-02T11:30:00Z"),
                1
            ))
        ))
            .isInstanceOf(ReservationsRepositoryException.class)
            .extracting(ex -> ((ReservationsRepositoryException) ex).getSqlState())
            .isEqualTo("23P01");

        // Adyacente [11:00, 12:00) si permitida fisicamente por GiST [)
        R.reservations().insertAggregate(
            sampleReservation(resGistB, requesterId, holderId, "CONFIRMED", null),
            List.of(sampleLine(
                UUID.fromString("01936b00-0300-7000-8000-000000000013"),
                resGistB, (short) 1, resourceA,
                Instant.parse("2026-12-02T11:00:00Z"),
                Instant.parse("2026-12-02T12:00:00Z"),
                1
            ))
        );

        List<String> deleteMethods = Arrays.stream(ReservationRepository.class.getDeclaredMethods())
            .map(Method::getName)
            .filter(name -> name.toLowerCase().startsWith("delete"))
            .toList();
        assertThat(deleteMethods).isEmpty();
    }

    private static StoredReservation sampleReservation(
        UUID id,
        UUID requesterId,
        UUID holderId,
        String status,
        Instant expiresAt
    ) {
        return new StoredReservation(
            id,
            requesterId,
            holderId,
            "AMB_03_MANAGEMENT",
            status,
            null,
            expiresAt,
            null,
            null,
            2500L,
            "EUR",
            "SIN_CARGO",
            null,
            null,
            null,
            null,
            null,
            1,
            FIXED_NOW,
            FIXED_NOW
        );
    }

    private static StoredReservationLine sampleLine(
        UUID id,
        UUID reservationId,
        short lineNumber,
        UUID resourceId,
        Instant startAt,
        Instant endAt,
        int units
    ) {
        return new StoredReservationLine(
            id,
            reservationId,
            lineNumber,
            resourceId,
            resourceId,
            startAt,
            endAt,
            units,
            units == 1,
            "ACTIVE",
            1250L,
            "EUR"
        );
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
