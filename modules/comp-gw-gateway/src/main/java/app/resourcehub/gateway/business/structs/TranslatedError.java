package app.resourcehub.gateway.business.structs;

import app.resourcehub.shared.api.structs.ResponseWrapper;

public record TranslatedError(int httpStatus, ResponseWrapper<Void> wrapper) {
}
