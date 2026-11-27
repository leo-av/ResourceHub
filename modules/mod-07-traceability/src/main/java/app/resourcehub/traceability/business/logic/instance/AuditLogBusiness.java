package app.resourcehub.traceability.business.logic.instance;

import app.resourcehub.access.business.B;
import app.resourcehub.access.business.logic.instance.AuthorizationBusiness;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import app.resourcehub.traceability.business.structs.AuditActorSummary;
import app.resourcehub.traceability.business.structs.AuditLogEntry;
import app.resourcehub.traceability.business.structs.AuditLogFilter;
import app.resourcehub.traceability.business.structs.AuditLogPage;
import app.resourcehub.traceability.business.structs.AuditOutcome;
import app.resourcehub.traceability.business.structs.AutonomousTransaction;
import app.resourcehub.traceability.business.structs.RecordAuditCommand;
import app.resourcehub.traceability.business.structs.TraceabilityBusinessException;
import app.resourcehub.traceability.repository.logic.local.AuditLogRepository;
import app.resourcehub.traceability.repository.structs.AuditLogQueryFilter;
import app.resourcehub.traceability.repository.structs.StoredAuditLog;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public class AuditLogBusiness {

    private static final int MAX_PAGE_SIZE = 100;
    private static final AuthorizationBusiness DEFAULT_AUTHZ = new AuthorizationBusiness();

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;
    private final Function<Set<UUID>, Map<UUID, AuditActorSummary>> actorSummaryResolver;

    public AuditLogBusiness(AuditLogRepository auditLogRepository, Clock clock) {
        this(auditLogRepository, clock, ids -> Map.of());
    }

    public AuditLogBusiness(
        AuditLogRepository auditLogRepository,
        Clock clock,
        Function<Set<UUID>, Map<UUID, AuditActorSummary>> actorSummaryResolver
    ) {
        if (auditLogRepository == null || clock == null) {
            throw new IllegalArgumentException("auditLogRepository and clock must not be null");
        }
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
        this.actorSummaryResolver = actorSummaryResolver != null ? actorSummaryResolver : ids -> Map.of();
    }

    @AutonomousTransaction
    public AuditLogEntry recordAuditInAutonomousTx(RecordAuditCommand command) {
        if (command == null
            || isBlank(command.correlationId())
            || isBlank(command.operationCode())
            || isBlank(command.targetEntityType())
            || command.actorType() == null
            || command.actorType() == ActorType.PLATFORM_ADMIN
            || isBlank(command.actorRole())
            || command.operationalScope() == null
            || command.outcome() == null
            || isBlank(command.detailsJson())) {
            throw new TraceabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "All mandatory audit log fields must be provided"
            );
        }
        if (command.outcome() == AuditOutcome.ACCEPTED && command.rejectionCategory() != null) {
            throw new TraceabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "ACCEPTED audit log entry must not define rejectionCategory"
            );
        }
        if (command.outcome() == AuditOutcome.REJECTED && isBlank(command.rejectionCategory())) {
            throw new TraceabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "REJECTED audit log entry requires a non-blank rejectionCategory"
            );
        }

        UUID id = UuidV7Utils.generate();
        Instant now = clock.instant();
        StoredAuditLog stored = new StoredAuditLog(
            id,
            command.correlationId().trim(),
            command.operationCode().trim(),
            command.targetEntityType().trim(),
            command.targetEntityId(),
            command.actorType().name(),
            command.requesterId(),
            command.holderId(),
            command.actorRole().trim(),
            command.operationalScope().name(),
            command.outcome().name(),
            command.rejectionCategory() == null ? null : command.rejectionCategory().trim(),
            isBlank(command.violatedRuleOrPolicyId()) ? null : command.violatedRuleOrPolicyId().trim(),
            command.detailsJson(),
            now
        );
        StoredAuditLog inserted = auditLogRepository.insertAutonomous(stored);
        return toDomain(inserted, Map.of());
    }

    public AuditLogPage listAuditLogs(ActorContext actor, AuditLogFilter filter, int page, int size) {
        AuthorizationBusiness authz = B.isInitialized() ? B.authorization() : DEFAULT_AUTHZ;
        try {
            authz.assertConsoleOperation(actor, false);
        } catch (AccessBusinessException ex) {
            throw new TraceabilityBusinessException(ex.getErrorCode(), ex.getMessage());
        }
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new TraceabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "Pagination parameters must satisfy page >= 1 and 1 <= size <= 100"
            );
        }
        AuditLogQueryFilter effectiveFilter = filter != null
            ? new AuditLogQueryFilter(
                filter.actorId(),
                filter.operationCode(),
                filter.outcome() != null ? filter.outcome().name() : null,
                filter.correlationId(),
                filter.fromOccurredAt(),
                filter.toOccurredAt()
            )
            : new AuditLogQueryFilter(null, null, null, null, null, null);
        if (effectiveFilter.fromOccurredAt() != null
            && effectiveFilter.toOccurredAt() != null
            && effectiveFilter.fromOccurredAt().isAfter(effectiveFilter.toOccurredAt())) {
            throw new TraceabilityBusinessException(
                ErrorCode.VALIDATION_FAILED,
                "fromOccurredAt must not be after toOccurredAt"
            );
        }

        long totalItems = auditLogRepository.countByFilter(effectiveFilter);
        if (totalItems == 0L) {
            return new AuditLogPage(List.of(), page, size, 0L, 0);
        }
        int offset = (page - 1) * size;
        List<StoredAuditLog> rows = auditLogRepository.findByFilter(effectiveFilter, size, offset);
        Set<UUID> actorIds = new HashSet<>();
        for (StoredAuditLog row : rows) {
            if (row.requesterId() != null) {
                actorIds.add(row.requesterId());
            }
            if (row.holderId() != null) {
                actorIds.add(row.holderId());
            }
        }
        Map<UUID, AuditActorSummary> hydrated = actorIds.isEmpty()
            ? Map.of()
            : safeResolve(Set.copyOf(actorIds));
        List<AuditLogEntry> entries = rows.stream()
            .map(row -> toDomain(row, hydrated))
            .toList();
        int totalPages = (int) ((totalItems + size - 1) / size);
        return new AuditLogPage(entries, page, size, totalItems, totalPages);
    }

    private Map<UUID, AuditActorSummary> safeResolve(Set<UUID> actorIds) {
        Map<UUID, AuditActorSummary> resolved = actorSummaryResolver.apply(actorIds);
        return resolved != null ? resolved : Map.of();
    }

    private static AuditLogEntry toDomain(StoredAuditLog stored, Map<UUID, AuditActorSummary> hydrated) {
        return new AuditLogEntry(
            stored.id(),
            stored.correlationId(),
            stored.operationCode(),
            stored.targetEntityType(),
            stored.targetEntityId(),
            ActorType.valueOf(stored.actorType()),
            stored.requesterId(),
            resolveActor(stored.requesterId(), hydrated),
            stored.holderId(),
            resolveActor(stored.holderId(), hydrated),
            stored.actorRole(),
            OperationalScope.valueOf(stored.operationalScope()),
            AuditOutcome.valueOf(stored.outcome()),
            stored.rejectionCategory(),
            stored.violatedRuleOrPolicyId(),
            stored.detailsJson(),
            stored.occurredAt()
        );
    }

    private static AuditActorSummary resolveActor(UUID actorId, Map<UUID, AuditActorSummary> hydrated) {
        if (actorId == null) {
            return null;
        }
        AuditActorSummary summary = hydrated.get(actorId);
        if (summary != null) {
            return summary;
        }
        return new AuditActorSummary(actorId, AuditActorSummary.ANONYMIZED_LABEL, true);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
