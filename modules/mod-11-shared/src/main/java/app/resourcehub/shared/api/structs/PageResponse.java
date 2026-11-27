package app.resourcehub.shared.api.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;
import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public PageResponse {
        if (page < 1) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "page must be >= 1");
        }
        if (size < 1) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "size must be >= 1");
        }
        if (totalItems < 0L) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "totalItems must be >= 0");
        }
        if (totalPages < 0) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "totalPages must be >= 0");
        }
        items = items == null ? List.of() : List.copyOf(items);
    }
}
