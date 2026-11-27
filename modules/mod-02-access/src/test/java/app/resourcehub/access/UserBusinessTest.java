package app.resourcehub.access;

import app.resourcehub.access.api.A;
import app.resourcehub.access.business.B;
import app.resourcehub.access.business.logic.instance.UserBusiness;
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
import app.resourcehub.access.repository.R;
import app.resourcehub.access.repository.logic.local.LocalCredentialRepository;
import app.resourcehub.access.repository.logic.local.PersonalDataRepository;
import app.resourcehub.access.repository.logic.local.RefreshTokenRepository;
import app.resourcehub.access.repository.logic.local.UserRepository;
import app.resourcehub.access.repository.structs.AccessRepositoryException;
import app.resourcehub.access.repository.structs.StoredIdentityAccount;
import app.resourcehub.access.repository.structs.StoredLocalCredential;
import app.resourcehub.access.repository.structs.StoredPersonalData;
import app.resourcehub.access.repository.structs.StoredRefreshToken;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-005 & TT-024 [PRU-01] — Reglas de negocio MOD-02 (UserBusiness, ConsoleUserService y Argon2id)")
class UserBusinessTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T12:40:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final UUID ORG_ID = UUID.fromString("01936a00-0000-7000-8000-000000000001");

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
        assertThat(userBusiness.listAccounts()).hasSize(1);
        assertThat(userBusiness.isUserActive(staff.id())).isTrue();
        assertThat(userBusiness.isUserActive(null)).isFalse();
        assertThat(userBusiness.isUserActive(UUID.randomUUID())).isFalse();

        assertThatThrownBy(() -> userBusiness.createIdentityAccount(null, null))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

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

        assertThatThrownBy(() -> userBusiness.hashPassword(null))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> userBusiness.hashPassword("short".toCharArray()))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("TT-024: Validaciones de constructor, createConsoleUser (Vault + fallback) y mapeo SQL")
    void shouldManageConsoleUserCreationWithVaultAndFallbackAndSqlErrors() {
        InMemoryUserRepository userRepo = new InMemoryUserRepository();
        InMemoryPersonalDataRepository vaultRepo = new InMemoryPersonalDataRepository();
        InMemoryLocalCredentialRepository credRepo = new InMemoryLocalCredentialRepository();
        InMemoryRefreshTokenRepository refreshRepo = new InMemoryRefreshTokenRepository();
        List<UserPersonLinkDisabledEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> new UserBusiness(null, FIXED_CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserBusiness(userRepo, null)).isInstanceOf(IllegalArgumentException.class);

        UserBusiness vaultBusiness = new UserBusiness(
            userRepo, FIXED_CLOCK, vaultRepo, credRepo, refreshRepo, events::add
        );
        UUID personId = UUID.fromString("01936a00-0010-7000-8000-000000000101");

        UserManagement createdEm = vaultBusiness.createConsoleUser(
            ORG_ID,
            new CreateConsoleUserCommand(
                UserRole.EM,
                "  Elena.Profesional@Demo.Test ",
                " Elena Navarro ",
                " +34 600112233 ",
                "StaffPass2026!".toCharArray(),
                personId
            )
        );
        assertThat(createdEm.role()).isEqualTo(UserRole.EM);
        assertThat(createdEm.status()).isEqualTo(IdentityStatus.ACTIVE);
        assertThat(createdEm.email()).isEqualTo("elena.profesional@demo.test");
        assertThat(createdEm.fullName()).isEqualTo("Elena Navarro");
        assertThat(createdEm.phone()).isEqualTo("+34 600112233");
        assertThat(createdEm.linkedPersonResourceId()).isEqualTo(personId);
        assertThat(vaultRepo.findByUserId(createdEm.id())).isPresent();

        // Correo duplicado en Vault -> 23505 -> ALREADY_EXISTS
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(
            ORG_ID,
            new CreateConsoleUserCommand(UserRole.MG, "elena.profesional@demo.test", "Otra Persona", null, null, null)
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_EXISTS);

        // Regla D-11 y validaciones de entrada
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(ORG_ID, null))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(
            ORG_ID, new CreateConsoleUserCommand(null, "a@b.c", "Name", null, null, null)
        )).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(
            ORG_ID, new CreateConsoleUserCommand(UserRole.US, "a@b.c", "Name", null, null, personId)
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(
            ORG_ID, new CreateConsoleUserCommand(UserRole.US, null, "Name", null, null, null)
        )).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(
            ORG_ID, new CreateConsoleUserCommand(UserRole.US, " ", "Name", null, null, null)
        )).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(
            ORG_ID, new CreateConsoleUserCommand(UserRole.US, "sin-arroba", "Name", null, null, null)
        )).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(
            ORG_ID, new CreateConsoleUserCommand(UserRole.US, "a".repeat(250) + "@b.com", "Name", null, null, null)
        )).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(
            ORG_ID, new CreateConsoleUserCommand(UserRole.US, "ok@b.com", " ", null, null, null)
        )).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(
            ORG_ID, new CreateConsoleUserCommand(UserRole.US, "ok@b.com", null, null, null, null)
        )).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(
            ORG_ID, new CreateConsoleUserCommand(UserRole.US, "ok@b.com", "N".repeat(151), null, null, null)
        )).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> vaultBusiness.createConsoleUser(
            ORG_ID, new CreateConsoleUserCommand(UserRole.US, "ok@b.com", "Name", "P".repeat(33), null, null)
        )).isInstanceOf(AccessBusinessException.class);

        // Errores SQL en userRepository y vaultRepository
        userRepo.forcedSqlState = "23505";
        assertThatThrownBy(() -> vaultBusiness.createIdentityAccount(UserRole.EM, personId))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_EXISTS);
        userRepo.forcedSqlState = "23514";
        assertThatThrownBy(() -> vaultBusiness.createIdentityAccount(UserRole.EM, personId))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        userRepo.forcedSqlState = "50000";
        assertThatThrownBy(() -> vaultBusiness.createIdentityAccount(UserRole.EM, personId))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.INTERNAL_ERROR);
        userRepo.forcedSqlState = null;

        // Fallback LocalCredentialRepository sin Vault
        UserBusiness noVaultBusiness = new UserBusiness(
            userRepo, FIXED_CLOCK, null, credRepo, refreshRepo, null
        );
        UserManagement localCredUser = noVaultBusiness.createConsoleUser(
            ORG_ID,
            new CreateConsoleUserCommand(
                UserRole.US, "local@demo.test", "Usuario Local", "   ", "LocalPass2026!".toCharArray(), null
            )
        );
        assertThat(localCredUser.phone()).isNull();
        assertThat(credRepo.stored).containsKey(localCredUser.id());

        UserManagement noCredsUser = noVaultBusiness.createConsoleUser(
            null,
            new CreateConsoleUserCommand(UserRole.MG, "nocreds@demo.test", "Sin Clave", null, null, null)
        );
        assertThat(noCredsUser.email()).isEqualTo("nocreds@demo.test");
    }

    @Test
    @DisplayName("TT-024: updateConsoleUser, revocacion tokens, UserPersonLinkDisabledEvent, paginacion y RGPD")
    void shouldUpdateConsoleUserRevokeTokensEmitEventAndPaginate() {
        InMemoryUserRepository userRepo = new InMemoryUserRepository();
        InMemoryPersonalDataRepository vaultRepo = new InMemoryPersonalDataRepository();
        InMemoryRefreshTokenRepository refreshRepo = new InMemoryRefreshTokenRepository();
        List<UserPersonLinkDisabledEvent> events = new ArrayList<>();
        UserBusiness business = new UserBusiness(userRepo, FIXED_CLOCK, vaultRepo, null, refreshRepo, events::add);

        assertThat(business.listUsersPage(1, 10).totalItems()).isZero();
        assertThat(business.listUsersPage(1, 10).totalPages()).isZero();

        UUID personId = UUID.fromString("01936a00-0010-7000-8000-000000000202");
        UserManagement em = business.createConsoleUser(
            ORG_ID,
            new CreateConsoleUserCommand(UserRole.EM, "pro@demo.test", "Carlos Ruiz", null, null, personId)
        );
        UserManagement us = business.createConsoleUser(
            ORG_ID,
            new CreateConsoleUserCommand(UserRole.US, "cliente@demo.test", "Ana Lopez", "+34611", null, null)
        );
        refreshRepo.seedActiveToken(em.id());

        // Paginacion y getUserManagement
        assertThatThrownBy(() -> business.listUsersPage(0, 10)).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> business.listUsersPage(1, 0)).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> business.listUsersPage(1, 101)).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> business.findById(null)).isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> business.getUserManagement(UUID.randomUUID()))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);

        UserManagementPage page1 = business.listUsersPage(1, 1);
        assertThat(page1.totalItems()).isEqualTo(2L);
        assertThat(page1.totalPages()).isEqualTo(2);
        assertThat(page1.items()).hasSize(1);
        assertThat(business.getUserManagement(em.id()).fullName()).isEqualTo("Carlos Ruiz");

        // Validaciones PATCH
        assertThatThrownBy(() -> business.updateConsoleUser(em.id(), null))
            .isInstanceOf(AccessBusinessException.class);
        assertThatThrownBy(() -> business.updateConsoleUser(
            UUID.randomUUID(), new PatchConsoleUserCommand(null, IdentityStatus.INACTIVE, null, false)
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> business.updateConsoleUser(
            em.id(), new PatchConsoleUserCommand(UserRole.US, null, null, false)
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> business.updateConsoleUser(
            em.id(), new PatchConsoleUserCommand(null, IdentityStatus.DELETED_GDPR, null, false)
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.TRANSITION_NOT_ALLOWED);

        // Error 23505 al vincular PERSON ya ocupado
        userRepo.forcedSqlState = "23505";
        assertThatThrownBy(() -> business.updateConsoleUser(
            us.id(), new PatchConsoleUserCommand(UserRole.EM, null, personId, false)
        ))
            .isInstanceOf(AccessBusinessException.class)
            .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_EXISTS);
        userRepo.forcedSqlState = null;

        // Desactivar EM y limpiar enlace -> revoca refresh tokens y emite UserPersonLinkDisabledEvent
        UserManagement deactivated = business.updateConsoleUser(
            em.id(),
            new PatchConsoleUserCommand(UserRole.MG, IdentityStatus.INACTIVE, null, true)
        );
        assertThat(deactivated.status()).isEqualTo(IdentityStatus.INACTIVE);
        assertThat(deactivated.linkedPersonResourceId()).isNull();
        assertThat(business.isUserActive(em.id())).isFalse();
        assertThat(refreshRepo.revokedUsers).containsExactly(em.id());
        assertThat(events).containsExactly(new UserPersonLinkDisabledEvent(em.id(), personId, FIXED_NOW));

        // deactivateAccount en usuario activo con PERSON y rollback GDPR
        UUID p2 = UUID.fromString("01936a00-0010-7000-8000-000000000303");
        UserManagement em2 = business.createConsoleUser(
            ORG_ID, new CreateConsoleUserCommand(UserRole.EM, "em2@demo.test", "Otro Pro", null, null, p2)
        );
        IdentityAccount deact2 = business.deactivateAccount(em2.id());
        assertThat(deact2.status()).isEqualTo(IdentityStatus.INACTIVE);
        assertThat(events).hasSize(2);

        // Anonimizacion DELETED_GDPR en getUserManagement y listUsersPage + bloqueo PATCH sobre DELETED_GDPR
        userRepo.updateStatus(us.id(), "DELETED_GDPR", FIXED_NOW, FIXED_NOW);
        UserManagement gdprView = business.getUserManagement(us.id());
        assertThat(gdprView.email()).isNull();
        assertThat(gdprView.fullName()).isEqualTo("Usuario eliminado (RGPD)");
        assertThat(gdprView.phone()).isNull();
        assertThatThrownBy(() -> business.updateConsoleUser(
            us.id(), new PatchConsoleUserCommand(null, IdentityStatus.ACTIVE, null, false)
        )).isInstanceOf(AccessBusinessException.class);
    }

    @Test
    @DisplayName("TT-024: ConsoleUserService RBAC ([Solo AD] escritura vs AD/MG lectura) y toInternalProblem")
    void shouldEnforceConsoleUserServiceRbacAndMapProblems() {
        try {
            R.init();
            B.init(FIXED_CLOCK, orgId -> Boolean.FALSE, ev -> {
            });
            A.init();

            ActorContext userActor = new ActorContext(
                UUID.randomUUID(), ORG_ID, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT, null, Set.of()
            );
            ActorContext wrongScopeAdmin = new ActorContext(
                UUID.randomUUID(), ORG_ID, ActorType.USER, "AD", OperationalScope.AMB_01_CLIENT, null, Set.of()
            );
            ActorContext managerActor = new ActorContext(
                UUID.randomUUID(), ORG_ID, ActorType.USER, "MG", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
            );

            assertThatThrownBy(() -> A.consoleUsers().listUsers(null, 1, 10, null, null))
                .isInstanceOf(AccessBusinessException.class)
                .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
            assertThatThrownBy(() -> A.consoleUsers().listUsers(userActor, 1, 10, "req-1", FIXED_CLOCK))
                .isInstanceOf(AccessBusinessException.class)
                .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCOPE_VIOLATION);
            assertThatThrownBy(() -> A.consoleUsers().listUsers(wrongScopeAdmin, 1, 10, "req-2", FIXED_CLOCK))
                .isInstanceOf(AccessBusinessException.class)
                .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCOPE_VIOLATION);

            assertThatThrownBy(() -> A.consoleUsers().createUser(
                managerActor,
                new CreateConsoleUserCommand(UserRole.US, "a@b.com", "A", null, null, null),
                "req-mg-w1",
                FIXED_CLOCK
            ))
                .isInstanceOf(AccessBusinessException.class)
                .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NO_PERMISSION);

            assertThatThrownBy(() -> A.consoleUsers().patchUser(
                managerActor,
                UUID.randomUUID(),
                new PatchConsoleUserCommand(null, IdentityStatus.INACTIVE, null, false),
                "req-mg-w2",
                FIXED_CLOCK
            ))
                .isInstanceOf(AccessBusinessException.class)
                .extracting(ex -> ((AccessBusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NO_PERMISSION);

            // toInternalProblem HTTP mapping
            Map<ErrorCode, Integer> expectedStatuses = Map.of(
                ErrorCode.VALIDATION_FAILED, 400,
                ErrorCode.UNAUTHENTICATED, 401,
                ErrorCode.SCOPE_VIOLATION, 403,
                ErrorCode.NO_PERMISSION, 403,
                ErrorCode.NOT_FOUND, 404,
                ErrorCode.ALREADY_EXISTS, 409,
                ErrorCode.TRANSITION_NOT_ALLOWED, 409,
                ErrorCode.INTERNAL_ERROR, 500
            );
            for (Map.Entry<ErrorCode, Integer> entry : expectedStatuses.entrySet()) {
                InternalProblem problem = A.consoleUsers().toInternalProblem(
                    new AccessBusinessException(entry.getKey(), "msg"),
                    null,
                    null
                );
                assertThat(problem.status()).isEqualTo(entry.getValue());
                assertThat(problem.instance()).isEqualTo("/api/v1/console/users");
                assertThat(problem.requestId()).isNotBlank();
            }
        } finally {
            app.resourcehub.access.XF.terminate();
        }
    }

    private static final class InMemoryUserRepository extends UserRepository {
        private final Map<UUID, StoredIdentityAccount> storage = new LinkedHashMap<>();
        private String forcedSqlState;

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
            if (forcedSqlState != null) {
                throw new AccessRepositoryException("Forced SQL failure", null, forcedSqlState);
            }
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
        public StoredIdentityAccount updateAccount(
            UUID id,
            String roleCode,
            String status,
            UUID linkedPersonResourceId,
            Instant updatedAt,
            Instant deletedAt
        ) {
            if (forcedSqlState != null) {
                throw new AccessRepositoryException("Forced SQL failure", null, forcedSqlState);
            }
            StoredIdentityAccount existing = storage.get(id);
            StoredIdentityAccount updated = new StoredIdentityAccount(
                existing.id(),
                roleCode,
                status,
                linkedPersonResourceId,
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

        @Override
        public List<StoredIdentityAccount> findPaged(int limit, int offset) {
            List<StoredIdentityAccount> all = findAll();
            int from = Math.min(offset, all.size());
            int to = Math.min(from + limit, all.size());
            return all.subList(from, to);
        }

        @Override
        public long countAll() {
            return storage.size();
        }
    }

    private static final class InMemoryPersonalDataRepository extends PersonalDataRepository {
        private final Map<UUID, StoredPersonalData> byUserId = new LinkedHashMap<>();

        @Override
        public StoredPersonalData upsert(StoredPersonalData data) {
            for (StoredPersonalData existing : byUserId.values()) {
                if (!existing.userId().equals(data.userId())
                    && existing.email().equalsIgnoreCase(data.email())) {
                    throw new AccessRepositoryException("Duplicate email", null, "23505");
                }
            }
            byUserId.put(data.userId(), data);
            return data;
        }

        @Override
        public Optional<StoredPersonalData> findByUserId(UUID userId) {
            return Optional.ofNullable(byUserId.get(userId));
        }

        @Override
        public Map<UUID, StoredPersonalData> findByUserIds(Set<UUID> userIds) {
            Map<UUID, StoredPersonalData> found = new LinkedHashMap<>();
            for (UUID id : userIds) {
                StoredPersonalData data = byUserId.get(id);
                if (data != null) {
                    found.put(id, data);
                }
            }
            return found;
        }
    }

    private static final class InMemoryLocalCredentialRepository extends LocalCredentialRepository {
        private final Map<UUID, StoredLocalCredential> stored = new LinkedHashMap<>();

        @Override
        public void upsert(StoredLocalCredential credential) {
            stored.put(credential.userId(), credential);
        }
    }

    private static final class InMemoryRefreshTokenRepository extends RefreshTokenRepository {
        private final List<UUID> revokedUsers = new ArrayList<>();
        private final List<StoredRefreshToken> activeTokens = new ArrayList<>();

        private void seedActiveToken(UUID userId) {
            activeTokens.add(new StoredRefreshToken(
                "hash-" + userId, UUID.randomUUID(), userId, ORG_ID, "AMB_02_PROFESSIONAL", FIXED_NOW, FIXED_NOW, false, false
            ));
        }

        @Override
        public void revokeAllForUser(UUID userId) {
            revokedUsers.add(userId);
        }
    }
}
