package app.resourcehub.reservations;

import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.CreateConsoleUserCommand;
import app.resourcehub.access.business.structs.LocalAuthCommand;
import app.resourcehub.access.business.structs.PatchConsoleUserCommand;
import app.resourcehub.access.business.structs.UserManagement;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.gateway.business.structs.GatewaySecurityAudit;
import app.resourcehub.platform.business.structs.Organization;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.CreateResourceCommand;
import app.resourcehub.resources.business.structs.CreateResourceTypeCommand;
import app.resourcehub.resources.business.structs.PatchResourceStatusCommand;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.ResourceNature;
import app.resourcehub.traceability.business.structs.AuditLogFilter;
import app.resourcehub.traceability.business.structs.AuditLogPage;
import app.resourcehub.traceability.business.structs.AuditOutcome;
import app.resourcehub.traceability.business.structs.RecordAuditCommand;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

@DisplayName("TT-026 [PRU-04 / PRU-05 / PRU-07] — Guardia Perimetral COMP-GW con JWT EdDSA y Auditoria MOD-07 en PG 16.6")
class GatewayScopeEnforcementPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final String DEMO_HOST = "resourcehub-demo.resourcehub.app";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T20:40:00Z"), ZoneOffset.UTC);

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
    @DisplayName("TT-026 AC-005 / PRU-SEG-01..05: Matriz cruzada con JWT reales, aislamiento tenant y auditoria REQUIRES_NEW")
    void shouldEnforcePerimeterWithRealEdDsaJwtsAndRecordAutonomousAuditInPostgres() {
        Organization org = app.resourcehub.platform.business.B.organization()
            .resolveActiveOrgBySlug("resourcehub-demo");

        // Provisionar AD, US y EM con recurso PERSON activo
        app.resourcehub.access.business.B.users().createConsoleUser(
            org.id(),
            new CreateConsoleUserCommand(
                UserRole.AD, "ad.gw@demo.test", "Admin GW", null, "AdminPass2026!".toCharArray(), null
            )
        );
        ActorContext adminActor = app.resourcehub.access.business.B.auth().validateToken(
            app.resourcehub.access.api.A.consoleAuth().login(
                new LocalAuthCommand(org.id(), "ad.gw@demo.test", "AdminPass2026!".toCharArray()),
                "req-gw-ad",
                FIXED_CLOCK
            ).data().accessToken()
        );

        app.resourcehub.access.api.A.consoleUsers().createUser(
            adminActor,
            new CreateConsoleUserCommand(
                UserRole.US, "us.gw@demo.test", "Cliente GW", null, "ClientPass2026!".toCharArray(), null
            ),
            "req-gw-us-create",
            FIXED_CLOCK
        );
        UserManagement emUser = app.resourcehub.access.api.A.consoleUsers().createUser(
            adminActor,
            new CreateConsoleUserCommand(
                UserRole.EM, "em.gw@demo.test", "Profesional GW", null, "StaffPass2026!".toCharArray(), null
            ),
            "req-gw-em-create",
            FIXED_CLOCK
        ).data();

        ResourceType personType = app.resourcehub.resources.api.A.consoleCatalog().createResourceType(
            adminActor,
            new CreateResourceTypeCommand("GW_PRO", "Profesional GW", null, ResourceNature.PERSON),
            "req-gw-rt",
            FIXED_CLOCK
        ).data();
        CatalogResource personRes = app.resourcehub.resources.api.A.consoleCatalog().createResource(
            adminActor,
            new CreateResourceCommand(personType.id(), "PRO-GW", "Dr. Marco", null, 1, false, false, emUser.id()),
            "req-gw-res",
            FIXED_CLOCK
        ).data();
        app.resourcehub.access.api.A.consoleUsers().patchUser(
            adminActor,
            emUser.id(),
            new PatchConsoleUserCommand(null, null, personRes.id(), false),
            "req-gw-link",
            FIXED_CLOCK
        );

        // Emision de 3 tokens JWT reales en sus respectivos canales
        AuthTokenBundle appTokens = app.resourcehub.access.api.A.appAuth().login(
            new LocalAuthCommand(org.id(), "us.gw@demo.test", "ClientPass2026!".toCharArray()),
            "req-login-app",
            FIXED_CLOCK
        ).data();
        AuthTokenBundle wsTokens = app.resourcehub.access.api.A.workspaceAuth().login(
            new LocalAuthCommand(org.id(), "em.gw@demo.test", "StaffPass2026!".toCharArray()),
            "req-login-ws",
            FIXED_CLOCK
        ).data();
        AuthTokenBundle consoleTokens = app.resourcehub.access.api.A.consoleAuth().login(
            new LocalAuthCommand(org.id(), "ad.gw@demo.test", "AdminPass2026!".toCharArray()),
            "req-login-console",
            FIXED_CLOCK
        ).data();

        List<String> jwts = List.of(appTokens.accessToken(), wsTokens.accessToken(), consoleTokens.accessToken());
        List<String> channels = List.of(
            "/api/v1/app/reservations",
            "/api/v1/workspace/agenda",
            "/api/v1/console/users"
        );

        // Matriz cruzada 3 x 3 (AC-005 / PRU-SEG-03)
        for (int i = 0; i < jwts.size(); i++) {
            for (int j = 0; j < channels.size(); j++) {
                String reqId = "req-xmatrix-" + i + "-" + j;
                Optional<GatewayResponse<Void>> verdict = app.resourcehub.gateway.api.A.edgeGateway()
                    .enforcePerimeter(
                        new GatewayRequest(
                            DEMO_HOST,
                            channels.get(j),
                            "GET",
                            Map.of(
                                "X-Request-Id", reqId,
                                "Authorization", "Bearer " + jwts.get(i),
                                "X-Internal-Problem-Details", "true"
                            ),
                            null
                        ),
                        org.id()
                    );
                if (i == j) {
                    assertThat(verdict).isEmpty();
                } else {
                    assertThat(verdict).isPresent();
                    assertThat(verdict.get().httpStatus()).isEqualTo(403);
                    assertThat(verdict.get().body().code()).isEqualTo(200003);
                    assertThat(verdict.get().requestId()).isEqualTo(reqId);
                }
            }
        }

        // PRU-SEG-01: ruta de plataforma desde host tenant -> 404 (300001)
        GatewayResponse<Void> seg01 = app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                DEMO_HOST,
                "/api/v1/organizations",
                "GET",
                Map.of("X-Request-Id", "req-it-seg01", "Authorization", "Bearer " + consoleTokens.accessToken()),
                null
            ),
            org.id()
        ).orElseThrow();
        assertThat(seg01.httpStatus()).isEqualTo(404);
        assertThat(seg01.body().code()).isEqualTo(300001);

        // PRU-SEG-02: expectedOrganizationId distinto al JWT.orgId -> 404 (300001)
        UUID otherOrgId = UUID.fromString("01936a00-0000-7000-8000-000000000999");
        GatewayResponse<Void> seg02 = app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                "other-org.resourcehub.app",
                "/api/v1/app/reservations",
                "GET",
                Map.of("X-Request-Id", "req-it-seg02", "Authorization", "Bearer " + appTokens.accessToken()),
                null
            ),
            otherOrgId
        ).orElseThrow();
        assertThat(seg02.httpStatus()).isEqualTo(404);
        assertThat(seg02.body().code()).isEqualTo(300001);

        // Deshabilitar recurso PERSON -> COMP-GW bloquea /workspace/agenda con 403 (200003)
        app.resourcehub.resources.api.A.consoleCatalog().patchResourceStatus(
            adminActor, personRes.id(), new PatchResourceStatusCommand(ResourceStatus.DISABLED), "req-dis-gw", FIXED_CLOCK
        );
        GatewayResponse<Void> disabledWorkspace = app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                DEMO_HOST,
                "/api/v1/workspace/agenda",
                "GET",
                Map.of("X-Request-Id", "req-it-ws-dis", "Authorization", "Bearer " + wsTokens.accessToken()),
                null
            ),
            org.id()
        ).orElseThrow();
        assertThat(disabledWorkspace.httpStatus()).isEqualTo(403);
        assertThat(disabledWorkspace.body().code()).isEqualTo(200003);

        // Verificar que traceability.audit_log registro los rechazos (6 cruce + seg01 + seg02 + ws_disabled = 9)
        AuditLogPage auditPage = app.resourcehub.traceability.business.B.audit().listAuditLogs(
            adminActor,
            new AuditLogFilter(null, "GATEWAY_PERIMETER_GUARD", AuditOutcome.REJECTED, null, null, null),
            1,
            20
        );
        assertThat(auditPage.totalItems()).isEqualTo(9L);
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
        app.resourcehub.traceability.XF.init(
            ds,
            FIXED_CLOCK,
            ids -> Map.of()
        );
        app.resourcehub.gateway.XF.init(
            () -> app.resourcehub.platform.business.B.errorCatalog().loadErrorCatalog(),
            FIXED_CLOCK,
            token -> app.resourcehub.access.business.B.auth().validateToken(token),
            personResourceId -> app.resourcehub.resources.business.B.resources()
                .findById(personResourceId)
                .map(res -> res.status() == ResourceStatus.AVAILABLE)
                .orElse(false),
            host -> true,
            GatewayScopeEnforcementPostgresIT::recordGatewayAudit
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
