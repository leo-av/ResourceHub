package app.resourcehub.access.business.logic.instance;

import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.IdentityStatus;
import app.resourcehub.access.business.structs.UserProfile;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.access.business.structs.UserSummary;
import app.resourcehub.access.business.utils.Argon2PasswordUtils;
import app.resourcehub.access.business.utils.JwtTokenUtils;
import app.resourcehub.access.repository.logic.local.LocalCredentialRepository;
import app.resourcehub.access.repository.logic.local.PersonalDataRepository;
import app.resourcehub.access.repository.logic.local.RefreshTokenRepository;
import app.resourcehub.access.repository.logic.local.UserRepository;
import app.resourcehub.access.repository.structs.AccessRepositoryException;
import app.resourcehub.access.repository.structs.RefreshTokenRotationOutcome;
import app.resourcehub.access.repository.structs.RefreshTokenRotationResult;
import app.resourcehub.access.repository.structs.StoredIdentityAccount;
import app.resourcehub.access.repository.structs.StoredLocalCredential;
import app.resourcehub.access.repository.structs.StoredPersonalData;
import app.resourcehub.access.repository.structs.StoredRefreshToken;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class AuthBusiness {

    public static final String ANONYMIZED_USER_LABEL = "Usuario eliminado (RGPD)";
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_FULL_NAME_LENGTH = 150;
    private static final int MAX_PHONE_LENGTH = 32;

    private final UserRepository userRepository;
    private final LocalCredentialRepository credentialRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PersonalDataRepository personalDataRepository;
    private final Clock clock;
    private final KeyPair signingKeyPair;

    public AuthBusiness(
        UserRepository userRepository,
        LocalCredentialRepository credentialRepository,
        RefreshTokenRepository refreshTokenRepository,
        Clock clock,
        KeyPair signingKeyPair
    ) {
        validateDependencies(userRepository, credentialRepository, refreshTokenRepository, clock, signingKeyPair);
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.personalDataRepository = null;
        this.clock = clock;
        this.signingKeyPair = signingKeyPair;
    }

    public AuthBusiness(
        UserRepository userRepository,
        LocalCredentialRepository credentialRepository,
        RefreshTokenRepository refreshTokenRepository,
        PersonalDataRepository personalDataRepository,
        Clock clock,
        KeyPair signingKeyPair
    ) {
        validateDependencies(userRepository, credentialRepository, refreshTokenRepository, clock, signingKeyPair);
        if (personalDataRepository == null) {
            throw new IllegalArgumentException("personalDataRepository must not be null");
        }
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.personalDataRepository = personalDataRepository;
        this.clock = clock;
        this.signingKeyPair = signingKeyPair;
    }

    private static void validateDependencies(
        UserRepository userRepository,
        LocalCredentialRepository credentialRepository,
        RefreshTokenRepository refreshTokenRepository,
        Clock clock,
        KeyPair signingKeyPair
    ) {
        if (userRepository == null) {
            throw new IllegalArgumentException("userRepository must not be null");
        }
        if (credentialRepository == null) {
            throw new IllegalArgumentException("credentialRepository must not be null");
        }
        if (refreshTokenRepository == null) {
            throw new IllegalArgumentException("refreshTokenRepository must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (signingKeyPair == null) {
            throw new IllegalArgumentException("signingKeyPair must not be null");
        }
    }

    public void registerLocalCredential(UUID organizationId, UUID userId, String email, char[] rawPassword) {
        if (organizationId == null || userId == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "organizationId and userId must not be null");
        }
        String normalizedEmail = normalizeEmail(email);
        if (rawPassword == null || rawPassword.length < MIN_PASSWORD_LENGTH) {
            throw new AccessBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Password must be at least 8 characters long"
            );
        }
        String encodedHash = Argon2PasswordUtils.hash(rawPassword);
        if (isPersonalVaultActive()) {
            Optional<StoredPersonalData> existing = personalDataRepository.findByUserId(userId);
            String fullName = existing.map(StoredPersonalData::fullName)
                .orElseGet(() -> defaultFullNameFromEmail(normalizedEmail));
            StoredPersonalData toStore = new StoredPersonalData(
                userId,
                normalizedEmail,
                fullName,
                existing.map(StoredPersonalData::phone).orElse(null),
                encodedHash,
                existing.map(StoredPersonalData::calendarSyncConsent).orElse(false),
                existing.map(StoredPersonalData::calendarSyncConsentAt).orElse(null),
                clock.instant()
            );
            upsertVault(toStore);
            return;
        }
        credentialRepository.upsert(new StoredLocalCredential(
            organizationId,
            userId,
            normalizedEmail,
            encodedHash,
            clock.instant()
        ));
    }

    public UserSummary registerPersonalProfile(UUID userId, String email, String fullName, String phone) {
        if (userId == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "userId must not be null");
        }
        String normalizedEmail = normalizeEmail(email);
        String cleanFullName = requireNonBlank(fullName, MAX_FULL_NAME_LENGTH, "fullName");
        String cleanPhone = trimOptional(phone, MAX_PHONE_LENGTH);

        StoredIdentityAccount account = userRepository.findById(userId)
            .orElseThrow(() -> new AccessBusinessException(ErrorCode.NOT_FOUND, "Identity account not found"));
        if (IdentityStatus.DELETED_GDPR.name().equals(account.status())) {
            throw new AccessBusinessException(
                ErrorCode.TRANSITION_NOT_ALLOWED,
                "Cannot register personal profile on GDPR-deleted account"
            );
        }
        if (!isPersonalVaultActive()) {
            throw new IllegalStateException("PersonalDataRepository is not configured");
        }

        Optional<StoredPersonalData> existing = personalDataRepository.findByUserId(userId);
        StoredPersonalData stored = upsertVault(new StoredPersonalData(
            userId,
            normalizedEmail,
            cleanFullName,
            cleanPhone,
            existing.map(StoredPersonalData::passwordHash).orElse(null),
            existing.map(StoredPersonalData::calendarSyncConsent).orElse(false),
            existing.map(StoredPersonalData::calendarSyncConsentAt).orElse(null),
            clock.instant()
        ));
        return new UserSummary(stored.userId(), stored.fullName(), stored.email(), false);
    }

    public Map<UUID, UserSummary> resolveUserSummaries(Set<UUID> userIds) {
        if (userIds == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "userIds must not be null");
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, StoredPersonalData> found = isPersonalVaultActive()
            ? personalDataRepository.findByUserIds(userIds)
            : Map.of();
        Map<UUID, UserSummary> summaries = new LinkedHashMap<>();
        for (UUID userId : userIds) {
            StoredPersonalData personalData = found.get(userId);
            if (personalData != null) {
                summaries.put(
                    userId,
                    new UserSummary(userId, personalData.fullName(), personalData.email(), false)
                );
            } else {
                summaries.put(userId, new UserSummary(userId, ANONYMIZED_USER_LABEL, null, true));
            }
        }
        return Collections.unmodifiableMap(summaries);
    }

    public AuthTokenBundle authenticateLocal(
        UUID organizationId,
        OperationalScope channelScope,
        String email,
        char[] rawPassword
    ) {
        validateChannelScopeSupported(channelScope);
        if (organizationId == null || email == null || email.isBlank() || rawPassword == null || rawPassword.length == 0) {
            throw new AccessBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "organizationId, email and password are required"
            );
        }
        String normalizedEmail = normalizeEmail(email);
        UUID authenticatedUserId;
        String storedHash;
        if (isPersonalVaultActive()) {
            StoredPersonalData personalData = personalDataRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new AccessBusinessException(
                    ErrorCode.INVALID_CREDENTIALS,
                    "Invalid email or password"
                ));
            authenticatedUserId = personalData.userId();
            storedHash = personalData.passwordHash();
        } else {
            StoredLocalCredential credential = credentialRepository
                .findByOrganizationAndEmail(organizationId, normalizedEmail)
                .orElseThrow(() -> new AccessBusinessException(
                    ErrorCode.INVALID_CREDENTIALS,
                    "Invalid email or password"
                ));
            authenticatedUserId = credential.userId();
            storedHash = credential.passwordHash();
        }
        if (storedHash == null || !Argon2PasswordUtils.matches(rawPassword, storedHash)) {
            throw new AccessBusinessException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }

        StoredIdentityAccount account = userRepository.findById(authenticatedUserId)
            .orElseThrow(() -> new AccessBusinessException(ErrorCode.INVALID_CREDENTIALS, "Account not found"));
        if (!IdentityStatus.ACTIVE.name().equals(account.status())) {
            throw new AccessBusinessException(ErrorCode.INVALID_CREDENTIALS, "Account is not active");
        }

        UserRole role = UserRole.valueOf(account.roleCode());
        assertChannelAuthorization(role, channelScope);

        return issueNewSessionBundle(organizationId, account, role, channelScope);
    }

    public AuthTokenBundle refresh(
        UUID organizationId,
        OperationalScope channelScope,
        String rawRefreshToken
    ) {
        validateChannelScopeSupported(channelScope);
        if (organizationId == null || rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "organizationId and refreshToken are required");
        }
        String tokenHash = JwtTokenUtils.sha256Hex(rawRefreshToken);
        Optional<StoredRefreshToken> inspectedOpt = refreshTokenRepository.findByTokenHash(tokenHash);
        if (inspectedOpt.isPresent()) {
            StoredRefreshToken inspected = inspectedOpt.get();
            if (!inspected.revoked() && !inspected.used()) {
                if (!organizationId.equals(inspected.organizationId())) {
                    throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Refresh token organization mismatch");
                }
                if (!channelScope.name().equals(inspected.operationalScope())) {
                    throw new AccessBusinessException(
                        ErrorCode.SCOPE_VIOLATION,
                        "Refresh token does not match operational channel scope"
                    );
                }
            }
        }

        Instant now = clock.instant();
        String nextRawRefreshToken = JwtTokenUtils.generateOpaqueRefreshToken();
        String nextTokenHash = JwtTokenUtils.sha256Hex(nextRawRefreshToken);
        Instant nextRefreshExpiry = now.plus(REFRESH_TOKEN_TTL);

        UUID familyId = inspectedOpt.map(StoredRefreshToken::familyId).orElseGet(UuidV7Utils::generate);
        UUID userId = inspectedOpt.map(StoredRefreshToken::userId).orElse(null);

        StoredRefreshToken replacement = userId == null ? null : new StoredRefreshToken(
            nextTokenHash,
            familyId,
            userId,
            organizationId,
            channelScope.name(),
            now,
            nextRefreshExpiry,
            false,
            false
        );

        RefreshTokenRotationResult rotation = refreshTokenRepository.consumeAndRotate(tokenHash, replacement, now);
        if (rotation.outcome() != RefreshTokenRotationOutcome.ROTATED) {
            throw new AccessBusinessException(
                ErrorCode.UNAUTHENTICATED,
                "Refresh token rejected: " + rotation.outcome().name()
            );
        }

        StoredIdentityAccount account = userRepository.findById(userId)
            .orElseThrow(() -> {
                refreshTokenRepository.revokeFamily(familyId);
                return new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Account no longer exists");
            });
        if (!IdentityStatus.ACTIVE.name().equals(account.status())) {
            refreshTokenRepository.revokeFamily(familyId);
            throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Account is not active");
        }
        UserRole role = UserRole.valueOf(account.roleCode());
        assertChannelAuthorization(role, channelScope);

        Instant accessExpiry = now.plus(ACCESS_TOKEN_TTL);
        ActorContext actor = toActorContext(organizationId, account, role, channelScope);
        String accessToken = JwtTokenUtils.signAccessToken(actor, now, accessExpiry, signingKeyPair.getPrivate());
        return new AuthTokenBundle(
            "Bearer",
            accessToken,
            accessExpiry,
            nextRawRefreshToken,
            nextRefreshExpiry,
            channelScope,
            organizationId,
            account.id(),
            role
        );
    }

    public void logout(OperationalScope channelScope, String accessToken, String rawRefreshToken) {
        validateChannelScopeSupported(channelScope);
        ActorContext actor = validateToken(accessToken);
        if (actor.operationalScope() != channelScope) {
            throw new AccessBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "Access token scope mismatch on logout channel"
            );
        }
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String tokenHash = JwtTokenUtils.sha256Hex(rawRefreshToken);
            refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(stored -> {
                if (!stored.userId().equals(actor.userId())) {
                    throw new AccessBusinessException(ErrorCode.SCOPE_VIOLATION, "Refresh token belongs to another user");
                }
                refreshTokenRepository.revokeFamily(stored.familyId());
            });
        }
    }

    public ActorContext validateToken(String accessToken) {
        ActorContext claims = JwtTokenUtils.verifyAndParse(accessToken, signingKeyPair.getPublic(), clock.instant());
        StoredIdentityAccount account = userRepository.findById(claims.userId())
            .orElseThrow(() -> new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Account not found"));
        if (!IdentityStatus.ACTIVE.name().equals(account.status())) {
            throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Account is no longer active");
        }
        return claims;
    }

    public UserProfile getCurrentProfile(OperationalScope channelScope, String accessToken) {
        validateChannelScopeSupported(channelScope);
        ActorContext claims = validateToken(accessToken);
        if (claims.operationalScope() != channelScope) {
            throw new AccessBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "Token scope " + claims.operationalScope() + " cannot access channel " + channelScope
            );
        }
        return new UserProfile(
            claims.userId(),
            claims.organizationId(),
            UserRole.valueOf(claims.actorRole()),
            IdentityStatus.ACTIVE,
            claims.operationalScope(),
            claims.linkedPersonResourceId()
        );
    }

    private AuthTokenBundle issueNewSessionBundle(
        UUID organizationId,
        StoredIdentityAccount account,
        UserRole role,
        OperationalScope channelScope
    ) {
        Instant now = clock.instant();
        Instant accessExpiry = now.plus(ACCESS_TOKEN_TTL);
        Instant refreshExpiry = now.plus(REFRESH_TOKEN_TTL);
        ActorContext actor = toActorContext(organizationId, account, role, channelScope);
        String accessToken = JwtTokenUtils.signAccessToken(actor, now, accessExpiry, signingKeyPair.getPrivate());

        String rawRefreshToken = JwtTokenUtils.generateOpaqueRefreshToken();
        String refreshTokenHash = JwtTokenUtils.sha256Hex(rawRefreshToken);
        UUID familyId = UuidV7Utils.generate();
        refreshTokenRepository.insert(new StoredRefreshToken(
            refreshTokenHash,
            familyId,
            account.id(),
            organizationId,
            channelScope.name(),
            now,
            refreshExpiry,
            false,
            false
        ));

        return new AuthTokenBundle(
            "Bearer",
            accessToken,
            accessExpiry,
            rawRefreshToken,
            refreshExpiry,
            channelScope,
            organizationId,
            account.id(),
            role
        );
    }

    private static ActorContext toActorContext(
        UUID organizationId,
        StoredIdentityAccount account,
        UserRole role,
        OperationalScope channelScope
    ) {
        return new ActorContext(
            account.id(),
            organizationId,
            ActorType.USER,
            role.name(),
            channelScope,
            account.linkedPersonResourceId(),
            Set.of()
        );
    }

    private static void validateChannelScopeSupported(OperationalScope channelScope) {
        if (channelScope != OperationalScope.AMB_01_CLIENT
            && channelScope != OperationalScope.AMB_03_MANAGEMENT) {
            throw new AccessBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "Unsupported local authentication channel scope in TT-006"
            );
        }
    }

    private static void assertChannelAuthorization(UserRole role, OperationalScope channelScope) {
        if (channelScope == OperationalScope.AMB_03_MANAGEMENT
            && role != UserRole.AD
            && role != UserRole.MG) {
            throw new AccessBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "Role " + role + " is forbidden from console channel AMB_03_MANAGEMENT"
            );
        }
    }

    private boolean isPersonalVaultActive() {
        return personalDataRepository != null && personalDataRepository.isConfigured();
    }

    private StoredPersonalData upsertVault(StoredPersonalData candidate) {
        try {
            return personalDataRepository.upsert(candidate);
        } catch (AccessRepositoryException ex) {
            if ("23505".equals(ex.getSqlState())) {
                throw new AccessBusinessException(
                    ErrorCode.ALREADY_EXISTS,
                    "Email already registered in personal data vault"
                );
            }
            throw ex;
        }
    }

    private static String defaultFullNameFromEmail(String normalizedEmail) {
        String prefix = normalizedEmail.substring(0, normalizedEmail.indexOf('@')).trim();
        String base = prefix.isEmpty() ? "Usuario" : prefix;
        return base.length() > MAX_FULL_NAME_LENGTH ? base.substring(0, MAX_FULL_NAME_LENGTH) : base;
    }

    private static String requireNonBlank(String raw, int maxLength, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, fieldName + " must not be blank");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new AccessBusinessException(
                ErrorCode.VALIDATION_FAILED,
                fieldName + " must not exceed " + maxLength + " characters"
            );
        }
        return trimmed;
    }

    private static String trimOptional(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new AccessBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Field must not exceed " + maxLength + " characters"
            );
        }
        return trimmed;
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "Invalid email format");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "Email must not exceed 254 characters");
        }
        return normalized;
    }
}
