package app.resourcehub.platform.api.logic.services;

import app.resourcehub.platform.business.B;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public class ErrorCatalogService {

    public ResponseWrapper<List<ErrorCatalogEntry>> getErrorCatalog(String requestId, Clock clock) {
        String effectiveRequestId = (requestId == null || requestId.isBlank())
            ? UUID.randomUUID().toString()
            : requestId;
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        List<ErrorCatalogEntry> entries = B.errorCatalog().loadErrorCatalog();
        return ResponseWrapper.ok("OK", entries, effectiveRequestId, effectiveClock.instant());
    }
}
