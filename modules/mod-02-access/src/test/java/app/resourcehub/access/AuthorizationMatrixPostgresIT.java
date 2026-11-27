package app.resourcehub.access;

import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.platform.business.structs.Organization;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.ResourceNature;
import java.io.PrintWriter;
import java.net.URI;
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
import java.util.Set;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("TT-023 [PRU-03 / PRU-05] — Matriz D-12 con allow_standard_user_space_booking real en PostgreSQL 16+")
class AuthorizationMatrixPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final String DEMO_HOST = "resourcehub-demo.resourcehub.app";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T19:15:00Z"), ZoneOffset.UTC);

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
        app.resourcehub.platform.XF.init(dbaApiDataSource);
        app.resourcehub.gateway.XF.init(FIXED_CLOCK);
        XF.init(
            dbaApiDataSource,
            FIXED_CLOCK,
            orgId -> app.resourcehub.platform.business.B.orgSettings()
                .getOrgSettings(orgId)
                .allowStandardUserSpaceBooking()
        );
    }

    @AfterAll
    static void stopPostgres() {
        XF.terminate();
        app.resourcehub.gateway.XF.terminate();
        app.resourcehub.platform.XF.terminate();
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    @DisplayName("D-12 + V3: ACT-US rechazado en SPACE (200004) con false por defecto y habilitado al activar setting")
    void shouldEnforceAndDynamicallyToggleStandardUserSpaceBookingFromPostgres() throws SQLException {
        Organization org = app.resourcehub.platform.business.B.organization()
            .resolveActiveOrgBySlug("resourcehub-demo");
        assertThat(app.resourcehub.platform.business.B.orgSettings()
            .getOrgSettings(org.id())
            .allowStandardUserSpaceBooking())
            .isFalse();

        ActorContext usClient = new ActorContext(
            UUID.fromString("01936c00-0001-7000-8000-000000000001"),
            org.id(),
            ActorType.USER,
            UserRole.US.name(),
            OperationalScope.AMB_01_CLIENT,
            null,
            Set.of()
        );
        ActorContext emClient = new ActorContext(
            UUID.fromString("01936c00-0002-7000-8000-000000000002"),
            org.id(),
            ActorType.USER,
            UserRole.EM.name(),
            OperationalScope.AMB_01_CLIENT,
            null,
            Set.of()
        );
        ActorContext usConsole = new ActorContext(
            usClient.userId(),
            org.id(),
            ActorType.USER,
            UserRole.US.name(),
            OperationalScope.AMB_03_MANAGEMENT,
            null,
            Set.of()
        );

        // Con configuración V3 por defecto (allow_standard_user_space_booking = false):
        AccessBusinessException usSpaceDenied = catchThrowableOfType(
            () -> app.resourcehub.access.business.B.authorization().assertCanBookNature(usClient, ResourceNature.SPACE),
            AccessBusinessException.class
        );
        assertThat(usSpaceDenied.getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);
        GatewayResponse<Void> gwNoPerm = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest(DEMO_HOST, "/api/v1/app/reservations", "POST", Map.of("X-Request-Id", "r-d12-1"), null),
            problem(usSpaceDenied, 403, "r-d12-1")
        );
        assertThat(gwNoPerm.httpStatus()).isEqualTo(403);
        assertThat(gwNoPerm.body().code()).isEqualTo(200004);

        // Otras 4 naturalezas sí permitidas para ACT-US en AMB-01
        for (ResourceNature allowed : List.of(
            ResourceNature.EQUIPMENT,
            ResourceNature.PERSON,
            ResourceNature.GENERIC_OBJECT,
            ResourceNature.CUSTOM
        )) {
            assertThatCode(() -> app.resourcehub.access.business.B.authorization()
                .assertCanBookNature(usClient, allowed))
                .doesNotThrowAnyException();
        }

        // ACT-EM: permitido SPACE, denegado PERSON (200004)
        assertThatCode(() -> app.resourcehub.access.business.B.authorization()
            .assertCanBookNature(emClient, ResourceNature.SPACE))
            .doesNotThrowAnyException();
        assertThat(catchThrowableOfType(
            () -> app.resourcehub.access.business.B.authorization().assertCanBookNature(emClient, ResourceNature.PERSON),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);

        // ACT-US intentando reservar vía /console -> SCOPE_VIOLATION (403 / 200003)
        AccessBusinessException usScopeDenied = catchThrowableOfType(
            () -> app.resourcehub.access.business.B.authorization().assertCanBookNature(
                usConsole, ResourceNature.EQUIPMENT
            ),
            AccessBusinessException.class
        );
        assertThat(usScopeDenied.getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);
        GatewayResponse<Void> gwScope = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest(
                DEMO_HOST, "/api/v1/console/reservations", "POST", Map.of("X-Request-Id", "r-d12-2"), null
            ),
            problem(usScopeDenied, 403, "r-d12-2")
        );
        assertThat(gwScope.httpStatus()).isEqualTo(403);
        assertThat(gwScope.body().code()).isEqualTo(200003);

        // Habilitar allow_standard_user_space_booking = TRUE en org_config.organization_settings -> ACT-US puede SPACE
        try (Connection conn = dbaApiDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE org_config.organization_settings SET allow_standard_user_space_booking = TRUE");
        }
        assertThatCode(() -> app.resourcehub.access.business.B.authorization()
            .assertCanBookNature(usClient, ResourceNature.SPACE))
            .doesNotThrowAnyException();
    }

    private static InternalProblem problem(AccessBusinessException ex, int status, String requestId) {
        return new InternalProblem(
            URI.create("https://internal.resourcehub/problems/" + ex.getErrorCode().name().toLowerCase()),
            ex.getErrorCode().name(),
            status,
            ex.getMessage(),
            "/api/v1/app/reservations",
            ex.getErrorCode(),
            requestId,
            null,
            List.of(),
            List.of(),
            List.of()
        );
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
