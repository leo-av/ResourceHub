package app.resourcehub.gateway.repository.structs;

import app.resourcehub.shared.business.structs.ErrorCode;

public record CachedErrorDefinition(
    ErrorCode code,
    int numericValue,
    short httpStatus,
    String defaultMessage
) {
}
