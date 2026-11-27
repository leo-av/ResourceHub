package app.resourcehub.traceability.repository.logic.local;

import app.resourcehub.traceability.repository.base.PostgresRepository;
import app.resourcehub.traceability.repository.structs.AuditLogQueryFilter;
import app.resourcehub.traceability.repository.structs.StoredAuditLog;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

public class AuditLogRepository extends PostgresRepository {

    private static final String COLUMNS = """
        id, correlation_id, operation_code, target_entity_type, target_entity_id,
        actor_type, requester_id, holder_id, actor_role, operational_scope,
        outcome, rejection_category, violated_rule_or_policy_id, details_json, occurred_at
        """;

    private static final String INSERT_SQL = """
        INSERT INTO traceability.audit_log (
            id, correlation_id, operation_code, target_entity_type, target_entity_id,
            actor_type, requester_id, holder_id, actor_role, operational_scope,
            outcome, rejection_category, violated_rule_or_policy_id, details_json, occurred_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        """;

    public AuditLogRepository() {
        super();
    }

    public AuditLogRepository(DataSource dataSource) {
        super(dataSource);
    }

    public StoredAuditLog insertAutonomous(StoredAuditLog entry) {
        return executeAutonomousTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                ps.setObject(1, entry.id());
                ps.setString(2, entry.correlationId());
                ps.setString(3, entry.operationCode());
                ps.setString(4, entry.targetEntityType());
                setNullableUuid(ps, 5, entry.targetEntityId());
                ps.setString(6, entry.actorType());
                setNullableUuid(ps, 7, entry.requesterId());
                setNullableUuid(ps, 8, entry.holderId());
                ps.setString(9, entry.actorRole());
                ps.setString(10, entry.operationalScope());
                ps.setString(11, entry.outcome());
                setNullableString(ps, 12, entry.rejectionCategory());
                setNullableString(ps, 13, entry.violatedRuleOrPolicyId());
                ps.setString(14, entry.detailsJson());
                ps.setTimestamp(15, Timestamp.from(entry.occurredAt()));
                ps.executeUpdate();
                return entry;
            }
        }, "AuditLogRepository.insertAutonomous");
    }

    public List<StoredAuditLog> findByFilter(AuditLogQueryFilter filter, int limit, int offset) {
        List<Object> params = new ArrayList<>();
        String whereClause = buildWhereClause(filter, params);
        String sql = "SELECT " + COLUMNS
            + " FROM traceability.audit_log"
            + whereClause
            + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?";

        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                for (Object param : params) {
                    bindParam(ps, idx++, param);
                }
                ps.setInt(idx++, limit);
                ps.setInt(idx, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredAuditLog> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                    return results;
                }
            }
        }, "AuditLogRepository.findByFilter");
    }

    public long countByFilter(AuditLogQueryFilter filter) {
        List<Object> params = new ArrayList<>();
        String whereClause = buildWhereClause(filter, params);
        String sql = "SELECT COUNT(*) FROM traceability.audit_log" + whereClause;

        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                for (Object param : params) {
                    bindParam(ps, idx++, param);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }, "AuditLogRepository.countByFilter");
    }

    private static String buildWhereClause(AuditLogQueryFilter filter, List<Object> params) {
        if (filter == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<String> clauses = new ArrayList<>();
        if (filter.actorId() != null) {
            clauses.add("(requester_id = ? OR holder_id = ?)");
            params.add(filter.actorId());
            params.add(filter.actorId());
        }
        if (filter.operationCode() != null && !filter.operationCode().isBlank()) {
            clauses.add("operation_code = ?");
            params.add(filter.operationCode().trim());
        }
        if (filter.outcome() != null && !filter.outcome().isBlank()) {
            clauses.add("outcome = ?");
            params.add(filter.outcome().trim());
        }
        if (filter.correlationId() != null && !filter.correlationId().isBlank()) {
            clauses.add("correlation_id = ?");
            params.add(filter.correlationId().trim());
        }
        if (filter.fromOccurredAt() != null) {
            clauses.add("occurred_at >= ?");
            params.add(Timestamp.from(filter.fromOccurredAt()));
        }
        if (filter.toOccurredAt() != null) {
            clauses.add("occurred_at <= ?");
            params.add(Timestamp.from(filter.toOccurredAt()));
        }
        if (!clauses.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", clauses));
        }
        return sb.toString();
    }

    private static void bindParam(PreparedStatement ps, int idx, Object value) throws SQLException {
        if (value instanceof UUID uuid) {
            ps.setObject(idx, uuid);
        } else if (value instanceof Timestamp ts) {
            ps.setTimestamp(idx, ts);
        } else {
            ps.setString(idx, value.toString());
        }
    }

    private static void setNullableUuid(PreparedStatement ps, int index, UUID value) throws SQLException {
        if (value != null) {
            ps.setObject(index, value);
        } else {
            ps.setNull(index, Types.OTHER);
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }

    private static StoredAuditLog mapRow(ResultSet rs) throws SQLException {
        return new StoredAuditLog(
            rs.getObject("id", UUID.class),
            rs.getString("correlation_id"),
            rs.getString("operation_code"),
            rs.getString("target_entity_type"),
            rs.getObject("target_entity_id", UUID.class),
            rs.getString("actor_type"),
            rs.getObject("requester_id", UUID.class),
            rs.getObject("holder_id", UUID.class),
            rs.getString("actor_role"),
            rs.getString("operational_scope"),
            rs.getString("outcome"),
            rs.getString("rejection_category"),
            rs.getString("violated_rule_or_policy_id"),
            rs.getString("details_json"),
            rs.getTimestamp("occurred_at").toInstant()
        );
    }
}
