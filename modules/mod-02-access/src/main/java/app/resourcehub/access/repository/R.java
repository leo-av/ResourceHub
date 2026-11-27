package app.resourcehub.access.repository;

import app.resourcehub.access.repository.logic.local.LocalCredentialRepository;
import app.resourcehub.access.repository.logic.local.PersonalDataRepository;
import app.resourcehub.access.repository.logic.local.RefreshTokenRepository;
import app.resourcehub.access.repository.logic.local.UserRepository;
import javax.sql.DataSource;

public final class R {

    private static UserRepository userRepository;
    private static LocalCredentialRepository localCredentialRepository;
    private static RefreshTokenRepository refreshTokenRepository;
    private static PersonalDataRepository personalDataRepository;

    private R() {
        throw new UnsupportedOperationException("Static XF repository injector");
    }

    public static void init() {
        userRepository = new UserRepository();
        localCredentialRepository = new LocalCredentialRepository();
        refreshTokenRepository = new RefreshTokenRepository();
        personalDataRepository = new PersonalDataRepository();
    }

    public static void init(DataSource dataSource) {
        userRepository = new UserRepository(dataSource);
        localCredentialRepository = new LocalCredentialRepository();
        refreshTokenRepository = new RefreshTokenRepository();
        personalDataRepository = new PersonalDataRepository(dataSource);
    }

    public static UserRepository users() {
        if (userRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return userRepository;
    }

    public static LocalCredentialRepository localCredentials() {
        if (localCredentialRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return localCredentialRepository;
    }

    public static RefreshTokenRepository refreshTokens() {
        if (refreshTokenRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return refreshTokenRepository;
    }

    public static PersonalDataRepository personalData() {
        if (personalDataRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return personalDataRepository;
    }

    public static void terminate() {
        if (localCredentialRepository != null) {
            localCredentialRepository.clear();
        }
        if (refreshTokenRepository != null) {
            refreshTokenRepository.clear();
        }
        userRepository = null;
        localCredentialRepository = null;
        refreshTokenRepository = null;
        personalDataRepository = null;
    }
}
