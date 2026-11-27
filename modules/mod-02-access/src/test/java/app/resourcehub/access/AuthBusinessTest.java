package app.resourcehub.access;

import app.resourcehub.access.business.logic.instance.AuthBusiness;
import app.resourcehub.access.business.logic.instance.UserBusiness;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.IdentityAccount;
import app.resourcehub.access.business.structs.IdentityStatus;
import app.resourcehub.access.business.structs.UserProfile;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.access.business.structs.UserSummary;
import app.resourcehub.access.business.utils.JwtTokenUtils;
import app.resourcehub.access.repository.logic.local.LocalCredentialRepository;
import app.resourcehub.access.repository.logic.local.PersonalDataRepository;
import app.resourcehub.access.repository.logic.local.RefreshTokenRepository;
import app.resourcehub.access.repository.logic.local.UserRepository;
import app.resourcehub.access.repository.structs.AccessRepositoryException;
import app.resourcehub.access.repository.structs.StoredIdentityAccount;
import app.resourcehub.access.repository.structs.StoredPersonalData;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-006 [PRU-01 / PRU-07] — AuthBusiness por canal, JWT EdDSA sin PII y RefreshToken rotativo")
class AuthBusinessTest {

    private static final UUID ORG_ID = UUID.fromString("01936a00-0000-7000-8000-000000000001");
    private static final UUID OTHER_ORG_ID = UUID.fromString("01936a00-0000-7000-8000-000000000099");
    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T14:00:00Z");

