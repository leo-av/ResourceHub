package app.resourcehub.gateway.api.structs;

import app.resourcehub.shared.api.structs.ResponseWrapper;
import java.util.Map;

public record GatewayResponse<T>(
    int httpStatus,
    String requestId,
    Map<String, String> headers,
    ResponseWrapper<T> body
) {

    public GatewayResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
