package app.resourcehub.access;

import app.resourcehub.access.business.logic.instance.UserBusiness;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.IdentityAccount;
import app.resourcehub.access.business.structs.IdentityStatus;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.access.business.utils.Argon2PasswordUtils;
import app.resourcehub.access.repository.logic.local.UserRepository;
import app.resourcehub.access.repository.structs.StoredIdentityAccount;
import app.resourcehub.shared.business.structs.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-005 [PRU-01] — Reglas de negocio MOD-02 (UserBusiness y Hashing Argon2id)")
class UserBusinessTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T12:40:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @Test
    void shouldCreateIdentityAccountForStaffAndRejectPersonLinkForStandardUser() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        UserBusiness userBusiness = new UserBusiness(repository, FIXED_CLOCK);
        UUID personResourceId = UUID.fromString("01936a00-0010-7000-8000-000000000001");

        IdentityAccount staff = userBusiness.createIdentityAccount(UserRole.EM, personResourceId);
        assertThat(staff.id().version()).isEqualTo(7);
        assertThat(staff.role()).isEqualTo(UserRole.EM);
        assertThat(staff.status()).isEqualTo(IdentityStatus.ACTIVE);
        assertThat(staff.linkedPersonResourceId()).isEqualTo(personResourceId);
        assertThat(staff.createdAt()).isEqualTo(FIXED_NOW);

        assertThatThrownBy(() -> userBusiness.createIdentityAccount(UserRole.US, personResourceId))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void shouldRejectDeactivatingGdprDeletedAccount() {
        UUID deletedId = UUID.fromString("01936a00-0010-7000-8000-000000000099");
        InMemoryUserRepository repository = new InMemoryUserRepository(List.of(
            new StoredIdentityAccount(
                deletedId,
                "US",
                "DELETED_GDPR",
                null,
                FIXED_NOW.minusSeconds(3600),
                FIXED_NOW.minusSeconds(60),
                FIXED_NOW.minusSeconds(60)
            )
        ));
        UserBusiness userBusiness = new UserBusiness(repository, FIXED_CLOCK);

        assertThatThrownBy(() -> userBusiness.deactivateAccount(deletedId))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.TRANSITION_NOT_ALLOWED);
    }

    @Test
    void shouldHashAndVerifyPasswordsUsingArgon2idWithUniqueSalts() {
        UserBusiness userBusiness = new UserBusiness(new InMemoryUserRepository(), FIXED_CLOCK);
        char[] secret = "S3gur@ResourceHub2026!".toCharArray();

        String hash1 = userBusiness.hashPassword(secret.clone());
        String hash2 = userBusiness.hashPassword(secret.clone());

        assertThat(hash1).startsWith("$argon2id$v=19$");
        assertThat(hash2).startsWith("$argon2id$v=19$");
        assertThat(hash1).isNotEqualTo(hash2);
        assertThat(userBusiness.verifyPassword(secret.clone(), hash1)).isTrue();
        assertThat(userBusiness.verifyPassword("WrongPassword123".toCharArray(), hash1)).isFalse();
        assertThat(Argon2PasswordUtils.isArgon2idHash(hash1)).isTrue();

        assertThatThrownBy(() -> userBusiness.hashPassword("short".toCharArray()))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private static final class InMemoryUserRepository extends UserRepository {
        private final Map<UUID, StoredIdentityAccount> storage = new LinkedHashMap<>();

        private InMemoryUserRepository() {
            this(List.of());
        }

        private InMemoryUserRepository(List<StoredIdentityAccount> seed) {
            for (StoredIdentityAccount item : seed) {
                storage.put(item.id(), item);
            }
        }

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

        @Override
        public List<StoredIdentityAccount> findAll() {
            return new ArrayList<>(storage.values());
        }
    }
}
