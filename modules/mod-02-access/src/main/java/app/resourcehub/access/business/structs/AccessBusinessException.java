package app.resourcehub.access.business.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;

public class AccessBusinessException extends SharedDomainException {

    public AccessBusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
