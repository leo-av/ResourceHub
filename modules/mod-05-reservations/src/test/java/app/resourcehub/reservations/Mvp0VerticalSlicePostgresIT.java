package app.resourcehub.reservations;

import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.IdentityAccount;
import app.resourcehub.access.business.structs.LocalAuthCommand;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.availability.business.structs.BlockOrigin;
import app.resourcehub.availability.business.structs.CreateTemporalBlockCommand;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import app.resourcehub.platform.business.structs.Organization;
import app.resourcehub.reservations.api.A;
import app.resourcehub.reservations.business.structs.CreateReservationCommand;
import app.resourcehub.reservations.business.structs.EffectiveAvailability;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationStatus;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.CreateResourceCommand;
import app.resourcehub.resources.business.structs.CreateResourceTypeCommand;
import app.resourcehub.resources.business.structs.PatchResourceStatusCommand;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.shared.api.structs.PageResponse;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.ResourceNature;
import app.resourcehub.shared.business.structs.SharedDomainException;
import app.resourcehub.shared.business.structs.TimeInterval;
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
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("TT-010, TT-012 & TT-013 [PRU-04..06 / PRU-08] — Vertical Slice MVP-0 E2E en PostgreSQL 16.6")
class Mvp0VerticalSlicePostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final String DEMO_HOST = "resourcehub-demo.resourcehub.app";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T17:00:00Z"), ZoneOffset.UTC);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("resourcehub")
        .withUsername("postgres")
        .withPassword("postgres");

    private static DataSource dbaApiDataSource;

    @BeforeAll
    static void startPostgresAndWireAllMvp0Modules() throws SQLException {
        POSTGRES.start();
        provisionRoles(POSTGRES);
        migrateAllSchemas(POSTGRES);
        dbaApiDataSource = new SimpleDriverDataSource(POSTGRES.getJdbcUrl(), DBA_API_USER, DBA_API_PASS);
        wireAllModules(dbaApiDataSource);
    }

    @AfterAll
    static void stopPostgres() {
        terminateAllModules();
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    @DisplayName("TT-013 [PRU-03 / PRU-04]: Biyeccion 1:1 ErrorCode <-> platform_catalog.error_catalog via COMP-GW")
    void shouldVerifyBijectionBetweenErrorCodeEnumAndPostgresCatalog() {
        GatewayResponse<List<ErrorCatalogEntry>> response = app.resourcehub.gateway.api.A.edgeGateway()
            .handleErrorCatalogEndpoint(new GatewayRequest(
                "platform.resourcehub.app",
                "/api/v1/platform/error-catalog",
                "GET",
                Map.of("X-Request-Id", "req-errcat-01"),
                null
            ));
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.body().code()).isZero();
        List<ErrorCatalogEntry> catalog = response.body().data();
        assertThat(catalog).hasSize(ErrorCode.values().length);
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(catalog)
                .anySatisfy(entry -> {
                    assertThat(entry.code()).isEqualTo(code);
                    assertThat(entry.active()).isTrue();
                    if (code == ErrorCode.OK) {
                        assertThat(entry.numericValue()).isZero();
                        assertThat((int) entry.httpStatus()).isEqualTo(200);
                    } else {
                        var translated = app.resourcehub.gateway.business.B.errorTranslation()
                            .translate(code, List.of());
                        assertThat(entry.numericValue()).isEqualTo(translated.numericCode());
                        assertThat((int) entry.httpStatus()).isEqualTo(translated.httpStatus());
                    }
                });
        }
    }

    @Test
    @DisplayName("TT-010, TT-012 & TT-013 [PRU-04..06 / PRU-08]: Vertical Slice MVP-0 + aislamiento multi-tenant")
    void shouldExecuteFullMvp0VerticalSliceAndEnforceTenantIsolation() throws SQLException {
        // 1. Resolucion organizativa (MOD-01)
        Organization org = app.resourcehub.platform.business.B.organization()
            .resolveActiveOrgBySlug("resourcehub-demo");
        OrganizationContext orgContext = new OrganizationContext(
            org.id(),
            org.slug(),
            org.initialTimeZoneId()
        );

        // 2. Provisionamiento de cuentas y login JWT Ed25519 por canal (MOD-02)
        IdentityAccount managerAccount = app.resourcehub.access.business.B.users()
            .createIdentityAccount(UserRole.MG, null);
        app.resourcehub.access.business.B.auth().registerLocalCredential(
            org.id(), managerAccount.id(), "manager@demo.test", "ManagerPass123!".toCharArray()
        );
        IdentityAccount clientAccount = app.resourcehub.access.business.B.users()
            .createIdentityAccount(UserRole.US, null);
        app.resourcehub.access.business.B.auth().registerLocalCredential(
            org.id(), clientAccount.id(), "client@demo.test", "ClientPass123!".toCharArray()
        );

        AuthTokenBundle managerTokens = app.resourcehub.access.api.A.consoleAuth().login(
            new LocalAuthCommand(org.id(), "manager@demo.test", "ManagerPass123!".toCharArray()),
            "req-login-mg",
            FIXED_CLOCK
        ).data();
        AuthTokenBundle clientTokens = app.resourcehub.access.api.A.appAuth().login(
            new LocalAuthCommand(org.id(), "client@demo.test", "ClientPass123!".toCharArray()),
            "req-login-us",
            FIXED_CLOCK
        ).data();

        ActorContext managerActor = app.resourcehub.access.business.B.auth()
            .validateToken(managerTokens.accessToken());
        ActorContext clientActor = app.resourcehub.access.business.B.auth()
            .validateToken(clientTokens.accessToken());

        // 3. Creacion de tipo y recurso en /console (MOD-03)
        ResourceType roomType = app.resourcehub.resources.api.A.consoleCatalog().createResourceType(
            managerActor,
            new CreateResourceTypeCommand("MEETING_ROOM", "Salas Reuniones", "Espacios", ResourceNature.SPACE),
            "req-type-01",
            FIXED_CLOCK
        ).data();

        CatalogResource room = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            managerActor,
            new CreateResourceCommand(roomType.id(), "SALA-MVP0", "Sala Alpha", "Planta 1", 1, false, false, null),
            "req-res-01",
            FIXED_CLOCK
        ).data();

        // 4. Configuracion horario lunes 09:00-15:00 Madrid (08:00Z-14:00Z el 2026-11-30) + bloqueo 10:00-11:00 Madrid
        app.resourcehub.availability.api.A.consoleAvailability().replaceWeeklySchedule(
            managerActor,
            room.id(),
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.of(15, 0), null, null)),
            "req-sched-01",
            FIXED_CLOCK
        );
        app.resourcehub.availability.api.A.consoleAvailability().createBlock(
            managerActor,
            room.id(),
            new CreateTemporalBlockCommand(
                BlockOrigin.MAINTENANCE,
                Instant.parse("2026-11-30T09:00:00Z"),
                Instant.parse("2026-11-30T10:00:00Z"),
                false,
                null,
                "Calibracion"
            ),
            "req-blk-01",
            FIXED_CLOCK
        );

        // 5. Consulta /app/resources y GET /app/resources/{id}/availability (TT-010)
        PageResponse<CatalogResource> appCatalog = app.resourcehub.resources.api.A.appCatalog()
            .listResources(clientActor, 1, 10, "req-cat-01", FIXED_CLOCK)
            .data();
        assertThat(appCatalog.totalItems()).isEqualTo(1);

        Instant qStart = Instant.parse("2026-11-30T07:00:00Z");
        Instant qEnd = Instant.parse("2026-11-30T15:00:00Z");
        ResponseWrapper<EffectiveAvailability> availBefore = A.appReservations().getEffectiveAvailability(
            clientActor, room.id(), qStart, qEnd, orgContext, "req-avail-01", FIXED_CLOCK
        );
        assertThat(availBefore.code()).isZero();
        assertThat(availBefore.data().availableWindows()).containsExactly(
            new TimeInterval(Instant.parse("2026-11-30T08:00:00Z"), Instant.parse("2026-11-30T09:00:00Z")),
            new TimeInterval(Instant.parse("2026-11-30T10:00:00Z"), Instant.parse("2026-11-30T14:00:00Z"))
        );

        // 6. AC-003: Reservas simples contiguas [10:00Z, 11:00Z) y [11:00Z, 12:00Z) (TT-012)
        Instant t10Z = Instant.parse("2026-11-30T10:00:00Z");
        Instant t11Z = Instant.parse("2026-11-30T11:00:00Z");
        Instant t12Z = Instant.parse("2026-11-30T12:00:00Z");

        ResponseWrapper<Reservation> res1Wrap = A.appReservations().createReservation(
            clientActor, new CreateReservationCommand(room.id(), t10Z, t11Z, 1, null),
            orgContext, "req-book-01", FIXED_CLOCK
        );
        GatewayResponse<Reservation> gwRes1 = app.resourcehub.gateway.api.A.edgeGateway().handleResponse(
            new GatewayRequest(DEMO_HOST, "/api/v1/app/reservations", "POST",
                Map.of("X-Request-Id", "req-book-01"), null),
            201,
            res1Wrap
        );
        assertThat(gwRes1.httpStatus()).isEqualTo(201);
        assertThat(gwRes1.body().data().status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(gwRes1.body().data().lines()).hasSize(1);
        UUID firstReservationId = gwRes1.body().data().id();

        ResponseWrapper<Reservation> res2Wrap = A.appReservations().createReservation(
            clientActor, new CreateReservationCommand(room.id(), t11Z, t12Z, 1, clientActor.userId()),
            orgContext, "req-book-02", FIXED_CLOCK
        );
        assertThat(res2Wrap.code()).isZero();
        assertThat(res2Wrap.data().status()).isEqualTo(ReservationStatus.CONFIRMED);

        // Nivel 5 re-evaluado en GET /availability tras reservas
        EffectiveAvailability availAfter = A.appReservations().getEffectiveAvailability(
            clientActor, room.id(), qStart, qEnd, orgContext, "req-avail-02", FIXED_CLOCK
        ).data();
        assertThat(availAfter.availableWindows()).containsExactly(
            new TimeInterval(Instant.parse("2026-11-30T08:00:00Z"), Instant.parse("2026-11-30T09:00:00Z")),
            new TimeInterval(Instant.parse("2026-11-30T12:00:00Z"), Instant.parse("2026-11-30T14:00:00Z"))
        );

        // 7. Discriminacion Gateway FFMMCC: 500401, 500402, 500403, 400501, 200004, 200003
        assertGatewayProblem(
            () -> A.appReservations().getEffectiveAvailability(
                clientActor, room.id(), qEnd, qStart, orgContext, "req-bad-int", FIXED_CLOCK),
            "/api/v1/app/resources/" + room.id() + "/availability", "GET", "req-bad-int", 422, 500401
        );
        assertGatewayProblem(
            () -> A.appReservations().createReservation(
                clientActor,
                new CreateReservationCommand(room.id(), qStart, Instant.parse("2026-11-30T08:00:00Z"), 1, null),
                orgContext, "req-out", FIXED_CLOCK),
            "/api/v1/app/reservations", "POST", "req-out", 422, 500402
        );
        assertGatewayProblem(
            () -> A.appReservations().createReservation(
                clientActor,
                new CreateReservationCommand(
                    room.id(), Instant.parse("2026-11-30T09:00:00Z"), t10Z, 1, null),
                orgContext, "req-blk", FIXED_CLOCK),
            "/api/v1/app/reservations", "POST", "req-blk", 422, 500403
        );
        assertGatewayProblem(
            () -> A.appReservations().createReservation(
                clientActor,
                new CreateReservationCommand(
                    room.id(), Instant.parse("2026-11-30T10:30:00Z"),
                    Instant.parse("2026-11-30T11:30:00Z"), 1, null),
                orgContext, "req-conf", FIXED_CLOCK),
            "/api/v1/app/reservations", "POST", "req-conf", 409, 400501
        );
        assertGatewayProblem(
            () -> A.appReservations().createReservation(
                clientActor,
                new CreateReservationCommand(
                    room.id(), t12Z, Instant.parse("2026-11-30T13:00:00Z"), 1, managerActor.userId()),
                orgContext, "req-holder", FIXED_CLOCK),
            "/api/v1/app/reservations", "POST", "req-holder", 403, 200004
        );
        assertGatewayProblem(
            () -> A.appReservations().createReservation(
                managerActor,
                new CreateReservationCommand(
                    room.id(), t12Z, Instant.parse("2026-11-30T13:00:00Z"), 1, null),
                orgContext, "req-scope", FIXED_CLOCK),
            "/api/v1/app/reservations", "POST", "req-scope", 403, 200003
        );

        // 8. AC-007: Deshabilitar recurso -> nuevas reservas 422/500301, existentes siguen CONFIRMED
        app.resourcehub.resources.api.A.consoleCatalog().patchResourceStatus(
            managerActor, room.id(), new PatchResourceStatusCommand(ResourceStatus.DISABLED),
            "req-disable", FIXED_CLOCK
        );
        assertGatewayProblem(
            () -> A.appReservations().createReservation(
                clientActor,
                new CreateReservationCommand(
                    room.id(), t12Z, Instant.parse("2026-11-30T13:00:00Z"), 1, null),
                orgContext, "req-disabled-book", FIXED_CLOCK),
            "/api/v1/app/reservations", "POST", "req-disabled-book", 422, 500301
        );
        EffectiveAvailability disabledAvail = A.appReservations().getEffectiveAvailability(
            clientActor, room.id(), qStart, qEnd, orgContext, "req-avail-disabled", FIXED_CLOCK
        ).data();
        assertThat(disabledAvail.resourceStatus()).isEqualTo(ResourceStatus.DISABLED);
        assertThat(disabledAvail.availableWindows()).isEmpty();

        PageResponse<Reservation> ownList = A.appReservations().listOwnReservations(
            clientActor, 1, 20, "req-list-own", FIXED_CLOCK
        ).data();
        assertThat(ownList.totalItems()).isEqualTo(2);
        assertThat(A.appReservations().getOwnReservation(
            clientActor, firstReservationId, "req-get-own", FIXED_CLOCK
        ).data().status()).isEqualTo(ReservationStatus.CONFIRMED);

        // 9. PRU-08 Base: Aislamiento fisico multi-tenant contra segundo PostgreSQL (org-beta -> 404 / 300001)
        try (PostgreSQLContainer betaPostgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("resourcehub")
            .withUsername("postgres")
            .withPassword("postgres")) {
            betaPostgres.start();
            provisionRoles(betaPostgres);
            migrateAllSchemas(betaPostgres);
            DataSource betaDs = new SimpleDriverDataSource(betaPostgres.getJdbcUrl(), DBA_API_USER, DBA_API_PASS);
            try {
                wireAllModules(betaDs);
                IdentityAccount betaClient = app.resourcehub.access.business.B.users()
                    .createIdentityAccount(UserRole.US, null);
                ActorContext betaActor = new ActorContext(
                    betaClient.id(),
                    UUID.fromString("01936a00-0000-7000-8000-000000000099"),
                    clientActor.actorType(),
                    "US",
                    clientActor.operationalScope(),
                    null,
                    clientActor.scopes()
                );

                assertGatewayProblem(
                    () -> A.appReservations().getOwnReservation(
                        betaActor, firstReservationId, "req-xtenant-res", FIXED_CLOCK),
                    "/api/v1/app/reservations/" + firstReservationId, "GET", "req-xtenant-res", 404, 300001
                );
                assertGatewayProblem(
                    () -> A.appReservations().getEffectiveAvailability(
                        betaActor, room.id(), qStart, qEnd, orgContext, "req-xtenant-avail", FIXED_CLOCK),
                    "/api/v1/app/resources/" + room.id() + "/availability", "GET", "req-xtenant-avail", 404, 300001
                );
            } finally {
                wireAllModules(dbaApiDataSource);
            }
        }
    }

    private static void assertGatewayProblem(
        Runnable action,
        String path,
        String method,
        String requestId,
        int expectedHttp,
        int expectedCode
    ) {
        SharedDomainException ex = catchThrowableOfType(SharedDomainException.class, action::run);
        assertThat(ex).isNotNull();
        GatewayResponse<Void> gw = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest(DEMO_HOST, path, method, Map.of("X-Request-Id", requestId), null),
            A.appReservations().toInternalProblem(ex, path, requestId)
        );
        assertThat(gw.httpStatus()).isEqualTo(expectedHttp);
        assertThat(gw.body().code()).isEqualTo(expectedCode);
    }

    private static void wireAllModules(DataSource ds) {
        terminateAllModules();
        app.resourcehub.platform.XF.init(ds);
        app.resourcehub.gateway.XF.init(FIXED_CLOCK);
        app.resourcehub.access.XF.init(ds, FIXED_CLOCK);
        app.resourcehub.resources.XF.init(ds, FIXED_CLOCK);
        app.resourcehub.availability.XF.init(ds, FIXED_CLOCK);
        XF.init(ds, FIXED_CLOCK);
    }

    private static void terminateAllModules() {
        XF.terminate();
        app.resourcehub.availability.XF.terminate();
        app.resourcehub.resources.XF.terminate();
        app.resourcehub.access.XF.terminate();
        app.resourcehub.gateway.XF.terminate();
        app.resourcehub.platform.XF.terminate();
    }

    private static void migrateAllSchemas(PostgreSQLContainer container) {
        Flyway.configure()
            .dataSource(container.getJdbcUrl(), ADMIN_USER, ADMIN_PASS)
            .locations("filesystem:" + resolveMigrationsDirectory().toAbsolutePath())
            .load()
            .migrate();
    }

    private static void provisionRoles(PostgreSQLContainer container) throws SQLException {
        try (Connection superConn = DriverManager.getConnection(
            container.getJdbcUrl(), container.getUsername(), container.getPassword()
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
