package app.resourcehub.gateway.business.logic.instance;

import app.resourcehub.gateway.business.structs.TranslatedError;
import app.resourcehub.gateway.business.structs.TranslatedErrorDetail;
import app.resourcehub.gateway.repository.logic.local.ErrorCatalogCacheRepository;
import app.resourcehub.gateway.repository.structs.CachedErrorDefinition;
import app.resourcehub.shared.business.structs.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ErrorTranslationBusiness {

    private static final Logger LOGGER = Logger.getLogger(ErrorTranslationBusiness.class.getName());

    private final ErrorCatalogCacheRepository cacheRepository;
    private final Clock clock;

    public ErrorTranslationBusiness(ErrorCatalogCacheRepository cacheRepository, Clock clock) {
        if (cacheRepository == null) {
            throw new IllegalArgumentException("cacheRepository must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.cacheRepository = cacheRepository;
        this.clock = clock;
    }

    public Instant currentTimestamp() {
        return clock.instant();
    }

    public TranslatedError translate(ErrorCode errorCode, List<TranslatedErrorDetail> fieldErrors) {
        if (errorCode == null || errorCode == ErrorCode.OK) {
            return translateUnhandled(new IllegalArgumentException("ErrorCode must not be null or OK"), "n/a");
        }
        CachedErrorDefinition definition = cacheRepository.resolve(errorCode.name());
        List<TranslatedErrorDetail> safeErrors;
        if (errorCode == ErrorCode.VALIDATION_FAILED && fieldErrors != null && !fieldErrors.isEmpty()) {
            safeErrors = fieldErrors;
        } else {
            safeErrors = List.of(new TranslatedErrorDetail(null, definition.defaultMessage()));
        }
        return new TranslatedError(definition.httpStatus(), definition.numericValue(), clock.instant(), safeErrors);
    }

    public TranslatedError translateNotFound() {
        CachedErrorDefinition definition = cacheRepository.resolve(ErrorCode.NOT_FOUND.name());
        return new TranslatedError(
            definition.httpStatus(),
            definition.numericValue(),
            clock.instant(),
            List.of(new TranslatedErrorDetail(null, definition.defaultMessage()))
        );
    }

    public TranslatedError translateUnhandled(Throwable exception, String requestId) {
        LOGGER.log(Level.SEVERE, "Unhandled error correlation requestId=" + requestId, exception);
        CachedErrorDefinition definition = cacheRepository.resolve(ErrorCode.INTERNAL_ERROR.name());
        return new TranslatedError(
            definition.httpStatus(),
            definition.numericValue(),
            clock.instant(),
            List.of(new TranslatedErrorDetail(null, definition.defaultMessage()))
        );
    }

    public TranslatedError translateServiceUnavailable() {
        CachedErrorDefinition definition = cacheRepository.resolve(ErrorCode.SERVICE_UNAVAILABLE.name());
        return new TranslatedError(
            definition.httpStatus(),
            definition.numericValue(),
            clock.instant(),
            List.of(new TranslatedErrorDetail(null, definition.defaultMessage()))
        );
    }
}
