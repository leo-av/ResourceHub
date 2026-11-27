package app.resourcehub.gateway.business.logic.instance;

import app.resourcehub.gateway.business.structs.TranslatedError;
import app.resourcehub.gateway.repository.logic.local.ErrorCatalogCacheRepository;
import app.resourcehub.gateway.repository.structs.CachedErrorDefinition;
import app.resourcehub.shared.api.structs.ErrorDetail;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ErrorCode;
import java.time.Clock;
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

    public <T> ResponseWrapper<T> buildOkWrapper(String description, T data, String requestId) {
        return ResponseWrapper.ok(description, data, requestId, clock.instant());
    }

    public TranslatedError translateProblem(InternalProblem problem, String requestId) {
        if (problem == null) {
            return translateUnhandled(new IllegalArgumentException("InternalProblem must not be null"), requestId);
        }
        CachedErrorDefinition definition = cacheRepository.resolve(problem.code());
        List<ErrorDetail> safeErrors;
        if (problem.code() == ErrorCode.VALIDATION_FAILED && !problem.fieldErrors().isEmpty()) {
            safeErrors = problem.fieldErrors();
        } else {
            safeErrors = List.of(new ErrorDetail(null, definition.defaultMessage()));
        }
        ResponseWrapper<Void> wrapper = ResponseWrapper.error(
            definition.numericValue(),
            safeErrors,
            requestId,
            clock.instant()
        );
        return new TranslatedError(definition.httpStatus(), wrapper);
    }

    public TranslatedError translateNotFound(String requestId) {
        CachedErrorDefinition definition = cacheRepository.resolve(ErrorCode.NOT_FOUND);
        ResponseWrapper<Void> wrapper = ResponseWrapper.error(
            definition.numericValue(),
            List.of(new ErrorDetail(null, definition.defaultMessage())),
            requestId,
            clock.instant()
        );
        return new TranslatedError(definition.httpStatus(), wrapper);
    }

    public TranslatedError translateUnhandled(Throwable exception, String requestId) {
        LOGGER.log(Level.SEVERE, "Unhandled error correlation requestId=" + requestId, exception);
        CachedErrorDefinition definition = cacheRepository.resolve(ErrorCode.INTERNAL_ERROR);
        ResponseWrapper<Void> wrapper = ResponseWrapper.error(
            definition.numericValue(),
            List.of(new ErrorDetail(null, definition.defaultMessage())),
            requestId,
            clock.instant()
        );
        return new TranslatedError(definition.httpStatus(), wrapper);
    }

    public TranslatedError translateServiceUnavailable(String requestId) {
        CachedErrorDefinition definition = cacheRepository.resolve(ErrorCode.SERVICE_UNAVAILABLE);
        ResponseWrapper<Void> wrapper = ResponseWrapper.error(
            definition.numericValue(),
            List.of(new ErrorDetail(null, definition.defaultMessage())),
            requestId,
            clock.instant()
        );
        return new TranslatedError(definition.httpStatus(), wrapper);
    }
}
