package app.resourcehub.gateway.api.logic.services;

import app.resourcehub.gateway.api.structs.GatewayResponse;
import app.resourcehub.gateway.business.B;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.gateway.business.structs.GatewaySanitizedRequest;
import app.resourcehub.gateway.business.structs.TranslatedError;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
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
        ResponseWrapper<T> wrapper = B.errorTranslation().buildOkWrapper(description, data, sanitized.requestId());
        return new GatewayResponse<>(200, sanitized.requestId(), responseHeaders(sanitized.requestId()), wrapper);
    }

    public <T> GatewayResponse<T> handleResponse(GatewayRequest request, int httpStatus, ResponseWrapper<T> alreadyWrapped) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        return new GatewayResponse<>(httpStatus, sanitized.requestId(), responseHeaders(sanitized.requestId()), alreadyWrapped);
    }

    public GatewayResponse<Void> handleInternalProblem(GatewayRequest request, InternalProblem problem) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        TranslatedError translated = B.errorTranslation().translateProblem(problem, sanitized.requestId());
        return new GatewayResponse<>(
            translated.httpStatus(),
            sanitized.requestId(),
            responseHeaders(sanitized.requestId()),
            translated.wrapper()
        );
    }

    public GatewayResponse<Void> handleNotFound(GatewayRequest request) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        TranslatedError translated = B.errorTranslation().translateNotFound(sanitized.requestId());
        return new GatewayResponse<>(
            translated.httpStatus(),
            sanitized.requestId(),
            responseHeaders(sanitized.requestId()),
            translated.wrapper()
        );
    }

    public GatewayResponse<Void> handleUnhandledFailure(GatewayRequest request, Throwable exception) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        TranslatedError translated = B.errorTranslation().translateUnhandled(exception, sanitized.requestId());
        return new GatewayResponse<>(
            translated.httpStatus(),
            sanitized.requestId(),
            responseHeaders(sanitized.requestId()),
            translated.wrapper()
        );
    }

    public GatewayResponse<Void> handleServiceUnavailable(GatewayRequest request) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        TranslatedError translated = B.errorTranslation().translateServiceUnavailable(sanitized.requestId());
        return new GatewayResponse<>(
            translated.httpStatus(),
            sanitized.requestId(),
            responseHeaders(sanitized.requestId()),
            translated.wrapper()
        );
    }

    public GatewayResponse<List<ErrorCatalogEntry>> handleErrorCatalogEndpoint(GatewayRequest request) {
        GatewaySanitizedRequest sanitized = B.hostRouting().sanitizeInboundRequest(request);
        ResponseWrapper<List<ErrorCatalogEntry>> upstream =
            app.resourcehub.platform.api.A.errorCatalog().getErrorCatalog(sanitized.requestId(), clock);
        return new GatewayResponse<>(200, sanitized.requestId(), responseHeaders(sanitized.requestId()), upstream);
    }

    private static Map<String, String> responseHeaders(String requestId) {
        return Map.of("X-Request-Id", requestId);
    }
}
