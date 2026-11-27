package app.resourcehub.platform.business.logic.instance;

import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import app.resourcehub.platform.business.structs.PlatformBusinessException;
import app.resourcehub.platform.repository.logic.local.ErrorCatalogRepository;
import app.resourcehub.platform.repository.structs.StoredErrorCatalogItem;
import app.resourcehub.shared.business.structs.ErrorCode;
import java.util.List;
import java.util.Optional;

public class ErrorCatalogBusiness {

    private final ErrorCatalogRepository errorCatalogRepository;

    public ErrorCatalogBusiness(ErrorCatalogRepository errorCatalogRepository) {
        if (errorCatalogRepository == null) {
            throw new IllegalArgumentException("errorCatalogRepository must not be null");
        }
        this.errorCatalogRepository = errorCatalogRepository;
    }

    public List<ErrorCatalogEntry> loadErrorCatalog() {
        return errorCatalogRepository.findAllActive().stream()
            .map(ErrorCatalogBusiness::mapToDomain)
            .toList();
    }

    public Optional<ErrorCatalogEntry> findByErrorCode(ErrorCode code) {
        if (code == null) {
            throw new PlatformBusinessException(ErrorCode.VALIDATION_FAILED, "code must not be null");
        }
        return errorCatalogRepository.findByCode(code.name()).map(ErrorCatalogBusiness::mapToDomain);
    }

    private static ErrorCatalogEntry mapToDomain(StoredErrorCatalogItem item) {
        ErrorCode parsedCode;
        try {
            parsedCode = ErrorCode.valueOf(item.code());
        } catch (IllegalArgumentException ex) {
            throw new PlatformBusinessException(
                ErrorCode.INTERNAL_ERROR,
                "Unknown ErrorCode persisted in platform_catalog.error_catalog: " + item.code()
            );
        }
        if (item.numericValue() < 0 || item.httpStatus() < 200 || item.httpStatus() > 599) {
            throw new PlatformBusinessException(
                ErrorCode.INTERNAL_ERROR,
                "Invalid error_catalog row metadata for code: " + item.code()
            );
        }
        return new ErrorCatalogEntry(
            item.id(),
            parsedCode,
            item.numericValue(),
            item.familyCode(),
            item.moduleCode(),
            item.httpStatus(),
            item.defaultMessage(),
            item.active(),
            item.createdAt(),
            item.updatedAt()
        );
    }
}
