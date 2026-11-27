package app.resourcehub.resources;

import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.resources.api.A;
import app.resourcehub.resources.business.B;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.CreateResourceCommand;
import app.resourcehub.resources.business.structs.CreateResourceTypeCommand;
import app.resourcehub.resources.business.structs.PatchResourceStatusCommand;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.resources.business.structs.ResourcesBusinessException;
import app.resourcehub.shared.api.structs.PageResponse;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.ResourceNature;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("TT-008 [PRU-04 / PRU-05 / PRU-06] — API REST Catálogo /console/** y /app/** + COMP-GW")
class CatalogRestApiPostgresIT {

    private static final String POSTGRES_IMAGE = "postgres:16.6-bookworm";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin_test_pass";
    private static final String DBA_API_USER = "dba_api";
    private static final String DBA_API_PASS = "dba_api_test_pass";
    private static final UUID DEMO_ORG_ID = UUID.fromString("01936a00-0000-7000-8000-000000000001");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T16:00:00Z"), ZoneOffset.UTC);

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
    @DisplayName("AC-007 y DTD-007: CRUD catalogo /console, filtrado AVAILABLE en /app y aislamiento de canal")
    void shouldExposeConsoleAndAppCatalogEndpointsWithResponseWrapperAndPageResponse() {
        ActorContext managerActor = new ActorContext(
            UUID.fromString("01936c00-0001-7000-8000-000000000001"),
            DEMO_ORG_ID,
            ActorType.USER,
            "MG",
            OperationalScope.AMB_03_MANAGEMENT,
            null,
            Set.of()
        );
        ActorContext clientActor = new ActorContext(
            UUID.fromString("01936c00-0002-7000-8000-000000000002"),
            DEMO_ORG_ID,
            ActorType.USER,
            "US",
            OperationalScope.AMB_01_CLIENT,
            null,
            Set.of()
        );

        // 1. Crear tipos via ConsoleCatalogService
        ResponseWrapper<ResourceType> typeSpaceWrapper = A.consoleCatalog().createResourceType(
            managerActor,
            new CreateResourceTypeCommand("ROOM_SPACE", "Salas de Juntas", "Espacios", ResourceNature.SPACE),
            "req-cat-type-01",
            FIXED_CLOCK
        );
        assertThat(typeSpaceWrapper.code()).isZero();
        ResourceType spaceType = typeSpaceWrapper.data();

        // 2. Crear 2 recursos AVAILABLE via ConsoleCatalogService
        ResponseWrapper<CatalogResource> res1 = A.consoleCatalog().createResource(
            managerActor,
            new CreateResourceCommand(spaceType.id(), "SALA-A", "Sala A", "Planta 1", 8, false, false, null),
            "req-cat-res-01",
            FIXED_CLOCK
        );
        ResponseWrapper<CatalogResource> res2 = A.consoleCatalog().createResource(
            managerActor,
            new CreateResourceCommand(spaceType.id(), "SALA-B", "Sala B", "Planta 2", 4, false, false, null),
            "req-cat-res-02",
            FIXED_CLOCK
        );
        assertThat(res1.code()).isZero();
        assertThat(res2.code()).isZero();

        // 3. /app/resources inicialmente lista los 2 AVAILABLE en PageResponse
        ResponseWrapper<PageResponse<CatalogResource>> appBefore = A.appCatalog().listResources(
            clientActor, 1, 10, "req-app-list-01", FIXED_CLOCK
        );
        assertThat(appBefore.data().totalItems()).isEqualTo(2);

        // 4. AC-007: PATCH a DISABLED devuelve 200 OK en ResponseWrapper<CatalogResource> y oculta de /app
        ResponseWrapper<CatalogResource> patched = A.consoleCatalog().patchResourceStatus(
            managerActor,
            res2.data().id(),
            new PatchResourceStatusCommand(ResourceStatus.DISABLED),
            "req-patch-01",
            FIXED_CLOCK
        );
        GatewayResponse<CatalogResource> gwPatch = app.resourcehub.gateway.api.A.edgeGateway()
            .handleResponse(
                new GatewayRequest("demo.resourcehub.app", "/api/v1/console/resources/" + res2.data().id(), "PATCH",
                    Map.of("X-Request-Id", "req-patch-01"), null),
                200,
                patched
            );
        assertThat(gwPatch.httpStatus()).isEqualTo(200);
        assertThat(gwPatch.body().code()).isZero();
        assertThat(gwPatch.body().data().status()).isEqualTo(ResourceStatus.DISABLED);

        ResponseWrapper<PageResponse<CatalogResource>> appAfter = A.appCatalog().listResources(
            clientActor, 1, 10, "req-app-list-02", FIXED_CLOCK
        );
        assertThat(appAfter.data().totalItems()).isEqualTo(1);
        assertThat(appAfter.data().items().getFirst().code()).isEqualTo("SALA-A");

        ResponseWrapper<PageResponse<CatalogResource>> consoleAll = A.consoleCatalog().listResources(
            managerActor, 1, 10, "req-con-list-01", FIXED_CLOCK
        );
        assertThat(consoleAll.data().totalItems()).isEqualTo(2);

        List<ResourceType> appTypes = A.appCatalog().listResourceTypes(
            clientActor, "req-app-types-01", FIXED_CLOCK
        ).data();
        assertThat(appTypes).extracting(ResourceType::code).containsExactly("ROOM_SPACE");

        // 5. Contrato interno CatalogB.resource.getResourceDescriptor sigue devolviendo ambos
        assertThat(B.resources().getResourceDescriptor(res2.data().id()).status())
            .isEqualTo(ResourceStatus.DISABLED);

        // 6. Seguridad de canal: usuario US en /console/resources -> 403 code 200003 via COMP-GW
        ResourcesBusinessException scopeDenied = catchThrowableOfType(
            ResourcesBusinessException.class,
            () -> A.consoleCatalog().createResourceType(
                clientActor,
                new CreateResourceTypeCommand("BAD", "Forbidden", null, ResourceNature.EQUIPMENT),
                "req-deny-01",
                FIXED_CLOCK
            )
        );
        GatewayResponse<Void> deniedGw = app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(
            new GatewayRequest("demo.resourcehub.app", "/api/v1/console/resource-types", "POST",
                Map.of("X-Request-Id", "req-deny-01"), null),
            A.consoleCatalog().toInternalProblem(scopeDenied, "/api/v1/console/resource-types", "req-deny-01")
        );
        assertThat(deniedGw.httpStatus()).isEqualTo(403);
        assertThat(deniedGw.body().code()).isEqualTo(200003);
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
