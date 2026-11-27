package app.resourcehub.resources.repository;

import app.resourcehub.resources.repository.logic.local.ResourceRepository;
import app.resourcehub.resources.repository.logic.local.ResourceTypeRepository;
import javax.sql.DataSource;

public final class R {

    private static ResourceTypeRepository resourceTypeRepository;
    private static ResourceRepository resourceRepository;

    private R() {
        throw new UnsupportedOperationException("Static XF repository injector");
    }

    public static void init() {
        resourceTypeRepository = new ResourceTypeRepository();
        resourceRepository = new ResourceRepository();
    }

    public static void init(DataSource dataSource) {
        resourceTypeRepository = new ResourceTypeRepository(dataSource);
        resourceRepository = new ResourceRepository(dataSource);
    }

    public static ResourceTypeRepository resourceTypes() {
        if (resourceTypeRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return resourceTypeRepository;
    }

    public static ResourceRepository resources() {
        if (resourceRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return resourceRepository;
    }

    public static void terminate() {
        resourceTypeRepository = null;
        resourceRepository = null;
    }
}
