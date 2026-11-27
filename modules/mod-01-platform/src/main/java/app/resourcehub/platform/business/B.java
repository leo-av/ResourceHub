package app.resourcehub.platform.business;

import app.resourcehub.platform.business.logic.instance.ErrorCatalogBusiness;
import app.resourcehub.platform.business.logic.instance.OrgSettingsBusiness;
import app.resourcehub.platform.business.logic.instance.OrganizationBusiness;
import app.resourcehub.platform.repository.R;

public final class B {

    private static OrganizationBusiness organizationBusiness;
    private static ErrorCatalogBusiness errorCatalogBusiness;
    private static OrgSettingsBusiness orgSettingsBusiness;

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        organizationBusiness = new OrganizationBusiness(R.organization());
        errorCatalogBusiness = new ErrorCatalogBusiness(R.errorCatalog());
        orgSettingsBusiness = new OrgSettingsBusiness(R.orgSettings());
    }

    public static OrganizationBusiness organization() {
        if (organizationBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return organizationBusiness;
    }

    public static ErrorCatalogBusiness errorCatalog() {
        if (errorCatalogBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return errorCatalogBusiness;
    }

    public static OrgSettingsBusiness orgSettings() {
        if (orgSettingsBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return orgSettingsBusiness;
    }

    public static void terminate() {
        organizationBusiness = null;
        errorCatalogBusiness = null;
        orgSettingsBusiness = null;
    }
}
