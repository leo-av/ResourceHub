package app.resourcehub.gateway;

import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayBusinessException;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.gateway.business.structs.GatewaySecurityAudit;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-026 [PRU-01 / PRU-04 / PRU-07] — Guardia Perimetral ScopeEnforcementBusiness en COMP-GW")
class ScopeEnforcementContractTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-27T20:40:00.123Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final UUID ORG_ALFA = UUID.fromString("01936a00-0000-7000-8000-000000000001");
    private static final UUID ORG_BETA = UUID.fromString("01936a00-0000-7000-8000-000000000002");
    private static final UUID PERSON_RES_ID = UUID.fromString("01936a00-0010-7000-8000-000000000501");
    private static final UUID DISABLED_PERSON_RES_ID = UUID.fromString("01936a00-0010-7000-8000-000000000502");

    private static final List<ErrorCatalogEntry> SEEDED_ENTRIES = List.of(
        entry(ErrorCode.OK, 0, 200, "OK"),
        entry(ErrorCode.VALIDATION_FAILED, 100001, 400, "Error de validación en los datos enviados"),
        entry(ErrorCode.MALFORMED_REQUEST, 100002, 400, "Formato de petición no válido"),
        entry(ErrorCode.UNAUTHENTICATED, 200001, 401, "Autenticación requerida o sesión expirada"),
        entry(ErrorCode.SCOPE_VIOLATION, 200003, 403, "El ámbito operacional actual no tiene acceso a este recurso"),
        entry(ErrorCode.ORGANIZATION_NOT_ACTIVE, 200005, 403, "La organización no se encuentra operativa"),
        entry(ErrorCode.NOT_FOUND, 300001, 404, "Recurso no encontrado"),
        entry(ErrorCode.INTERNAL_ERROR, 900001, 500, "Error interno del servidor")
    );

    private List<GatewaySecurityAudit> recordedAudits;

    @BeforeEach
    void setUp() {
        recordedAudits = new ArrayList<>();
        XF.init(
            () -> SEEDED_ENTRIES,
            FIXED_CLOCK,
            bearerToken -> {
                if ("token-amb01-us".equals(bearerToken)) {
                    return actor(ORG_ALFA, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT, null);
                }
                if ("token-amb02-em".equals(bearerToken)) {
                    return actor(ORG_ALFA, ActorType.USER, "EM", OperationalScope.AMB_02_PROFESSIONAL, PERSON_RES_ID);
                }
                if ("token-amb03-mg".equals(bearerToken)) {
                    return actor(ORG_ALFA, ActorType.USER, "MG", OperationalScope.AMB_03_MANAGEMENT, null);
                }
                if ("token-amb03-us-bad".equals(bearerToken)) {
                    return actor(ORG_ALFA, ActorType.USER, "US", OperationalScope.AMB_03_MANAGEMENT, null);
                }
                if ("token-amb02-disabled-res".equals(bearerToken)) {
                    return actor(
                        ORG_ALFA, ActorType.USER, "EM", OperationalScope.AMB_02_PROFESSIONAL, DISABLED_PERSON_RES_ID
                    );
                }
                if ("token-amb02-us-bad".equals(bearerToken)) {
                    return actor(ORG_ALFA, ActorType.USER, "US", OperationalScope.AMB_02_PROFESSIONAL, PERSON_RES_ID);
                }
                if ("token-beta".equals(bearerToken)) {
                    return actor(ORG_BETA, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT, null);
                }
                if ("token-platform-pa".equals(bearerToken)) {
                    return actor(null, ActorType.PLATFORM_ADMIN, "PA", OperationalScope.PLATFORM, null);
                }
                if ("token-m2m".equals(bearerToken)) {
                    return actor(ORG_ALFA, ActorType.EXTERNAL_CLIENT, "M2M", OperationalScope.EXTERNAL_CLIENT, null);
                }
                throw new GatewayBusinessException(ErrorCode.UNAUTHENTICATED, "Invalid token");
            },
            PERSON_RES_ID::equals,
            host -> !host.startsWith("suspended."),
            recordedAudits::add
        );
    }

    @AfterEach
    void tearDown() {
        XF.terminate();
    }

    @Test
    @DisplayName("PRU-SEG-03 & AC-005: Matriz cruzada 3 tokens (AMB-01/02/03) x 3 canales (/app,/workspace,/console)")
    void shouldEnforceCrossScopeMatrixAndAuditRejections() {
        List<String> tokens = List.of("token-amb01-us", "token-amb02-em", "token-amb03-mg");
        List<String> paths = List.of(
            "/api/v1/app/reservations",
            "/api/v1/workspace/agenda",
            "/api/v1/console/users"
        );

        for (int t = 0; t < tokens.size(); t++) {
            for (int p = 0; p < paths.size(); p++) {
                GatewayRequest req = new GatewayRequest(
                    "org-alfa.resourcehub.app",
                    paths.get(p),
                    "GET",
                    Map.of("X-Request-Id", "req-m-" + t + "-" + p, "Authorization", "Bearer " + tokens.get(t)),
                    null
                );
                Optional<GatewayResponse<Void>> result =
                    app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(req, ORG_ALFA);
                if (t == p) {
                    assertThat(result).isEmpty();
                } else {
                    assertThat(result).isPresent();
                    assertThat(result.get().httpStatus()).isEqualTo(403);
                    assertThat(result.get().body().code()).isEqualTo(200003);
                }
            }
        }
        assertThat(recordedAudits).hasSize(6);
        assertThat(recordedAudits).allMatch(audit -> audit.errorCode() == ErrorCode.SCOPE_VIOLATION);
    }

    @Test
    @DisplayName("PRU-SEG-01, PRU-SEG-02 & PRU-SEG-05: Aislamiento host plataforma, org mismatch y rutas auth/error")
    void shouldEnforcePlatformHostTenantIsolationPublicEndpointsAndRoleGuards() {
        // 1. PRU-SEG-01: Host organizativo intentando acceder a /api/v1/organizations -> 404 (300001) + audit
        GatewayResponse<Void> seg01 = app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                "org-alfa.resourcehub.app",
                "/api/v1/organizations",
                "GET",
                Map.of("X-Request-Id", "req-seg01", "Authorization", "Bearer token-platform-pa"),
                null
            ),
            ORG_ALFA
        ).orElseThrow();
        assertThat(seg01.httpStatus()).isEqualTo(404);
        assertThat(seg01.body().code()).isEqualTo(300001);

        // 2. Host plataforma accediendo a rutas org -> 404 (300001), y /organizations con PA -> PASS
        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                "platform.resourcehub.app:8443",
                "/api/v1/app/resources",
                "GET",
                Map.of("Authorization", "Bearer token-amb01-us"),
                null
            ),
            null
        )).isPresent().get().extracting(GatewayResponse::httpStatus).isEqualTo(404);

        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                "platform.resourcehub.app",
                "/api/v1/organizations",
                "GET",
                Map.of("Authorization", "Bearer token-platform-pa"),
                null
            ),
            null
        )).isEmpty();

        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                "platform.resourcehub.app",
                "/api/v1/organizations",
                "GET",
                Map.of("Authorization", "Bearer token-amb03-mg"),
                null
            ),
            null
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200003);

        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest("platform.resourcehub.app", "/api/v1/auth/login", "POST", Map.of(), null),
            null
        )).isEmpty();
        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest("platform.resourcehub.app", "/api/v1/error-catalog", "GET", Map.of(), null),
            null
        )).isEmpty();

        // 3. PRU-SEG-02: Token de org-beta en org-alfa -> 404 (300001)
        GatewayResponse<Void> seg02 = app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                "org-alfa.resourcehub.app",
                "/api/v1/app/reservations",
                "GET",
                Map.of("Authorization", "Bearer token-beta"),
                null
            ),
            ORG_ALFA
        ).orElseThrow();
        assertThat(seg02.httpStatus()).isEqualTo(404);
        assertThat(seg02.body().code()).isEqualTo(300001);

        // 4. Organizacion suspendida -> 403 ORGANIZATION_NOT_ACTIVE (200005)
        GatewayResponse<Void> suspended = app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest("suspended.resourcehub.app", "/api/v1/app/resources", "GET", Map.of(), null),
            ORG_ALFA
        ).orElseThrow();
        assertThat(suspended.httpStatus()).isEqualTo(403);
        assertThat(suspended.body().code()).isEqualTo(200005);

        // 5. Rutas auth/login, refresh, webhooks y external validas
        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest("org-alfa.resourcehub.app", "/api/v1/workspace/auth/login", "POST", Map.of(), null),
            ORG_ALFA
        )).isEmpty();
        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest("org-alfa.resourcehub.app", "/api/v1/webhooks/inbound/stripe", "POST", Map.of(), null),
            ORG_ALFA
        )).isEmpty();
        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                "org-alfa.resourcehub.app",
                "/api/v1/external/reservations",
                "GET",
                Map.of("Authorization", "Bearer token-m2m"),
                null
            ),
            ORG_ALFA
        )).isEmpty();
        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                "org-alfa.resourcehub.app",
                "/api/v1/external/reservations",
                "GET",
                Map.of("Authorization", "Bearer token-amb01-us"),
                null
            ),
            ORG_ALFA
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200003);

        // 6. Roles / PERSON deshabilitado en /workspace y /console -> 403 (200003)
        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                "org-alfa.resourcehub.app",
                "/api/v1/workspace/agenda",
                "GET",
                Map.of("Authorization", "Bearer token-amb02-disabled-res"),
                null
            ),
            ORG_ALFA
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200003);
        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                "org-alfa.resourcehub.app",
                "/api/v1/workspace/agenda",
                "GET",
                Map.of("Authorization", "Bearer token-amb02-us-bad"),
                null
            ),
            ORG_ALFA
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200003);
        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(
                "org-alfa.resourcehub.app",
                "/api/v1/console/users",
                "GET",
                Map.of("Authorization", "Bearer token-amb03-us-bad"),
                null
            ),
            ORG_ALFA
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200003);

        // 7. Sin JWT / token invalido / host nulo / ruta desconocida / enforceAndDispatch
        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest("org-alfa.resourcehub.app", "/api/v1/app/reservations", "GET", Map.of(), null),
            ORG_ALFA
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200001);

        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest(" ", "/api/v1/app/reservations", "GET", Map.of(), null),
            ORG_ALFA
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(100002);

        assertThat(app.resourcehub.gateway.api.A.edgeGateway().enforcePerimeter(
            new GatewayRequest("org-alfa.resourcehub.app", "/unknown/path", "GET", Map.of(), null),
            ORG_ALFA
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(300001);

        GatewayResponse<?> dispatchedOk = app.resourcehub.gateway.api.A.edgeGateway().enforceAndDispatch(
            new GatewayRequest(
                "org-alfa.resourcehub.app",
                "/api/v2/app/reservations",
                "GET",
                Map.of("X-Request-Id", "req-disp"),
                null
            ),
            actor(ORG_ALFA, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT, null),
            ORG_ALFA,
            () -> app.resourcehub.gateway.api.A.edgeGateway().handleOk(
                new GatewayRequest(
                    "org-alfa.resourcehub.app",
                    "/api/v2/app/reservations",
                    "GET",
                    Map.of("X-Request-Id", "req-disp"),
                    null
                ),
                "OK",
                "passed"
            )
        );
        assertThat(dispatchedOk.httpStatus()).isEqualTo(200);
        assertThat(dispatchedOk.body().data()).isEqualTo("passed");

        GatewayResponse<?> dispatchedKo = app.resourcehub.gateway.api.A.edgeGateway().enforceAndDispatch(
            new GatewayRequest(
                "org-alfa.resourcehub.app",
                "/api/v2/console/audit-logs",
                "GET",
                Map.of("X-Request-Id", "req-disp-ko"),
                null
            ),
            actor(ORG_ALFA, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT, null),
            ORG_ALFA,
            () -> app.resourcehub.gateway.api.A.edgeGateway().handleOk(
                new GatewayRequest("org-alfa.resourcehub.app", "/api/v2/console/audit-logs", "GET", Map.of(), null),
                "OK",
                "never"
            )
        );
        assertThat(dispatchedKo.httpStatus()).isEqualTo(403);
        assertThat(dispatchedKo.body().code()).isEqualTo(200003);

        assertThatThrownBy(() -> app.resourcehub.gateway.business.B.scopeEnforcement().enforce(null, null, null))
            .isInstanceOf(GatewayBusinessException.class);
    }

    @Test
    @DisplayName("TT-026 [PRU-01 / PRU-04] — Cobertura exhaustiva de rutas plataforma, roles AD/PA, bearer y wiring por defecto")
    void shouldCoverDefaultWiringPlatformPathsTokenEdgeCasesAndRoles() {
        var edge = app.resourcehub.gateway.api.A.edgeGateway();

        // Constructor por defecto sin lambdas (ScopeEnforcementBusiness)
        var defaultGuard = new app.resourcehub.gateway.business.logic.instance.ScopeEnforcementBusiness();
        assertThatThrownBy(() -> defaultGuard.enforce(
            new app.resourcehub.gateway.business.structs.GatewaySanitizedRequest(
                "org-alfa.resourcehub.app", " ", "GET", "req-null-path", Map.of(), null
            ),
            null,
            ORG_ALFA
        )).isInstanceOf(GatewayBusinessException.class);
        assertThatThrownBy(() -> defaultGuard.enforce(
            new app.resourcehub.gateway.business.structs.GatewaySanitizedRequest(
                "org-alfa.resourcehub.app", "/api/v1/app/items", "GET", "req-no-validator",
                Map.of("authorization", "Bearer some-token"), null
            ),
            null,
            ORG_ALFA
        )).isInstanceOf(GatewayBusinessException.class);
        assertThat(defaultGuard.enforce(
            new app.resourcehub.gateway.business.structs.GatewaySanitizedRequest(
                "org-alfa.resourcehub.app", "/api/v1/workspace/agenda", "GET", "req-default-pass",
                Map.of(), null
            ),
            actor(ORG_ALFA, ActorType.USER, "EM", OperationalScope.AMB_02_PROFESSIONAL, PERSON_RES_ID),
            ORG_ALFA
        ).actor().actorRole()).isEqualTo("EM");

        // Rutas exclusivas de plataforma en host plataforma vs host organizativo
        for (String platPath : List.of(
            "/api/v1/organizations/123",
            "/api/v1/audit-logs",
            "/api/v1/audit-logs/123",
            "/api/v1/platform",
            "/api/v1/platform/health",
            "/api/v1/error-catalog/100001",
            "/api/v1/auth",
            "/api/v1/auth/logout"
        )) {
            assertThat(edge.enforcePerimeter(
                new GatewayRequest("org-alfa.resourcehub.app", platPath, "GET", Map.of(), null), ORG_ALFA
            )).isPresent().get().extracting(GatewayResponse::httpStatus).isEqualTo(404);
            assertThat(edge.enforcePerimeter(
                new GatewayRequest(
                    "platform.resourcehub.app",
                    platPath,
                    "GET",
                    Map.of("Authorization", "Bearer token-platform-pa"),
                    null
                ),
                null
            )).isEmpty();
        }
        assertThat(edge.enforcePerimeter(
            new GatewayRequest("platform.resourcehub.app", "/api/v1/platform/error-catalog", "GET", Map.of(), null),
            null
        )).isEmpty();

        // Variantes de fallo en host plataforma: PLATFORM_ADMIN sin scope PLATFORM o sin rol PA
        assertThat(edge.enforcePerimeter(
            new GatewayRequest("platform.resourcehub.app", "/api/v1/organizations", "GET", Map.of(), null),
            actor(null, ActorType.PLATFORM_ADMIN, "PA", OperationalScope.AMB_03_MANAGEMENT, null),
            null
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200003);
        assertThat(edge.enforcePerimeter(
            new GatewayRequest("platform.resourcehub.app", "/api/v1/organizations", "GET", Map.of(), null),
            actor(null, ActorType.PLATFORM_ADMIN, "AD", OperationalScope.PLATFORM, null),
            null
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200003);

        // Roles AD en /console permitido, null linkedPersonResourceId en /workspace rechazado, EXTERNAL_CLIENT con scope errado
        assertThat(edge.enforcePerimeter(
            new GatewayRequest("org-alfa.resourcehub.app", "/api/v1/console/users", "GET", Map.of(), null),
            actor(ORG_ALFA, ActorType.USER, "AD", OperationalScope.AMB_03_MANAGEMENT, null),
            ORG_ALFA
        )).isEmpty();
        assertThat(edge.enforcePerimeter(
            new GatewayRequest("org-alfa.resourcehub.app", "/api/v1/workspace/agenda", "GET", Map.of(), null),
            actor(ORG_ALFA, ActorType.USER, "EM", OperationalScope.AMB_02_PROFESSIONAL, null),
            ORG_ALFA
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200003);
        assertThat(edge.enforcePerimeter(
            new GatewayRequest("org-alfa.resourcehub.app", "/api/v1/external/reservations", "GET", Map.of(), null),
            actor(ORG_ALFA, ActorType.EXTERNAL_CLIENT, "M2M", OperationalScope.AMB_01_CLIENT, null),
            ORG_ALFA
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200003);

        // Edge cases de token Bearer y tokenValidator devolviendo null o lanzando SharedDomainException
        for (String authHeader : List.of("Basic abc", "Bearer   ", "Short")) {
            assertThat(edge.enforcePerimeter(
                new GatewayRequest(
                    "org-alfa.resourcehub.app", "/api/v1/app/reservations", "GET",
                    Map.of("Authorization", authHeader), null
                ),
                ORG_ALFA
            )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200001);
        }
        assertThat(edge.enforcePerimeter(
            new GatewayRequest(
                "org-alfa.resourcehub.app", "/api/v1/app/reservations", "GET",
                Map.of("X-Request-Id", "req-custom", "Authorization", "Bearer unknown-token"), null
            ),
            ORG_ALFA
        )).isPresent().get().extracting(r -> r.body().code()).isEqualTo(200001);

        var nullValidatorGuard = new app.resourcehub.gateway.business.logic.instance.ScopeEnforcementBusiness(
            token -> {
                if ("scope-ex".equals(token)) {
                    throw new app.resourcehub.shared.business.structs.SharedDomainException(
                        ErrorCode.SCOPE_VIOLATION, "Scope failure"
                    );
                }
                return null;
            },
            null,
            null,
            null
        );
        assertThatThrownBy(() -> nullValidatorGuard.enforce(
            new app.resourcehub.gateway.business.structs.GatewaySanitizedRequest(
                "org-alfa.resourcehub.app", "/api/v1/app/items", "GET", "req-null-actor",
                Map.of("Authorization", "Bearer nil"), null
            ),
            null,
            null
        )).isInstanceOf(GatewayBusinessException.class)
            .extracting(e -> ((GatewayBusinessException) e).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThatThrownBy(() -> nullValidatorGuard.enforce(
            new app.resourcehub.gateway.business.structs.GatewaySanitizedRequest(
                "org-alfa.resourcehub.app", "/api/v1/app/items", "GET", "req-scope-ex",
                Map.of("Authorization", "Bearer scope-ex"), null
            ),
            null,
            null
        )).isInstanceOf(GatewayBusinessException.class)
            .extracting(e -> ((GatewayBusinessException) e).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);
    }

    private static ActorContext actor(
        UUID orgId,
        ActorType type,
        String role,
        OperationalScope scope,
        UUID linkedPersonId
    ) {
        return new ActorContext(
            UUID.fromString("01936a00-0099-7000-8000-000000000001"),
            orgId,
            type,
            role,
            scope,
            linkedPersonId,
            Set.of()
        );
    }

    private static ErrorCatalogEntry entry(ErrorCode code, int num, int status, String msg) {
        return new ErrorCatalogEntry(
            UUID.fromString("01936a00-0001-7000-8000-000000000001"),
            code,
            num,
            (short) (num / 10000),
            (short) ((num / 100) % 100),
            (short) status,
            msg,
            true,
            FIXED_INSTANT,
            FIXED_INSTANT
        );
    }
}
