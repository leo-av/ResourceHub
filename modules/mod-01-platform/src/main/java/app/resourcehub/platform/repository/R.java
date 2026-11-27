package app.resourcehub.platform.repository;

import app.resourcehub.platform.repository.logic.local.ErrorCatalogRepository;
import app.resourcehub.platform.repository.logic.local.OrgSettingsRepository;
import app.resourcehub.platform.repository.logic.local.OrganizationRepository;
import javax.sql.DataSource;

public final class R {

    private static OrganizationRepository organizationRepository;
    private static ErrorCatalogRepository errorCatalogRepository;
    private static OrgSettingsRepository orgSettingsRepository;

    private R() {
        throw new UnsupportedOperationException("Static XF repository injector");
    }

    public static void init() {
        organizationRepository = new OrganizationRepository();
        errorCatalogRepository = new ErrorCatalogRepository();
        orgSettingsRepository = new OrgSettingsRepository();
    }

    public static void init(DataSource dataSource) {
        organizationRepository = new OrganizationRepository(dataSource);
        errorCatalogRepository = new ErrorCatalogRepository(dataSource);
        orgSettingsRepository = new OrgSettingsRepository(dataSource);
    }

    public static OrganizationRepository organization() {
        if (organizationRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return organizationRepository;
    }

    public static ErrorCatalogRepository errorCatalog() {
        if (errorCatalogRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return errorCatalogRepository;
    }

    public static OrgSettingsRepository orgSettings() {
        if (orgSettingsRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return orgSettingsRepository;
    }

    public static void terminate() {
        organizationRepository = null;
        errorCatalogRepository = null;
        orgSettingsRepository = null;
    }
}
