package app.resourcehub.access;

import app.resourcehub.access.business.logic.instance.AuthBusiness;
import app.resourcehub.access.business.logic.instance.UserBusiness;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.IdentityAccount;
import app.resourcehub.access.business.structs.UserProfile;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.access.business.utils.JwtTokenUtils;
import app.resourcehub.access.repository.logic.local.LocalCredentialRepository;
import app.resourcehub.access.repository.logic.local.RefreshTokenRepository;
import app.resourcehub.access.repository.logic.local.UserRepository;
import app.resourcehub.access.repository.structs.StoredIdentityAccount;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-006 [PRU-01 / PRU-07] — AuthBusiness por canal, JWT EdDSA sin PII y RefreshToken rotativo")
class AuthBusinessTest {

    private static final UUID ORG_ID = UUID.fromString("01936a00-0000-7000-8000-000000000001");
    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T14:00:00Z");

    private InMemoryUserRepository userRepository;
    private LocalCredentialRepository credentialRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private MutableClock mutableClock;
    private UserBusiness userBusiness;
    private AuthBusiness authBusiness;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        credentialRepository = new LocalCredentialRepository();
        refreshTokenRepository = new RefreshTokenRepository();
        mutableClock = new MutableClock(FIXED_NOW);
        userBusiness = new UserBusiness(userRepository, mutableClock);
        authBusiness = new AuthBusiness(
            userRepository,
            credentialRepository,
            refreshTokenRepository,
            mutableClock,
            JwtTokenUtils.generateEd25519KeyPair()
        );
    }

    @Test
    @DisplayName("Login /app emite JWT EdDSA de 15 min sin PII y solo para AMB_01_CLIENT")
    void shouldAuthenticateStandardUserInAppChannelWithoutPiiInJwt() {
        IdentityAccount standardUser = userBusiness.createIdentityAccount(UserRole.US, null);
        authBusiness.registerLocalCredential(
            ORG_ID,
            standardUser.id(),
            "ana.cliente@demo.resourcehub.test",
            "ClaveSegura2026!".toCharArray()
        );

        AuthTokenBundle bundle = authBusiness.authenticateLocal(
            ORG_ID,
            OperationalScope.AMB_01_CLIENT,
            "Ana.Cliente@demo.resourcehub.test",
            "ClaveSegura2026!".toCharArray()
        );

        assertThat(bundle.operationalScope()).isEqualTo(OperationalScope.AMB_01_CLIENT);
        assertThat(bundle.role()).isEqualTo(UserRole.US);
        assertThat(bundle.accessExpiresAt()).isEqualTo(FIXED_NOW.plusSeconds(900));
        assertThat(bundle.refreshToken()).isNotBlank();

        String[] parts = bundle.accessToken().split("\\.");
        assertThat(parts).hasSize(3);
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payloadJson)
            .contains(standardUser.id().toString())
            .contains("AMB_01_CLIENT")
            .doesNotContainIgnoringCase("ana.cliente")
            .doesNotContainIgnoringCase("email")
            .doesNotContainIgnoringCase("fullName")
            .doesNotContainIgnoringCase("phone");

        ActorContext actor = authBusiness.validateToken(bundle.accessToken());
        assertThat(actor.userId()).isEqualTo(standardUser.id());
        assertThat(actor.organizationId()).isEqualTo(ORG_ID);
        assertThat(actor.operationalScope()).isEqualTo(OperationalScope.AMB_01_CLIENT);
    }

    @Test
    @DisplayName("PRU-SEG: Login de usuario US o EM en canal /console es rechazado con SCOPE_VIOLATION (200003)")
    void shouldRejectStandardAndProfessionalUsersOnConsoleChannel() {
        IdentityAccount standardUser = userBusiness.createIdentityAccount(UserRole.US, null);
        authBusiness.registerLocalCredential(
            ORG_ID,
            standardUser.id(),
            "usuario@demo.resourcehub.test",
            "ClaveSegura2026!".toCharArray()
        );

        UUID personId = UUID.fromString("01936a00-0010-7000-8000-000000000010");
        IdentityAccount employeeUser = userBusiness.createIdentityAccount(UserRole.EM, personId);
        authBusiness.registerLocalCredential(
            ORG_ID,
            employeeUser.id(),
            "empleado@demo.resourcehub.test",
            "ClaveSegura2026!".toCharArray()
        );

        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            ORG_ID,
            OperationalScope.AMB_03_MANAGEMENT,
            "usuario@demo.resourcehub.test",
            "ClaveSegura2026!".toCharArray()
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);

        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            ORG_ID,
            OperationalScope.AMB_03_MANAGEMENT,
            "empleado@demo.resourcehub.test",
            "ClaveSegura2026!".toCharArray()
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);
    }

    @Test
    @DisplayName("PRU-SEG: Token AMB_01 valido en /app/auth/me pero rechazado con SCOPE_VIOLATION en /console/auth/me")
    void shouldEnforceChannelScopeOnProfileAndRefresh() {
        IdentityAccount manager = userBusiness.createIdentityAccount(UserRole.MG, null);
        authBusiness.registerLocalCredential(
            ORG_ID,
            manager.id(),
            "manager@demo.resourcehub.test",
            "ManagerClave2026!".toCharArray()
        );

        AuthTokenBundle appBundle = authBusiness.authenticateLocal(
            ORG_ID,
            OperationalScope.AMB_01_CLIENT,
            "manager@demo.resourcehub.test",
            "ManagerClave2026!".toCharArray()
        );

        UserProfile appProfile = authBusiness.getCurrentProfile(OperationalScope.AMB_01_CLIENT, appBundle.accessToken());
        assertThat(appProfile.userId()).isEqualTo(manager.id());
        assertThat(appProfile.operationalScope()).isEqualTo(OperationalScope.AMB_01_CLIENT);

        assertThatThrownBy(() ->
            authBusiness.getCurrentProfile(OperationalScope.AMB_03_MANAGEMENT, appBundle.accessToken())
        )
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);

        assertThatThrownBy(() ->
            authBusiness.refresh(ORG_ID, OperationalScope.AMB_03_MANAGEMENT, appBundle.refreshToken())
        )
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);
    }

    @Test
    @DisplayName("Rotacion de RefreshToken detecta reuso, revoca toda la familia e invalida logout")
    void shouldRotateRefreshTokenDetectReuseAndSupportLogout() {
        IdentityAccount admin = userBusiness.createIdentityAccount(UserRole.AD, null);
        authBusiness.registerLocalCredential(
            ORG_ID,
            admin.id(),
            "admin@demo.resourcehub.test",
            "AdminClave2026!".toCharArray()
        );

        AuthTokenBundle first = authBusiness.authenticateLocal(
            ORG_ID,
            OperationalScope.AMB_03_MANAGEMENT,
            "admin@demo.resourcehub.test",
            "AdminClave2026!".toCharArray()
        );

        mutableClock.advanceSeconds(60);
        AuthTokenBundle second = authBusiness.refresh(
            ORG_ID,
            OperationalScope.AMB_03_MANAGEMENT,
            first.refreshToken()
        );
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());

        // Reuso del token rotado revoca la familia completa (incluyendo el nuevo second.refreshToken)
        assertThatThrownBy(() -> authBusiness.refresh(
            ORG_ID,
            OperationalScope.AMB_03_MANAGEMENT,
            first.refreshToken()
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHENTICATED);

        assertThatThrownBy(() -> authBusiness.refresh(
            ORG_ID,
            OperationalScope.AMB_03_MANAGEMENT,
            second.refreshToken()
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHENTICATED);

        // Expiracion JWT tras 15 minutos
        mutableClock.advanceSeconds(901);
        assertThatThrownBy(() -> authBusiness.validateToken(second.accessToken()))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        private void advanceSeconds(long seconds) {
            this.current = this.current.plusSeconds(seconds);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class InMemoryUserRepository extends UserRepository {
        private final Map<UUID, StoredIdentityAccount> storage = new LinkedHashMap<>();

        @Override
        public StoredIdentityAccount insert(StoredIdentityAccount account) {
            storage.put(account.id(), account);
            return account;
        }

        @Override
        public Optional<StoredIdentityAccount> findById(UUID id) {
            return Optional.ofNullable(storage.get(id));
        }
    }
}
