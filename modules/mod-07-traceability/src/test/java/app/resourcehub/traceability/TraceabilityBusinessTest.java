package app.resourcehub.traceability;

import app.resourcehub.shared.api.structs.PageResponse;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.traceability.api.A;
import app.resourcehub.traceability.api.logic.services.AppHistoryService;
import app.resourcehub.traceability.api.logic.services.ConsoleAuditService;
import app.resourcehub.traceability.business.B;
import app.resourcehub.traceability.business.logic.instance.AuditLogBusiness;
import app.resourcehub.traceability.business.logic.instance.ReservationHistoryBusiness;
import app.resourcehub.traceability.business.structs.AuditActorSummary;
import app.resourcehub.traceability.business.structs.AuditLogEntry;
import app.resourcehub.traceability.business.structs.AuditLogFilter;
import app.resourcehub.traceability.business.structs.AuditLogPage;
import app.resourcehub.traceability.business.structs.AuditOutcome;
import app.resourcehub.traceability.business.structs.HistoryChangeType;
import app.resourcehub.traceability.business.structs.RecordAuditCommand;
import app.resourcehub.traceability.business.structs.RecordHistoryCommand;
import app.resourcehub.traceability.business.structs.ReservationHistoryEntry;
import app.resourcehub.traceability.business.structs.TraceabilityBusinessException;
import app.resourcehub.traceability.repository.logic.local.AuditLogRepository;
import app.resourcehub.traceability.repository.logic.local.ReservationHistoryRepository;
import app.resourcehub.traceability.repository.structs.AuditLogQueryFilter;
import app.resourcehub.traceability.repository.structs.StoredAuditLog;
import app.resourcehub.traceability.repository.structs.StoredReservationHistory;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("TT-021 / TT-022 [PRU-01 / PRU-07] — Traceability Business y API Services")
class TraceabilityBusinessTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T18:00:00Z"), ZoneOffset.UTC);
    private static final UUID HOLDER_A = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID HOLDER_B = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID ADMIN_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID ORG_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID RESERVATION_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");

    @AfterEach
    void cleanupStatics() {
        XF.terminate();
    }

    @Test
    @DisplayName("ReservationHistoryBusiness valida entradas, registra append-only y protege titularidad en AMB-01")
    void shouldAppendAndListReservationHistoryWithOwnershipIsolation() {
        assertThatThrownBy(() -> new ReservationHistoryBusiness(null, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationHistoryBusiness(new InMemoryHistoryRepository(), null))
            .isInstanceOf(IllegalArgumentException.class);

        InMemoryHistoryRepository repo = new InMemoryHistoryRepository();
        ReservationHistoryBusiness business = new ReservationHistoryBusiness(repo, FIXED_CLOCK);

        ReservationHistoryEntry created = business.appendHistoryInCurrentTx(new RecordHistoryCommand(
            RESERVATION_ID,
            HistoryChangeType.CREATED,
            null,
            "CONFIRMED",
            ActorType.USER,
            HOLDER_A,
            HOLDER_A,
            OperationalScope.AMB_01_CLIENT,
            null,
            "{\"status\":\"CONFIRMED\",\"version\":1}",
            " req-001 "
        ));
        assertThat(created.changeType()).isEqualTo(HistoryChangeType.CREATED);
        assertThat(created.correlationId()).isEqualTo("req-001");

        ReservationHistoryEntry modified = business.appendHistoryInCurrentTx(new RecordHistoryCommand(
            RESERVATION_ID,
            HistoryChangeType.MODIFIED,
            "CONFIRMED",
            "CONFIRMED",
            ActorType.USER,
            HOLDER_A,
            HOLDER_A,
            OperationalScope.AMB_01_CLIENT,
            "{\"version\":1}",
            "{\"version\":2}",
            "req-002"
        ));
        assertThat(modified.snapshotBeforeJson()).isEqualTo("{\"version\":1}");

        ActorContext ownerActor = new ActorContext(
            HOLDER_A, ORG_ID, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT, null, Set.of()
        );
        ActorContext otherActor = new ActorContext(
            HOLDER_B, ORG_ID, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT, null, Set.of()
        );
        ActorContext managerActor = new ActorContext(
            ADMIN_ID, ORG_ID, ActorType.USER, "MG", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
        );
        ActorContext platformActor = new ActorContext(
            ADMIN_ID, null, ActorType.PLATFORM_ADMIN, "PA", OperationalScope.PLATFORM, null, Set.of()
        );

        assertThat(business.listReservationHistory(ownerActor, RESERVATION_ID)).hasSize(2);
        assertThat(business.listReservationHistory(managerActor, RESERVATION_ID)).hasSize(2);

        TraceabilityBusinessException unauth = catchThrowableOfType(
            () -> business.listReservationHistory(null, RESERVATION_ID),
            TraceabilityBusinessException.class
        );
        assertThat(unauth.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);

        TraceabilityBusinessException badScope = catchThrowableOfType(
            () -> business.listReservationHistory(platformActor, RESERVATION_ID),
            TraceabilityBusinessException.class
        );
        assertThat(badScope.getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);

        TraceabilityBusinessException nullId = catchThrowableOfType(
            () -> business.listReservationHistory(ownerActor, null),
            TraceabilityBusinessException.class
        );
        assertThat(nullId.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        TraceabilityBusinessException notFound = catchThrowableOfType(
            () -> business.listReservationHistory(ownerActor, UUID.randomUUID()),
            TraceabilityBusinessException.class
        );
        assertThat(notFound.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);

        TraceabilityBusinessException noPermission = catchThrowableOfType(
            () -> business.listReservationHistory(otherActor, RESERVATION_ID),
            TraceabilityBusinessException.class
        );
        assertThat(noPermission.getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);

        // Ramas inválidas de appendHistoryInCurrentTx
        assertInvalidHistoryCommand(business, null);
        assertInvalidHistoryCommand(business, new RecordHistoryCommand(
            null, HistoryChangeType.CREATED, null, "CONFIRMED", ActorType.USER, HOLDER_A, HOLDER_A,
            OperationalScope.AMB_01_CLIENT, null, "{}", "c1"
        ));
        assertInvalidHistoryCommand(business, new RecordHistoryCommand(
            RESERVATION_ID, null, null, "CONFIRMED", ActorType.USER, HOLDER_A, HOLDER_A,
            OperationalScope.AMB_01_CLIENT, null, "{}", "c1"
        ));
        assertInvalidHistoryCommand(business, new RecordHistoryCommand(
            RESERVATION_ID, HistoryChangeType.CREATED, null, " ", ActorType.USER, HOLDER_A, HOLDER_A,
            OperationalScope.AMB_01_CLIENT, null, "{}", "c1"
        ));
        assertInvalidHistoryCommand(business, new RecordHistoryCommand(
            RESERVATION_ID, HistoryChangeType.CREATED, null, "CONFIRMED", null, HOLDER_A, HOLDER_A,
            OperationalScope.AMB_01_CLIENT, null, "{}", "c1"
        ));
        assertInvalidHistoryCommand(business, new RecordHistoryCommand(
            RESERVATION_ID, HistoryChangeType.CREATED, null, "CONFIRMED", ActorType.PLATFORM_ADMIN, HOLDER_A, HOLDER_A,
            OperationalScope.AMB_01_CLIENT, null, "{}", "c1"
        ));
        assertInvalidHistoryCommand(business, new RecordHistoryCommand(
            RESERVATION_ID, HistoryChangeType.CREATED, null, "CONFIRMED", ActorType.USER, HOLDER_A, null,
            OperationalScope.AMB_01_CLIENT, null, "{}", "c1"
        ));
        assertInvalidHistoryCommand(business, new RecordHistoryCommand(
            RESERVATION_ID, HistoryChangeType.CREATED, null, "CONFIRMED", ActorType.USER, HOLDER_A, HOLDER_A,
            null, null, "{}", "c1"
        ));
        assertInvalidHistoryCommand(business, new RecordHistoryCommand(
            RESERVATION_ID, HistoryChangeType.CREATED, null, "CONFIRMED", ActorType.USER, HOLDER_A, HOLDER_A,
            OperationalScope.AMB_01_CLIENT, null, " ", "c1"
        ));
        assertInvalidHistoryCommand(business, new RecordHistoryCommand(
            RESERVATION_ID, HistoryChangeType.CREATED, null, "CONFIRMED", ActorType.USER, HOLDER_A, HOLDER_A,
            OperationalScope.AMB_01_CLIENT, null, "{}", " "
        ));
        assertInvalidHistoryCommand(business, new RecordHistoryCommand(
            RESERVATION_ID, HistoryChangeType.MODIFIED, "CONFIRMED", "CONFIRMED", ActorType.USER, HOLDER_A, HOLDER_A,
            OperationalScope.AMB_01_CLIENT, " ", "{}", "c1"
        ));
    }

    @Test
    @DisplayName("AuditLogBusiness graba ACCEPTED/REJECTED autónomos, filtra en consola e hidrata actores en O(1)")
    void shouldRecordAuditLogsFilterByCriteriaAndHydrateActorsInSingleBatch() {
        assertThatThrownBy(() -> new AuditLogBusiness(null, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditLogBusiness(new InMemoryAuditRepository(), null))
            .isInstanceOf(IllegalArgumentException.class);

        InMemoryAuditRepository auditRepo = new InMemoryAuditRepository();
        AtomicInteger resolverCalls = new AtomicInteger();
        AuditLogBusiness auditBusiness = new AuditLogBusiness(auditRepo, FIXED_CLOCK, ids -> {
            resolverCalls.incrementAndGet();
            if (ids.contains(HOLDER_A)) {
                return Map.of(HOLDER_A, new AuditActorSummary(HOLDER_A, "Ana Gomez", false));
            }
            return Map.of();
        });

        AuditLogEntry accepted = auditBusiness.recordAuditInAutonomousTx(new RecordAuditCommand(
            " corr-ok ",
            " RESERVATION_CREATE ",
            " RESERVATION ",
            RESERVATION_ID,
            ActorType.USER,
            HOLDER_A,
            HOLDER_A,
            " US ",
            OperationalScope.AMB_01_CLIENT,
            AuditOutcome.ACCEPTED,
            null,
            " ",
            "{\"ok\":true}"
        ));
        assertThat(accepted.outcome()).isEqualTo(AuditOutcome.ACCEPTED);
        assertThat(accepted.violatedRuleOrPolicyId()).isNull();

        AuditLogEntry rejected = auditBusiness.recordAuditInAutonomousTx(new RecordAuditCommand(
            "corr-err",
            "RESERVATION_MODIFY",
            "RESERVATION",
            RESERVATION_ID,
            ActorType.SYSTEM,
            null,
            HOLDER_B,
            "SY",
            OperationalScope.SYSTEM,
            AuditOutcome.REJECTED,
            "BOOKING_CONFLICT",
            "AC-003",
            "{\"error\":\"BOOKING_CONFLICT\"}"
        ));
        assertThat(rejected.rejectionCategory()).isEqualTo("BOOKING_CONFLICT");

        AuditLogEntry nullRequesterAndHolder = auditBusiness.recordAuditInAutonomousTx(new RecordAuditCommand(
            "corr-sys",
            "SYSTEM_SWEEP",
            "SYSTEM",
            null,
            ActorType.SYSTEM,
            null,
            null,
            "SY",
            OperationalScope.SYSTEM,
            AuditOutcome.ACCEPTED,
            null,
            null,
            "{}"
        ));
        assertThat(nullRequesterAndHolder.requesterSummary()).isNull();
        assertThat(nullRequesterAndHolder.holderSummary()).isNull();

        ActorContext adminActor = new ActorContext(
            ADMIN_ID, ORG_ID, ActorType.USER, "AD", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
        );
        ActorContext managerActor = new ActorContext(
            ADMIN_ID, ORG_ID, ActorType.USER, "MG", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
        );
        ActorContext nonAdminConsole = new ActorContext(
            HOLDER_A, ORG_ID, ActorType.USER, "EM", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
        );
        ActorContext clientActor = new ActorContext(
            HOLDER_A, ORG_ID, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT, null, Set.of()
        );

        AuditLogPage page = auditBusiness.listAuditLogs(adminActor, null, 1, 10);
        assertThat(resolverCalls.get()).isEqualTo(1);
        assertThat(page.totalItems()).isEqualTo(3L);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.items().get(0).requesterSummary().displayName()).isEqualTo("Ana Gomez");
        assertThat(page.items().get(1).holderSummary().displayName()).isEqualTo(AuditActorSummary.ANONYMIZED_LABEL);
        assertThat(page.items().get(1).holderSummary().anonymized()).isTrue();

        // Filtro por outcome y sin resultados
        AuditLogPage filteredRejected = auditBusiness.listAuditLogs(
            managerActor,
            new AuditLogFilter(null, null, AuditOutcome.REJECTED, null, null, null),
            1,
            10
        );
        assertThat(filteredRejected.totalItems()).isEqualTo(1L);

        AuditLogPage emptyPage = auditBusiness.listAuditLogs(
            managerActor,
            new AuditLogFilter(null, "UNKNOWN_OP", null, null, null, null),
            1,
            10
        );
        assertThat(emptyPage.totalItems()).isZero();
        assertThat(emptyPage.items()).isEmpty();

        // Filtro unicamente con sys-only sin actorIds y resolver devolviendo null
        AuditLogBusiness nullMapBusiness = new AuditLogBusiness(auditRepo, FIXED_CLOCK, ids -> null);
        AuditLogPage filteredHolder = nullMapBusiness.listAuditLogs(
            managerActor,
            new AuditLogFilter(HOLDER_A, null, null, null, null, null),
            1,
            10
        );
        assertThat(filteredHolder.items().getFirst().requesterSummary().anonymized()).isTrue();

        AuditLogPage onlySys = auditBusiness.listAuditLogs(
            managerActor,
            new AuditLogFilter(null, "SYSTEM_SWEEP", null, null, null, null),
            1,
            10
        );
        assertThat(onlySys.totalItems()).isEqualTo(1L);

        // Errores en listAuditLogs y recordAuditInAutonomousTx
        assertThat(catchThrowableOfType(
            () -> auditBusiness.listAuditLogs(null, null, 1, 10),
            TraceabilityBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);

        assertThat(catchThrowableOfType(
            () -> auditBusiness.listAuditLogs(clientActor, null, 1, 10),
            TraceabilityBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);

        assertThat(catchThrowableOfType(
            () -> auditBusiness.listAuditLogs(nonAdminConsole, null, 1, 10),
            TraceabilityBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);

        assertThat(catchThrowableOfType(
            () -> auditBusiness.listAuditLogs(adminActor, null, 0, 10),
            TraceabilityBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(catchThrowableOfType(
            () -> auditBusiness.listAuditLogs(adminActor, null, 1, 0),
            TraceabilityBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(catchThrowableOfType(
            () -> auditBusiness.listAuditLogs(adminActor, null, 1, 101),
            TraceabilityBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(catchThrowableOfType(
            () -> auditBusiness.listAuditLogs(
                adminActor,
                new AuditLogFilter(
                    null,
                    null,
                    null,
                    null,
                    Instant.parse("2026-11-27T19:00:00Z"),
                    Instant.parse("2026-11-27T18:00:00Z")
                ),
                1,
                10
            ),
            TraceabilityBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertInvalidAuditCommand(auditBusiness, null);
        assertInvalidAuditCommand(auditBusiness, new RecordAuditCommand(
            " ", "OP", "RESERVATION", null, ActorType.USER, null, null, "US",
            OperationalScope.AMB_01_CLIENT, AuditOutcome.ACCEPTED, null, null, "{}"
        ));
        assertInvalidAuditCommand(auditBusiness, new RecordAuditCommand(
            "c1", " ", "RESERVATION", null, ActorType.USER, null, null, "US",
            OperationalScope.AMB_01_CLIENT, AuditOutcome.ACCEPTED, null, null, "{}"
        ));
        assertInvalidAuditCommand(auditBusiness, new RecordAuditCommand(
            "c1", "OP", " ", null, ActorType.USER, null, null, "US",
            OperationalScope.AMB_01_CLIENT, AuditOutcome.ACCEPTED, null, null, "{}"
        ));
        assertInvalidAuditCommand(auditBusiness, new RecordAuditCommand(
            "c1", "OP", "RESERVATION", null, null, null, null, "US",
            OperationalScope.AMB_01_CLIENT, AuditOutcome.ACCEPTED, null, null, "{}"
        ));
        assertInvalidAuditCommand(auditBusiness, new RecordAuditCommand(
            "c1", "OP", "RESERVATION", null, ActorType.PLATFORM_ADMIN, null, null, "PA",
            OperationalScope.AMB_01_CLIENT, AuditOutcome.ACCEPTED, null, null, "{}"
        ));
        assertInvalidAuditCommand(auditBusiness, new RecordAuditCommand(
            "c1", "OP", "RESERVATION", null, ActorType.USER, null, null, " ",
            OperationalScope.AMB_01_CLIENT, AuditOutcome.ACCEPTED, null, null, "{}"
        ));
        assertInvalidAuditCommand(auditBusiness, new RecordAuditCommand(
            "c1", "OP", "RESERVATION", null, ActorType.USER, null, null, "US",
            null, AuditOutcome.ACCEPTED, null, null, "{}"
        ));
        assertInvalidAuditCommand(auditBusiness, new RecordAuditCommand(
            "c1", "OP", "RESERVATION", null, ActorType.USER, null, null, "US",
            OperationalScope.AMB_01_CLIENT, null, null, null, "{}"
        ));
        assertInvalidAuditCommand(auditBusiness, new RecordAuditCommand(
            "c1", "OP", "RESERVATION", null, ActorType.USER, null, null, "US",
            OperationalScope.AMB_01_CLIENT, AuditOutcome.ACCEPTED, null, null, " "
        ));
        assertInvalidAuditCommand(auditBusiness, new RecordAuditCommand(
            "c1", "OP", "RESERVATION", null, ActorType.USER, null, null, "US",
            OperationalScope.AMB_01_CLIENT, AuditOutcome.ACCEPTED, "BAD", null, "{}"
        ));
        assertInvalidAuditCommand(auditBusiness, new RecordAuditCommand(
            "c1", "OP", "RESERVATION", null, ActorType.USER, null, null, "US",
            OperationalScope.AMB_01_CLIENT, AuditOutcome.REJECTED, " ", null, "{}"
        ));
    }

    @Test
    @DisplayName("Servicios API AppHistoryService y ConsoleAuditService y wiring estático XF/B/A")
    void shouldExposeApiServicesAndInternalProblemTranslations() {
        assertThat(B.isInitialized()).isFalse();
        assertThatThrownBy(B::history).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(B::audit).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(A::appHistory).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(A::consoleAudit).isInstanceOf(IllegalStateException.class);

        XF.init();
        assertThat(B.isInitialized()).isTrue();
        B.init(FIXED_CLOCK);
        assertThat(new AuditLogBusiness(new InMemoryAuditRepository(), FIXED_CLOCK)).isNotNull();

        AppHistoryService historyService = A.appHistory();
        ConsoleAuditService auditService = A.consoleAudit();
        assertThat(historyService).isNotNull();
        assertThat(auditService).isNotNull();

        // Mapeos InternalProblem completos en AppHistoryService
        assertThat(historyService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.VALIDATION_FAILED, "bad"), null, null
        ).status()).isEqualTo(400);
        assertThat(historyService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.MALFORMED_REQUEST, "malformed"), "/p", "req"
        ).status()).isEqualTo(400);
        assertThat(historyService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.UNAUTHENTICATED, "unauth"), "/p", " "
        ).status()).isEqualTo(401);
        assertThat(historyService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.SCOPE_VIOLATION, "scope"), "/p", "r"
        ).status()).isEqualTo(403);
        assertThat(historyService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.NO_PERMISSION, "perm"), "/p", "r"
        ).status()).isEqualTo(403);
        assertThat(historyService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.NOT_FOUND, "missing"), "/p", "r"
        ).status()).isEqualTo(404);
        assertThat(historyService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.INTERNAL_ERROR, "boom"), "/p", "r"
        ).status()).isEqualTo(500);

        // Mapeos InternalProblem en ConsoleAuditService y validación de filtro outcome
        assertThat(auditService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.VALIDATION_FAILED, "bad"), null, null
        ).status()).isEqualTo(400);
        assertThat(auditService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.MALFORMED_REQUEST, "bad"), "/c", " "
        ).status()).isEqualTo(400);
        assertThat(auditService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.UNAUTHENTICATED, "unauth"), "/c", "r"
        ).status()).isEqualTo(401);
        assertThat(auditService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.SCOPE_VIOLATION, "scope"), "/c", "r"
        ).status()).isEqualTo(403);
        assertThat(auditService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.NO_PERMISSION, "perm"), "/c", "r"
        ).status()).isEqualTo(403);
        assertThat(auditService.toInternalProblem(
            new TraceabilityBusinessException(ErrorCode.INTERNAL_ERROR, "err"), "/c", "r"
        ).status()).isEqualTo(500);

        ActorContext admin = new ActorContext(
            ADMIN_ID, ORG_ID, ActorType.USER, "AD", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
        );
        assertThat(catchThrowableOfType(
            () -> auditService.listAuditLogs(admin, null, null, "UNKNOWN", null, null, null, 1, 10, "r", null),
            TraceabilityBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        ResponseWrapper<PageResponse<AuditLogEntry>> dummy = ResponseWrapper.ok(
            "OK",
            new PageResponse<>(List.of(), 1, 10, 0L, 0),
            "req",
            FIXED_CLOCK.instant()
        );
        assertThat(dummy.code()).isZero();
    }

    private static void assertInvalidHistoryCommand(ReservationHistoryBusiness business, RecordHistoryCommand cmd) {
        TraceabilityBusinessException ex = catchThrowableOfType(
            () -> business.appendHistoryInCurrentTx(cmd),
            TraceabilityBusinessException.class
        );
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private static void assertInvalidAuditCommand(AuditLogBusiness business, RecordAuditCommand cmd) {
        TraceabilityBusinessException ex = catchThrowableOfType(
            () -> business.recordAuditInAutonomousTx(cmd),
            TraceabilityBusinessException.class
        );
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private static DataSource dummyDataSource() {
        return (DataSource) Proxy.newProxyInstance(
            TraceabilityBusinessTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> null
        );
    }

    private static final class InMemoryHistoryRepository extends ReservationHistoryRepository {

        private final List<StoredReservationHistory> entries = new ArrayList<>();

        private InMemoryHistoryRepository() {
            super(dummyDataSource());
        }

        @Override
        public StoredReservationHistory insert(StoredReservationHistory entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public List<StoredReservationHistory> findByReservationId(UUID reservationId) {
            return entries.stream()
                .filter(item -> item.reservationId().equals(reservationId))
                .sorted(Comparator.comparing(StoredReservationHistory::occurredAt))
                .toList();
        }
    }

    private static final class InMemoryAuditRepository extends AuditLogRepository {

        private final List<StoredAuditLog> entries = new ArrayList<>();

        private InMemoryAuditRepository() {
            super(dummyDataSource());
        }

        @Override
        public StoredAuditLog insertAutonomous(StoredAuditLog entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public List<StoredAuditLog> findByFilter(AuditLogQueryFilter filter, int limit, int offset) {
            List<StoredAuditLog> matched = filterRows(filter);
            int toIndex = Math.min(offset + limit, matched.size());
            return matched.subList(Math.min(offset, matched.size()), toIndex);
        }

        @Override
        public long countByFilter(AuditLogQueryFilter filter) {
            return filterRows(filter).size();
        }

        private List<StoredAuditLog> filterRows(AuditLogQueryFilter filter) {
            return entries.stream()
                .filter(e -> filter.actorId() == null
                    || filter.actorId().equals(e.requesterId())
                    || filter.actorId().equals(e.holderId()))
                .filter(e -> filter.operationCode() == null || filter.operationCode().equals(e.operationCode()))
                .filter(e -> filter.outcome() == null || filter.outcome().equals(e.outcome()))
                .filter(e -> filter.correlationId() == null || filter.correlationId().equals(e.correlationId()))
                .toList();
        }
    }
}
