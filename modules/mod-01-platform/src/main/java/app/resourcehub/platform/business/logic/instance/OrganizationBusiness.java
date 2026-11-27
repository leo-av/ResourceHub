package app.resourcehub.platform.business.logic.instance;

import app.resourcehub.platform.business.structs.Organization;
import app.resourcehub.platform.business.structs.OrganizationStatus;
import app.resourcehub.platform.business.structs.PlatformBusinessException;
import app.resourcehub.platform.repository.logic.local.OrganizationRepository;
import app.resourcehub.platform.repository.structs.StoredOrganization;
import app.resourcehub.shared.business.structs.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public class OrganizationBusiness {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{1,63}$");

    private final OrganizationRepository organizationRepository;

    public OrganizationBusiness(OrganizationRepository organizationRepository) {
        if (organizationRepository == null) {
            throw new IllegalArgumentException("organizationRepository must not be null");
        }
        this.organizationRepository = organizationRepository;
    }

    public Organization resolveActiveOrgBySlug(String orgSlug) {
        validateSlug(orgSlug);
        StoredOrganization stored = organizationRepository.findBySlug(orgSlug)
            .orElseThrow(() -> new PlatformBusinessException(ErrorCode.NOT_FOUND, "Organization not found"));
        Organization mapped = mapToDomain(stored);
        if (!mapped.isOperational()) {
            throw new PlatformBusinessException(
                ErrorCode.ORGANIZATION_NOT_ACTIVE,
                "Organization '" + orgSlug + "' is in status " + mapped.status()
            );
        }
        return mapped;
    }

    public Optional<Organization> findBySlug(String orgSlug) {
        validateSlug(orgSlug);
        return organizationRepository.findBySlug(orgSlug).map(OrganizationBusiness::mapToDomain);
    }

    public Optional<Organization> findById(UUID id) {
        if (id == null) {
            throw new PlatformBusinessException(ErrorCode.VALIDATION_FAILED, "organizationId must not be null");
        }
        return organizationRepository.findById(id).map(OrganizationBusiness::mapToDomain);
    }

    public List<Organization> listOrganizations() {
        return organizationRepository.findAll().stream()
            .map(OrganizationBusiness::mapToDomain)
            .toList();
    }

    private static void validateSlug(String orgSlug) {
        if (orgSlug == null || !SLUG_PATTERN.matcher(orgSlug).matches()) {
            throw new PlatformBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "orgSlug must match ^[a-z0-9-]{1,63}$"
            );
        }
    }

    private static Organization mapToDomain(StoredOrganization stored) {
        OrganizationStatus status;
        try {
            status = OrganizationStatus.valueOf(stored.status());
        } catch (IllegalArgumentException ex) {
            throw new PlatformBusinessException(
                ErrorCode.INTERNAL_ERROR,
                "Unknown organization status stored in database: " + stored.status()
            );
        }
        return new Organization(
            stored.id(),
            stored.slug(),
            stored.name(),
            status,
            stored.initialTimeZoneId(),
            stored.dbHost(),
            stored.dbPort(),
            stored.dbName(),
            stored.currentSchemaVersion(),
            stored.createdAt(),
            stored.updatedAt(),
            stored.archivedAt()
        );
    }
}
