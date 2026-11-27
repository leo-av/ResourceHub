package app.resourcehub.availability;

import app.resourcehub.availability.api.A;
import app.resourcehub.availability.business.B;
import app.resourcehub.availability.business.structs.AvailabilityBusinessException;
import app.resourcehub.availability.business.structs.BlockOrigin;
import app.resourcehub.availability.business.structs.CalendarException;
import app.resourcehub.availability.business.structs.CreateCalendarExceptionCommand;
import app.resourcehub.availability.business.structs.CreateTemporalBlockCommand;
import app.resourcehub.availability.business.structs.ExceptionType;
import app.resourcehub.availability.business.structs.RecurringScheduleSlot;
import app.resourcehub.availability.business.structs.TemporalBlock;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.ResourceNature;
import app.resourcehub.shared.business.structs.TimeInterval;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

@DisplayName("TT-009/TT-015 [PRU-03/04/05] — Flyway V5+V8 availability, excepciones Nivel 3 y COMP-GW 200003")
class AvailabilityPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T16:10:00Z"), ZoneOffset.UTC);

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
        app.resourcehub.platform.XF.init(dbaApiDataSource);
        app.resourcehub.gateway.XF.init(FIXED_CLOCK);
        app.resourcehub.resources.XF.init(dbaApiDataSource, FIXED_CLOCK);
        XF.init(dbaApiDataSource, FIXED_CLOCK);
    }

    @AfterAll
    static void stopPostgres() {
        XF.terminate();
        app.resourcehub.resources.XF.terminate();
        app.resourcehub.gateway.XF.terminate();
        app.resourcehub.platform.XF.terminate();
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    @DisplayName("PRU-03: V5/V8 crean recurring_schedule (8), temporal_block (12) y availability_exception (9) bajo dba_api")
    void shouldCreateAvailabilityTablesAndEnforceSqlConstraints() throws SQLException {
        assertThat(migrateResult.migrationsExecuted).isGreaterThanOrEqualTo(6);

        try (Connection conn = dbaApiDataSource.getConnection()) {
            assertThat(countTableColumns(conn, "availability", "recurring_schedule")).isEqualTo(8);
            assertThat(countTableColumns(conn, "availability", "temporal_block")).isEqualTo(12);
            assertThat(countTableColumns(conn, "availability", "availability_exception")).isEqualTo(9);

            try (Statement stmt = conn.createStatement()) {
                assertThatThrownBy(() -> stmt.executeUpdate("""
                    INSERT INTO availability.recurring_schedule (
                        id, resource_id, day_of_week, start_local_time, end_local_time, created_at
                    ) VALUES (
                        '01936b00-0010-7000-8000-000000000001',
                        '01936b00-0010-7000-8000-000000000099',
                        0,
                        '09:00:00'::time,
                        '10:00:00'::time,
                        now()
                    )
                    """))
                    .isInstanceOf(SQLException.class)
                    .extracting(ex -> ((SQLException) ex).getSQLState())
                    .isEqualTo("23514");

                assertThatThrownBy(() -> stmt.executeUpdate("""
                    INSERT INTO availability.recurring_schedule (
                        id, resource_id, day_of_week, start_local_time, end_local_time, created_at
                    ) VALUES (
                        '01936b00-0010-7000-8000-000000000002',
                        '01936b00-0010-7000-8000-000000000099',
                        1,
                        '10:00:00'::time,
                        '10:00:00'::time,
                        now()
                    )
                    """))
                    .isInstanceOf(SQLException.class)
                    .extracting(ex -> ((SQLException) ex).getSQLState())
                    .isEqualTo("23514");

                assertThatThrownBy(() -> stmt.executeUpdate("""
                    INSERT INTO availability.temporal_block (
                        id, resource_id, block_origin, start_at, end_at,
                        forced_confirmation, is_active, created_at
                    ) VALUES (
                        '01936b00-0010-7000-8000-000000000003',
                        '01936b00-0010-7000-8000-000000000099',
                        'CALENDAR',
                        TIMESTAMPTZ '2026-12-01T09:00:00Z',
                        TIMESTAMPTZ '2026-12-01T10:00:00Z',
                        TRUE,
                        TRUE,
                        now()
                    )
                    """))
                    .isInstanceOf(SQLException.class)
                    .extracting(ex -> ((SQLException) ex).getSQLState())
                    .isEqualTo("23514");

                assertThatThrownBy(() -> stmt.executeUpdate("""
                    INSERT INTO availability.availability_exception (
                        id, resource_id, exception_date, exception_type,
                        start_at, end_at, reason, created_by_user_id, created_at
                    ) VALUES (
                        '01936b00-0010-7000-8000-000000000004',
                        '01936b00-0010-7000-8000-000000000099',
                        DATE '2026-12-25',
                        'INVALID_TYPE',
                        TIMESTAMPTZ '2026-12-25T00:00:00Z',
                        TIMESTAMPTZ '2026-12-25T23:00:00Z',
                        'Test',
                        '01936b00-0020-7000-8000-000000000001',
                        now()
                    )
                    """))
                    .isInstanceOf(SQLException.class)
                    .extracting(ex -> ((SQLException) ex).getSQLState())
                    .isEqualTo("23514");

                assertThatThrownBy(() -> stmt.executeUpdate("""
                    INSERT INTO availability.availability_exception (
                        id, resource_id, exception_date, exception_type,
                        start_at, end_at, reason, created_by_user_id, created_at
                    ) VALUES (
                        '01936b00-0010-7000-8000-000000000005',
                        '01936b00-0010-7000-8000-000000000099',
                        DATE '2026-12-25',
                        'CLOSED',
                        TIMESTAMPTZ '2026-12-25T10:00:00Z',
                        TIMESTAMPTZ '2026-12-25T10:00:00Z',
                        'Test',
                        '01936b00-0020-7000-8000-000000000001',
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
    @DisplayName("PRU-04/05: ConsoleAvailabilityService persiste 24:00:00, bloqueos/liberacion y COMP-GW traduce US -> 403/200003")
    void shouldExecuteConsoleAvailabilityAndTranslateScopeViolationThroughGateway() {
        ResourceType spaceType = app.resourcehub.resources.business.B.resourceTypes().createResourceType(
            "SALAS_AVAIL",
            "Salas Disponibilidad",
            null,
            ResourceNature.SPACE
        );
        CatalogResource sala = app.resourcehub.resources.business.B.resources().createSimpleResource(
            spaceType.id(),
            "SALA-DISP-01",
            "Sala Horarios 01",
            "Con horario nocturno",
            6,
            false,
            false,
            null
        );

        UUID orgId = UUID.fromString("01936b00-0000-7000-8000-000000000001");
        ActorContext adminActor = new ActorContext(
            UUID.fromString("01936b00-0020-7000-8000-000000000001"),
            orgId,
            ActorType.USER,
            "AD",
            OperationalScope.AMB_03_MANAGEMENT,
            null,
            Set.of("availability:write")
        );
        ActorContext endUserActor = new ActorContext(
            UUID.fromString("01936b00-0020-7000-8000-000000000002"),
            orgId,
            ActorType.USER,
            "US",
            OperationalScope.AMB_01_CLIENT,
            null,
            Set.of("booking:read")
        );

        ResponseWrapper<List<RecurringScheduleSlot>> scheduleResp = A.consoleAvailability().replaceWeeklySchedule(
            adminActor,
            sala.id(),
            List.of(new WeeklyScheduleEntry(7, LocalTime.of(22, 0), LocalTime.of(6, 0), null, null)),
            "req-tt009-sched-01",
            FIXED_CLOCK
        );
        assertThat(scheduleResp.data()).hasSize(2);
        assertThat(scheduleResp.data().getFirst().endLocalTime()).isEqualTo("24:00:00");
        assertThat(A.consoleAvailability().listWeeklySchedules(adminActor, sala.id(), "req-tt009-sched-02", FIXED_CLOCK).data())
            .hasSize(2);

        ResponseWrapper<TemporalBlock> createdBlock = A.consoleAvailability().createBlock(
            adminActor,
            sala.id(),
            new CreateTemporalBlockCommand(
                BlockOrigin.MAINTENANCE,
                Instant.parse("2026-01-19T00:00:00Z"),
                Instant.parse("2026-01-19T02:00:00Z"),
                false,
                null,
                "Limpieza nocturna"
            ),
            "req-tt009-block-01",
            FIXED_CLOCK
        );
        assertThat(createdBlock.data().active()).isTrue();

        OrganizationContext madridOrg = new OrganizationContext(orgId, "org-madrid", "Europe/Madrid");
        TimeInterval overnightRange = new TimeInterval(
            Instant.parse("2026-01-18T20:00:00Z"),
            Instant.parse("2026-01-19T06:00:00Z")
        );
        assertThat(B.rules().evaluateOpenWindows(sala.id(), overnightRange, madridOrg)).containsExactly(
            new TimeInterval(Instant.parse("2026-01-18T21:00:00Z"), Instant.parse("2026-01-19T00:00:00Z")),
            new TimeInterval(Instant.parse("2026-01-19T02:00:00Z"), Instant.parse("2026-01-19T05:00:00Z"))
        );

        ResponseWrapper<TemporalBlock> releasedBlock = A.consoleAvailability().releaseBlock(
            adminActor,
            createdBlock.data().id(),
            "req-tt009-block-02",
            FIXED_CLOCK
        );
        assertThat(releasedBlock.data().active()).isFalse();
        assertThat(B.rules().evaluateOpenWindows(sala.id(), overnightRange, madridOrg)).containsExactly(
            new TimeInterval(Instant.parse("2026-01-18T21:00:00Z"), Instant.parse("2026-01-19T05:00:00Z"))
        );

        // Evento externo de calendario duplicado en Postgres -> 23505 -> ALREADY_EXISTS
        A.consoleAvailability().createBlock(
            adminActor,
            sala.id(),
            new CreateTemporalBlockCommand(
                BlockOrigin.CALENDAR,
                Instant.parse("2026-01-20T09:00:00Z"),
                Instant.parse("2026-01-20T10:00:00Z"),
                false,
                "gcal-evt-99",
                "Sync externo"
            ),
            "req-tt009-cal-01",
            FIXED_CLOCK
        );
        assertThatThrownBy(() -> A.consoleAvailability().createBlock(
            adminActor,
            sala.id(),
            new CreateTemporalBlockCommand(
                BlockOrigin.CALENDAR,
                Instant.parse("2026-01-20T11:00:00Z"),
                Instant.parse("2026-01-20T12:00:00Z"),
                false,
                "gcal-evt-99",
                "Sync duplicado"
            ),
            "req-tt009-cal-02",
            FIXED_CLOCK
        ))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_EXISTS);

        // TT-015 [PRU-04/05]: Excepciones Nivel 3 (CLOSED y CUSTOM_HOURS) en Postgres y precedencia sobre Nivel 4 / bajo Nivel 2
        ResponseWrapper<CalendarException> closedExc = A.consoleAvailability().createException(
            adminActor,
            sala.id(),
            new CreateCalendarExceptionCommand(LocalDate.of(2026, 1, 25), ExceptionType.CLOSED, null, null, "Festivo"),
            madridOrg,
            "req-tt015-exc-01",
            FIXED_CLOCK
        );
        assertThat(closedExc.data().type()).isEqualTo(ExceptionType.CLOSED);

        ResponseWrapper<CalendarException> customExc = A.consoleAvailability().createException(
            adminActor,
            sala.id(),
            new CreateCalendarExceptionCommand(
                LocalDate.of(2026, 1, 24),
                ExceptionType.CUSTOM_HOURS,
                LocalTime.of(10, 0),
                LocalTime.of(13, 0),
                "Apertura extraordinaria"
            ),
            madridOrg,
            "req-tt015-exc-02",
            FIXED_CLOCK
        );
        assertThat(customExc.data().type()).isEqualTo(ExceptionType.CUSTOM_HOURS);
        assertThat(A.consoleAvailability().listExceptions(adminActor, sala.id(), "req-tt015-exc-03", FIXED_CLOCK).data())
            .hasSize(2);

        A.consoleAvailability().createBlock(
            adminActor,
            sala.id(),
            new CreateTemporalBlockCommand(
                BlockOrigin.MAINTENANCE,
                Instant.parse("2026-01-24T10:00:00Z"),
                Instant.parse("2026-01-24T11:00:00Z"),
                false,
                null,
                "Revision sobre excepcion"
            ),
            "req-tt015-blk-01",
            FIXED_CLOCK
        );
        assertThat(B.rules().evaluateOpenWindows(
            sala.id(),
            new TimeInterval(Instant.parse("2026-01-24T08:00:00Z"), Instant.parse("2026-01-24T14:00:00Z")),
            madridOrg
        )).containsExactly(
            new TimeInterval(Instant.parse("2026-01-24T09:00:00Z"), Instant.parse("2026-01-24T10:00:00Z")),
            new TimeInterval(Instant.parse("2026-01-24T11:00:00Z"), Instant.parse("2026-01-24T12:00:00Z"))
        );

        assertThatThrownBy(() -> A.consoleAvailability().createException(
            endUserActor,
            sala.id(),
            new CreateCalendarExceptionCommand(LocalDate.of(2026, 2, 1), ExceptionType.CLOSED, null, null, "No autorizado"),
            madridOrg,
            "req-tt015-403",
            FIXED_CLOCK
        ))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);

        // PRU-05: Rol US recibe SCOPE_VIOLATION -> COMP-GW traduce a 403 / 200003
        AvailabilityBusinessException scopeEx = null;
        try {
            A.consoleAvailability().createBlock(
                endUserActor,
                sala.id(),
                new CreateTemporalBlockCommand(
                    BlockOrigin.MANUAL,
                    Instant.parse("2026-01-21T09:00:00Z"),
                    Instant.parse("2026-01-21T10:00:00Z"),
                    false,
                    null,
                    "No autorizado"
                ),
                "req-tt009-403",
                FIXED_CLOCK
            );
        } catch (AvailabilityBusinessException ex) {
            scopeEx = ex;
        }
        assertThat(scopeEx).isNotNull();
        InternalProblem problem = A.consoleAvailability().toInternalProblem(
            scopeEx,
            "/api/v1/console/availability/resources/" + sala.id() + "/blocks",
            "req-tt009-403"
        );
        GatewayRequest gwReq = new GatewayRequest(
            "console.resourcehub.test",
            "/api/v1/console/availability/resources/" + sala.id() + "/blocks",
            "POST",
            Map.of("X-Request-Id", "req-tt009-403"),
            null
        );
        GatewayResponse<Void> gwResp = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(gwReq, problem);
        assertThat(gwResp.httpStatus()).isEqualTo(403);
        assertThat(gwResp.body().code()).isEqualTo(200003);
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
