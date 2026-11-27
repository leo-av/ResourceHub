package app.resourcehub.shared.api.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;
import java.util.List;

public record CursorPageResponse<T>(List<T> items, String nextCursor, boolean hasNext, int size) {

    public CursorPageResponse {
        if (size < 1) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "size must be >= 1");
        }
        if (hasNext && (nextCursor == null || nextCursor.isBlank())) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "nextCursor is required when hasNext is true");
        }
        if (!hasNext && nextCursor != null && !nextCursor.isBlank()) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "nextCursor must be null when hasNext is false");
        }
        if (!hasNext) {
            nextCursor = null;
        }
        items = items == null ? List.of() : List.copyOf(items);
    }
}
