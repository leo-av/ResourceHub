package app.resourcehub.platform.api.logic.services;

import app.resourcehub.platform.business.B;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.time.Clock;
import java.util.List;

public class ErrorCatalogService {

    public ResponseWrapper<List<ErrorCatalogEntry>> getErrorCatalog(String requestId, Clock clock) {
        String effectiveRequestId = (requestId == null || requestId.isBlank())
            ? UuidV7Utils.generate().toString()
            : requestId;
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        List<ErrorCatalogEntry> entries = B.errorCatalog().loadErrorCatalog();
        return ResponseWrapper.ok("OK", entries, effectiveRequestId, effectiveClock.instant());
    }
}
