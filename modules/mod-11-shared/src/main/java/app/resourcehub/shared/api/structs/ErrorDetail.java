package app.resourcehub.shared.api.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;

public record ErrorDetail(String field, String message) {

    public ErrorDetail {
        if (field != null && field.isBlank()) {
            field = null;
        }
        if (message == null || message.isBlank()) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "ErrorDetail message must not be blank");
        }
    }
}
