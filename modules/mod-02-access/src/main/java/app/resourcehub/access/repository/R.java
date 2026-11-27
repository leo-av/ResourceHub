package app.resourcehub.access.repository;

import app.resourcehub.access.repository.logic.local.UserRepository;
import javax.sql.DataSource;

public final class R {

    private static UserRepository userRepository;

    private R() {
        throw new UnsupportedOperationException("Static XF repository injector");
    }

    public static void init() {
        userRepository = new UserRepository();
    }

    public static void init(DataSource dataSource) {
        userRepository = new UserRepository(dataSource);
    }

    public static UserRepository users() {
        if (userRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return userRepository;
    }

    public static void terminate() {
        userRepository = null;
    }
}
