package app.resourcehub.platform;

import app.resourcehub.platform.business.B;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import app.resourcehub.platform.business.structs.Organization;
import app.resourcehub.platform.business.structs.OrganizationStatus;
import app.resourcehub.platform.business.structs.PlatformBusinessException;
import app.resourcehub.platform.repository.R;
import app.resourcehub.platform.repository.structs.StoredOrganization;
import app.resourcehub.shared.business.structs.ErrorCode;
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
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-003 [PRU-03] — Flyway V2, Constraints platform_catalog y Biyección 1:1 ErrorCode <-> error_catalog")
class PlatformCatalogPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("resourcehub")
        .withUsername("postgres")
        .withPassword("postgres");

    private static DataSource dbaApiDataSource;
    private static MigrateResult migrateResult;

    @BeforeAll
    static void startContainerAndRunMigrations() throws SQLException {
        POSTGRES.start();
        provisionRoles();

        Path migrationsDir = resolveMigrationsDirectory();
        Flyway flyway = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASS)
            .locations("filesystem:" + migrationsDir.toAbsolutePath())
            .load();

        migrateResult = flyway.migrate();
        dbaApiDataSource = new SimpleDriverDataSource(POSTGRES.getJdbcUrl(), DBA_API_USER, DBA_API_PASS);
        XF.init(dbaApiDataSource);
    }

    @AfterAll
    static void stopContainer() {
        XF.terminate();
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    @DisplayName("PRU-03: V1 y V2 aplicadas idempotentemente con 12 columnas en organization y 10 en error_catalog")
    void shouldApplyV1AndV2AndExposeExpectedTableColumns() throws SQLException {
        assertThat(migrateResult.migrationsExecuted).isGreaterThanOrEqualTo(2);

        try (Connection conn = dbaApiDataSource.getConnection()) {
            assertThat(countTableColumns(conn, "platform_catalog", "organization")).isEqualTo(12);
            assertThat(countTableColumns(conn, "platform_catalog", "error_catalog")).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("PRU-03 [DTD-011 / PCR-DAT-002]: Biyección exacta 1:1 entre ErrorCode (34) y platform_catalog.error_catalog")
    void shouldEnforceExactOneToOneBijectionBetweenErrorCodeEnumAndErrorCatalogTable() {
        List<ErrorCatalogEntry> catalog = B.errorCatalog().loadErrorCatalog();

        Set<ErrorCode> enumValues = Arrays.stream(ErrorCode.values()).collect(Collectors.toSet());
        Set<ErrorCode> dbCodes = catalog.stream().map(ErrorCatalogEntry::code).collect(Collectors.toSet());
        Set<Integer> numericValues = new HashSet<>();
        for (ErrorCatalogEntry entry : catalog) {
            assertThat(entry.active()).isTrue();
            assertThat(entry.numericValue()).isGreaterThanOrEqualTo(0);
            assertThat(numericValues.add(entry.numericValue()))
                .as("numeric_value %s duplicado en error_catalog", entry.numericValue())
                .isTrue();
        }

        assertThat(catalog).hasSize(enumValues.size());
        assertThat(dbCodes).containsExactlyInAnyOrderElementsOf(enumValues);

        ErrorCatalogEntry rateLimited = B.errorCatalog().findByErrorCode(ErrorCode.RATE_LIMITED).orElseThrow();
        assertThat(rateLimited.numericValue()).isEqualTo(600001);
        assertThat(rateLimited.familyCode()).isEqualTo((short) 60);
        assertThat(rateLimited.httpStatus()).isEqualTo((short) 429);
    }

    @Test
    @DisplayName("PRU-03: Semilla ACTIVE inicial resoluble vía B.organization y persistencia bajo rol dba_api")
    void shouldResolveSeededActiveOrganizationAndPersistNewOrganizationAsDbaApi() {
        Organization seeded = B.organization().resolveActiveOrgBySlug("resourcehub-demo");
        assertThat(seeded.status()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(seeded.initialTimeZoneId()).isEqualTo("Europe/Madrid");
        assertThat(seeded.toContext().slug()).isEqualTo("resourcehub-demo");

        Instant created = Instant.parse("2026-11-27T12:00:00Z");
        UUID newId = UUID.fromString("01936a00-0000-7000-8000-000000000002");
        StoredOrganization suspended = new StoredOrganization(
            newId,
            "org-suspendida",
            "Organización Suspendida",
            "SUSPENDED",
            "Europe/Madrid",
            "localhost",
            5432,
            "resourcehub_org_suspendida",
            "1",
            created,
            created,
            null
        );
        R.organization().insert(suspended);

        assertThat(B.organization().findById(newId)).isPresent();
        assertThatThrownBy(() -> B.organization().resolveActiveOrgBySlug("org-suspendida"))
            .isInstanceOf(PlatformBusinessException.class)
            .extracting(ex -> ((PlatformBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ORGANIZATION_NOT_ACTIVE);
    }

    @Test
    @DisplayName("PRU-03: Constraints CHECK y UNIQUE físicas activas en organization y error_catalog")
    void shouldEnforcePhysicalCheckAndUniqueConstraintsInPostgres() throws SQLException {
        try (Connection adminConn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASS)) {
            assertCheckViolation(
                adminConn,
                """
                INSERT INTO platform_catalog.organization
                (id, slug, name, status, initial_time_zone_id, db_host, db_port, db_name,
                 current_schema_version, created_at, updated_at, archived_at)
                VALUES ('01936a00-0000-7000-8000-000000000010', 'INVALID_SLUG', 'Bad Slug', 'ACTIVE',
                        'UTC', 'localhost', 5432, 'db_bad_slug', '1', NOW(), NOW(), NULL)
                """
            );
            assertCheckViolation(
                adminConn,
                """
                INSERT INTO platform_catalog.organization
                (id, slug, name, status, initial_time_zone_id, db_host, db_port, db_name,
                 current_schema_version, created_at, updated_at, archived_at)
                VALUES ('01936a00-0000-7000-8000-000000000011', 'archived-no-ts', 'Bad Archived', 'ARCHIVED',
                        'UTC', 'localhost', 5432, 'db_bad_arch', '1', NOW(), NOW(), NULL)
                """
            );
            assertCheckViolation(
                adminConn,
                """
                INSERT INTO platform_catalog.error_catalog
                (id, code, numeric_value, family_code, module_code, http_status,
                 default_message, is_active, created_at, updated_at)
                VALUES ('01936a00-0001-7000-8000-000000000099', 'INVALID_FAMILY_TEST', 770001, 77, 0,
                        400, 'Invalid family', TRUE, NOW(), NOW())
                """
            );
        }
    }

    private static void assertCheckViolation(Connection conn, String sql) {
        try (Statement stmt = conn.createStatement()) {
            assertThatThrownBy(() -> stmt.execute(sql))
                .isInstanceOf(SQLException.class)
                .extracting(ex -> ((SQLException) ex).getSQLState())
                .isEqualTo("23514");
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static int countTableColumns(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
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
        throw new IllegalStateException("Could not locate db/migration from " + Path.of("").toAbsolutePath());
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
