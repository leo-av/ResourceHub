package app.resourcehub.access;

import app.resourcehub.access.api.A;
import app.resourcehub.access.business.B;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.IdentityAccount;
import app.resourcehub.access.business.structs.LocalAuthCommand;
import app.resourcehub.access.business.structs.LogoutCommand;
import app.resourcehub.access.business.structs.RefreshTokenCommand;
import app.resourcehub.access.business.structs.UserProfile;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.OperationalScope;
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

@DisplayName("TT-006 [PRU-03 / PRU-04 / PRU-07] — Canales /app/auth/** y /console/auth/** con PG 16.6 y COMP-GW")
class AuthChannelsPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final UUID DEMO_ORG_ID = UUID.fromString("01936a00-0000-7000-8000-000000000001");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T14:30:00Z"), ZoneOffset.UTC);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("resourcehub")
        .withUsername("postgres")
        .withPassword("postgres");

    private static DataSource dbaApiDataSource;

    @BeforeAll
    static void startPostgresAndWireModules() throws SQLException {
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
        XF.init(dbaApiDataSource, FIXED_CLOCK);
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
    @DisplayName("PRU-04 / PRU-07: Login US rechazado en /console (403 200003) y token AMB-01 valido en /app/auth/me")
    void shouldIsolateChannelsTranslateErrorsViaGatewayAndRotateTokensInPostgres() {
        IdentityAccount user = B.users().createIdentityAccount(UserRole.US, null);
        B.auth().registerLocalCredential(DEMO_ORG_ID, user.id(), "us@demo.resourcehub.test", "UserSecret2026!".toCharArray());

        IdentityAccount manager = B.users().createIdentityAccount(UserRole.MG, null);
        B.auth().registerLocalCredential(DEMO_ORG_ID, manager.id(), "mg@demo.resourcehub.test", "MgmtSecret2026!".toCharArray());

        GatewayRequest appLoginReq = new GatewayRequest(
            "demo.resourcehub.app",
            "/api/v1/app/auth/login",
            "POST",
            Map.of("X-Request-Id", "req-app-login-001"),
            null
        );
        ResponseWrapper<AuthTokenBundle> appLoginWrapper = A.appAuth().login(
            new LocalAuthCommand(DEMO_ORG_ID, "us@demo.resourcehub.test", "UserSecret2026!".toCharArray()),
            "req-app-login-001",
            FIXED_CLOCK
        );
        GatewayResponse<AuthTokenBundle> appLoginGw = app.resourcehub.gateway.api.A.edgeGateway()
            .handleResponse(appLoginReq, 200, appLoginWrapper);

        assertThat(appLoginGw.httpStatus()).isEqualTo(200);
        assertThat(appLoginGw.body().code()).isZero();
        AuthTokenBundle usAppBundle = appLoginGw.body().data();
        assertThat(usAppBundle.operationalScope()).isEqualTo(OperationalScope.AMB_01_CLIENT);

        // Token AMB_01 valido en /api/v1/app/auth/me
        ResponseWrapper<UserProfile> meWrapper = A.appAuth().me(usAppBundle.accessToken(), "req-app-me-001", FIXED_CLOCK);
        assertThat(meWrapper.code()).isZero();
        assertThat(meWrapper.data().userId()).isEqualTo(user.id());
        assertThat(meWrapper.data().operationalScope()).isEqualTo(OperationalScope.AMB_01_CLIENT);

        // Intento US en /api/v1/console/auth/login -> 403 code 200003 en COMP-GW
        GatewayRequest consoleLoginReq = new GatewayRequest(
            "demo.resourcehub.app",
            "/api/v1/console/auth/login",
            "POST",
            Map.of("X-Request-Id", "req-console-deny-001"),
            null
        );
        AccessBusinessException usConsoleDenied = catchThrowableOfType(
            AccessBusinessException.class,
            () -> A.consoleAuth().login(
                new LocalAuthCommand(DEMO_ORG_ID, "us@demo.resourcehub.test", "UserSecret2026!".toCharArray()),
                "req-console-deny-001",
                FIXED_CLOCK
            )
        );
        GatewayResponse<Void> deniedGw = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            consoleLoginReq,
            A.consoleAuth().toInternalProblem(usConsoleDenied, "/api/v1/console/auth/login", "req-console-deny-001")
        );
        assertThat(deniedGw.httpStatus()).isEqualTo(403);
        assertThat(deniedGw.body().code()).isEqualTo(200003);

        // Login MG valido en /console -> rotate -> logout -> revocado 401 200001
        ResponseWrapper<AuthTokenBundle> mgConsole = A.consoleAuth().login(
            new LocalAuthCommand(DEMO_ORG_ID, "mg@demo.resourcehub.test", "MgmtSecret2026!".toCharArray()),
            "req-mg-login-001",
            FIXED_CLOCK
        );
        assertThat(mgConsole.data().operationalScope()).isEqualTo(OperationalScope.AMB_03_MANAGEMENT);

        ResponseWrapper<AuthTokenBundle> rotated = A.consoleAuth().refresh(
            new RefreshTokenCommand(DEMO_ORG_ID, mgConsole.data().refreshToken()),
            "req-mg-refresh-001",
            FIXED_CLOCK
        );
        assertThat(rotated.data().refreshToken()).isNotEqualTo(mgConsole.data().refreshToken());

        ResponseWrapper<Void> loggedOut = A.consoleAuth().logout(
            new LogoutCommand(rotated.data().accessToken(), rotated.data().refreshToken()),
            "req-mg-logout-001",
            FIXED_CLOCK
        );
        assertThat(loggedOut.code()).isZero();

        AccessBusinessException afterLogout = catchThrowableOfType(
            AccessBusinessException.class,
            () -> A.consoleAuth().refresh(
                new RefreshTokenCommand(DEMO_ORG_ID, rotated.data().refreshToken()),
                "req-mg-after-logout",
                FIXED_CLOCK
            )
        );
        GatewayResponse<Void> expiredGw = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest("demo.resourcehub.app", "/api/v1/console/auth/refresh", "POST", Map.of(), null),
            A.consoleAuth().toInternalProblem(afterLogout, "/api/v1/console/auth/refresh", "req-mg-after-logout")
        );
        assertThat(expiredGw.httpStatus()).isEqualTo(401);
        assertThat(expiredGw.body().code()).isEqualTo(200001);

        // Credenciales invalidas -> 401 200002
        AccessBusinessException badCreds = catchThrowableOfType(
            AccessBusinessException.class,
            () -> A.appAuth().login(
                new LocalAuthCommand(DEMO_ORG_ID, "us@demo.resourcehub.test", "WrongPass!".toCharArray()),
                "req-bad-creds",
                FIXED_CLOCK
            )
        );
        GatewayResponse<Void> badCredsGw = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            appLoginReq,
            A.appAuth().toInternalProblem(badCreds, "/api/v1/app/auth/login", "req-bad-creds")
        );
        assertThat(badCredsGw.httpStatus()).isEqualTo(401);
        assertThat(badCredsGw.body().code()).isEqualTo(200002);
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
