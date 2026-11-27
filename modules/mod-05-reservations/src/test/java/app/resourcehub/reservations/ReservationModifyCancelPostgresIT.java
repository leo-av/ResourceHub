package app.resourcehub.reservations;

import app.resourcehub.access.business.structs.IdentityAccount;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.reservations.api.A;
import app.resourcehub.reservations.business.structs.CreateReservationCommand;
import app.resourcehub.reservations.business.structs.ModifyReservationCommand;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationStatus;
import app.resourcehub.reservations.business.structs.ReservationsBusinessException;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.CreateResourceCommand;
import app.resourcehub.resources.business.structs.CreateResourceTypeCommand;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-018 & TT-019 [PRU-03/05/06/09] — Modificacion AC-010 y Cancelacion /app + /console en Postgres 16.6")
class ReservationModifyCancelPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final UUID DEMO_ORG_ID = UUID.fromString("01936a00-0000-7000-8000-000000000001");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T18:30:00Z"), ZoneOffset.UTC);

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
        app.resourcehub.access.XF.init(dbaApiDataSource, FIXED_CLOCK);
        app.resourcehub.resources.XF.init(dbaApiDataSource, FIXED_CLOCK);
        app.resourcehub.availability.XF.init(dbaApiDataSource, FIXED_CLOCK);
        XF.init(dbaApiDataSource, FIXED_CLOCK);
    }

    @AfterAll
    static void stopPostgres() {
        XF.terminate();
        app.resourcehub.availability.XF.terminate();
        app.resourcehub.resources.XF.terminate();
        app.resourcehub.access.XF.terminate();
        app.resourcehub.gateway.XF.terminate();
        app.resourcehub.platform.XF.terminate();
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    @DisplayName("AC-010, PRU-06, PRU-09: Modificacion integral, rollback total, concurrencia y cancelacion HOLDER/MANAGEMENT")
    void shouldModifyCancelAndReleaseGistConstraintEndToEndInPostgres() throws Exception {
        IdentityAccount user1 = app.resourcehub.access.business.B.users().createIdentityAccount(UserRole.US, null);
        IdentityAccount user2 = app.resourcehub.access.business.B.users().createIdentityAccount(UserRole.US, null);
        IdentityAccount manager = app.resourcehub.access.business.B.users().createIdentityAccount(UserRole.MG, null);

        ActorContext actor1 = clientActor(user1.id());
        ActorContext actor2 = clientActor(user2.id());
        ActorContext managerActor = new ActorContext(
            manager.id(), DEMO_ORG_ID, ActorType.USER, "MG", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
        );
        OrganizationContext madrid = new OrganizationContext(DEMO_ORG_ID, "resourcehub-demo", "Europe/Madrid");

        ResourceType type = app.resourcehub.resources.api.A.consoleCatalog().createResourceType(
            managerActor,
            new CreateResourceTypeCommand("SALA_MOD", "Salas Modificables", "PRU-06", ResourceNature.SPACE),
            "req-mod-type",
            FIXED_CLOCK
        ).data();
        CatalogResource room1 = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            managerActor,
            new CreateResourceCommand(type.id(), "SALA-M1", "Sala M1", null, 1, false, false, null),
            "req-mod-r1",
            FIXED_CLOCK
        ).data();
        CatalogResource room2 = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            managerActor,
            new CreateResourceCommand(type.id(), "SALA-M2", "Sala M2", null, 1, false, false, null),
            "req-mod-r2",
            FIXED_CLOCK
        ).data();

        List<WeeklyScheduleEntry> mondaySlots = List.of(
            new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.of(16, 0), null, null)
        );
        app.resourcehub.availability.api.A.consoleAvailability().replaceWeeklySchedule(
            managerActor, room1.id(), mondaySlots, "req-s1", FIXED_CLOCK
        );
        app.resourcehub.availability.api.A.consoleAvailability().replaceWeeklySchedule(
            managerActor, room2.id(), mondaySlots, "req-s2", FIXED_CLOCK
        );

        Instant t09Z = Instant.parse("2026-11-30T09:00:00Z");
        Instant t10Z = Instant.parse("2026-11-30T10:00:00Z");
        Instant t11Z = Instant.parse("2026-11-30T11:00:00Z");
        Instant t12Z = Instant.parse("2026-11-30T12:00:00Z");
        Instant t13Z = Instant.parse("2026-11-30T13:00:00Z");

        Reservation res1 = A.appReservations().createReservation(
            actor1, new CreateReservationCommand(room1.id(), t09Z, t11Z, 1, null), madrid, "req-c1", FIXED_CLOCK
        ).data();
        Reservation res2 = A.appReservations().createReservation(
            actor2, new CreateReservationCommand(room2.id(), t12Z, t13Z, 1, null), madrid, "req-c2", FIXED_CLOCK
        ).data();

        // 1. Modificacion dentro de la propia franja [09:00Z, 11:00Z) -> [09:30Z, 11:30Z) sin auto-colision
        Instant t0930Z = Instant.parse("2026-11-30T09:30:00Z");
        Instant t1130Z = Instant.parse("2026-11-30T11:30:00Z");
        ResponseWrapper<Reservation> selfOverlapMod = A.appReservations().modifyReservation(
            actor1, res1.id(), new ModifyReservationCommand(room1.id(), t0930Z, t1130Z, 1),
            madrid, "req-mod-1", FIXED_CLOCK
        );
        assertThat(selfOverlapMod.data().version()).isEqualTo(2);
        assertThat(selfOverlapMod.data().lines().getFirst().startAt()).isEqualTo(t0930Z);

        // 2. AC-010: Modificacion que colisiona en room2 [12:00Z, 13:00Z) falla 409 / 400501 y preserva res1 intacta
        ReservationsBusinessException conflictEx = null;
        try {
            A.appReservations().modifyReservation(
                actor1, res1.id(), new ModifyReservationCommand(room2.id(), t12Z, t13Z, 1),
                madrid, "req-mod-fail", FIXED_CLOCK
            );
        } catch (ReservationsBusinessException ex) {
            conflictEx = ex;
        }
        assertThat(conflictEx).isNotNull();
        GatewayResponse<Void> gwConflict = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest("resourcehub-demo.resourcehub.app", "/api/v1/app/reservations/" + res1.id(), "PUT",
                Map.of("X-Request-Id", "req-mod-fail"), null),
            A.appReservations().toInternalProblem(conflictEx, "/api/v1/app/reservations/" + res1.id(), "req-mod-fail")
        );
        assertThat(gwConflict.httpStatus()).isEqualTo(409);
        assertThat(gwConflict.body().code()).isEqualTo(400501);

        Reservation afterFailedModify = A.appReservations().getOwnReservation(actor1, res1.id(), "req-get-1", FIXED_CLOCK).data();
        assertThat(afterFailedModify.version()).isEqualTo(2);
        assertThat(afterFailedModify.lines().getFirst().resourceId()).isEqualTo(room1.id());
        assertThat(afterFailedModify.lines().getFirst().startAt()).isEqualTo(t0930Z);

        // 3. Cancelacion HOLDER (TT-019): libera [09:30Z, 11:30Z) y permite reservarlo inmediatamente
        ResponseWrapper<Reservation> holderCancelled = A.appReservations().cancelReservation(
            actor1, res1.id(), "req-cancel-holder", FIXED_CLOCK
        );
        assertThat(holderCancelled.data().status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(holderCancelled.data().lines().getFirst().status()).isEqualTo("RELEASED");

        Reservation bookedAfterCancel = A.appReservations().createReservation(
            actor2, new CreateReservationCommand(room1.id(), t0930Z, t1130Z, 1, null),
            madrid, "req-rebook", FIXED_CLOCK
        ).data();
        assertThat(bookedAfterCancel.status()).isEqualTo(ReservationStatus.CONFIRMED);

        // 4. Cancelacion en consola AMB-03 (MANAGEMENT) y rechazo 403 / 200003 si ACT-US intenta /console/.../cancel
        assertThatThrownBy(() -> A.consoleReservations().cancelReservation(actor1, res2.id(), "req-403", FIXED_CLOCK))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);

        ResponseWrapper<Reservation> mgCancelled = A.consoleReservations().cancelReservation(
            managerActor, res2.id(), "req-cancel-mg", FIXED_CLOCK
        );
        assertThat(mgCancelled.data().status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(mgCancelled.data().lines().getFirst().status()).isEqualTo("RELEASED");

        // 5. PRU-09 concurrencia en modificacion: 2 reservas independientes compiten por moverse a room2 [14:00Z, 15:00Z)
        Instant t1330Z = Instant.parse("2026-11-30T13:30:00Z");
        Instant t14Z = Instant.parse("2026-11-30T14:00:00Z");
        Instant t15Z = Instant.parse("2026-11-30T15:00:00Z");
        Reservation raceA = A.appReservations().createReservation(
            actor1, new CreateReservationCommand(room1.id(), t12Z, t13Z, 1, null), madrid, "req-ra", FIXED_CLOCK
        ).data();
        Reservation raceB = A.appReservations().createReservation(
            actor2, new CreateReservationCommand(room1.id(), t13Z, t1330Z, 1, null), madrid, "req-rb", FIXED_CLOCK
        ).data();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        Future<?> f1 = pool.submit(() ->
            runConcurrentModify(ready, start, actor1, raceA.id(), room2.id(), t14Z, t15Z, madrid, winners, conflicts));
        Future<?> f2 = pool.submit(() ->
            runConcurrentModify(ready, start, actor2, raceB.id(), room2.id(), t14Z, t15Z, madrid, winners, conflicts));
        ready.await();
        start.countDown();
        f1.get();
        f2.get();
        pool.shutdown();

        assertThat(winners.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
    }

    private static void runConcurrentModify(
        CountDownLatch ready,
        CountDownLatch start,
        ActorContext actor,
        UUID reservationId,
        UUID targetResourceId,
        Instant targetStart,
        Instant targetEnd,
        OrganizationContext org,
        AtomicInteger winners,
        AtomicInteger conflicts
    ) {
        ready.countDown();
        try {
            start.await();
            A.appReservations().modifyReservation(
                actor,
                reservationId,
                new ModifyReservationCommand(targetResourceId, targetStart, targetEnd, 1),
                org,
                "req-race-" + reservationId,
                FIXED_CLOCK
            );
            winners.incrementAndGet();
        } catch (ReservationsBusinessException ex) {
            if (ex.getErrorCode() == ErrorCode.BOOKING_CONFLICT) {
                conflicts.incrementAndGet();
            } else {
                throw ex;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static ActorContext clientActor(UUID userId) {
        return new ActorContext(userId, DEMO_ORG_ID, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT, null, Set.of());
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
