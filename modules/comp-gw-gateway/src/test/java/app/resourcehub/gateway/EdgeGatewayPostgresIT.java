package app.resourcehub.gateway;

import app.resourcehub.gateway.api.A;
import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ErrorCode;
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
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TT-004 [PRU-03 / PRU-04] — COMP-GW + MOD-01 GET /api/v1/error-catalog sobre Testcontainers PG 16+")
class EdgeGatewayPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T12:35:00.000Z"), ZoneOffset.UTC);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("resourcehub")
        .withUsername("postgres")
        .withPassword("postgres");

    @BeforeAll
    static void startPostgresAndInitModules() throws SQLException {
        POSTGRES.start();
        provisionRoles();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASS)
            .locations("filesystem:" + resolveMigrationsDirectory().toAbsolutePath())
            .load()
            .migrate();

        DataSource dbaApiDs = new SimpleDriverDataSource(POSTGRES.getJdbcUrl(), DBA_API_USER, DBA_API_PASS);
        app.resourcehub.platform.XF.init(dbaApiDs);
        XF.init(FIXED_CLOCK);
    }

    @AfterAll
    static void stopPostgres() {
        XF.terminate();
        app.resourcehub.platform.XF.terminate();
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    void shouldExposeErrorCatalogEndpointAndTranslateRealDbCatalog() {
        GatewayRequest request = new GatewayRequest(
            "platform.resourcehub.app",
            "/api/v1/error-catalog",
            "GET",
            Map.of("X-Request-Id", "it-gw-catalog-01"),
            null
        );

        GatewayResponse<List<ErrorCatalogEntry>> catalogResponse = A.edgeGateway().handleErrorCatalogEndpoint(request);
        assertThat(catalogResponse.httpStatus()).isEqualTo(200);
        assertThat(catalogResponse.body().code()).isZero();
        assertThat(catalogResponse.body().requestId()).isEqualTo("it-gw-catalog-01");
        assertThat(catalogResponse.body().data()).hasSize(ErrorCode.values().length);

        ResponseWrapper<List<ErrorCatalogEntry>> directService =
            app.resourcehub.platform.api.A.errorCatalog().getErrorCatalog("it-gw-catalog-01", FIXED_CLOCK);
        assertThat(directService.code()).isZero();
        assertThat(directService.data()).hasSize(ErrorCode.values().length);

        InternalProblem bookingConflict = new InternalProblem(
            URI.create("urn:resourcehub:problem:booking"),
            "Booking Conflict",
            409,
            "excl_reservation_line_active_gist violation",
            "/api/v1/app/reservations",
            ErrorCode.BOOKING_CONFLICT,
            "it-gw-catalog-01",
            null,
            List.of(),
            List.of(),
            List.of()
        );
        GatewayResponse<Void> translated = A.edgeGateway().handleInternalProblem(request, bookingConflict);
        assertThat(translated.httpStatus()).isEqualTo(409);
        assertThat(translated.body().code()).isEqualTo(400501);
        assertThat(translated.body().errors().getFirst().message()).isEqualTo("La franja seleccionada ya está ocupada");
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
