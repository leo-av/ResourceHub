package app.resourcehub.access.business.logic.instance;

import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.CreateConsoleUserCommand;
import app.resourcehub.access.business.structs.IdentityAccount;
import app.resourcehub.access.business.structs.IdentityStatus;
import app.resourcehub.access.business.structs.PatchConsoleUserCommand;
import app.resourcehub.access.business.structs.UserManagement;
import app.resourcehub.access.business.structs.UserManagementPage;
import app.resourcehub.access.business.structs.UserPersonLinkDisabledEvent;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.access.business.utils.Argon2PasswordUtils;
import app.resourcehub.access.repository.logic.local.LocalCredentialRepository;
import app.resourcehub.access.repository.logic.local.PersonalDataRepository;
import app.resourcehub.access.repository.logic.local.RefreshTokenRepository;
import app.resourcehub.access.repository.logic.local.UserRepository;
import app.resourcehub.access.repository.structs.AccessRepositoryException;
import app.resourcehub.access.repository.structs.StoredIdentityAccount;
import app.resourcehub.access.repository.structs.StoredLocalCredential;
import app.resourcehub.access.repository.structs.StoredPersonalData;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class UserBusiness {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_FULL_NAME_LENGTH = 150;
    private static final int MAX_PHONE_LENGTH = 32;

    private final UserRepository userRepository;
    private final Clock clock;
    private final PersonalDataRepository personalDataRepository;
    private final LocalCredentialRepository localCredentialRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Consumer<UserPersonLinkDisabledEvent> personLinkDisabledListener;

    public UserBusiness(UserRepository userRepository, Clock clock) {
        this(userRepository, clock, null, null, null, null);
    }

    public UserBusiness(
        UserRepository userRepository,
        Clock clock,
        PersonalDataRepository personalDataRepository,
        LocalCredentialRepository localCredentialRepository,
        RefreshTokenRepository refreshTokenRepository,
        Consumer<UserPersonLinkDisabledEvent> personLinkDisabledListener
    ) {
        if (userRepository == null) {
            throw new IllegalArgumentException("userRepository must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.userRepository = userRepository;
        this.clock = clock;
        this.personalDataRepository = personalDataRepository;
        this.localCredentialRepository = localCredentialRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.personLinkDisabledListener = personLinkDisabledListener != null ? personLinkDisabledListener : ev -> {
        };
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
        try {
            return mapToDomain(userRepository.insert(stored));
        } catch (AccessRepositoryException ex) {
            throw mapRepositoryException(ex, "PERSON resource already linked to another account");
        }
    }

    public UserManagement createConsoleUser(UUID organizationId, CreateConsoleUserCommand command) {
        if (command == null || command.role() == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "command and role must not be null");
        }
        if (command.role() == UserRole.US && command.linkedPersonResourceId() != null) {
            throw new AccessBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Standard user (US) cannot be linked to a PERSON resource"
            );
        }
        String normalizedEmail = normalizeEmail(command.email());
        String cleanFullName = validateRequiredText(command.fullName(), MAX_FULL_NAME_LENGTH, "fullName");
        String cleanPhone = trimOptional(command.phone(), MAX_PHONE_LENGTH);
        String encodedHash = command.rawPassword() != null ? hashPassword(command.rawPassword()) : null;

        IdentityAccount created = createIdentityAccount(command.role(), command.linkedPersonResourceId());
        Instant now = clock.instant();

        if (personalDataRepository != null) {
            StoredPersonalData personalData;
            try {
                personalData = personalDataRepository.upsert(new StoredPersonalData(
                    created.id(),
                    normalizedEmail,
                    cleanFullName,
                    cleanPhone,
                    encodedHash,
                    false,
                    null,
                    now
                ));
            } catch (AccessRepositoryException ex) {
                throw mapRepositoryException(ex, "Email already registered in personal data vault");
            }
            return toUserManagement(created, personalData);
        }

        if (localCredentialRepository != null && encodedHash != null && organizationId != null) {
            localCredentialRepository.upsert(new StoredLocalCredential(
                organizationId,
                created.id(),
                normalizedEmail,
                encodedHash,
                now
            ));
        }
        return new UserManagement(
            created.id(),
            created.role(),
            created.status(),
            created.linkedPersonResourceId(),
            normalizedEmail,
            cleanFullName,
            cleanPhone,
            created.createdAt(),
            created.updatedAt(),
            created.deletedAt()
        );
    }

    public UserManagement updateConsoleUser(UUID userId, PatchConsoleUserCommand command) {
        if (command == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "Patch command must not be null");
        }
        IdentityAccount existing = findById(userId)
            .orElseThrow(() -> new AccessBusinessException(ErrorCode.NOT_FOUND, "Identity account not found"));
        if (existing.status() == IdentityStatus.DELETED_GDPR || command.status() == IdentityStatus.DELETED_GDPR) {
            throw new AccessBusinessException(
                ErrorCode.TRANSITION_NOT_ALLOWED,
                "Cannot modify or transition to DELETED_GDPR via console PATCH"
            );
        }

        UserRole nextRole = command.role() != null ? command.role() : existing.role();
        IdentityStatus nextStatus = command.status() != null ? command.status() : existing.status();
        UUID nextLinkedPersonId = command.clearPersonLink()
            ? null
            : (command.linkedPersonResourceId() != null
                ? command.linkedPersonResourceId()
                : existing.linkedPersonResourceId());
        if (nextRole == UserRole.US && nextLinkedPersonId != null) {
            throw new AccessBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Standard user (US) cannot be linked to a PERSON resource"
            );
        }

        Instant now = clock.instant();
        StoredIdentityAccount updatedStored;
        try {
            updatedStored = userRepository.updateAccount(
                userId,
                nextRole.name(),
                nextStatus.name(),
                nextLinkedPersonId,
                now,
                existing.deletedAt()
            );
        } catch (AccessRepositoryException ex) {
            throw mapRepositoryException(ex, "PERSON resource already linked to another account");
        }

        IdentityAccount updated = mapToDomain(updatedStored);
        if (nextStatus == IdentityStatus.INACTIVE) {
            revokeAndNotifyDisabled(updated, existing.linkedPersonResourceId());
        }
        return hydrateSingle(updated);
    }

    public UserManagement getUserManagement(UUID userId) {
        IdentityAccount existing = findById(userId)
            .orElseThrow(() -> new AccessBusinessException(ErrorCode.NOT_FOUND, "Identity account not found"));
        return hydrateSingle(existing);
    }

    public UserManagementPage listUsersPage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new AccessBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Pagination parameters must satisfy page >= 1 and 1 <= size <= 100"
            );
        }
        long totalItems = userRepository.countAll();
        if (totalItems == 0L) {
            return new UserManagementPage(List.of(), page, size, 0L, 0);
        }
        int offset = (page - 1) * size;
        List<IdentityAccount> accounts = userRepository.findPaged(size, offset).stream()
            .map(UserBusiness::mapToDomain)
            .toList();
        Map<UUID, StoredPersonalData> personalDataById = hydrateBatch(accounts);
        List<UserManagement> items = accounts.stream()
            .map(acc -> toUserManagement(acc, personalDataById.get(acc.id())))
            .toList();
        int totalPages = (int) ((totalItems + size - 1L) / size);
        return new UserManagementPage(items, page, size, totalItems, totalPages);
    }

    public Optional<IdentityAccount> findById(UUID id) {
        if (id == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "userId must not be null");
        }
        return userRepository.findById(id).map(UserBusiness::mapToDomain);
    }

    public boolean isUserActive(UUID userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
            .map(stored -> IdentityStatus.ACTIVE.name().equals(stored.status()))
            .orElse(false);
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
        StoredIdentityAccount updatedStored = userRepository.updateStatus(
            id,
            IdentityStatus.INACTIVE.name(),
            now,
            existing.deletedAt()
        );
        IdentityAccount updated = mapToDomain(updatedStored);
        revokeAndNotifyDisabled(updated, existing.linkedPersonResourceId());
        return updated;
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

    private void revokeAndNotifyDisabled(IdentityAccount account, UUID previousLinkedPersonResourceId) {
        if (refreshTokenRepository != null) {
            refreshTokenRepository.revokeAllForUser(account.id());
        }
        UUID affectedPersonResourceId = account.linkedPersonResourceId() != null
            ? account.linkedPersonResourceId()
            : previousLinkedPersonResourceId;
        if (affectedPersonResourceId != null) {
            personLinkDisabledListener.accept(
                new UserPersonLinkDisabledEvent(account.id(), affectedPersonResourceId, account.updatedAt())
            );
        }
    }

    private UserManagement hydrateSingle(IdentityAccount account) {
        StoredPersonalData data = personalDataRepository != null
            ? personalDataRepository.findByUserId(account.id()).orElse(null)
            : null;
        return toUserManagement(account, data);
    }

    private Map<UUID, StoredPersonalData> hydrateBatch(List<IdentityAccount> accounts) {
        if (personalDataRepository == null || accounts.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = accounts.stream().map(IdentityAccount::id).collect(Collectors.toSet());
        return personalDataRepository.findByUserIds(ids);
    }

    private static UserManagement toUserManagement(IdentityAccount account, StoredPersonalData personalData) {
        boolean anonymized = personalData == null || account.status() == IdentityStatus.DELETED_GDPR;
        return new UserManagement(
            account.id(),
            account.role(),
            account.status(),
            account.linkedPersonResourceId(),
            anonymized ? null : personalData.email(),
            anonymized ? "Usuario eliminado (RGPD)" : personalData.fullName(),
            anonymized ? null : personalData.phone(),
            account.createdAt(),
            account.updatedAt(),
            account.deletedAt()
        );
    }

    private static AccessBusinessException mapRepositoryException(AccessRepositoryException ex, String conflictMsg) {
        if ("23505".equals(ex.getSqlState())) {
            return new AccessBusinessException(ErrorCode.ALREADY_EXISTS, conflictMsg);
        }
        if ("23514".equals(ex.getSqlState())) {
            return new AccessBusinessException(ErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
        return new AccessBusinessException(ErrorCode.INTERNAL_ERROR, ex.getMessage());
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "Invalid email format");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "email exceeds 254 characters");
        }
        return normalized;
    }

    private static String validateRequiredText(String raw, int maxLength, String field) {
        if (raw == null || raw.isBlank()) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, field + " must not be blank");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new AccessBusinessException(
                ErrorCode.VALIDATION_FAILED,
                field + " must not exceed " + maxLength + " characters"
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
                "Optional field must not exceed " + maxLength + " characters"
            );
        }
        return trimmed;
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
