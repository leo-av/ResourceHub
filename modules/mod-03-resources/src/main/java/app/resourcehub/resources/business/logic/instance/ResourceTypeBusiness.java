package app.resourcehub.resources.business.logic.instance;

import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.resources.business.structs.ResourcesBusinessException;
import app.resourcehub.resources.repository.logic.local.ResourceTypeRepository;
import app.resourcehub.resources.repository.structs.ResourcesRepositoryException;
import app.resourcehub.resources.repository.structs.StoredResourceType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.ResourceNature;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ResourceTypeBusiness {

    private static final int MAX_CODE_LENGTH = 50;
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final ResourceTypeRepository resourceTypeRepository;
    private final Clock clock;

    public ResourceTypeBusiness(ResourceTypeRepository resourceTypeRepository, Clock clock) {
        if (resourceTypeRepository == null) {
            throw new IllegalArgumentException("resourceTypeRepository must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.resourceTypeRepository = resourceTypeRepository;
        this.clock = clock;
    }

    public ResourceType createResourceType(
        String code,
        String name,
        String description,
        ResourceNature nature
    ) {
        String cleanCode = validateRequiredText(code, "code", MAX_CODE_LENGTH);
        String cleanName = validateRequiredText(name, "name", MAX_NAME_LENGTH);
        String cleanDescription = validateOptionalText(description, "description", MAX_DESCRIPTION_LENGTH);
        if (nature == null) {
            throw new ResourcesBusinessException(ErrorCode.VALIDATION_FAILED, "nature must not be null");
        }
        if (resourceTypeRepository.findByCode(cleanCode).isPresent()) {
            throw new ResourcesBusinessException(
                ErrorCode.ALREADY_EXISTS,
                "ResourceType code already exists: " + cleanCode
            );
        }

        Instant now = clock.instant();
        StoredResourceType stored = new StoredResourceType(
            UuidV7Utils.generate(),
            cleanCode,
            cleanName,
            cleanDescription,
            nature.name(),
            true,
            now,
            now
        );
        try {
            return mapToDomain(resourceTypeRepository.insert(stored));
        } catch (ResourcesRepositoryException ex) {
            if ("23505".equals(ex.getSqlState())) {
                throw new ResourcesBusinessException(
                    ErrorCode.ALREADY_EXISTS,
                    "ResourceType code already exists: " + cleanCode
                );
            }
            throw ex;
        }
    }

    public Optional<ResourceType> findById(UUID id) {
        if (id == null) {
            throw new ResourcesBusinessException(ErrorCode.VALIDATION_FAILED, "resourceTypeId must not be null");
        }
        return resourceTypeRepository.findById(id).map(ResourceTypeBusiness::mapToDomain);
    }

    public Optional<ResourceType> findByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ResourcesBusinessException(ErrorCode.VALIDATION_FAILED, "code must not be blank");
        }
        return resourceTypeRepository.findByCode(code.trim()).map(ResourceTypeBusiness::mapToDomain);
    }

    public List<ResourceType> listResourceTypes() {
        return resourceTypeRepository.findAll().stream()
            .map(ResourceTypeBusiness::mapToDomain)
            .toList();
    }

    public ResourceType setActive(UUID id, boolean active) {
        ResourceType existing = findById(id)
            .orElseThrow(() -> new ResourcesBusinessException(ErrorCode.NOT_FOUND, "ResourceType not found"));
        StoredResourceType updated = resourceTypeRepository.updateActive(existing.id(), active, clock.instant());
        return mapToDomain(updated);
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

    private static ResourceType mapToDomain(StoredResourceType stored) {
        return new ResourceType(
            stored.id(),
            stored.code(),
            stored.name(),
            stored.description(),
            ResourceNature.valueOf(stored.natureCode()),
            stored.active(),
            stored.createdAt(),
            stored.updatedAt()
        );
    }
}
