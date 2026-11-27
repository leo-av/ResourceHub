package app.resourcehub.reservations;

import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.IdentityAccount;
import app.resourcehub.access.business.structs.LocalAuthCommand;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.access.business.structs.UserSummary;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.platform.business.structs.Organization;
import app.resourcehub.reservations.api.A;
import app.resourcehub.reservations.business.structs.CreateReservationCommand;
import app.resourcehub.reservations.business.structs.ModifyReservationCommand;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationStatus;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.CreateResourceCommand;
import app.resourcehub.resources.business.structs.CreateResourceTypeCommand;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.PageResponse;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.ResourceNature;
import app.resourcehub.shared.business.structs.SharedDomainException;
import app.resourcehub.traceability.business.structs.AuditActorSummary;
import app.resourcehub.traceability.business.structs.AuditLogEntry;
import app.resourcehub.traceability.business.structs.AuditOutcome;
import app.resourcehub.traceability.business.structs.HistoryChangeType;
import app.resourcehub.traceability.business.structs.RecordAuditCommand;
import app.resourcehub.traceability.business.structs.ReservationHistoryEntry;
import app.resourcehub.traceability.business.structs.TraceabilityBusinessException;
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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
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

@DisplayName("TT-021 & TT-022 [PRU-04..07 / AC-008..010] — Trazabilidad Triple Aserción y APIs E2E en PostgreSQL")
class TraceabilityEndToEndPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final String DEMO_HOST = "resourcehub-demo.resourcehub.app";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T18:00:00Z"), ZoneOffset.UTC);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("resourcehub")
        .withUsername("postgres")
        .withPassword("postgres");

    private static DataSource dbaApiDataSource;

    @BeforeAll
    static void startPostgresAndWireModules() throws SQLException {
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
    @DisplayName("AC-008..010 & TT-022: Histórico append-only, auditoría REQUIRES_NEW y consulta hidratada O(1) RGPD")
    void shouldTraceLifecycleHistoryAutonomousRejectionsAndHydratedConsoleAuditLogs() throws SQLException {
        // 1. Resolver organización y aprovisionar cuentas + vault personal_data en Postgres
        Organization org = app.resourcehub.platform.business.B.organization()
            .resolveActiveOrgBySlug("resourcehub-demo");
        OrganizationContext orgCtx = new OrganizationContext(org.id(), org.slug(), org.initialTimeZoneId());

        IdentityAccount clientA = app.resourcehub.access.business.B.users().createIdentityAccount(UserRole.US, null);
        app.resourcehub.access.business.B.auth().registerPersonalProfile(
            clientA.id(), "ana.gomez@example.com", "Ana Gomez", null
        );
        app.resourcehub.access.business.B.auth().registerLocalCredential(
            org.id(), clientA.id(), "ana.gomez@example.com", "StrongPass!1".toCharArray()
        );

        IdentityAccount clientB = app.resourcehub.access.business.B.users().createIdentityAccount(UserRole.US, null);
        app.resourcehub.access.business.B.auth().registerPersonalProfile(
            clientB.id(), "carlos.ruiz@example.com", "Carlos Ruiz", null
        );
        app.resourcehub.access.business.B.auth().registerLocalCredential(
            org.id(), clientB.id(), "carlos.ruiz@example.com", "StrongPass!2".toCharArray()
        );

        IdentityAccount manager = app.resourcehub.access.business.B.users().createIdentityAccount(UserRole.MG, null);
        app.resourcehub.access.business.B.auth().registerPersonalProfile(
            manager.id(), "marta.manager@example.com", "Marta Manager", null
        );
        app.resourcehub.access.business.B.auth().registerLocalCredential(
            org.id(), manager.id(), "marta.manager@example.com", "StrongPass!3".toCharArray()
        );

        AuthTokenBundle clientATokens = app.resourcehub.access.api.A.appAuth().login(
            new LocalAuthCommand(org.id(), "ana.gomez@example.com", "StrongPass!1".toCharArray()),
            "req-login-a",
            FIXED_CLOCK
        ).data();
        AuthTokenBundle clientBTokens = app.resourcehub.access.api.A.appAuth().login(
            new LocalAuthCommand(org.id(), "carlos.ruiz@example.com", "StrongPass!2".toCharArray()),
            "req-login-b",
            FIXED_CLOCK
        ).data();
        AuthTokenBundle managerTokens = app.resourcehub.access.api.A.consoleAuth().login(
            new LocalAuthCommand(org.id(), "marta.manager@example.com", "StrongPass!3".toCharArray()),
            "req-login-mg",
            FIXED_CLOCK
        ).data();

        ActorContext actorA = app.resourcehub.access.business.B.auth().validateToken(clientATokens.accessToken());
        ActorContext actorB = app.resourcehub.access.business.B.auth().validateToken(clientBTokens.accessToken());
        ActorContext actorManager = app.resourcehub.access.business.B.auth().validateToken(managerTokens.accessToken());

        ResourceType roomType = app.resourcehub.resources.api.A.consoleCatalog().createResourceType(
            actorManager,
            new CreateResourceTypeCommand("ROOM_TRA", "Salas Traceability", "Espacios", ResourceNature.SPACE),
            "req-type-tra",
            FIXED_CLOCK
        ).data();
        CatalogResource resource = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            actorManager,
            new CreateResourceCommand(roomType.id(), "SALA-TRA", "Sala Trazabilidad", "Planta 1", 8, true, true, null),
            "req-res-tra",
            FIXED_CLOCK
        ).data();
        app.resourcehub.availability.api.A.consoleAvailability().replaceWeeklySchedule(
            actorManager,
            resource.id(),
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(8, 0), LocalTime.of(20, 0), null, null)),
            "req-sched-tra",
            FIXED_CLOCK
        );

        // 2. Ciclo completo AC-008: Crear -> Conflicto -> Modificar -> Cancelar con X-Request-Id explícitos
        Instant s1 = Instant.parse("2026-11-30T09:00:00Z");
        Instant e1 = Instant.parse("2026-11-30T10:00:00Z");
        Instant s2 = Instant.parse("2026-11-30T11:00:00Z");
        Instant e2 = Instant.parse("2026-11-30T12:00:00Z");

        Reservation created = A.appReservations().createReservation(
            actorA,
            new CreateReservationCommand(resource.id(), s1, e1, 1, null),
            orgCtx,
            "corr-create-001",
            FIXED_CLOCK
        ).data();

        // Reserva competidora de actorB para provocar BOOKING_CONFLICT posterior en modificación
        A.appReservations().createReservation(
            actorB,
            new CreateReservationCommand(resource.id(), s2, e2, 1, null),
            orgCtx,
            "corr-other-002",
            FIXED_CLOCK
        );

        // AC-009 + AC-010: Intento de modificación con conflicto GiST debe rechazar y auditar REJECTED
        Instant validNewStart = Instant.parse("2026-11-30T13:00:00Z");
        Instant validNewEnd = Instant.parse("2026-11-30T14:00:00Z");

        SharedDomainException conflict = catchThrowableOfType(
            () -> A.appReservations().modifyReservation(
                actorA,
                created.id(),
                new ModifyReservationCommand(resource.id(), s2, e2, 1),
                orgCtx,
                "corr-modify-conflict",
                FIXED_CLOCK
            ),
            SharedDomainException.class
        );
        assertThat(conflict.getErrorCode()).isEqualTo(ErrorCode.BOOKING_CONFLICT);

        // La reserva original sigue intacta en versión 1 y sin entrada errónea en histórico
        Reservation stillIntact = A.appReservations().getOwnReservation(
            actorA, created.id(), "corr-check", FIXED_CLOCK
        ).data();
        assertThat(stillIntact.version()).isEqualTo(1);
        assertThat(stillIntact.lines().getFirst().startAt()).isEqualTo(s1);

        // Modificación válida y cancelación final
        Reservation modified = A.appReservations().modifyReservation(
            actorA,
            created.id(),
            new ModifyReservationCommand(resource.id(), validNewStart, validNewEnd, 1),
            orgCtx,
            "corr-modify-ok",
            FIXED_CLOCK
        ).data();
        assertThat(modified.version()).isEqualTo(2);

        Reservation cancelled = A.appReservations().cancelReservation(
            actorA,
            created.id(),
            "corr-cancel-ok",
            FIXED_CLOCK
        ).data();
        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);

        // 3. TT-022 Endpoint GET /api/v1/app/reservations/{id}/history -> 3 entradas cronológicas sin PII
        ResponseWrapper<List<ReservationHistoryEntry>> historyResp = app.resourcehub.traceability.api.A.appHistory()
            .getReservationHistory(actorA, created.id(), "req-hist-001", FIXED_CLOCK);
        List<ReservationHistoryEntry> history = historyResp.data();
        assertThat(history).hasSize(3);
        assertThat(history.get(0).changeType()).isEqualTo(HistoryChangeType.CREATED);
        assertThat(history.get(0).correlationId()).isEqualTo("corr-create-001");
        assertThat(history.get(1).changeType()).isEqualTo(HistoryChangeType.MODIFIED);
        assertThat(history.get(1).snapshotBeforeJson()).contains(s1.toString());
        assertThat(history.get(1).snapshotAfterJson()).contains(validNewStart.toString());
        assertThat(history.get(1).correlationId()).isEqualTo("corr-modify-ok");
        assertThat(history.get(2).changeType()).isEqualTo(HistoryChangeType.CANCELLED);
        assertThat(history.get(2).newStatus()).isEqualTo("CANCELLED");
        assertThat(history.get(2).correlationId()).isEqualTo("corr-cancel-ok");
        for (ReservationHistoryEntry entry : history) {
            assertThat(entry.snapshotAfterJson())
                .doesNotContain("ana.gomez@example.com")
                .doesNotContain("Ana Gomez");
        }

        // Aislamiento titularidad AMB_01: actorB recibe NO_PERMISSION -> GW 403 / 200004
        TraceabilityBusinessException otherUserEx = catchThrowableOfType(
            () -> app.resourcehub.traceability.api.A.appHistory()
                .getReservationHistory(actorB, created.id(), "req-hist-unauth", FIXED_CLOCK),
            TraceabilityBusinessException.class
        );
        assertThat(otherUserEx.getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);
        InternalProblem noPermProblem = app.resourcehub.traceability.api.A.appHistory().toInternalProblem(
            otherUserEx, "/api/v1/app/reservations/" + created.id() + "/history", "req-hist-unauth"
        );
        GatewayResponse<Void> gw403 = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest(
                DEMO_HOST,
                "/api/v1/app/reservations/" + created.id() + "/history",
                "GET",
                Map.of("X-Request-Id", "req-hist-unauth"),
                null
            ),
            noPermProblem
        );
        assertThat(gw403.httpStatus()).isEqualTo(403);
        assertThat(gw403.body().code()).isEqualTo(200004);

        // 4. Registrar un evento de usuario eliminado en access.personal_data (RGPD) y consultar /console/audit-logs
        UUID deletedGdprUser = UUID.fromString("99999999-9999-7999-8999-999999999999");
        app.resourcehub.traceability.business.B.audit().recordAuditInAutonomousTx(new RecordAuditCommand(
            "corr-gdpr-user",
            "RESERVATION_MODIFY",
            "RESERVATION",
            created.id(),
            ActorType.USER,
            deletedGdprUser,
            deletedGdprUser,
            "US",
            OperationalScope.AMB_01_CLIENT,
            AuditOutcome.REJECTED,
            "VALIDATION_FAILED",
            "TEST-RGPD",
            "{\"error\":\"VALIDATION_FAILED\"}"
        ));

        ResponseWrapper<PageResponse<AuditLogEntry>> consolePage = app.resourcehub.traceability.api.A.consoleAudit()
            .listAuditLogs(
                actorManager,
                null,
                "RESERVATION_MODIFY",
                "REJECTED",
                null,
                null,
                null,
                1,
                20,
                "req-console-001",
                FIXED_CLOCK
            );
        assertThat(consolePage.data().items()).hasSize(2);
        Map<String, AuditLogEntry> byCorrelation = new HashMap<>();
        for (AuditLogEntry entry : consolePage.data().items()) {
            byCorrelation.put(entry.correlationId(), entry);
        }
        assertThat(byCorrelation.get("corr-modify-conflict").requesterSummary().displayName()).isEqualTo("Ana Gomez");
        assertThat(byCorrelation.get("corr-modify-conflict").rejectionCategory()).isEqualTo("BOOKING_CONFLICT");
        assertThat(byCorrelation.get("corr-gdpr-user").requesterSummary().displayName())
            .isEqualTo(AuditActorSummary.ANONYMIZED_LABEL);
        assertThat(byCorrelation.get("corr-gdpr-user").requesterSummary().anonymized()).isTrue();

        // Cliente AMB_01 intentando consultar /console/audit-logs -> GW 403 / 200003
        TraceabilityBusinessException scopeEx = catchThrowableOfType(
            () -> app.resourcehub.traceability.api.A.consoleAudit().listAuditLogs(
                actorA, null, null, null, null, null, null, 1, 10, "req-sec-001", FIXED_CLOCK
            ),
            TraceabilityBusinessException.class
        );
        assertThat(scopeEx.getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);
        GatewayResponse<Void> gwScopeDenied = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest(
                DEMO_HOST,
                "/api/v1/console/audit-logs",
                "GET",
                Map.of("X-Request-Id", "req-sec-001"),
                null
            ),
            app.resourcehub.traceability.api.A.consoleAudit().toInternalProblem(
                scopeEx, "/api/v1/console/audit-logs", "req-sec-001"
            )
        );
        assertThat(gwScopeDenied.httpStatus()).isEqualTo(403);
        assertThat(gwScopeDenied.body().code()).isEqualTo(200003);

        // Verificación física en Postgres de ausencia total de PII en traceability.*
        try (Connection conn = dbaApiDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM traceability.audit_log WHERE correlation_id = ?"
             )) {
            stmt.setString(1, "corr-modify-conflict");
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    private static void wireAllModules(DataSource dataSource) {
        terminateAllModules();
        app.resourcehub.platform.XF.init(dataSource);
        app.resourcehub.gateway.XF.init(FIXED_CLOCK);
        app.resourcehub.access.XF.init(dataSource, FIXED_CLOCK);
        app.resourcehub.resources.XF.init(dataSource, FIXED_CLOCK);
        app.resourcehub.availability.XF.init(dataSource, FIXED_CLOCK);
        app.resourcehub.traceability.XF.init(dataSource, FIXED_CLOCK, actorIds -> {
            Map<UUID, UserSummary> summaries = app.resourcehub.access.business.B.auth()
                .resolveUserSummaries(actorIds);
            Map<UUID, AuditActorSummary> result = new HashMap<>();
            for (Map.Entry<UUID, UserSummary> entry : summaries.entrySet()) {
                UserSummary summary = entry.getValue();
                result.put(
                    entry.getKey(),
                    new AuditActorSummary(summary.userId(), summary.fullName(), summary.anonymized())
                );
            }
            return result;
        });
        XF.init(dataSource, FIXED_CLOCK);
    }

    private static void terminateAllModules() {
        XF.terminate();
        app.resourcehub.traceability.XF.terminate();
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
