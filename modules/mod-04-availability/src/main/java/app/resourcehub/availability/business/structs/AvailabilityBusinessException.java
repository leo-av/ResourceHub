package app.resourcehub.availability.business.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;

public class AvailabilityBusinessException extends SharedDomainException {

    public AvailabilityBusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
