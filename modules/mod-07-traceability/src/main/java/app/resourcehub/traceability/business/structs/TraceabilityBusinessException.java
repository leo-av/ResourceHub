package app.resourcehub.traceability.business.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;

public class TraceabilityBusinessException extends SharedDomainException {

    public TraceabilityBusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
