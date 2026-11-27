package app.resourcehub.reservations.business.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;

public class ReservationsBusinessException extends SharedDomainException {

    public ReservationsBusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
