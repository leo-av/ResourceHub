package app.resourcehub.resources.business.logic.instance;

import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.resources.business.structs.ResourcesBusinessException;
import app.resourcehub.resources.repository.logic.local.ResourceRepository;
import app.resourcehub.resources.repository.logic.local.ResourceTypeRepository;
import app.resourcehub.resources.repository.structs.ResourcesRepositoryException;
import app.resourcehub.resources.repository.structs.StoredResource;
import app.resourcehub.resources.repository.structs.StoredResourceType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ResourceBusiness {

    private static final int MAX_CODE_LENGTH = 50;
    private static final int MAX_NAME_LENGTH = 150;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final short ROOT_DEPTH = 1;

    private final ResourceRepository resourceRepository;
    private final ResourceTypeRepository resourceTypeRepository;
    private final Clock clock;

    public ResourceBusiness(
        ResourceRepository resourceRepository,
        ResourceTypeRepository resourceTypeRepository,
        Clock clock
    ) {
        if (resourceRepository == null) {
            throw new IllegalArgumentException("resourceRepository must not be null");
        }
        if (resourceTypeRepository == null) {
            throw new IllegalArgumentException("resourceTypeRepository must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.resourceRepository = resourceRepository;
        this.resourceTypeRepository = resourceTypeRepository;
        this.clock = clock;
    }

    public CatalogResource createSimpleResource(
        UUID resourceTypeId,
        String code,
        String name,
        String description,
        int capacity,
        boolean requiresApproval,
        boolean allowProfessionalApproval,
        UUID linkedUserId
    ) {
        if (resourceTypeId == null) {
            throw new ResourcesBusinessException(ErrorCode.VALIDATION_FAILED, "resourceTypeId must not be null");
        }
        String cleanCode = validateRequiredText(code, "code", MAX_CODE_LENGTH);
        String cleanName = validateRequiredText(name, "name", MAX_NAME_LENGTH);
        String cleanDescription = validateOptionalText(description, "description", MAX_DESCRIPTION_LENGTH);
        if (capacity < 1) {
            throw new ResourcesBusinessException(ErrorCode.VALIDATION_FAILED, "capacity must be >= 1");
        }

        StoredResourceType resourceType = resourceTypeRepository.findById(resourceTypeId)
            .orElseThrow(() -> new ResourcesBusinessException(ErrorCode.NOT_FOUND, "ResourceType not found"));
        if (!resourceType.active()) {
            throw new ResourcesBusinessException(
                ErrorCode.CATALOG_RESOURCE_NOT_BOOKABLE,
                "ResourceType is inactive"
            );
        }
        if (resourceRepository.findByCode(cleanCode).isPresent()) {
            throw new ResourcesBusinessException(
                ErrorCode.ALREADY_EXISTS,
                "Resource code already exists: " + cleanCode
            );
        }

        UUID resourceId = UuidV7Utils.generate();
        String hierarchyPath = toLtreeNodeLabel(resourceId);
        Instant now = clock.instant();
        StoredResource stored = new StoredResource(
            resourceId,
            resourceTypeId,
            cleanCode,
            cleanName,
            cleanDescription,
            ResourceStatus.AVAILABLE.name(),
            capacity,
            requiresApproval,
            allowProfessionalApproval,
            null,
            resourceId,
            hierarchyPath,
            ROOT_DEPTH,
            linkedUserId,
            now,
            now
        );
        try {
            return mapToDomain(resourceRepository.insert(stored));
        } catch (ResourcesRepositoryException ex) {
            if ("23505".equals(ex.getSqlState())) {
                throw new ResourcesBusinessException(
                    ErrorCode.ALREADY_EXISTS,
                    "Resource code already exists: " + cleanCode
                );
            }
            throw ex;
        }
    }

    public Optional<CatalogResource> findById(UUID id) {
        if (id == null) {
            throw new ResourcesBusinessException(ErrorCode.VALIDATION_FAILED, "resourceId must not be null");
        }
        return resourceRepository.findById(id).map(ResourceBusiness::mapToDomain);
    }

    public Optional<CatalogResource> findByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ResourcesBusinessException(ErrorCode.VALIDATION_FAILED, "code must not be blank");
        }
        return resourceRepository.findByCode(code.trim()).map(ResourceBusiness::mapToDomain);
    }

    public List<CatalogResource> listAll() {
        return resourceRepository.findAll().stream()
            .map(ResourceBusiness::mapToDomain)
            .toList();
    }

    public List<CatalogResource> listAvailable() {
        return resourceRepository.findByStatus(ResourceStatus.AVAILABLE.name()).stream()
            .map(ResourceBusiness::mapToDomain)
            .toList();
    }

    public CatalogResource disableResource(UUID id) {
        CatalogResource existing = findById(id)
            .orElseThrow(() -> new ResourcesBusinessException(ErrorCode.NOT_FOUND, "Resource not found"));
        if (existing.status() != ResourceStatus.AVAILABLE) {
            throw new ResourcesBusinessException(
                ErrorCode.TRANSITION_NOT_ALLOWED,
                "Only AVAILABLE resources can transition to DISABLED"
            );
        }
        return mapToDomain(resourceRepository.updateStatus(id, ResourceStatus.DISABLED.name(), clock.instant()));
    }

    public CatalogResource enableResource(UUID id) {
        CatalogResource existing = findById(id)
            .orElseThrow(() -> new ResourcesBusinessException(ErrorCode.NOT_FOUND, "Resource not found"));
        if (existing.status() != ResourceStatus.DISABLED) {
            throw new ResourcesBusinessException(
                ErrorCode.TRANSITION_NOT_ALLOWED,
                "Only DISABLED resources can transition to AVAILABLE"
            );
        }
        return mapToDomain(resourceRepository.updateStatus(id, ResourceStatus.AVAILABLE.name(), clock.instant()));
    }

    private static String toLtreeNodeLabel(UUID resourceId) {
        return "r_" + resourceId.toString().replace("-", "");
    }

    private static String validateRequiredText(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw new ResourcesBusinessException(ErrorCode.VALIDATION_FAILED, field + " must not be blank");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new ResourcesBusinessException(
                ErrorCode.VALIDATION_FAILED,
                field + " must not exceed " + maxLength + " characters"
            );
        }
        return trimmed;
    }

    private static String validateOptionalText(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new ResourcesBusinessException(
                ErrorCode.VALIDATION_FAILED,
                field + " must not exceed " + maxLength + " characters"
            );
        }
        return trimmed;
    }

    private static CatalogResource mapToDomain(StoredResource stored) {
        return new CatalogResource(
            stored.id(),
            stored.resourceTypeId(),
            stored.code(),
            stored.name(),
            stored.description(),
            ResourceStatus.valueOf(stored.status()),
            stored.capacity(),
            stored.requiresApproval(),
            stored.allowProfessionalApproval(),
            stored.parentId(),
            stored.rootId(),
            stored.hierarchyPath(),
            stored.depth(),
            stored.linkedUserId(),
            stored.createdAt(),
            stored.updatedAt()
        );
    }
}
