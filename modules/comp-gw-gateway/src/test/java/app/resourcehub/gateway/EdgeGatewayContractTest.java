package app.resourcehub.gateway;

import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.gateway.business.structs.GatewaySanitizedRequest;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import app.resourcehub.shared.api.structs.ErrorDetail;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TT-004 [PRU-04 / PRU-SEG-04 / PRU-SEG-10] — API Gateway COMP-GW y Contrato ResponseWrapper")
class EdgeGatewayContractTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-27T12:30:00.456Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private static final List<ErrorCatalogEntry> SEEDED_ENTRIES = List.of(
        entry(ErrorCode.OK, 0, 0, 0, 200, "OK"),
        entry(ErrorCode.VALIDATION_FAILED, 100001, 10, 0, 400, "Error de validación en los datos enviados"),
        entry(ErrorCode.NOT_FOUND, 300001, 30, 0, 404, "Recurso no encontrado"),
        entry(ErrorCode.BOOKING_CONFLICT, 400501, 40, 5, 409, "La franja seleccionada ya está ocupada"),
        entry(ErrorCode.POLICY_QUOTA_EXCEEDED, 500602, 50, 6, 422, "Se ha superado el cupo máximo de reservas permitido"),
        entry(ErrorCode.INTERNAL_ERROR, 900001, 90, 0, 500, "Error interno del servidor"),
        entry(ErrorCode.SERVICE_UNAVAILABLE, 900002, 90, 0, 503, "Servicio temporalmente no disponible")
    );

    @BeforeEach
    void initGateway() {
        XF.init(() -> SEEDED_ENTRIES, FIXED_CLOCK);
    }

    @AfterEach
    void tearDownGateway() {
        XF.terminate();
    }

    @Test
    @DisplayName("PRU-04 (1 y 2): OK con datos y OK sin datos omiten errors y correlacionan X-Request-Id")
    void shouldWrapOkWithAndWithoutDataAndAvoidDoubleWrapping() {
        GatewayRequest request = new GatewayRequest(
            "org-alfa.resourcehub.app",
            "/api/v1/app/resources",
            "GET",
            Map.of("X-Request-Id", "req-valid_01.trace"),
            null
        );

        GatewayResponse<Map<String, Object>> okWithData =
            app.resourcehub.gateway.api.A.edgeGateway().handleOk(request, "OK", Map.of("id", 42));
        assertThat(okWithData.httpStatus()).isEqualTo(200);
        assertThat(okWithData.requestId()).isEqualTo("req-valid_01.trace");
        assertThat(okWithData.headers().get("X-Request-Id")).isEqualTo("req-valid_01.trace");
        assertThat(okWithData.body().code()).isZero();
        assertThat(okWithData.body().description()).isEqualTo("OK");
        assertThat(okWithData.body().data()).containsEntry("id", 42);
        assertThat(okWithData.body().errors()).isNull();
        assertThat(okWithData.body().timestamp()).isEqualTo("2026-11-27T12:30:00.456Z");

        GatewayResponse<Void> okWithoutData =
            app.resourcehub.gateway.api.A.edgeGateway().handleOk(request, "Operación completada", null);
        assertThat(okWithoutData.body().code()).isZero();
        assertThat(okWithoutData.body().description()).isEqualTo("Operación completada");
        assertThat(okWithoutData.body().data()).isNull();
        assertThat(okWithoutData.body().errors()).isNull();

        ResponseWrapper<String> preWrapped = ResponseWrapper.ok("Ya envuelto", "payload", "inner-req", FIXED_INSTANT);
        GatewayResponse<?> nonDoubleWrapped =
            app.resourcehub.gateway.api.A.edgeGateway().handleResponse(request, 200, preWrapped);
        assertThat(nonDoubleWrapped.body().data()).isEqualTo("payload");
        assertThat(nonDoubleWrapped.body().code()).isZero();
    }

    @Test
    @DisplayName("PRU-04 (3 y 4): Traduce InternalProblem RFC 9457 de validación (100001) y negocio (500602) sin fugas")
    void shouldTranslateValidationAndDomainInternalProblemsWithoutLeakingTechnicalDetails() {
        GatewayRequest request = new GatewayRequest(
            "org-alfa.resourcehub.app",
            "/api/v1/app/reservations",
            "POST",
            Map.of("X-Request-Id", "req-domain-01"),
            null
        );

        InternalProblem validationProblem = new InternalProblem(
            URI.create("urn:resourcehub:problem:validation"),
            "Validation Failed",
            400,
            "SELECT * FROM reservations.reservation failed at constraint chk_interval",
            "/api/v1/app/reservations",
            ErrorCode.VALIDATION_FAILED,
            "req-domain-01",
            null,
            List.of(new ErrorDetail("startAt", "Debe ser anterior a endAt")),
            List.of(),
            List.of()
        );
        GatewayResponse<Void> validationResponse =
            app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(request, validationProblem);

        assertThat(validationResponse.httpStatus()).isEqualTo(400);
        assertThat(validationResponse.body().code()).isEqualTo(100001);
        assertThat(validationResponse.body().description()).isNull();
        assertThat(validationResponse.body().data()).isNull();
        assertThat(validationResponse.body().errors())
            .containsExactly(new ErrorDetail("startAt", "Debe ser anterior a endAt"));

        InternalProblem policyProblem = new InternalProblem(
            URI.create("urn:resourcehub:problem:policy"),
            "Policy Quota Exceeded",
            422,
            "Internal stack: PolicyEvaluationBusiness.java:88 quota=3",
            "/api/v1/app/reservations",
            ErrorCode.POLICY_QUOTA_EXCEEDED,
            "req-domain-01",
            UUID.fromString("01936a00-0000-7000-8000-000000000099"),
            List.of(),
            List.of(),
            List.of()
        );
        GatewayResponse<Void> domainResponse =
            app.resourcehub.gateway.api.A.edgeGateway().handleInternalProblem(request, policyProblem);

        assertThat(domainResponse.httpStatus()).isEqualTo(422);
        assertThat(domainResponse.body().code()).isEqualTo(500602);
        assertThat(domainResponse.body().description()).isNull();
        assertThat(domainResponse.body().data()).isNull();
        assertThat(domainResponse.body().errors())
            .containsExactly(new ErrorDetail(null, "Se ha superado el cupo máximo de reservas permitido"));
    }

    @Test
    @DisplayName("PRU-04 (5, 6 y 7): Ruta inexistente 404 (300001), error no controlado 500 (900001) y X-Request-Id inseguro")
    void shouldHandleNotFoundUnhandledErrorAndUnsafeRequestId() {
        String tooLong = "x".repeat(129);
        GatewayRequest unsafeRequest = new GatewayRequest(
            "org-alfa.resourcehub.app",
            "/api/v1/app/unknown",
            "GET",
            Map.of("X-Request-Id", tooLong),
            null
        );

        GatewayResponse<Void> notFound = app.resourcehub.gateway.api.A.edgeGateway().handleNotFound(unsafeRequest);
        assertThat(notFound.httpStatus()).isEqualTo(404);
        assertThat(notFound.body().code()).isEqualTo(300001);
        assertThat(notFound.requestId()).isNotEqualTo(tooLong).hasSize(36);
        assertThat(notFound.headers().get("X-Request-Id")).isEqualTo(notFound.requestId());

        GatewayRequest injectedControlChars = new GatewayRequest(
            "org-alfa.resourcehub.app",
            "/api/v1/app/reservations",
            "POST",
            Map.of("X-Request-Id", "bad\r\nid with spaces"),
            null
        );
        GatewayResponse<Void> unhandled = app.resourcehub.gateway.api.A.edgeGateway().handleUnhandledFailure(
            injectedControlChars,
            new IllegalStateException("JDBC password leak /var/secrets/pg")
        );
        assertThat(unhandled.httpStatus()).isEqualTo(500);
        assertThat(unhandled.body().code()).isEqualTo(900001);
        assertThat(unhandled.body().errors()).containsExactly(new ErrorDetail(null, "Error interno del servidor"));
        assertThat(unhandled.requestId()).matches("^[A-Za-z0-9._-]{1,128}$");
    }

    @Test
    @DisplayName("PRU-SEG-04 y PRU-SEG-10: Elimina cabeceras internas y activa fallback estático 900001/900002 si cae BD")
    void shouldStripInternalHeadersAndUseStaticFallbackWhenPlatformDbIsDown() {
        GatewayRequest spoofed = new GatewayRequest(
            "org-alfa.resourcehub.app",
            "/api/v1/app/reservations",
            "POST",
            Map.of(
                "X-Request-Id", "safe-id-123",
                "X-Internal-Problem-Details", "true",
                "x-internal-org-id", "01936a00-0000-7000-8000-000000000099",
                "X-Internal-Actor-Context", "PLATFORM_ADMIN",
                "Authorization", "Bearer jwt-token"
            ),
            null
        );

        GatewaySanitizedRequest sanitized =
            app.resourcehub.gateway.business.B.hostRouting().sanitizeInboundRequest(spoofed);
        assertThat(sanitized.cleanHeaders())
            .containsEntry("X-Request-Id", "safe-id-123")
            .containsEntry("Authorization", "Bearer jwt-token")
            .doesNotContainKeys(
                "X-Internal-Problem-Details",
                "x-internal-org-id",
                "X-Internal-Actor-Context"
            );

        XF.terminate();
        XF.init(() -> {
            throw new IllegalStateException("Connection to resourcehub_platform_db refused");
        }, FIXED_CLOCK);

        GatewayResponse<Void> fallback500 = app.resourcehub.gateway.api.A.edgeGateway().handleUnhandledFailure(
            spoofed,
            new RuntimeException("Unexpected crash")
        );
        assertThat(fallback500.httpStatus()).isEqualTo(500);
        assertThat(fallback500.body().code()).isEqualTo(900001);

        GatewayResponse<Void> fallback503 = app.resourcehub.gateway.api.A.edgeGateway().handleServiceUnavailable(spoofed);
        assertThat(fallback503.httpStatus()).isEqualTo(503);
        assertThat(fallback503.body().code()).isEqualTo(900002);
    }

    private static ErrorCatalogEntry entry(
        ErrorCode code,
        int numericValue,
        int familyCode,
        int moduleCode,
        int httpStatus,
        String message
    ) {
        return new ErrorCatalogEntry(
            UUID.fromString("01936a00-0001-7000-8000-000000000001"),
            code,
            numericValue,
            (short) familyCode,
            (short) moduleCode,
            (short) httpStatus,
            message,
            true,
            FIXED_INSTANT,
            FIXED_INSTANT
        );
    }
}
