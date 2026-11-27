package app.resourcehub.platform.business.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;

public class PlatformBusinessException extends SharedDomainException {

    public PlatformBusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
