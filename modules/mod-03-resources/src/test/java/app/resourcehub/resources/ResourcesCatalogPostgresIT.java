package app.resourcehub.resources;

import app.resourcehub.resources.business.B;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.resources.business.structs.ResourcesBusinessException;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.ResourceNature;
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

@DisplayName("TT-007 [PRU-03] — Flyway V4 resources.resource_type (8 cols) y resources.resource (16 cols + ltree)")
class ResourcesCatalogPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T15:10:00Z"), ZoneOffset.UTC);

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
        XF.init(dbaApiDataSource, FIXED_CLOCK);
    }

    @AfterAll
    static void stopPostgres() {
        XF.terminate();
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Test
    @DisplayName("PRU-03: V1..V4 crean resources.resource_type (8 cols) y resources.resource (16 cols) con constraints")
    void shouldCreateResourcesTablesAndEnforceSqlConstraints() throws SQLException {
        assertThat(migrateResult.migrationsExecuted).isGreaterThanOrEqualTo(4);

        try (Connection conn = dbaApiDataSource.getConnection()) {
            assertThat(countTableColumns(conn, "resources", "resource_type")).isEqualTo(8);
            assertThat(countTableColumns(conn, "resources", "resource")).isEqualTo(16);

            try (Statement stmt = conn.createStatement()) {
                assertThatThrownBy(() -> stmt.executeUpdate("""
                    INSERT INTO resources.resource_type (
                        id, code, name, nature_code, is_active, created_at, updated_at
                    ) VALUES (
                        '01936b00-0001-7000-8000-000000000001', 'BAD_NAT', 'Bad', 'ALIEN', true, now(), now()
                    )
                    """))
                    .isInstanceOf(SQLException.class)
                    .extracting(ex -> ((SQLException) ex).getSQLState())
                    .isEqualTo("23514");
            }
        }
    }

    @Test
    @DisplayName("PRU-03: Persistencia real de ResourceType y Resource bajo dba_api con ltree, CIC-02 y unicidad")
    void shouldPersistResourceTypesAndResourcesUnderDbaApi() throws SQLException {
        ResourceType spaceType = B.resourceTypes().createResourceType(
            "SALAS",
            "Salas Polivalentes",
            "Espacios de reunion",
            ResourceNature.SPACE
        );
        UUID linkedUserId = UUID.fromString("01936b00-0099-7000-8000-000000000001");
        ResourceType personType = B.resourceTypes().createResourceType(
            "ESPECIALISTAS",
            "Especialistas",
            null,
            ResourceNature.PERSON
        );

        CatalogResource sala = B.resources().createSimpleResource(
            spaceType.id(),
            "SALA-ALPHA",
            "Sala Alpha",
            "Capacidad 8 personas",
            8,
            true,
            false,
            null
        );
        CatalogResource medico = B.resources().createSimpleResource(
            personType.id(),
            "DOC-01",
            "Dra. Elena Gomez",
            null,
            1,
            true,
            true,
            linkedUserId
        );

        assertThat(B.resources().listAvailable()).hasSize(2);
        assertThat(medico.linkedUserId()).isEqualTo(linkedUserId);

        assertThatThrownBy(() -> B.resources().createSimpleResource(
            spaceType.id(),
            "SALA-ALPHA",
            "Duplicada",
            null,
            2,
            false,
            false,
            null
        ))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_EXISTS);

        CatalogResource disabled = B.resources().disableResource(sala.id());
        assertThat(disabled.status()).isEqualTo(ResourceStatus.DISABLED);
        assertThat(B.resources().listAvailable()).extracting(CatalogResource::id).containsExactly(medico.id());

        CatalogResource reEnabled = B.resources().enableResource(sala.id());
        assertThat(reEnabled.status()).isEqualTo(ResourceStatus.AVAILABLE);

        try (Connection conn = dbaApiDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            assertThatThrownBy(() -> stmt.executeUpdate("""
                INSERT INTO resources.resource (
                    id, resource_type_id, code, name, status, capacity,
                    requires_approval, allow_professional_approval,
                    parent_id, root_id, hierarchy_path, depth, created_at, updated_at
                ) VALUES (
                    '01936b00-0002-7000-8000-000000000002',
                    '%s',
                    'BAD-CAP',
                    'Bad Capacity',
                    'AVAILABLE',
                    0,
                    false,
                    false,
                    NULL,
                    '01936b00-0002-7000-8000-000000000002',
                    'r_bad'::public.ltree,
                    1,
                    now(),
                    now()
                )
                """.formatted(spaceType.id())))
                .isInstanceOf(SQLException.class)
                .extracting(ex -> ((SQLException) ex).getSQLState())
                .isEqualTo("23514");
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
