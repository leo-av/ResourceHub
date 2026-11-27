package app.resourcehub.access;

import app.resourcehub.access.business.B;
import app.resourcehub.access.business.structs.IdentityAccount;
import app.resourcehub.access.business.structs.IdentityStatus;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.platform.business.structs.OrganizationSettings;
import app.resourcehub.platform.business.structs.PlatformBusinessException;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.logging.Logger;
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

@DisplayName("TT-005 [PRU-03] — Flyway V3 org_config.organization_settings, access.identity_account y Argon2id")
class OrgConfigAndAccessPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final UUID DEMO_ORG_ID = UUID.fromString("01936a00-0000-7000-8000-000000000001");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T12:45:00Z"), ZoneOffset.UTC);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("resourcehub")
        .withUsername("postgres")
        .withPassword("postgres");

    private static DataSource dbaApiDataSource;
    private static MigrateResult migrateResult;

    @BeforeAll
    static void startPostgresAndApplyMigrations() throws SQLException {
        POSTGRES.start();
        provisionRoles();
        migrateResult = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASS)
            .locations("filesystem:" + resolveMigrationsDirectory().toAbsolutePath())
            .load()
            .migrate();

        dbaApiDataSource = new SimpleDriverDataSource(POSTGRES.getJdbcUrl(), DBA_API_USER, DBA_API_PASS);
        app.resourcehub.platform.XF.init(dbaApiDataSource);
        XF.init(dbaApiDataSource, FIXED_CLOCK);
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
    @DisplayName("PRU-03: V1..V3 crean org_config.organization_settings (13 cols) y access.identity_account (7 cols)")
    void shouldCreateOrgSettingsAndIdentityAccountTables() throws SQLException {
        assertThat(migrateResult.migrationsExecuted).isGreaterThanOrEqualTo(3);

        try (Connection conn = dbaApiDataSource.getConnection()) {
            assertThat(countTableColumns(conn, "org_config", "organization_settings")).isEqualTo(13);
            assertThat(countTableColumns(conn, "access", "identity_account")).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("PRU-03 [MDD-013]: OrgSettingsRepository / Business lee singleton por defecto bajo rol dba_api")
    void shouldLoadSeededOrganizationSettingsAndEnforceTenantCheck() {
        OrganizationSettings settings = app.resourcehub.platform.business.B.orgSettings().getOrgSettings(DEMO_ORG_ID);
        assertThat(settings.singletonKey()).isTrue();
        assertThat(settings.organizationId()).isEqualTo(DEMO_ORG_ID);
        assertThat(settings.timeZoneId()).isEqualTo("Europe/Madrid");
        assertThat(settings.defaultCurrencyCode()).isEqualTo("EUR");
        assertThat(settings.maxResourceDepth()).isEqualTo((short) 3);
        assertThat(settings.seriesMaxHorizonDays()).isEqualTo(365);
        assertThat(settings.allowStandardUserSpaceBooking()).isFalse();

        UUID wrongTenant = UUID.fromString("01936a00-0000-7000-8000-999999999999");
        assertThatThrownBy(() -> app.resourcehub.platform.business.B.orgSettings().getOrgSettings(wrongTenant))
            .isInstanceOf(PlatformBusinessException.class)
            .extracting(ex -> ((PlatformBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("PRU-03: UserRepository + UserBusiness persisten, buscan y desactivan cuentas bajo rol dba_api")
    void shouldPersistAndDeactivateIdentityAccountUnderDbaApi() {
        UUID personResourceId = UUID.fromString("01936a00-0020-7000-8000-000000000001");
        IdentityAccount created = B.users().createIdentityAccount(UserRole.MG, personResourceId);

        assertThat(created.status()).isEqualTo(IdentityStatus.ACTIVE);
        assertThat(created.linkedPersonResourceId()).isEqualTo(personResourceId);

        IdentityAccount loaded = B.users().findById(created.id()).orElseThrow();
        assertThat(loaded.role()).isEqualTo(UserRole.MG);

        IdentityAccount deactivated = B.users().deactivateAccount(created.id());
        assertThat(deactivated.status()).isEqualTo(IdentityStatus.INACTIVE);
    }

    @Test
    @DisplayName("PRU-03: Constraints CHECK y UNIQUE físicas activas en organization_settings e identity_account")
    void shouldEnforceSingletonAndIdentityConstraintsInPostgres() throws SQLException {
        try (Connection adminConn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASS)) {
            assertSqlState(
                adminConn,
                "23514",
                """
                INSERT INTO org_config.organization_settings (
                    singleton_key, organization_id, time_zone_id, default_currency_code,
                    max_resource_depth, series_max_horizon_days, series_max_occurrences,
                    waitlist_max_entries, waitlist_offer_window_minutes,
                    approval_expiration_window_hours, attendance_auto_complete_window_hours,
                    allow_standard_user_space_booking, updated_at
                ) VALUES (
                    FALSE, '01936a00-0000-7000-8000-000000000099', 'UTC', 'EUR',
                    3, 365, 52, 50, 15, 24, 48, FALSE, NOW()
                )
                """
            );
            assertSqlState(
                adminConn,
                "23514",
                """
                INSERT INTO access.identity_account (
                    id, role_code, status, linked_person_resource_id, created_at, updated_at, deleted_at
                ) VALUES (
                    '01936a00-0030-7000-8000-000000000001', 'US', 'ACTIVE',
                    '01936a00-0020-7000-8000-000000000099', NOW(), NOW(), NULL
                )
                """
            );
            assertSqlState(
                adminConn,
                "23514",
                """
                INSERT INTO access.identity_account (
                    id, role_code, status, linked_person_resource_id, created_at, updated_at, deleted_at
                ) VALUES (
                    '01936a00-0030-7000-8000-000000000002', 'AD', 'DELETED_GDPR',
                    NULL, NOW(), NOW(), NULL
                )
                """
            );
        }
    }

    private static void assertSqlState(Connection conn, String expectedState, String sql) {
        try (Statement stmt = conn.createStatement()) {
            assertThatThrownBy(() -> stmt.execute(sql))
                .isInstanceOf(SQLException.class)
                .extracting(ex -> ((SQLException) ex).getSQLState())
                .isEqualTo(expectedState);
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
