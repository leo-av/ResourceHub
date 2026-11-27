package app.resourcehub.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresTestcontainersBaselineIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";

    private static final List<String> EXPECTED_SCHEMAS = List.of(
        "platform_catalog",
        "org_config",
        "access",
        "resources",
        "availability",
        "reservations",
        "policies",
        "traceability",
        "events",
        "integrations",
        "payments"
    );

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("resourcehub")
        .withUsername("postgres")
        .withPassword("postgres");

    private static Flyway flyway;
    private static MigrateResult initialMigration;

    @BeforeAll
    static void startContainerAndApplyBaselineMigration() throws SQLException {
        POSTGRES.start();
        provisionBaselineRoles();

        Path migrationsDir = resolveMigrationsDirectory();
        flyway = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASS)
            .locations("filesystem:" + migrationsDir.toAbsolutePath())
            .load();

        initialMigration = flyway.migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    void shouldBootPostgres16AndApplyV1Idempotently() throws SQLException {
        assertNotNull(initialMigration);
        assertEquals(1, initialMigration.migrationsExecuted);

        MigrateResult rerun = flyway.migrate();
        assertEquals(0, rerun.migrationsExecuted);

        try (Connection conn = openAdminConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW server_version_num")) {
            assertTrue(rs.next());
            int versionNum = Integer.parseInt(rs.getString(1));
            assertTrue(versionNum >= 160000, "PostgreSQL major version must be >= 16, got " + versionNum);
        }
    }

    @Test
    void shouldInstallRequiredExtensionsAndAllElevenSchemasOwnedByAdmin() throws SQLException {
        try (Connection conn = openAdminConnection()) {
            Map<String, String> extensions = new HashMap<>();
            String extSql = """
                SELECT e.extname, n.nspname
                FROM pg_catalog.pg_extension e
                JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace
                WHERE e.extname IN ('btree_gist', 'ltree')
                """;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(extSql)) {
                while (rs.next()) {
                    extensions.put(rs.getString("extname"), rs.getString("nspname"));
                }
            }
            assertEquals("public", extensions.get("btree_gist"));
            assertEquals("public", extensions.get("ltree"));

            Map<String, String> schemaOwners = new HashMap<>();
            String schemaSql = """
                SELECT schema_name, schema_owner
                FROM information_schema.schemata
                WHERE schema_name = ANY (?)
                """;
            try (PreparedStatement ps = conn.prepareStatement(schemaSql)) {
                ps.setArray(1, conn.createArrayOf("text", EXPECTED_SCHEMAS.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        schemaOwners.put(rs.getString("schema_name"), rs.getString("schema_owner"));
                    }
                }
            }

            assertEquals(11, schemaOwners.size());
            for (String expectedSchema : EXPECTED_SCHEMAS) {
                assertEquals(ADMIN_USER, schemaOwners.get(expectedSchema), "Invalid owner for " + expectedSchema);
            }
        }
    }

    @Test
    void shouldEnforceAppendOnlyTriggerFunctionWithSqlState55000() throws SQLException {
        try (Connection conn = openAdminConnection();
             Statement stmt = conn.createStatement()) {
            String procSql = """
                SELECT pg_catalog.pg_get_userbyid(p.proowner) AS owner_name
                FROM pg_catalog.pg_proc p
                JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname = 'prevent_append_only_mutation'
                """;
            try (ResultSet rs = stmt.executeQuery(procSql)) {
                assertTrue(rs.next(), "public.prevent_append_only_mutation() must exist");
                assertEquals(ADMIN_USER, rs.getString("owner_name"));
            }

            try {
                stmt.execute("""
                    CREATE TABLE traceability.pru03_append_only_probe (
                        id INT PRIMARY KEY,
                        payload TEXT NOT NULL
                    )
                    """);
                stmt.execute("""
                    CREATE TRIGGER trg_pru03_append_only
                    BEFORE UPDATE OR DELETE ON traceability.pru03_append_only_probe
                    FOR EACH ROW EXECUTE FUNCTION public.prevent_append_only_mutation()
                    """);
                stmt.execute("INSERT INTO traceability.pru03_append_only_probe (id, payload) VALUES (1, 'immutable')");

                SQLException updateEx = assertThrows(
                    SQLException.class,
                    () -> stmt.execute("UPDATE traceability.pru03_append_only_probe SET payload = 'tampered' WHERE id = 1")
                );
                assertEquals("55000", updateEx.getSQLState());

                SQLException deleteEx = assertThrows(
                    SQLException.class,
                    () -> stmt.execute("DELETE FROM traceability.pru03_append_only_probe WHERE id = 1")
                );
                assertEquals("55000", deleteEx.getSQLState());
            } finally {
                stmt.execute("DROP TABLE IF EXISTS traceability.pru03_append_only_probe");
            }
        }
    }

    @Test
    void shouldEnforceLeastPrivilegeForDbaApiRole() throws SQLException {
        try (Connection dbaConn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), DBA_API_USER, DBA_API_PASS);
             Statement stmt = dbaConn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM public.flyway_schema_history")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1);
            }

            SQLException deleteHistoryEx = assertThrows(
                SQLException.class,
                () -> stmt.execute("DELETE FROM public.flyway_schema_history")
            );
            assertEquals("42501", deleteHistoryEx.getSQLState());

            SQLException createPublicEx = assertThrows(
                SQLException.class,
                () -> stmt.execute("CREATE TABLE public.unauthorized_probe (id INT)")
            );
            assertEquals("42501", createPublicEx.getSQLState());
        }
    }

    private static void provisionBaselineRoles() throws SQLException {
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

    private static Connection openAdminConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASS);
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
        throw new IllegalStateException("Could not locate db/migration directory from " + Path.of("").toAbsolutePath());
    }
}
