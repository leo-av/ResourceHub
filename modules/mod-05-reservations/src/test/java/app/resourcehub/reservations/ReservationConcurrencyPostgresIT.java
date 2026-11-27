package app.resourcehub.reservations;

import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.platform.business.structs.Organization;
import app.resourcehub.reservations.api.A;
import app.resourcehub.reservations.business.structs.CreateReservationCommand;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationStatus;
import app.resourcehub.reservations.business.structs.ReservationsBusinessException;
import app.resourcehub.reservations.repository.R;
import app.resourcehub.reservations.repository.structs.StoredReservationLine;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.CreateResourceCommand;
import app.resourcehub.resources.business.structs.CreateResourceTypeCommand;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.ResourceNature;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TT-014 [PRU-09 / AC-004] — Concurrencia 20 hilos simultáneos, Bloqueo Canónico y GiST en PG 16.6")
class ReservationConcurrencyPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final int THREAD_COUNT = 20;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T17:30:00Z"), ZoneOffset.UTC);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("resourcehub")
        .withUsername("postgres")
        .withPassword("postgres");

    private static DataSource dbaApiDataSource;

    @BeforeAll
    static void startPostgres() throws SQLException {
        POSTGRES.start();
        provisionRoles();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASS)
            .locations("filesystem:" + resolveMigrationsDirectory().toAbsolutePath())
            .load()
            .migrate();

        dbaApiDataSource = new SimpleDriverDataSource(POSTGRES.getJdbcUrl(), DBA_API_USER, DBA_API_PASS);
        app.resourcehub.platform.XF.init(dbaApiDataSource);
        app.resourcehub.gateway.XF.init(FIXED_CLOCK);
        app.resourcehub.resources.XF.init(dbaApiDataSource, FIXED_CLOCK);
        app.resourcehub.availability.XF.init(dbaApiDataSource, FIXED_CLOCK);
        XF.init(dbaApiDataSource, FIXED_CLOCK);
    }

    @AfterAll
    static void stopPostgres() {
        XF.terminate();
        app.resourcehub.availability.XF.terminate();
        app.resourcehub.resources.XF.terminate();
        app.resourcehub.gateway.XF.terminate();
        app.resourcehub.platform.XF.terminate();
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    @DisplayName("AC-004: 20 peticiones simultáneas sobre [10:00, 11:00) -> 1 CONFIRMED (201) y 19 409 (400501)")
    void shouldAllowSingleWinnerAndReject19ConcurrentConflictsWithoutDeadlocks() throws Exception {
        Organization org = app.resourcehub.platform.business.B.organization()
            .resolveActiveOrgBySlug("resourcehub-demo");
        OrganizationContext orgContext = new OrganizationContext(
            org.id(), org.slug(), org.initialTimeZoneId()
        );

        ActorContext managerActor = new ActorContext(
            UUID.fromString("01936d00-0001-7000-8000-000000000001"),
            org.id(), ActorType.USER, "MG", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
        );

        ResourceType type = app.resourcehub.resources.api.A.consoleCatalog().createResourceType(
            managerActor,
            new CreateResourceTypeCommand("CONC_ROOM", "Salas Concurrencia", "Prueba AC-004", ResourceNature.SPACE),
            "req-conc-type",
            FIXED_CLOCK
        ).data();
        CatalogResource room = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            managerActor,
            new CreateResourceCommand(type.id(), "SALA-RACE", "Sala Race", "Planta 1", 1, false, false, null),
            "req-conc-res",
            FIXED_CLOCK
        ).data();

        // Lunes 2026-11-30 09:00..15:00 Europe/Madrid (= 08:00Z..14:00Z)
        app.resourcehub.availability.api.A.consoleAvailability().replaceWeeklySchedule(
            managerActor,
            room.id(),
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.of(15, 0), null, null)),
            "req-conc-sched",
            FIXED_CLOCK
        );

        Instant slotStart = Instant.parse("2026-11-30T10:00:00Z");
        Instant slotEnd = Instant.parse("2026-11-30T11:00:00Z");

        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>(THREAD_COUNT);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT)) {
            for (int i = 0; i < THREAD_COUNT; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ActorContext client = new ActorContext(
                        UUID.fromString(String.format("01936d10-%04x-7000-8000-000000000001", idx + 1)),
                        org.id(), ActorType.USER, "US", OperationalScope.AMB_01_CLIENT, null, Set.of()
                    );
                    String reqId = "req-race-" + idx;
                    ready.countDown();
                    startGate.await();
                    try {
                        ResponseWrapper<Reservation> ok = A.appReservations().createReservation(
                            client,
                            new CreateReservationCommand(room.id(), slotStart, slotEnd, 1, null),
                            orgContext,
                            reqId,
                            FIXED_CLOCK
                        );
                        GatewayResponse<Reservation> gwOk = app.resourcehub.gateway.api.A.edgeGateway()
                            .handleResponse(
                                new GatewayRequest("resourcehub-demo.resourcehub.app", "/api/v1/app/reservations",
                                    "POST", Map.of("X-Request-Id", reqId), null),
                                201,
                                ok
                            );
                        assertThat(gwOk.body().data().status()).isEqualTo(ReservationStatus.CONFIRMED);
                        return gwOk.httpStatus();
                    } catch (ReservationsBusinessException ex) {
                        GatewayResponse<Void> gwErr = app.resourcehub.gateway.api.A.edgeGateway()
                            .handleInternalProblem(
                                new GatewayRequest("resourcehub-demo.resourcehub.app", "/api/v1/app/reservations",
                                    "POST", Map.of("X-Request-Id", reqId), null),
                                A.appReservations().toInternalProblem(ex, "/api/v1/app/reservations", reqId)
                            );
                        assertThat(gwErr.body().code()).isEqualTo(400501);
                        return gwErr.httpStatus();
                    }
                }));
            }

            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            startGate.countDown();

            int created201 = 0;
            int conflict409 = 0;
            for (Future<Integer> future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);
                if (status == 201) {
                    created201++;
                } else if (status == 409) {
                    conflict409++;
                } else {
                    throw new AssertionError("Unexpected HTTP status during concurrency race: " + status);
                }
            }
            assertThat(created201).isEqualTo(1);
            assertThat(conflict409).isEqualTo(19);
        }

        List<StoredReservationLine> activeLines = R.reservations()
            .findActiveLinesOverlapping(room.id(), slotStart, slotEnd);
        assertThat(activeLines).hasSize(1);
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
