package app.resourcehub.policies.business.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;

public class PoliciesBusinessException extends SharedDomainException {

    public PoliciesBusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
