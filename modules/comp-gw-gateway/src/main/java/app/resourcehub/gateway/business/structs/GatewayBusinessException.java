package app.resourcehub.gateway.business.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;

public class GatewayBusinessException extends SharedDomainException {

    public GatewayBusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
