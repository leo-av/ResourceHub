package app.resourcehub.gateway.business.structs;

import java.util.Map;

public record GatewaySanitizedRequest(
    String host,
    String path,
    String method,
    String requestId,
    Map<String, String> cleanHeaders,
    Object body
) {

    public GatewaySanitizedRequest {
        cleanHeaders = cleanHeaders == null ? Map.of() : Map.copyOf(cleanHeaders);
    }
}
