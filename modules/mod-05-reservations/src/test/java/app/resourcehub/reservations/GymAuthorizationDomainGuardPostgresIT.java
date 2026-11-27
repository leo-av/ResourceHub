package app.resourcehub.reservations;

import app.resourcehub.access.business.structs.CreateConsoleUserCommand;
import app.resourcehub.access.business.structs.LocalAuthCommand;
import app.resourcehub.access.business.structs.PatchConsoleUserCommand;
import app.resourcehub.access.business.structs.UserManagement;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.availability.business.structs.CreateTemporalBlockCommand;
import app.resourcehub.availability.business.structs.TemporalBlock;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.gateway.business.structs.GatewaySecurityAudit;
import app.resourcehub.platform.business.structs.Organization;
import app.resourcehub.policies.business.structs.PoliciesBusinessException;
import app.resourcehub.reservations.business.structs.CreateReservationCommand;
import app.resourcehub.reservations.business.structs.ModifyReservationCommand;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationStatus;
import app.resourcehub.reservations.business.structs.ReservationsBusinessException;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.CreateResourceCommand;
import app.resourcehub.resources.business.structs.CreateResourceTypeCommand;
import app.resourcehub.resources.business.structs.PatchResourceStatusCommand;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.shared.api.structs.PageResponse;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.ResourceNature;
import app.resourcehub.shared.business.structs.SharedDomainException;
import app.resourcehub.traceability.business.structs.AuditLogEntry;
import app.resourcehub.traceability.business.structs.AuditOutcome;
import app.resourcehub.traceability.business.structs.HistoryChangeType;
import app.resourcehub.traceability.business.structs.RecordAuditCommand;
import app.resourcehub.traceability.business.structs.ReservationHistoryEntry;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("TT-027 [PRU-03 / PRU-05 / PRU-06 / PRU-07 / AC-021] — Escenario Gimnasio y Guardia MOD-02..07 en PG 16.6")
class GymAuthorizationDomainGuardPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T08:00:00Z"), ZoneOffset.UTC);

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
    @DisplayName("AC-021 completo: GM1 WebCliente vs BackOffice, EM2 bloqueos/sesiones propias vs EM1/sala, y MG vs [Solo AD]")
    void shouldEnforceGymScenarioAc021AndManagerVsAdminAcrossModulesInPostgres() {
        Organization org = app.resourcehub.platform.business.B.organization()
            .resolveActiveOrgBySlug("resourcehub-demo");
        OrganizationContext orgCtx = org.toContext();

        // Provisionar AD, GM1 (MG + PERSON), EM2 (EM + PERSON) y US2
        app.resourcehub.access.business.B.users().createConsoleUser(
            org.id(),
            new CreateConsoleUserCommand(
                UserRole.AD, "ad.gym@demo.test", "Admin Gym", null, "AdminPass2026!".toCharArray(), null
            )
        );
        ActorContext adConsole = app.resourcehub.access.business.B.auth().validateToken(
            app.resourcehub.access.api.A.consoleAuth().login(
                new LocalAuthCommand(org.id(), "ad.gym@demo.test", "AdminPass2026!".toCharArray()),
                "req-gym-ad-login",
                FIXED_CLOCK
            ).data().accessToken()
        );

        UserManagement gm1User = app.resourcehub.access.api.A.consoleUsers().createUser(
            adConsole,
            new CreateConsoleUserCommand(
                UserRole.MG, "gm1.gym@demo.test", "Gerente Entrenador", null, "GmPass2026!".toCharArray(), null
            ),
            "req-gym-gm1-create",
            FIXED_CLOCK
        ).data();
        UserManagement em2User = app.resourcehub.access.api.A.consoleUsers().createUser(
            adConsole,
            new CreateConsoleUserCommand(
                UserRole.EM, "em2.gym@demo.test", "Entrenador Dos", null, "Em2Pass2026!".toCharArray(), null
            ),
            "req-gym-em2-create",
            FIXED_CLOCK
        ).data();
        app.resourcehub.access.api.A.consoleUsers().createUser(
            adConsole,
            new CreateConsoleUserCommand(
                UserRole.US, "us2.gym@demo.test", "Socia Dos", null, "Us2Pass2026!".toCharArray(), null
            ),
            "req-gym-us2-create",
            FIXED_CLOCK
        );

        // Crear tipos SPACE/PERSON, sala y recursos vinculados para GM1 (EM1) y EM2
        ResourceType spaceType = app.resourcehub.resources.api.A.consoleCatalog().createResourceType(
            adConsole,
            new CreateResourceTypeCommand("GYM_ROOM", "Sala Gimnasio", "Salas", ResourceNature.SPACE),
            "req-gym-type-space",
            FIXED_CLOCK
        ).data();
        ResourceType trainerType = app.resourcehub.resources.api.A.consoleCatalog().createResourceType(
            adConsole,
            new CreateResourceTypeCommand("TRAINER", "Entrenador", "Personal", ResourceNature.PERSON),
            "req-gym-type-person",
            FIXED_CLOCK
        ).data();

        CatalogResource gymRoom = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            adConsole,
            new CreateResourceCommand(spaceType.id(), "SALA-1", "Sala 1", "Sala principal", 1, false, false, null),
            "req-gym-room",
            FIXED_CLOCK
        ).data();
        CatalogResource em1Person = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            adConsole,
            new CreateResourceCommand(
                trainerType.id(), "TRAINER-GM1", "Coach GM1", "Coach", 1, false, false, gm1User.id()
            ),
            "req-gym-p1",
            FIXED_CLOCK
        ).data();
        CatalogResource em2Person = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            adConsole,
            new CreateResourceCommand(
                trainerType.id(), "TRAINER-EM2", "Coach EM2", "Coach", 1, false, false, em2User.id()
            ),
            "req-gym-p2",
            FIXED_CLOCK
        ).data();

        app.resourcehub.access.api.A.consoleUsers().patchUser(
            adConsole,
            gm1User.id(),
            new PatchConsoleUserCommand(null, null, em1Person.id(), false),
            "req-gym-link-gm1",
            FIXED_CLOCK
        );
        app.resourcehub.access.api.A.consoleUsers().patchUser(
            adConsole,
            em2User.id(),
            new PatchConsoleUserCommand(null, null, em2Person.id(), false),
            "req-gym-link-em2",
            FIXED_CLOCK
        );

        for (CatalogResource res : List.of(gymRoom, em1Person, em2Person)) {
            app.resourcehub.resources.api.A.consoleCatalog().patchResourceStatus(
                adConsole, res.id(), new PatchResourceStatusCommand(ResourceStatus.AVAILABLE), "req-act", FIXED_CLOCK
            );
            app.resourcehub.availability.api.A.consoleAvailability().replaceWeeklySchedule(
                adConsole,
                res.id(),
                List.of(new WeeklyScheduleEntry(1, LocalTime.of(8, 0), LocalTime.of(20, 0), null, null)),
                "req-sched",
                FIXED_CLOCK
            );
        }

        // Logins reales en AMB-01 (US2, GM1), AMB-02 (EM2), AMB-03 (GM1)
        ActorContext us2App = app.resourcehub.access.business.B.auth().validateToken(
            app.resourcehub.access.api.A.appAuth().login(
                new LocalAuthCommand(org.id(), "us2.gym@demo.test", "Us2Pass2026!".toCharArray()),
                "req-us2-app", FIXED_CLOCK
            ).data().accessToken()
        );
        ActorContext gm1App = app.resourcehub.access.business.B.auth().validateToken(
            app.resourcehub.access.api.A.appAuth().login(
                new LocalAuthCommand(org.id(), "gm1.gym@demo.test", "GmPass2026!".toCharArray()),
                "req-gm1-app", FIXED_CLOCK
            ).data().accessToken()
        );
        ActorContext gm1Console = app.resourcehub.access.business.B.auth().validateToken(
            app.resourcehub.access.api.A.consoleAuth().login(
                new LocalAuthCommand(org.id(), "gm1.gym@demo.test", "GmPass2026!".toCharArray()),
                "req-gm1-console", FIXED_CLOCK
            ).data().accessToken()
        );
        ActorContext em2Workspace = app.resourcehub.access.business.B.auth().validateToken(
            app.resourcehub.access.api.A.workspaceAuth().login(
                new LocalAuthCommand(org.id(), "em2.gym@demo.test", "Em2Pass2026!".toCharArray()),
                "req-em2-ws", FIXED_CLOCK
            ).data().accessToken()
        );

        // US2 reserva sesión con Coach GM1 (EM1) en lunes 2026-11-30 10:00-11:00 UTC
        Instant s1 = Instant.parse("2026-11-30T10:00:00Z");
        Instant e1 = Instant.parse("2026-11-30T11:00:00Z");
        Instant s2 = Instant.parse("2026-11-30T12:00:00Z");
        Instant e2 = Instant.parse("2026-11-30T13:00:00Z");
        Reservation us2Booking = app.resourcehub.reservations.api.A.appReservations().createReservation(
            us2App,
            new CreateReservationCommand(em1Person.id(), s1, e1, 1, null),
            orgCtx,
            "req-us2-book",
            FIXED_CLOCK
        ).data();

        // AC-021(a): GM1 en WebCliente (AMB-01) NO puede crear, modificar ni cancelar la reserva de US2 -> 403 NO_PERMISSION (200004)
        ReservationsBusinessException appCreateOther = catchThrowableOfType(
            () -> app.resourcehub.reservations.api.A.appReservations().createReservation(
                gm1App,
                new CreateReservationCommand(em1Person.id(), s2, e2, 1, us2Booking.holderId()),
                orgCtx,
                "req-gm1-app-create-other",
                FIXED_CLOCK
            ),
            ReservationsBusinessException.class
        );
        assertThat(appCreateOther.getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);
        assertThat(app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest(
                "resourcehub-demo.resourcehub.app",
                "/api/v1/app/reservations",
                "POST",
                Map.of("X-Request-Id", "req-gm1-app-create-other"),
                null
            ),
            app.resourcehub.reservations.api.A.appReservations().toInternalProblem(
                appCreateOther, "/api/v1/app/reservations", "req-gm1-app-create-other"
            )
        ).body().code()).isEqualTo(200004);

        ReservationsBusinessException appModifyOther = catchThrowableOfType(
            () -> app.resourcehub.reservations.api.A.appReservations().modifyReservation(
                gm1App,
                us2Booking.id(),
                new ModifyReservationCommand(em1Person.id(), s2, e2, 1),
                orgCtx,
                "req-gm1-app-mod-other",
                FIXED_CLOCK
            ),
            ReservationsBusinessException.class
        );
        assertThat(appModifyOther.getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);

        ReservationsBusinessException appCancelOther = catchThrowableOfType(
            () -> app.resourcehub.reservations.api.A.appReservations().cancelReservation(
                gm1App, us2Booking.id(), "req-gm1-app-cancel-other", FIXED_CLOCK
            ),
            ReservationsBusinessException.class
        );
        assertThat(appCancelOther.getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);

        // Verificar que la reserva de US2 sigue intacta (CONFIRMED v1)
        assertThat(app.resourcehub.reservations.api.A.appReservations().getOwnReservation(
            us2App, us2Booking.id(), "req-us2-check", FIXED_CLOCK
        ).data().version()).isEqualTo(1);

        // AC-021(a): El mismo GM1 desde BackOffice (AMB-03) SÍ puede modificar y cancelar la reserva de US2
        ResponseWrapper<Reservation> modFromConsole =
            app.resourcehub.reservations.api.A.consoleReservations().modifyReservation(
                gm1Console,
                us2Booking.id(),
                new ModifyReservationCommand(em1Person.id(), s2, e2, 1),
                orgCtx,
                "req-gm1-console-mod",
                FIXED_CLOCK
            );
        assertThat(modFromConsole.data().version()).isEqualTo(2);
        assertThat(modFromConsole.data().lines().getFirst().startAt()).isEqualTo(s2);

        ResponseWrapper<Reservation> cancelFromConsole =
            app.resourcehub.reservations.api.A.consoleReservations().cancelReservation(
                gm1Console, us2Booking.id(), "req-gm1-console-cancel", FIXED_CLOCK
            );
        assertThat(cancelFromConsole.data().status()).isEqualTo(ReservationStatus.CANCELLED);

        List<ReservationHistoryEntry> history = app.resourcehub.traceability.api.A.appHistory()
            .getReservationHistory(us2App, us2Booking.id(), "req-hist", FIXED_CLOCK).data();
        assertThat(history).extracting(ReservationHistoryEntry::changeType)
            .containsExactly(HistoryChangeType.CREATED, HistoryChangeType.MODIFIED, HistoryChangeType.CANCELLED);

        // AC-021(b): EM2 en AMB-02 solo puede bloquear su propio recurso EM2 y acceder a sus propias sesiones
        TemporalBlock selfBlock = app.resourcehub.availability.api.A.consoleAvailability().registerWorkspaceSelfBlock(
            em2Workspace,
            em2Person.id(),
            new CreateTemporalBlockCommand(null, s1, e1, false, null, "Descanso medico EM2"),
            "req-em2-self-block",
            FIXED_CLOCK
        ).data();
        assertThat(selfBlock.resourceId()).isEqualTo(em2Person.id());
        assertThatCode(() -> app.resourcehub.reservations.business.B.reservations()
            .assertWorkspaceSessionAccess(em2Workspace, em2Person.id())).doesNotThrowAnyException();

        SharedDomainException em2BlockEm1 = catchThrowableOfType(
            () -> app.resourcehub.availability.api.A.consoleAvailability().registerWorkspaceSelfBlock(
                em2Workspace,
                em1Person.id(),
                new CreateTemporalBlockCommand(null, s1, e1, false, null, "Intento ajeno"),
                "req-em2-block-em1",
                FIXED_CLOCK
            ),
            SharedDomainException.class
        );
        assertThat(em2BlockEm1.getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);

        SharedDomainException em2BlockRoom = catchThrowableOfType(
            () -> app.resourcehub.availability.api.A.consoleAvailability().registerWorkspaceSelfBlock(
                em2Workspace,
                gymRoom.id(),
                new CreateTemporalBlockCommand(null, s1, e1, false, null, "Intento sala"),
                "req-em2-block-room",
                FIXED_CLOCK
            ),
            SharedDomainException.class
        );
        assertThat(em2BlockRoom.getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);

        SharedDomainException em2SessionEm1 = catchThrowableOfType(
            () -> app.resourcehub.reservations.business.B.reservations()
                .assertWorkspaceSessionAccess(em2Workspace, em1Person.id()),
            SharedDomainException.class
        );
        assertThat(em2SessionEm1.getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);

        // Matriz MG vs [Solo AD] entre MOD-02, MOD-03, MOD-06 y MOD-07
        assertThatCode(() -> app.resourcehub.access.api.A.consoleUsers()
            .listUsers(gm1Console, 1, 10, "req-mg-users-get", FIXED_CLOCK)).doesNotThrowAnyException();
        assertThat(catchThrowableOfType(
            () -> app.resourcehub.access.api.A.consoleUsers().createUser(
                gm1Console,
                new CreateConsoleUserCommand(
                    UserRole.US, "blocked@demo.test", "No", null, "Pass2026!".toCharArray(), null
                ),
                "req-mg-user-write",
                FIXED_CLOCK
            ),
            SharedDomainException.class
        ).getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);

        assertThatCode(() -> app.resourcehub.resources.api.A.consoleCatalog()
            .listResourceTypes(gm1Console, "req-mg-cat", FIXED_CLOCK)).doesNotThrowAnyException();
        assertThatCode(() -> app.resourcehub.policies.api.A.consolePolicy()
            .assertPolicyReadAccess(gm1Console, "req-mg-pol-read", FIXED_CLOCK)).doesNotThrowAnyException();
        assertThat(catchThrowableOfType(
            () -> app.resourcehub.policies.api.A.consolePolicy()
                .assertPolicyMutationAccess(gm1Console, "req-mg-pol-write", FIXED_CLOCK),
            PoliciesBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);
        assertThatCode(() -> app.resourcehub.policies.api.A.consolePolicy()
            .assertPolicyMutationAccess(adConsole, "req-ad-pol-write", FIXED_CLOCK)).doesNotThrowAnyException();

        PageResponse<AuditLogEntry> auditPage = app.resourcehub.traceability.api.A.consoleAudit().listAuditLogs(
            gm1Console, null, null, null, null, null, null, 1, 50, "req-mg-audit", FIXED_CLOCK
        ).data();
        assertThat(auditPage.items()).isNotEmpty();
    }

    private static void wireAllModules(DataSource ds) {
        terminateAllModules();
        app.resourcehub.platform.XF.init(ds);
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
        app.resourcehub.policies.XF.init();
        app.resourcehub.traceability.XF.init(ds, FIXED_CLOCK, ids -> Map.of());
        app.resourcehub.gateway.XF.init(
            () -> app.resourcehub.platform.business.B.errorCatalog().loadErrorCatalog(),
            FIXED_CLOCK,
            token -> app.resourcehub.access.business.B.auth().validateToken(token),
            personResourceId -> app.resourcehub.resources.business.B.resources()
                .findById(personResourceId)
                .map(res -> res.status() == ResourceStatus.AVAILABLE)
                .orElse(false),
            host -> true,
            GymAuthorizationDomainGuardPostgresIT::recordGatewayAudit
        );
        XF.init(ds, FIXED_CLOCK);
    }

    private static void recordGatewayAudit(GatewaySecurityAudit audit) {
        ActorType effectiveType = audit.actorType() != null && audit.actorType() != ActorType.PLATFORM_ADMIN
            ? audit.actorType()
            : ActorType.SYSTEM;
        String effectiveRole = audit.actorRole() != null ? audit.actorRole() : "ANONYMOUS";
        OperationalScope effectiveScope = audit.operationalScope() != null
            ? audit.operationalScope()
            : OperationalScope.SYSTEM;
        app.resourcehub.traceability.business.B.audit().recordAuditInAutonomousTx(
            new RecordAuditCommand(
                audit.requestId(),
                "GATEWAY_PERIMETER_GUARD",
                "GATEWAY_ROUTE",
                audit.organizationId(),
                effectiveType,
                audit.actorId(),
                null,
                effectiveRole,
                effectiveScope,
                AuditOutcome.REJECTED,
                audit.errorCode().name(),
                "DTD-001",
                "{\"method\":\"" + audit.method() + "\",\"path\":\"" + audit.path() + "\"}"
            )
        );
    }

    private static void terminateAllModules() {
        XF.terminate();
        app.resourcehub.gateway.XF.terminate();
        app.resourcehub.traceability.XF.terminate();
        app.resourcehub.policies.XF.terminate();
        app.resourcehub.availability.XF.terminate();
        app.resourcehub.resources.XF.terminate();
        app.resourcehub.access.XF.terminate();
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
