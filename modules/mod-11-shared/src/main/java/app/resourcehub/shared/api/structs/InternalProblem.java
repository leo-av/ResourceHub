package app.resourcehub.shared.api.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

public record InternalProblem(
        URI type,
        String title,
        int status,
        String detail,
        String instance,
        ErrorCode code,
        String requestId,
        UUID policyId,
        List<ErrorDetail> fieldErrors,
        List<ErrorDetail> lineErrors,
        List<ErrorDetail> occurrenceErrors) {

    public InternalProblem {
        if (type == null) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "type must not be null");
        }
        if (title == null || title.isBlank()) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "title must not be blank");
        }
        if (status < 400 || status > 599) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "status must be between 400 and 599");
        }
        if (code == null || code == ErrorCode.OK) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "code must be a non-OK ErrorCode");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "requestId must not be blank");
        }
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        lineErrors = lineErrors == null ? List.of() : List.copyOf(lineErrors);
        occurrenceErrors = occurrenceErrors == null ? List.of() : List.copyOf(occurrenceErrors);
    }
}
