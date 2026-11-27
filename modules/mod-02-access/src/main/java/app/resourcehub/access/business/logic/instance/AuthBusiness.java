package app.resourcehub.access.business.logic.instance;

import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.AuthTokenBundle;
import app.resourcehub.access.business.structs.IdentityStatus;
import app.resourcehub.access.business.structs.UserProfile;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.access.business.utils.Argon2PasswordUtils;
import app.resourcehub.access.business.utils.JwtTokenUtils;
import app.resourcehub.access.repository.logic.local.LocalCredentialRepository;
import app.resourcehub.access.repository.logic.local.RefreshTokenRepository;
import app.resourcehub.access.repository.logic.local.UserRepository;
import app.resourcehub.access.repository.structs.RefreshTokenRotationOutcome;
import app.resourcehub.access.repository.structs.RefreshTokenRotationResult;
import app.resourcehub.access.repository.structs.StoredIdentityAccount;
import app.resourcehub.access.repository.structs.StoredLocalCredential;
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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class AuthBusiness {

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final LocalCredentialRepository credentialRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final KeyPair signingKeyPair;

    public AuthBusiness(
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
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
        this.signingKeyPair = signingKeyPair;
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
        credentialRepository.upsert(new StoredLocalCredential(
            organizationId,
            userId,
            normalizedEmail,
            encodedHash,
            clock.instant()
        ));
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
        StoredLocalCredential credential = credentialRepository.findByOrganizationAndEmail(organizationId, normalizedEmail)
            .orElseThrow(() -> new AccessBusinessException(
                ErrorCode.INVALID_CREDENTIALS,
                "Invalid email or password"
            ));
        if (!Argon2PasswordUtils.matches(rawPassword, credential.passwordHash())) {
            throw new AccessBusinessException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }

        StoredIdentityAccount account = userRepository.findById(credential.userId())
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

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "Invalid email format");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
