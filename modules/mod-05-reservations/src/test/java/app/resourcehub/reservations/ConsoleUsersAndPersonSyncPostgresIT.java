package app.resourcehub.reservations;

import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.CreateConsoleUserCommand;
import app.resourcehub.access.business.structs.IdentityStatus;
import app.resourcehub.access.business.structs.LocalAuthCommand;
import app.resourcehub.access.business.structs.PatchConsoleUserCommand;
import app.resourcehub.access.business.structs.UserManagement;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.platform.business.structs.Organization;
import app.resourcehub.reservations.api.A;
import app.resourcehub.reservations.business.structs.CreateReservationCommand;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationStatus;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.CreateResourceCommand;
import app.resourcehub.resources.business.structs.CreateResourceTypeCommand;
import app.resourcehub.resources.business.structs.PatchResourceStatusCommand;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.resources.business.structs.ResourcesBusinessException;
import app.resourcehub.shared.api.structs.PageResponse;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.ResourceNature;
import app.resourcehub.shared.business.structs.SharedDomainException;
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

@DisplayName("TT-024 [PRU-04..06 / PRU-08] — /console/users [Solo AD] y Sincronizacion RN-ACC-003 en PostgreSQL 16.6")
class ConsoleUsersAndPersonSyncPostgresIT {

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

