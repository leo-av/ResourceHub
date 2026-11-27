package app.resourcehub.resources.business.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;

public class ResourcesBusinessException extends SharedDomainException {

    public ResourcesBusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
