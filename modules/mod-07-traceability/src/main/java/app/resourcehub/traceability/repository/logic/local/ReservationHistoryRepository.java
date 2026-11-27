package app.resourcehub.traceability.repository.logic.local;

import app.resourcehub.traceability.repository.base.PostgresRepository;
import app.resourcehub.traceability.repository.structs.StoredReservationHistory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

public class ReservationHistoryRepository extends PostgresRepository {

    private static final String COLUMNS = """
        id, reservation_id, change_type, previous_status, new_status,
        actor_type, requester_id, holder_id, operational_scope,
        snapshot_before_json, snapshot_after_json, correlation_id, occurred_at
        """;

    private static final String INSERT_SQL = """
        INSERT INTO traceability.reservation_history (
            id, reservation_id, change_type, previous_status, new_status,
            actor_type, requester_id, holder_id, operational_scope,
            snapshot_before_json, snapshot_after_json, correlation_id, occurred_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
        """;

    public ReservationHistoryRepository() {
        super();
    }

    public ReservationHistoryRepository(DataSource dataSource) {
        super(dataSource);
    }

    public StoredReservationHistory insert(StoredReservationHistory entry) {
        return executeWithConnection(conn -> insertInConnection(conn, entry), "ReservationHistoryRepository.insert");
    }

    public StoredReservationHistory insertInConnection(
        Connection connection,
        StoredReservationHistory entry
    ) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
            ps.setObject(1, entry.id());
            ps.setObject(2, entry.reservationId());
            ps.setString(3, entry.changeType());
            if (entry.previousStatus() != null) {
                ps.setString(4, entry.previousStatus());
            } else {
                ps.setNull(4, Types.VARCHAR);
            }
            ps.setString(5, entry.newStatus());
            ps.setString(6, entry.actorType());
            if (entry.requesterId() != null) {
                ps.setObject(7, entry.requesterId());
            } else {
                ps.setNull(7, Types.OTHER);
            }
            ps.setObject(8, entry.holderId());
            ps.setString(9, entry.operationalScope());
            if (entry.snapshotBeforeJson() != null) {
                ps.setString(10, entry.snapshotBeforeJson());
            } else {
                ps.setNull(10, Types.VARCHAR);
            }
            ps.setString(11, entry.snapshotAfterJson());
            ps.setString(12, entry.correlationId());
            ps.setTimestamp(13, Timestamp.from(entry.occurredAt()));
            ps.executeUpdate();
            return entry;
        }
    }

    public List<StoredReservationHistory> findByReservationId(UUID reservationId) {
        String sql = "SELECT " + COLUMNS + """
            FROM traceability.reservation_history
            WHERE reservation_id = ?
            ORDER BY occurred_at ASC, id ASC
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, reservationId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredReservationHistory> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                    return results;
                }
            }
        }, "ReservationHistoryRepository.findByReservationId");
    }

    private static StoredReservationHistory mapRow(ResultSet rs) throws SQLException {
        return new StoredReservationHistory(
            rs.getObject("id", UUID.class),
            rs.getObject("reservation_id", UUID.class),
            rs.getString("change_type"),
            rs.getString("previous_status"),
            rs.getString("new_status"),
            rs.getString("actor_type"),
            rs.getObject("requester_id", UUID.class),
            rs.getObject("holder_id", UUID.class),
            rs.getString("operational_scope"),
            rs.getString("snapshot_before_json"),
            rs.getString("snapshot_after_json"),
            rs.getString("correlation_id"),
            rs.getTimestamp("occurred_at").toInstant()
        );
    }
}