    @BeforeAll
    static void startPostgresAndWireModules() throws SQLException {
        POSTGRES.start();
        provisionRoles(POSTGRES);
        migrateAllSchemas(POSTGRES);
        DataSource dbaApiDataSource = new SimpleDriverDataSource(POSTGRES.getJdbcUrl(), DBA_API_USER, DBA_API_PASS);
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
    @DisplayName("TT-024: CRUD /console/users, PII Vault DTD-005, revocacion refresh tokens y sincronizacion PERSON")
    void shouldManageConsoleUsersRevokeTokensAndSynchronizePersonResource() {
        Organization org = app.resourcehub.platform.business.B.organization()
            .resolveActiveOrgBySlug("resourcehub-demo");
        OrganizationContext orgContext = new OrganizationContext(
            org.id(),
            org.slug(),
            org.initialTimeZoneId()
        );

        // Seed AD inicial y login en /console/auth
        UserManagement adminUser = app.resourcehub.access.business.B.users().createConsoleUser(
            org.id(),
            new CreateConsoleUserCommand(
                UserRole.AD,
                "admin.tt024@demo.test",
                "Admin Principal",
                "+34 600000001",
                "AdminPass2026!".toCharArray(),
                null
            )
        );
        AuthTokenBundle adminTokens = app.resourcehub.access.api.A.consoleAuth().login(
            new LocalAuthCommand(org.id(), "admin.tt024@demo.test", "AdminPass2026!".toCharArray()),
            "req-ad-login",
            FIXED_CLOCK
        ).data();
        ActorContext adminActor = app.resourcehub.access.business.B.auth().validateToken(adminTokens.accessToken());

        // Alta de MG y US via /console/users [Solo AD]
        ResponseWrapper<UserManagement> mgCreated = app.resourcehub.access.api.A.consoleUsers().createUser(
            adminActor,
            new CreateConsoleUserCommand(
                UserRole.MG,
                "manager.tt024@demo.test",
                "Gestor Centro",
                "+34 600000002",
                "ManagerPass2026!".toCharArray(),
                null
            ),
            "req-create-mg",
            FIXED_CLOCK
        );
        assertThat(mgCreated.code()).isZero();
        assertThat(mgCreated.data().fullName()).isEqualTo("Gestor Centro");

        ActorContext managerActor = app.resourcehub.access.business.B.auth().validateToken(
            app.resourcehub.access.api.A.consoleAuth().login(
                new LocalAuthCommand(org.id(), "manager.tt024@demo.test", "ManagerPass2026!".toCharArray()),
                "req-mg-login",
                FIXED_CLOCK
            ).data().accessToken()
        );

        UserManagement clientUser = app.resourcehub.access.api.A.consoleUsers().createUser(
            adminActor,
            new CreateConsoleUserCommand(
                UserRole.US,
                "client.tt024@demo.test",
                "Cliente Reserva",
                null,
                "ClientPass2026!".toCharArray(),
                null
            ),
            "req-create-us",
            FIXED_CLOCK
        ).data();
        ActorContext clientActor = app.resourcehub.access.business.B.auth().validateToken(
            app.resourcehub.access.api.A.appAuth().login(
                new LocalAuthCommand(org.id(), "client.tt024@demo.test", "ClientPass2026!".toCharArray()),
                "req-us-login",
                FIXED_CLOCK
            ).data().accessToken()
        );

        // MG puede leer /console/users (O(1) Vault DTD-005), pero NO puede escribir ([Solo AD] -> 403 / 200004)
        PageResponse<UserManagement> listedByManager = app.resourcehub.access.api.A.consoleUsers()
            .listUsers(managerActor, 1, 10, "req-list-mg", FIXED_CLOCK)
            .data();
        assertThat(listedByManager.totalItems()).isEqualTo(3L);
        assertThat(listedByManager.items())
            .extracting(UserManagement::email)
            .containsExactlyInAnyOrder("admin.tt024@demo.test", "manager.tt024@demo.test", "client.tt024@demo.test");

        UserManagement fetchedByManager = app.resourcehub.access.api.A.consoleUsers()
            .getUser(managerActor, adminUser.id(), "req-get-mg", FIXED_CLOCK)
            .data();
        assertThat(fetchedByManager.fullName()).isEqualTo("Admin Principal");

        assertAccessProblem(
            () -> app.resourcehub.access.api.A.consoleUsers().createUser(
                managerActor,
                new CreateConsoleUserCommand(UserRole.US, "blocked@demo.test", "No Perm", null, null, null),
                "req-mg-denied",
                FIXED_CLOCK
            ),
            "/api/v1/console/users",
            "POST",
            "req-mg-denied",
            403,
            200004
        );
        assertAccessProblem(
            () -> app.resourcehub.access.api.A.consoleUsers().patchUser(
                managerActor,
                clientUser.id(),
                new PatchConsoleUserCommand(null, IdentityStatus.INACTIVE, null, false),
                "req-mg-patch-denied",
                FIXED_CLOCK
            ),
            "/api/v1/console/users/" + clientUser.id(),
            "PATCH",
            "req-mg-patch-denied",
            403,
            200004
        );

        // D-11: US no puede vincularse a PERSON
        UUID dummyPersonId = UUID.fromString("01936a00-0010-7000-8000-000000000909");
        assertAccessProblem(
            () -> app.resourcehub.access.api.A.consoleUsers().createUser(
                adminActor,
                new CreateConsoleUserCommand(UserRole.US, "d11@demo.test", "Usuario US", null, null, dummyPersonId),
                "req-d11",
                FIXED_CLOCK
            ),
            "/api/v1/console/users",
            "POST",
            "req-d11",
            400,
            100001
        );

        // Alta EM + recurso PERSON simetrico + horario + reserva CONFIRMED previa
        UserManagement emCreated = app.resourcehub.access.api.A.consoleUsers().createUser(
            adminActor,
            new CreateConsoleUserCommand(
                UserRole.EM,
                "pro.sync@demo.test",
                "Dra. Sofia Vega",
                "+34 611223344",
                "StaffPass2026!".toCharArray(),
                null
            ),
            "req-create-em",
            FIXED_CLOCK
        ).data();

        ResourceType personType = app.resourcehub.resources.api.A.consoleCatalog().createResourceType(
            adminActor,
            new CreateResourceTypeCommand("PHYSIO_PRO", "Fisioterapeuta", "Especialistas", ResourceNature.PERSON),
            "req-rt-person",
            FIXED_CLOCK
        ).data();

        CatalogResource personResource = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            adminActor,
            new CreateResourceCommand(
                personType.id(),
                "PRO-SVEGA",
                "Dra. Sofia Vega",
                "Fisioterapia deportiva",
                1,
                false,
                false,
                emCreated.id()
            ),
            "req-res-person",
            FIXED_CLOCK
        ).data();

        UserManagement emLinked = app.resourcehub.access.api.A.consoleUsers().patchUser(
            adminActor,
            emCreated.id(),
            new PatchConsoleUserCommand(null, null, personResource.id(), false),
            "req-link-em",
            FIXED_CLOCK
        ).data();
        assertThat(emLinked.linkedPersonResourceId()).isEqualTo(personResource.id());

        app.resourcehub.availability.api.A.consoleAvailability().replaceWeeklySchedule(
            adminActor,
            personResource.id(),
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.of(15, 0), null, null)),
            "req-sched-person",
            FIXED_CLOCK
        );

        // EM inicia sesion en AMB_01_CLIENT y obtiene refreshToken activo
        AuthTokenBundle emTokens = app.resourcehub.access.api.A.appAuth().login(
            new LocalAuthCommand(org.id(), "pro.sync@demo.test", "StaffPass2026!".toCharArray()),
            "req-em-login",
            FIXED_CLOCK
        ).data();

        Instant slot1Start = Instant.parse("2026-11-30T10:00:00Z");
        Instant slot1End = Instant.parse("2026-11-30T11:00:00Z");
        Reservation preExistingBooking = A.appReservations().createReservation(
            clientActor,
            new CreateReservationCommand(personResource.id(), slot1Start, slot1End, 1, null),
            orgContext,
            "req-book-before-disable",
            FIXED_CLOCK
        ).data();
        assertThat(preExistingBooking.status()).isEqualTo(ReservationStatus.CONFIRMED);

        // AD desactiva EM -> revoca refreshToken, sincroniza PERSON -> DISABLED y conserva reserva existente (D-3)
        UserManagement emDisabled = app.resourcehub.access.api.A.consoleUsers().patchUser(
            adminActor,
            emCreated.id(),
            new PatchConsoleUserCommand(null, IdentityStatus.INACTIVE, null, false),
            "req-disable-em",
            FIXED_CLOCK
        ).data();
        assertThat(emDisabled.status()).isEqualTo(IdentityStatus.INACTIVE);

        assertAccessProblem(
            () -> app.resourcehub.access.business.B.auth().refresh(
                org.id(),
                OperationalScope.AMB_01_CLIENT,
                emTokens.refreshToken()
            ),
            "/api/v1/app/auth/refresh",
            "POST",
            "req-em-refresh-revoked",
            401,
            200001
        );

        CatalogResource syncedResource = app.resourcehub.resources.business.B.resources()
            .findById(personResource.id())
            .orElseThrow();
        assertThat(syncedResource.status()).isEqualTo(ResourceStatus.DISABLED);

        // RN-ACC-003: no se puede habilitar el recurso PERSON mientras EM siga INACTIVE
        assertResourceProblem(
            () -> app.resourcehub.resources.api.A.consoleCatalog().patchResourceStatus(
                adminActor,
                personResource.id(),
                new PatchResourceStatusCommand(ResourceStatus.AVAILABLE),
                "req-enable-blocked",
                FIXED_CLOCK
            ),
            "/api/v1/console/resources/" + personResource.id() + "/status",
            "PATCH",
            "req-enable-blocked",
            409,
            400002
        );

        // Nuevas reservas quedan bloqueadas con 422 / 500301, mientras la preexistente sigue CONFIRMED (D-3)
        Instant slot2Start = Instant.parse("2026-11-30T11:00:00Z");
        Instant slot2End = Instant.parse("2026-11-30T12:00:00Z");
        assertReservationProblem(
            () -> A.appReservations().createReservation(
                clientActor,
                new CreateReservationCommand(personResource.id(), slot2Start, slot2End, 1, null),
                orgContext,
                "req-book-disabled",
                FIXED_CLOCK
            ),
            "/api/v1/app/reservations",
            "POST",
            "req-book-disabled",
            422,
            500301
        );

        Reservation existingStillConfirmed = A.appReservations().getOwnReservation(
            clientActor,
            preExistingBooking.id(),
            "req-check-d3",
            FIXED_CLOCK
        ).data();
        assertThat(existingStillConfirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);

        // Reactivando a EM vuelve a permitir habilitar PERSON -> AVAILABLE y aceptar reservas
        app.resourcehub.access.api.A.consoleUsers().patchUser(
            adminActor,
            emCreated.id(),
            new PatchConsoleUserCommand(null, IdentityStatus.ACTIVE, null, false),
            "req-reactivate-em",
            FIXED_CLOCK
        );
        CatalogResource reEnabled = app.resourcehub.resources.api.A.consoleCatalog().patchResourceStatus(
            adminActor,
            personResource.id(),
            new PatchResourceStatusCommand(ResourceStatus.AVAILABLE),
            "req-enable-ok",
            FIXED_CLOCK
        ).data();
        assertThat(reEnabled.status()).isEqualTo(ResourceStatus.AVAILABLE);

        Reservation bookedAfterRecovery = A.appReservations().createReservation(
            clientActor,
            new CreateReservationCommand(personResource.id(), slot2Start, slot2End, 1, null),
            orgContext,
            "req-book-after-recover",
            FIXED_CLOCK
        ).data();
        assertThat(bookedAfterRecovery.status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    private static void assertAccessProblem(
        Runnable action,
        String path,
        String method,
        String requestId,
        int expectedHttp,
        int expectedCode
    ) {
        AccessBusinessException ex = catchThrowableOfType(AccessBusinessException.class, action::run);
        assertThat(ex).isNotNull();
        GatewayResponse<Void> gw = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest(DEMO_HOST, path, method, Map.of("X-Request-Id", requestId), null),
            app.resourcehub.access.api.A.consoleUsers().toInternalProblem(ex, path, requestId)
        );
        assertThat(gw.httpStatus()).isEqualTo(expectedHttp);
        assertThat(gw.body().code()).isEqualTo(expectedCode);
    }

    private static void assertResourceProblem(
        Runnable action,
        String path,
        String method,
        String requestId,
        int expectedHttp,
        int expectedCode
    ) {
        ResourcesBusinessException ex = catchThrowableOfType(ResourcesBusinessException.class, action::run);
        assertThat(ex).isNotNull();
        GatewayResponse<Void> gw = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest(DEMO_HOST, path, method, Map.of("X-Request-Id", requestId), null),
            app.resourcehub.resources.api.A.consoleCatalog().toInternalProblem(ex, path, requestId)
        );
        assertThat(gw.httpStatus()).isEqualTo(expectedHttp);
        assertThat(gw.body().code()).isEqualTo(expectedCode);
    }

    private static void assertReservationProblem(
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
        app.resourcehub.access.XF.init(
            ds,
            FIXED_CLOCK,
            orgId -> Boolean.FALSE,
            event -> app.resourcehub.resources.business.B.resources()
                .onUserPersonLinkDisabled(event.userId(), event.linkedPersonResourceId())
        );
        app.resourcehub.resources.XF.init(
            ds,
            FIXED_CLOCK,
            userId -> app.resourcehub.access.business.B.users().isUserActive(userId)
        );
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