    private InMemoryUserRepository userRepository;
    private LocalCredentialRepository credentialRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private MutableClock mutableClock;
    private KeyPair keyPair;
    private UserBusiness userBusiness;
    private AuthBusiness authBusiness;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        credentialRepository = new LocalCredentialRepository();
        refreshTokenRepository = new RefreshTokenRepository();
        mutableClock = new MutableClock(FIXED_NOW);
        keyPair = JwtTokenUtils.generateEd25519KeyPair();
        userBusiness = new UserBusiness(userRepository, mutableClock);
        authBusiness = new AuthBusiness(
            userRepository,
            credentialRepository,
            refreshTokenRepository,
            mutableClock,
            keyPair
        );
    }

    @Test
    @DisplayName("Validaciones de constructor en AuthBusiness")
    void shouldValidateConstructorArguments() {
        assertThatThrownBy(() -> new AuthBusiness(null, credentialRepository, refreshTokenRepository, mutableClock, keyPair))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthBusiness(userRepository, null, refreshTokenRepository, mutableClock, keyPair))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthBusiness(userRepository, credentialRepository, null, mutableClock, keyPair))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthBusiness(userRepository, credentialRepository, refreshTokenRepository, null, keyPair))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthBusiness(userRepository, credentialRepository, refreshTokenRepository, mutableClock, null))
            .isInstanceOf(IllegalArgumentException.class);
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

    @Test
    @DisplayName("Ramas completas de validacion, credenciales, inactividad, cross-org y logout en AuthBusiness")
    void shouldCoverAllValidationFailureAndSecurityBranchesInAuthBusiness() {
        // 1. Registro de credenciales: errores de validacion
        UUID uid = UUID.fromString("01936a00-0010-7000-8000-000000000055");
        assertThatThrownBy(() -> authBusiness.registerLocalCredential(null, uid, "a@b.test", "12345678".toCharArray()))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.registerLocalCredential(ORG_ID, null, "a@b.test", "12345678".toCharArray()))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.registerLocalCredential(ORG_ID, uid, null, "12345678".toCharArray()))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.registerLocalCredential(ORG_ID, uid, "   ", "12345678".toCharArray()))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.registerLocalCredential(ORG_ID, uid, "sin-arroba", "12345678".toCharArray()))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.registerLocalCredential(ORG_ID, uid, "a@b.test", null))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.registerLocalCredential(ORG_ID, uid, "a@b.test", "short".toCharArray()))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // 2. Canal no soportado en login/refresh/logout/me (EXTERNAL_CLIENT no usa login local de usuario)
        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.EXTERNAL_CLIENT, "a@b.test", "12345678".toCharArray()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);

        // 3. Validaciones de entrada en authenticateLocal
        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            null, OperationalScope.AMB_01_CLIENT, "a@b.test", "12345678".toCharArray()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, null, "12345678".toCharArray()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, " ", "12345678".toCharArray()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "a@b.test", null
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "a@b.test", new char[0]
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        // 4. Credencial inexistente y cuenta huerfana o inactiva
        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "nobody@b.test", "12345678".toCharArray()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        authBusiness.registerLocalCredential(ORG_ID, uid, "orphan@b.test", "OrphanSecret2026!".toCharArray());
        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "orphan@b.test", "OrphanSecret2026!".toCharArray()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        IdentityAccount activeUser = userBusiness.createIdentityAccount(UserRole.US, null);
        authBusiness.registerLocalCredential(ORG_ID, activeUser.id(), "active@b.test", "ActiveSecret2026!".toCharArray());
        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "active@b.test", "WrongPass2026!".toCharArray()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        AuthTokenBundle validBundle = authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "active@b.test", "ActiveSecret2026!".toCharArray()
        );

        // Validaciones refresh: campos invalidos, token desconocido, otra org, cuenta eliminada tras emitir token
        assertThatThrownBy(() -> authBusiness.refresh(null, OperationalScope.AMB_01_CLIENT, "tok"))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.refresh(ORG_ID, OperationalScope.AMB_01_CLIENT, null))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.refresh(ORG_ID, OperationalScope.AMB_01_CLIENT, "   "))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> authBusiness.refresh(ORG_ID, OperationalScope.AMB_01_CLIENT, "unknown-token"))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThatThrownBy(() -> authBusiness.refresh(OTHER_ORG_ID, OperationalScope.AMB_01_CLIENT, validBundle.refreshToken()))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);

        // Logout: mismatch de canal, otro usuario, sin refresh token, y token valido
        IdentityAccount secondUser = userBusiness.createIdentityAccount(UserRole.US, null);
        authBusiness.registerLocalCredential(ORG_ID, secondUser.id(), "second@b.test", "SecondSecret2026!".toCharArray());
        AuthTokenBundle secondBundle = authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "second@b.test", "SecondSecret2026!".toCharArray()
        );

        assertThatThrownBy(() -> authBusiness.logout(
            OperationalScope.AMB_03_MANAGEMENT, validBundle.accessToken(), validBundle.refreshToken()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);

        assertThatThrownBy(() -> authBusiness.logout(
            OperationalScope.AMB_01_CLIENT, validBundle.accessToken(), secondBundle.refreshToken()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);

        authBusiness.logout(OperationalScope.AMB_01_CLIENT, validBundle.accessToken(), null);
        authBusiness.logout(OperationalScope.AMB_01_CLIENT, validBundle.accessToken(), " ");
        authBusiness.logout(OperationalScope.AMB_01_CLIENT, validBundle.accessToken(), validBundle.refreshToken());

        // Desactivar cuenta invalida login, validateToken y refresh
        userBusiness.deactivateAccount(secondUser.id());
        assertThatThrownBy(() -> authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "second@b.test", "SecondSecret2026!".toCharArray()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThatThrownBy(() -> authBusiness.validateToken(secondBundle.accessToken()))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThatThrownBy(() -> authBusiness.refresh(
            ORG_ID, OperationalScope.AMB_01_CLIENT, secondBundle.refreshToken()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);

        // Cuenta eliminada completamente del repositorio
        userRepository.removeForTest(activeUser.id());
        assertThatThrownBy(() -> authBusiness.validateToken(validBundle.accessToken()))
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);

        // Expiracion del refresh token tras 7 dias
        IdentityAccount freshUser = userBusiness.createIdentityAccount(UserRole.US, null);
        authBusiness.registerLocalCredential(ORG_ID, freshUser.id(), "fresh@b.test", "FreshSecret2026!".toCharArray());
        AuthTokenBundle freshBundle = authBusiness.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "fresh@b.test", "FreshSecret2026!".toCharArray()
        );
        userRepository.removeForTest(freshUser.id());
        assertThatThrownBy(() -> authBusiness.refresh(
            ORG_ID, OperationalScope.AMB_01_CLIENT, freshBundle.refreshToken()
        )).extracting(ex -> ((AccessBusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("TT-016 [PRU-01]: PII Vault personal_data, unicidad LOWER(email) e hidratacion O(1) con baja RGPD")
    void shouldPersistCredentialsInPersonalVaultResolveSummariesAndHandleGdprAbsence() {
        InMemoryPersonalDataRepository vaultRepo = new InMemoryPersonalDataRepository();
        AuthBusiness vaultAuth = new AuthBusiness(
            userRepository,
            credentialRepository,
            refreshTokenRepository,
            vaultRepo,
            mutableClock,
            keyPair
        );

        assertThatThrownBy(() -> new AuthBusiness(
            userRepository, credentialRepository, refreshTokenRepository, null, mutableClock, keyPair
        )).isInstanceOf(IllegalArgumentException.class);

        IdentityAccount user1 = userBusiness.createIdentityAccount(UserRole.US, null);
        IdentityAccount user2 = userBusiness.createIdentityAccount(UserRole.MG, null);
        UUID deletedUserId = UUID.fromString("01936a00-0099-7000-8000-000000000099");

        UserSummary summary1 = vaultAuth.registerPersonalProfile(user1.id(), "Ana.Gomez@Example.com", "Ana Gomez", "+34600111222");
        assertThat(summary1.fullName()).isEqualTo("Ana Gomez");
        assertThat(summary1.email()).isEqualTo("ana.gomez@example.com");
        assertThat(summary1.anonymized()).isFalse();

        // Registrar credencial preserva fullName y phone existentes y permite autenticar desde vault
        vaultAuth.registerLocalCredential(ORG_ID, user1.id(), "ana.gomez@example.com", "VaultPass2026!".toCharArray());
        AuthTokenBundle tokenBundle = vaultAuth.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "ANA.GOMEZ@example.com", "VaultPass2026!".toCharArray()
        );
        assertThat(tokenBundle.userId()).isEqualTo(user1.id());

        // Registrar credencial directa sin perfil previo usa prefijo de email como nombre inicial
        vaultAuth.registerLocalCredential(ORG_ID, user2.id(), "carlos@example.com", "ManagerVault2026!".toCharArray());

        // Email duplicado en otro usuario -> ALREADY_EXISTS
        assertThatThrownBy(() -> vaultAuth.registerPersonalProfile(
            user2.id(), "ANA.GOMEZ@example.com", "Carlos Ruiz", null
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_EXISTS);

        // Hidratacion O(1) en lote con 2 usuarios existentes + 1 ausente -> "Usuario eliminado (RGPD)"
        Set<UUID> requested = new LinkedHashSet<>();
        requested.add(user1.id());
        requested.add(user2.id());
        requested.add(deletedUserId);
        Map<UUID, UserSummary> resolved = vaultAuth.resolveUserSummaries(requested);
        assertThat(resolved).hasSize(3);
        assertThat(resolved.get(user1.id()).fullName()).isEqualTo("Ana Gomez");
        assertThat(resolved.get(user2.id()).fullName()).isEqualTo("carlos");
        assertThat(resolved.get(deletedUserId).anonymized()).isTrue();
        assertThat(resolved.get(deletedUserId).fullName()).isEqualTo("Usuario eliminado (RGPD)");
        assertThat(resolved.get(deletedUserId).email()).isNull();

        assertThat(vaultAuth.resolveUserSummaries(Set.of())).isEmpty();
        assertThat(authBusiness.resolveUserSummaries(Set.of(user1.id())).get(user1.id()).anonymized()).isTrue();

        // Validaciones de errores
        assertThatThrownBy(() -> vaultAuth.resolveUserSummaries(null))
            .isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultAuth.registerPersonalProfile(null, "a@b.com", "Nombre", null))
            .isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultAuth.registerPersonalProfile(user1.id(), "a@b.com", "   ", null))
            .isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultAuth.registerPersonalProfile(user1.id(), "a@b.com", "x".repeat(151), null))
            .isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultAuth.registerPersonalProfile(user1.id(), "a@b.com", "Nombre", "1".repeat(33)))
            .isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultAuth.registerPersonalProfile(user1.id(), "a".repeat(250) + "@b.com", "Nombre", null))
            .isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultAuth.registerPersonalProfile(UUID.randomUUID(), "new@b.com", "Nombre", null))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
        userRepository.updateStatus(user2.id(), IdentityStatus.DELETED_GDPR.name(), FIXED_NOW, FIXED_NOW);
        assertThatThrownBy(() -> vaultAuth.registerPersonalProfile(user2.id(), "carlos@example.com", "Carlos", null))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.TRANSITION_NOT_ALLOWED);
        assertThatThrownBy(() -> authBusiness.registerPersonalProfile(user1.id(), "ana@b.com", "Ana", null))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vaultAuth.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "noexiste@example.com", "Pass2026!".toCharArray()
        )).isInstanceOf(AccessBusinessException.class);
        IdentityAccount noPassUser = userBusiness.createIdentityAccount(UserRole.US, null);
        vaultAuth.registerPersonalProfile(noPassUser.id(), "nopass@example.com", "Sin Pass", null);
        assertThatThrownBy(() -> vaultAuth.authenticateLocal(
            ORG_ID, OperationalScope.AMB_01_CLIENT, "nopass@example.com", "AnyPass2026!".toCharArray()
        )).isInstanceOf(AccessBusinessException.class);
    }

    @Test
    @DisplayName("TT-025 PRU-SEG-05: Canal AMB_02_PROFESSIONAL exige personal + PERSON activo en login/refresh")
    void shouldSupportWorkspaceChannelAndEnforcePrSeg05Rules() {
        InMemoryPersonalDataRepository vaultRepo = new InMemoryPersonalDataRepository();
        UUID activePersonId = UUID.fromString("01936a00-0010-7000-8000-000000000801");
        UUID disabledPersonId = UUID.fromString("01936a00-0010-7000-8000-000000000802");
        final boolean[] personActiveFlag = {true};

        AuthBusiness workspaceAuth = new AuthBusiness(
            userRepository,
            credentialRepository,
            refreshTokenRepository,
            vaultRepo,
            mutableClock,
            keyPair,
            personId -> activePersonId.equals(personId) && personActiveFlag[0]
        );

        // 1. US y EM sin recurso PERSON -> 403 SCOPE_VIOLATION (PRU-SEG-05)
        IdentityAccount standardUser = userBusiness.createIdentityAccount(UserRole.US, null);
        workspaceAuth.registerLocalCredential(
            ORG_ID, standardUser.id(), "us.ws@demo.test", "ClientPass2026!".toCharArray()
        );
        assertThatThrownBy(() -> workspaceAuth.authenticateLocal(
            ORG_ID, OperationalScope.AMB_02_PROFESSIONAL, "us.ws@demo.test", "ClientPass2026!".toCharArray()
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);

        IdentityAccount unlinkedStaff = userBusiness.createIdentityAccount(UserRole.EM, null);
        workspaceAuth.registerLocalCredential(
            ORG_ID, unlinkedStaff.id(), "unlinked.em@demo.test", "StaffPass2026!".toCharArray()
        );
        assertThatThrownBy(() -> workspaceAuth.authenticateLocal(
            ORG_ID, OperationalScope.AMB_02_PROFESSIONAL, "unlinked.em@demo.test", "StaffPass2026!".toCharArray()
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);

        // 2. EM con PERSON DISABLED -> 403 SCOPE_VIOLATION (RN-ACC-003)
        IdentityAccount disabledPersonStaff = userBusiness.createIdentityAccount(UserRole.EM, disabledPersonId);
        workspaceAuth.registerLocalCredential(
            ORG_ID, disabledPersonStaff.id(), "disabled.person@demo.test", "StaffPass2026!".toCharArray()
        );
        assertThatThrownBy(() -> workspaceAuth.authenticateLocal(
            ORG_ID, OperationalScope.AMB_02_PROFESSIONAL, "disabled.person@demo.test", "StaffPass2026!".toCharArray()
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);

        // 3. EM activo con PERSON activo -> emite JWT AMB_02_PROFESSIONAL con lpr y sin PII
        IdentityAccount activeStaff = userBusiness.createIdentityAccount(UserRole.EM, activePersonId);
        workspaceAuth.registerLocalCredential(
            ORG_ID, activeStaff.id(), "active.pro@demo.test", "StaffPass2026!".toCharArray()
        );
        AuthTokenBundle bundle = workspaceAuth.authenticateLocal(
            ORG_ID, OperationalScope.AMB_02_PROFESSIONAL, "active.pro@demo.test", "StaffPass2026!".toCharArray()
        );
        assertThat(bundle.operationalScope()).isEqualTo(OperationalScope.AMB_02_PROFESSIONAL);
        ActorContext actor = workspaceAuth.validateToken(bundle.accessToken());
        assertThat(actor.linkedPersonResourceId()).isEqualTo(activePersonId);
        assertThat(actor.operationalScope()).isEqualTo(OperationalScope.AMB_02_PROFESSIONAL);

        UserProfile wsProfile = workspaceAuth.getCurrentProfile(OperationalScope.AMB_02_PROFESSIONAL, bundle.accessToken());
        assertThat(wsProfile.linkedPersonResourceId()).isEqualTo(activePersonId);
        assertThatThrownBy(() -> workspaceAuth.getCurrentProfile(OperationalScope.AMB_01_CLIENT, bundle.accessToken()))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);
        assertThatThrownBy(() -> workspaceAuth.getCurrentProfile(OperationalScope.AMB_03_MANAGEMENT, bundle.accessToken()))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);

        // 4. Refresh exitoso y luego bloqueo cuando el recurso PERSON pasa a DISABLED
        AuthTokenBundle rotated = workspaceAuth.refresh(
            ORG_ID, OperationalScope.AMB_02_PROFESSIONAL, bundle.refreshToken()
        );
        assertThat(rotated.refreshToken()).isNotEqualTo(bundle.refreshToken());

        personActiveFlag[0] = false;
        assertThatThrownBy(() -> workspaceAuth.refresh(
            ORG_ID, OperationalScope.AMB_02_PROFESSIONAL, rotated.refreshToken()
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);

        // 5. Logout en AMB_02_PROFESSIONAL funciona con accessToken valido
        workspaceAuth.logout(OperationalScope.AMB_02_PROFESSIONAL, bundle.accessToken(), null);

        // 6. WorkspaceAuthService toInternalProblem & validacion body null
        app.resourcehub.access.api.logic.services.WorkspaceAuthService wsService =
            new app.resourcehub.access.api.logic.services.WorkspaceAuthService();
        assertThatThrownBy(() -> wsService.login(null, null, null))
            .isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> wsService.refresh(null, "r1", mutableClock))
            .isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> wsService.logout(null, "r2", mutableClock))
            .isInstanceOf(AccessBusinessException.class);
        assertThat(wsService.toInternalProblem(
            new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "bad"), null, null
        ).status()).isEqualTo(400);
        assertThat(wsService.toInternalProblem(
            new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "unauth"), "/api/v1/workspace/auth/me", "r-1"
        ).status()).isEqualTo(401);
        assertThat(wsService.toInternalProblem(
            new AccessBusinessException(ErrorCode.SCOPE_VIOLATION, "forbidden"), null, "r-2"
        ).status()).isEqualTo(403);
        assertThat(wsService.toInternalProblem(
            new AccessBusinessException(ErrorCode.NOT_FOUND, "missing"), null, "r-3"
        ).status()).isEqualTo(404);
        assertThat(wsService.toInternalProblem(
            new AccessBusinessException(ErrorCode.ALREADY_EXISTS, "conflict"), null, "r-4"
        ).status()).isEqualTo(409);
        assertThat(wsService.toInternalProblem(
            new AccessBusinessException(ErrorCode.INTERNAL_ERROR, "err"), null, "r-5"
        ).status()).isEqualTo(500);
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

        @Override
        public StoredIdentityAccount updateStatus(UUID id, String status, Instant updatedAt, Instant deletedAt) {
            StoredIdentityAccount existing = storage.get(id);
            StoredIdentityAccount updated = new StoredIdentityAccount(
                existing.id(),
                existing.roleCode(),
                status,
                existing.linkedPersonResourceId(),
                existing.createdAt(),
                updatedAt,
                deletedAt
            );
            storage.put(id, updated);
            return updated;
        }

        private void removeForTest(UUID id) {
            storage.remove(id);
        }
    }

    private static final class InMemoryPersonalDataRepository extends PersonalDataRepository {

        private final Map<UUID, StoredPersonalData> byUserId = new LinkedHashMap<>();

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public StoredPersonalData upsert(StoredPersonalData data) {
            for (StoredPersonalData existing : byUserId.values()) {
                if (!existing.userId().equals(data.userId())
                    && existing.email().equalsIgnoreCase(data.email())) {
                    throw new AccessRepositoryException("Duplicate email", new RuntimeException(), "23505");
                }
            }
            StoredPersonalData previous = byUserId.get(data.userId());
            String passwordHash = data.passwordHash() != null
                ? data.passwordHash()
                : (previous != null ? previous.passwordHash() : null);
            StoredPersonalData stored = new StoredPersonalData(
                data.userId(),
                data.email(),
                data.fullName(),
                data.phone(),
                passwordHash,
                data.calendarSyncConsent(),
                data.calendarSyncConsentAt(),
                data.updatedAt()
            );
            byUserId.put(data.userId(), stored);
            return stored;
        }

        @Override
        public Optional<StoredPersonalData> findByUserId(UUID userId) {
            return Optional.ofNullable(byUserId.get(userId));
        }

        @Override
        public Optional<StoredPersonalData> findByEmailIgnoreCase(String email) {
            return byUserId.values().stream()
                .filter(item -> item.email().equalsIgnoreCase(email))
                .findFirst();
        }

        @Override
        public Map<UUID, StoredPersonalData> findByUserIds(Set<UUID> userIds) {
            Map<UUID, StoredPersonalData> result = new LinkedHashMap<>();
            for (UUID id : userIds) {
                StoredPersonalData found = byUserId.get(id);
                if (found != null) {
                    result.put(id, found);
                }
            }
            return result;
        }
    }
}
