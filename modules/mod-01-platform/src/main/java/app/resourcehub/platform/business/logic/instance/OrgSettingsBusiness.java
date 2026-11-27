package app.resourcehub.platform.business.logic.instance;

import app.resourcehub.platform.business.structs.OrganizationSettings;
import app.resourcehub.platform.business.structs.PlatformBusinessException;
import app.resourcehub.platform.repository.logic.local.OrgSettingsRepository;
import app.resourcehub.platform.repository.structs.StoredOrganizationSettings;
import app.resourcehub.shared.business.structs.ErrorCode;
import java.util.UUID;

public class OrgSettingsBusiness {

    private final OrgSettingsRepository orgSettingsRepository;

    public OrgSettingsBusiness(OrgSettingsRepository orgSettingsRepository) {
        if (orgSettingsRepository == null) {
            throw new IllegalArgumentException("orgSettingsRepository must not be null");
        }
        this.orgSettingsRepository = orgSettingsRepository;
    }

    public OrganizationSettings getOrgSettings(UUID organizationId) {
        if (organizationId == null) {
            throw new PlatformBusinessException(ErrorCode.VALIDATION_FAILED, "organizationId must not be null");
        }
        StoredOrganizationSettings stored = orgSettingsRepository.findSingleton()
            .orElseThrow(() -> new PlatformBusinessException(
                ErrorCode.NOT_FOUND,
                "Organization settings singleton not found"
            ));
        if (!stored.organizationId().equals(organizationId)) {
            throw new PlatformBusinessException(
                ErrorCode.NOT_FOUND,
                "Organization settings not found for organization"
            );
        }
        return mapToDomain(stored);
    }

    private static OrganizationSettings mapToDomain(StoredOrganizationSettings stored) {
        return new OrganizationSettings(
            stored.singletonKey(),
            stored.organizationId(),
            stored.timeZoneId(),
            stored.defaultCurrencyCode(),
            stored.maxResourceDepth(),
            stored.seriesMaxHorizonDays(),
            stored.seriesMaxOccurrences(),
            stored.waitlistMaxEntries(),
            stored.waitlistOfferWindowMinutes(),
            stored.approvalExpirationWindowHours(),
            stored.attendanceAutoCompleteWindowHours(),
            stored.allowStandardUserSpaceBooking(),
            stored.updatedAt()
        );
    }
}
