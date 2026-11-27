package app.resourcehub.access;

import app.resourcehub.access.business.B;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.IdentityAccount;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.access.business.structs.UserSummary;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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

@DisplayName("TT-016 [PRU-03 / PRU-07] — PII Vault V9 access.personal_data e hidratacion O(1) en Postgres")
class PersonalDataVaultPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final UUID DEMO_ORG_ID = UUID.fromString("01936a00-0000-7000-8000-000000000001");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T17:00:00Z"), ZoneOffset.UTC);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("resourcehub")
        .withUsername("postgres")
        .withPassword("postgres");

    private static DataSource dbaApiDataSource;
    private static MigrateResult migrateResult;

    @BeforeAll
    static void startPostgres() throws SQLException {
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
    @DisplayName("PRU-03/07: V9 crea personal_data (8 cols), identity_account sin PII e hidratacion O(1) con RGPD")
    void shouldEnforcePiiVaultSchemaUniquenessAndBatchHydration() throws SQLException {
        assertThat(migrateResult.migrationsExecuted).isGreaterThanOrEqualTo(7);

        try (Connection conn = dbaApiDataSource.getConnection()) {
            assertThat(countColumns(conn, "access", "personal_data")).isEqualTo(8);
            assertThat(countColumns(conn, "access", "identity_account")).isEqualTo(7);
            assertThat(countPiiColumns(conn, "access", "identity_account")).isZero();
        }

        IdentityAccount u1 = B.users().createIdentityAccount(UserRole.US, null);
        IdentityAccount u2 = B.users().createIdentityAccount(UserRole.MG, null);
        UUID deletedGdprId = UUID.fromString("01936a00-0099-7000-8000-000000000123");

        B.auth().registerPersonalProfile(u1.id(), "Elena.Soto@Demo.Test", "Elena Soto", "+34600999888");
        B.auth().registerLocalCredential(DEMO_ORG_ID, u1.id(), "elena.soto@demo.test", "ElenaVault2026!".toCharArray());
        B.auth().registerPersonalProfile(u2.id(), "marcos.gil@demo.test", "Marcos Gil", null);

        // Login real persistido contra access.personal_data
        AuthTokenBundle bundle = B.auth().authenticateLocal(
            DEMO_ORG_ID, OperationalScope.AMB_01_CLIENT, "ELENA.SOTO@DEMO.TEST", "ElenaVault2026!".toCharArray()
        );
        assertThat(bundle.userId()).isEqualTo(u1.id());

        // Email duplicado case-insensitive -> uq_personal_data_email -> 23505 -> ALREADY_EXISTS
        assertThatThrownBy(() -> B.auth().registerPersonalProfile(
            u2.id(), "ELENA.soto@demo.test", "Otro Nombre", null
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_EXISTS);

        // Hidratacion O(1) en lote con 1 fila ausente -> "Usuario eliminado (RGPD)"
        Set<UUID> batchIds = new LinkedHashSet<>();
        batchIds.add(u1.id());
        batchIds.add(u2.id());
        batchIds.add(deletedGdprId);
        Map<UUID, UserSummary> summaries = B.auth().resolveUserSummaries(batchIds);
        assertThat(summaries).hasSize(3);
        assertThat(summaries.get(u1.id()).fullName()).isEqualTo("Elena Soto");
        assertThat(summaries.get(u1.id()).anonymized()).isFalse();
        assertThat(summaries.get(u2.id()).fullName()).isEqualTo("Marcos Gil");
        assertThat(summaries.get(deletedGdprId).anonymized()).isTrue();
        assertThat(summaries.get(deletedGdprId).fullName()).isEqualTo("Usuario eliminado (RGPD)");
    }

    private static int countColumns(Connection conn, String schema, String table) throws SQLException {
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

    private static int countPiiColumns(Connection conn, String schema, String table) throws SQLException {
        String sql = """
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
              AND LOWER(column_name) IN ('email', 'full_name', 'phone', 'dni', 'tax_id', 'password_hash')
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
