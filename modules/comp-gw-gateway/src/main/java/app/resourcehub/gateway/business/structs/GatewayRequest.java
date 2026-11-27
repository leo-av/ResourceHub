package app.resourcehub.gateway.business.structs;

import java.util.Map;

public record GatewayRequest(
    String host,
    String path,
    String method,
    Map<String, String> headers,
    Object body
) {

    public GatewayRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
