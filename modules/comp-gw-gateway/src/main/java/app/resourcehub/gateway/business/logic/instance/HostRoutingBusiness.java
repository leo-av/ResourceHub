package app.resourcehub.gateway.business.logic.instance;

import app.resourcehub.gateway.business.structs.GatewayBusinessException;
import app.resourcehub.gateway.business.structs.GatewayRequest;
import app.resourcehub.gateway.business.structs.GatewaySanitizedRequest;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class HostRoutingBusiness {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final String INTERNAL_HEADER_PREFIX = "x-internal-";
    private static final Set<String> BLOCKED_EXACT_HEADERS = Set.of(
        "x-original-problem",
        "x-problem-details"
    );

    public GatewaySanitizedRequest sanitizeInboundRequest(GatewayRequest request) {
        if (request == null) {
            throw new GatewayBusinessException(ErrorCode.MALFORMED_REQUEST, "GatewayRequest must not be null");
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        String candidateRequestId = null;

        for (Map.Entry<String, String> entry : request.headers().entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.equals("x-request-id")) {
                candidateRequestId = entry.getValue();
                continue;
            }
            if (lower.startsWith(INTERNAL_HEADER_PREFIX) || BLOCKED_EXACT_HEADERS.contains(lower)) {
                continue;
            }
            if (entry.getValue() != null) {
                cleaned.put(key, entry.getValue());
            }
        }

        String effectiveRequestId = normalizeRequestId(candidateRequestId);
        cleaned.put(REQUEST_ID_HEADER, effectiveRequestId);

        return new GatewaySanitizedRequest(
            request.host(),
            request.path(),
            request.method(),
            effectiveRequestId,
            cleaned,
            request.body()
        );
    }

    public String normalizeRequestId(String candidate) {
        if (candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UuidV7Utils.generate().toString();
    }
}
