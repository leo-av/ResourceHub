package app.resourcehub.reservations;

import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.CreateConsoleUserCommand;
import app.resourcehub.access.business.structs.LocalAuthCommand;
import app.resourcehub.access.business.structs.LogoutCommand;
import app.resourcehub.access.business.structs.PatchConsoleUserCommand;
import app.resourcehub.access.business.structs.RefreshTokenCommand;
import app.resourcehub.access.business.structs.UserManagement;
import app.resourcehub.access.business.structs.UserProfile;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.platform.business.structs.Organization;
import app.resourcehub.reservations.api.A;
import app.resourcehub.reservations.business.structs.AgendaSessionItem;
import app.resourcehub.reservations.business.structs.CreateReservationCommand;
import app.resourcehub.reservations.business.structs.ProfessionalAgenda;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationsBusinessException;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.CreateResourceCommand;
import app.resourcehub.resources.business.structs.CreateResourceTypeCommand;
import app.resourcehub.resources.business.structs.PatchResourceStatusCommand;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
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

@DisplayName("TT-025 [PRU-04..07] — Canal /workspace/auth/** (AMB_02) y /workspace/agenda en PostgreSQL 16.6")
class WorkspaceAuthAndAgendaPostgresIT {

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
    @DisplayName("TT-025 PRU-SEG-05: Login /workspace/auth, agenda profesional propia y bloqueo si PERSON DISABLED")
    void shouldAuthenticateWorkspaceChannelReadAgendaAndBlockDisabledOrUnlinkedAccounts() {
        Organization org = app.resourcehub.platform.business.B.organization()
            .resolveActiveOrgBySlug("resourcehub-demo");
        OrganizationContext orgContext = new OrganizationContext(
            org.id(),
            org.slug(),
            org.initialTimeZoneId()
        );

        // AD inicial y actor de consola
        app.resourcehub.access.business.B.users().createConsoleUser(
            org.id(),
            new CreateConsoleUserCommand(
                UserRole.AD,
                "admin.tt025@demo.test",
                "Admin TT025",
                null,
                "AdminPass2026!".toCharArray(),
                null
            )
        );
        ActorContext adminActor = app.resourcehub.access.business.B.auth().validateToken(
            app.resourcehub.access.api.A.consoleAuth().login(
                new LocalAuthCommand(org.id(), "admin.tt025@demo.test", "AdminPass2026!".toCharArray()),
                "req-ad-login",
                FIXED_CLOCK
            ).data().accessToken()
        );

        // Cliente US, EM sin recurso PERSON y dos EM con recurso PERSON
        UserManagement clientUser = app.resourcehub.access.api.A.consoleUsers().createUser(
            adminActor,
            new CreateConsoleUserCommand(
                UserRole.US, "client.ws@demo.test", "Cliente WS", null, "ClientPass2026!".toCharArray(), null
            ),
            "req-create-us",
            FIXED_CLOCK
        ).data();
        ActorContext clientActor = app.resourcehub.access.business.B.auth().validateToken(
            app.resourcehub.access.api.A.appAuth().login(
                new LocalAuthCommand(org.id(), "client.ws@demo.test", "ClientPass2026!".toCharArray()),
                "req-us-login",
                FIXED_CLOCK
            ).data().accessToken()
        );

        app.resourcehub.access.api.A.consoleUsers().createUser(
            adminActor,
            new CreateConsoleUserCommand(
                UserRole.EM, "unlinked.ws@demo.test", "Sin Recurso", null, "StaffPass2026!".toCharArray(), null
            ),
            "req-create-unlinked",
            FIXED_CLOCK
        );

        // PRU-SEG-05: US y EM sin PERSON -> 403 / 200003 en /workspace/auth/login
        assertWorkspaceAuthProblem(
            () -> app.resourcehub.access.api.A.workspaceAuth().login(
                new LocalAuthCommand(org.id(), "client.ws@demo.test", "ClientPass2026!".toCharArray()),
                "req-seg05-us",
                FIXED_CLOCK
            ),
            "/api/v1/workspace/auth/login",
            "POST",
            "req-seg05-us",
            403,
            200003
        );
        assertWorkspaceAuthProblem(
            () -> app.resourcehub.access.api.A.workspaceAuth().login(
                new LocalAuthCommand(org.id(), "unlinked.ws@demo.test", "StaffPass2026!".toCharArray()),
                "req-seg05-unlinked",
                FIXED_CLOCK
            ),
            "/api/v1/workspace/auth/login",
            "POST",
            "req-seg05-unlinked",
            403,
            200003
        );

        // Alta EM1 + EM2 con recursos PERSON y horarios semanales
        UserManagement em1 = app.resourcehub.access.api.A.consoleUsers().createUser(
            adminActor,
            new CreateConsoleUserCommand(
                UserRole.EM, "pro1.ws@demo.test", "Dr. Lucas Alba", null, "StaffPass2026!".toCharArray(), null
            ),
            "req-create-em1",
            FIXED_CLOCK
        ).data();
        UserManagement em2 = app.resourcehub.access.api.A.consoleUsers().createUser(
            adminActor,
            new CreateConsoleUserCommand(
                UserRole.EM, "pro2.ws@demo.test", "Dra. Nora Gil", null, "StaffPass2026!".toCharArray(), null
            ),
            "req-create-em2",
            FIXED_CLOCK
        ).data();

        ResourceType personType = app.resourcehub.resources.api.A.consoleCatalog().createResourceType(
            adminActor,
            new CreateResourceTypeCommand("SPEC_PRO", "Especialista", "Profesionales", ResourceNature.PERSON),
            "req-rt-spec",
            FIXED_CLOCK
        ).data();

        CatalogResource pro1Resource = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            adminActor,
            new CreateResourceCommand(
                personType.id(), "PRO-LALBA", "Dr. Lucas Alba", "Consulta 1", 1, false, false, em1.id()
            ),
            "req-res-pro1",
            FIXED_CLOCK
        ).data();
        CatalogResource pro2Resource = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            adminActor,
            new CreateResourceCommand(
                personType.id(), "PRO-NGIL", "Dra. Nora Gil", "Consulta 2", 1, false, false, em2.id()
            ),
            "req-res-pro2",
            FIXED_CLOCK
        ).data();

        app.resourcehub.access.api.A.consoleUsers().patchUser(
            adminActor, em1.id(), new PatchConsoleUserCommand(null, null, pro1Resource.id(), false), "req-lk1", FIXED_CLOCK
        );
        app.resourcehub.access.api.A.consoleUsers().patchUser(
            adminActor, em2.id(), new PatchConsoleUserCommand(null, null, pro2Resource.id(), false), "req-lk2", FIXED_CLOCK
        );

        app.resourcehub.availability.api.A.consoleAvailability().replaceWeeklySchedule(
            adminActor,
            pro1Resource.id(),
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.of(16, 0), null, null)),
            "req-sch1",
            FIXED_CLOCK
        );
        app.resourcehub.availability.api.A.consoleAvailability().replaceWeeklySchedule(
            adminActor,
            pro2Resource.id(),
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.of(16, 0), null, null)),
            "req-sch2",
            FIXED_CLOCK
        );

        // Reservas en PRO1 (2 activas + 1 cancelada) y PRO2 (1 activa)
        Instant t09 = Instant.parse("2026-11-30T08:00:00Z");
        Instant t10 = Instant.parse("2026-11-30T09:00:00Z");
        Instant t11 = Instant.parse("2026-11-30T10:00:00Z");
        Instant t12 = Instant.parse("2026-11-30T11:00:00Z");

        Reservation res1Late = A.appReservations().createReservation(
            clientActor, new CreateReservationCommand(pro1Resource.id(), t11, t12, 1, null), orgContext, "b-1", FIXED_CLOCK
        ).data();
        Reservation res1Can = A.appReservations().createReservation(
            clientActor, new CreateReservationCommand(pro1Resource.id(), t09, t10, 1, null), orgContext, "b-2", FIXED_CLOCK
        ).data();
        A.appReservations().cancelReservation(clientActor, res1Can.id(), "b-2-cancel", FIXED_CLOCK);
        Reservation res1Early = A.appReservations().createReservation(
            clientActor, new CreateReservationCommand(pro1Resource.id(), t10, t11, 1, null), orgContext, "b-3", FIXED_CLOCK
        ).data();
        A.appReservations().createReservation(
            clientActor, new CreateReservationCommand(pro2Resource.id(), t10, t11, 1, null), orgContext, "b-4", FIXED_CLOCK
        );

        // EM1 inicia sesion en /workspace/auth/login -> AMB_02_PROFESSIONAL con lpr = pro1Resource.id()
        ResponseWrapper<AuthTokenBundle> pro1Login = app.resourcehub.access.api.A.workspaceAuth().login(
            new LocalAuthCommand(org.id(), "pro1.ws@demo.test", "StaffPass2026!".toCharArray()),
            "req-ws-login-1",
            FIXED_CLOCK
        );
        assertThat(pro1Login.code()).isZero();
        assertThat(pro1Login.data().operationalScope()).isEqualTo(OperationalScope.AMB_02_PROFESSIONAL);
        ActorContext pro1Actor = app.resourcehub.access.business.B.auth()
            .validateToken(pro1Login.data().accessToken());
        assertThat(pro1Actor.linkedPersonResourceId()).isEqualTo(pro1Resource.id());

        ResponseWrapper<UserProfile> meWrap = app.resourcehub.access.api.A.workspaceAuth()
            .me(pro1Login.data().accessToken(), "req-ws-me", FIXED_CLOCK);
        assertThat(meWrap.data().linkedPersonResourceId()).isEqualTo(pro1Resource.id());

        // Cruce de ambito: token de /workspace rechazado en /console/auth/me y /app/auth/me (403 / 200003)
        assertWorkspaceAuthProblem(
            () -> app.resourcehub.access.api.A.appAuth().me(pro1Login.data().accessToken(), "req-xscope-1", FIXED_CLOCK),
            "/api/v1/app/auth/me",
            "GET",
            "req-xscope-1",
            403,
            200003
        );
        assertWorkspaceAuthProblem(
            () -> app.resourcehub.access.api.A.consoleAuth()
                .me(pro1Login.data().accessToken(), "req-xscope-2", FIXED_CLOCK),
            "/api/v1/console/auth/me",
            "GET",
            "req-xscope-2",
            403,
            200003
        );

        // GET /api/v1/workspace/agenda devuelve exclusivamente sesiones activas de pro1 ordenadas por hora
        ResponseWrapper<ProfessionalAgenda> fullAgendaWrap = A.workspaceAgenda()
            .getAgenda(pro1Actor, null, null, "req-agenda-all", FIXED_CLOCK);
        GatewayResponse<ProfessionalAgenda> gwAgenda = app.resourcehub.gateway.api.A.edgeGateway().handleResponse(
            new GatewayRequest(DEMO_HOST, "/api/v1/workspace/agenda", "GET",
                Map.of("X-Request-Id", "req-agenda-all"), null),
            200,
            fullAgendaWrap
        );
        assertThat(gwAgenda.httpStatus()).isEqualTo(200);
        assertThat(gwAgenda.body().code()).isZero();
        assertThat(gwAgenda.body().data().personResourceId()).isEqualTo(pro1Resource.id());
        assertThat(gwAgenda.body().data().items())
            .extracting(AgendaSessionItem::reservationId)
            .containsExactly(res1Early.id(), res1Late.id());
        assertThat(gwAgenda.body().data().items())
            .extracting(AgendaSessionItem::holderId)
            .containsOnly(clientUser.id());

        // Filtro por rango [t09, t11)
        ProfessionalAgenda filteredAgenda = A.workspaceAgenda()
            .getAgenda(pro1Actor, t09, t11, "req-agenda-range", FIXED_CLOCK)
            .data();
        assertThat(filteredAgenda.items())
            .extracting(AgendaSessionItem::reservationId)
            .containsExactly(res1Early.id());

        // US o AD (/app o /console) no pueden consultar /workspace/agenda -> 403 / 200003
        assertAgendaProblem(
            () -> A.workspaceAgenda().getAgenda(clientActor, null, null, "req-ag-us", FIXED_CLOCK),
            "/api/v1/workspace/agenda",
            "GET",
            "req-ag-us",
            403,
            200003
        );
        assertAgendaProblem(
            () -> A.workspaceAgenda().getAgenda(adminActor, null, null, "req-ag-ad", FIXED_CLOCK),
            "/api/v1/workspace/agenda",
            "GET",
            "req-ag-ad",
            403,
            200003
        );

        // Deshabilitar recurso PERSON pro1 -> bloquea refresh, /workspace/auth/login, /me y /workspace/agenda
        app.resourcehub.resources.api.A.consoleCatalog().patchResourceStatus(
            adminActor,
            pro1Resource.id(),
            new PatchResourceStatusCommand(ResourceStatus.DISABLED),
            "req-disable-pro1-res",
            FIXED_CLOCK
        );

        assertWorkspaceAuthProblem(
            () -> app.resourcehub.access.api.A.workspaceAuth().refresh(
                new RefreshTokenCommand(org.id(), pro1Login.data().refreshToken()),
                "req-ws-refresh-disabled",
                FIXED_CLOCK
            ),
            "/api/v1/workspace/auth/refresh",
            "POST",
            "req-ws-refresh-disabled",
            403,
            200003
        );
        assertWorkspaceAuthProblem(
            () -> app.resourcehub.access.api.A.workspaceAuth().login(
                new LocalAuthCommand(org.id(), "pro1.ws@demo.test", "StaffPass2026!".toCharArray()),
                "req-ws-login-disabled",
                FIXED_CLOCK
            ),
            "/api/v1/workspace/auth/login",
            "POST",
            "req-ws-login-disabled",
            403,
            200003
        );
        assertAgendaProblem(
            () -> A.workspaceAgenda().getAgenda(pro1Actor, null, null, "req-ag-disabled", FIXED_CLOCK),
            "/api/v1/workspace/agenda",
            "GET",
            "req-ag-disabled",
            403,
            200003
        );

        // Rehabilitar pro1Resource -> vuelve a permitir login, refresh y logout en /workspace/auth
        app.resourcehub.resources.api.A.consoleCatalog().patchResourceStatus(
            adminActor,
            pro1Resource.id(),
            new PatchResourceStatusCommand(ResourceStatus.AVAILABLE),
            "req-enable-pro1-res",
            FIXED_CLOCK
        );
        ResponseWrapper<AuthTokenBundle> recovered = app.resourcehub.access.api.A.workspaceAuth().login(
            new LocalAuthCommand(org.id(), "pro1.ws@demo.test", "StaffPass2026!".toCharArray()),
            "req-ws-recover",
            FIXED_CLOCK
        );
        assertThat(recovered.code()).isZero();
        ResponseWrapper<Void> logoutWrap = app.resourcehub.access.api.A.workspaceAuth().logout(
            new LogoutCommand(recovered.data().accessToken(), recovered.data().refreshToken()),
            "req-ws-logout",
            FIXED_CLOCK
        );
        assertThat(logoutWrap.code()).isZero();
    }

    private static void assertWorkspaceAuthProblem(
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
            app.resourcehub.access.api.A.workspaceAuth().toInternalProblem(ex, path, requestId)
        );
        assertThat(gw.httpStatus()).isEqualTo(expectedHttp);
        assertThat(gw.body().code()).isEqualTo(expectedCode);
    }

    private static void assertAgendaProblem(
        Runnable action,
        String path,
        String method,
        String requestId,
        int expectedHttp,
        int expectedCode
    ) {
        ReservationsBusinessException ex = catchThrowableOfType(ReservationsBusinessException.class, action::run);
        assertThat(ex).isNotNull();
        GatewayResponse<Void> gw = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest(DEMO_HOST, path, method, Map.of("X-Request-Id", requestId), null),
            A.workspaceAgenda().toInternalProblem(ex, path, requestId)
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
                .onUserPersonLinkDisabled(event.userId(), event.linkedPersonResourceId()),
            personResourceId -> app.resourcehub.resources.business.B.resources()
                .findById(personResourceId)
                .map(res -> res.status() == ResourceStatus.AVAILABLE)
                .orElse(false)
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
