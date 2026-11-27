package app.resourcehub.access.business.logic.instance;

import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.IdentityAccount;
import app.resourcehub.access.business.structs.IdentityStatus;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.access.business.utils.Argon2PasswordUtils;
import app.resourcehub.access.repository.logic.local.UserRepository;
import app.resourcehub.access.repository.structs.StoredIdentityAccount;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class UserBusiness {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final Clock clock;

    public UserBusiness(UserRepository userRepository, Clock clock) {
        if (userRepository == null) {
            throw new IllegalArgumentException("userRepository must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.userRepository = userRepository;
        this.clock = clock;
    }

    public IdentityAccount createIdentityAccount(UserRole role, UUID linkedPersonResourceId) {
        if (role == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "role must not be null");
        }
        if (role == UserRole.US && linkedPersonResourceId != null) {
            throw new AccessBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Standard user (US) cannot be linked to a PERSON resource"
            );
        }
        Instant now = clock.instant();
        StoredIdentityAccount stored = new StoredIdentityAccount(
            UuidV7Utils.generate(),
            role.name(),
            IdentityStatus.ACTIVE.name(),
            linkedPersonResourceId,
            now,
            now,
            null
        );
        return mapToDomain(userRepository.insert(stored));
    }

    public Optional<IdentityAccount> findById(UUID id) {
        if (id == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "userId must not be null");
        }
        return userRepository.findById(id).map(UserBusiness::mapToDomain);
    }

    public IdentityAccount deactivateAccount(UUID id) {
        IdentityAccount existing = findById(id)
            .orElseThrow(() -> new AccessBusinessException(ErrorCode.NOT_FOUND, "Identity account not found"));
        if (existing.status() == IdentityStatus.DELETED_GDPR) {
            throw new AccessBusinessException(
                ErrorCode.TRANSITION_NOT_ALLOWED,
                "Cannot deactivate an account in DELETED_GDPR status"
            );
        }
        Instant now = clock.instant();
        StoredIdentityAccount updated = userRepository.updateStatus(
            id,
            IdentityStatus.INACTIVE.name(),
            now,
            existing.deletedAt()
        );
        return mapToDomain(updated);
    }

    public List<IdentityAccount> listAccounts() {
        return userRepository.findAll().stream()
            .map(UserBusiness::mapToDomain)
            .toList();
    }

    public String hashPassword(char[] rawPassword) {
        if (rawPassword == null || rawPassword.length < MIN_PASSWORD_LENGTH) {
            throw new AccessBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Password must be at least 8 characters long"
            );
        }
        return Argon2PasswordUtils.hash(rawPassword);
    }

    public boolean verifyPassword(char[] rawPassword, String encodedHash) {
        return Argon2PasswordUtils.matches(rawPassword, encodedHash);
    }

    private static IdentityAccount mapToDomain(StoredIdentityAccount stored) {
        return new IdentityAccount(
            stored.id(),
            UserRole.valueOf(stored.roleCode()),
            IdentityStatus.valueOf(stored.status()),
            stored.linkedPersonResourceId(),
            stored.createdAt(),
            stored.updatedAt(),
            stored.deletedAt()
        );
    }
}
