package app.resourcehub.gateway.api.logic.services;

import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.B;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.gateway.business.structs.GatewaySanitizedRequest;
import app.resourcehub.gateway.business.structs.TranslatedError;
import app.resourcehub.gateway.business.structs.TranslatedErrorDetail;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import app.resourcehub.shared.api.structs.ErrorDetail;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;

public class EdgeGatewayService {

    private final Clock clock;

    public EdgeGatewayService(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public <T> GatewayResponse<T> handleOk(GatewayRequest request, String description, T data) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        ResponseWrapper<T> wrapper = ResponseWrapper.ok(
            description,
            data,
            sanitized.requestId(),
            B.errorTranslation().currentTimestamp()
        );
        return new GatewayResponse<>(200, sanitized.requestId(), responseHeaders(sanitized.requestId()), wrapper);
    }

    public <T> GatewayResponse<T> handleResponse(GatewayRequest request, int httpStatus, ResponseWrapper<T> alreadyWrapped) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        return new GatewayResponse<>(httpStatus, sanitized.requestId(), responseHeaders(sanitized.requestId()), alreadyWrapped);
    }

    public GatewayResponse<Void> handleInternalProblem(GatewayRequest request, InternalProblem problem) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        if (problem == null) {
            return toGatewayResponse(
                sanitized.requestId(),
                B.errorTranslation().translateUnhandled(new IllegalArgumentException("InternalProblem null"), sanitized.requestId())
            );
        }
        List<TranslatedErrorDetail> fields = problem.fieldErrors().stream()
            .map(err -> new TranslatedErrorDetail(err.field(), err.message()))
            .toList();
        TranslatedError translated = B.errorTranslation().translate(problem.code(), fields);
        return toGatewayResponse(sanitized.requestId(), translated);
    }

    public GatewayResponse<Void> handleNotFound(GatewayRequest request) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        return toGatewayResponse(sanitized.requestId(), B.errorTranslation().translateNotFound());
    }

    public GatewayResponse<Void> handleUnhandledFailure(GatewayRequest request, Throwable exception) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        return toGatewayResponse(
            sanitized.requestId(),
            B.errorTranslation().translateUnhandled(exception, sanitized.requestId())
        );
    }

    public GatewayResponse<Void> handleServiceUnavailable(GatewayRequest request) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        return toGatewayResponse(sanitized.requestId(), B.errorTranslation().translateServiceUnavailable());
    }

    public GatewayResponse<List<ErrorCatalogEntry>> handleErrorCatalogEndpoint(GatewayRequest request) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        ResponseWrapper<List<ErrorCatalogEntry>> upstream =
            app.resourcehub.platform.api.A.errorCatalog().getErrorCatalog(sanitized.requestId(), clock);
        return new GatewayResponse<>(200, sanitized.requestId(), responseHeaders(sanitized.requestId()), upstream);
    }

    private static GatewayResponse<Void> toGatewayResponse(String requestId, TranslatedError translated) {
        List<ErrorDetail> apiErrors = translated.errors().stream()
            .map(err -> new ErrorDetail(err.field(), err.message()))
            .toList();
        ResponseWrapper<Void> wrapper = ResponseWrapper.error(
            translated.numericCode(),
            apiErrors,
            requestId,
            translated.timestamp()
        );
        return new GatewayResponse<>(
            translated.httpStatus(),
            requestId,
            responseHeaders(requestId),
            wrapper
        );
    }

    private static Map<String, String> responseHeaders(String requestId) {
        return Map.of("X-Request-Id", requestId);
    }
}
