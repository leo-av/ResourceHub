package app.resourcehub.availability.repository.logic.local;

import app.resourcehub.availability.repository.base.PostgresRepository;
import app.resourcehub.availability.repository.structs.StoredTemporalBlock;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public class TemporalBlockRepository extends PostgresRepository {

    private static final String BASE_SELECT = """
        SELECT id, resource_id, block_origin, start_at, end_at,
               forced_confirmation, external_calendar_event_id, reason,
               is_active, created_by_user_id, created_at, released_at
        FROM availability.temporal_block
        """;

    public TemporalBlockRepository() {
        super();
    }

    public TemporalBlockRepository(DataSource dataSource) {
        super(dataSource);
    }

    public StoredTemporalBlock insert(StoredTemporalBlock block) {
        String sql = """
            INSERT INTO availability.temporal_block (
                id, resource_id, block_origin, start_at, end_at,
                forced_confirmation, external_calendar_event_id, reason,
                is_active, created_by_user_id, created_at, released_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, block.id());
                ps.setObject(2, block.resourceId());
                ps.setString(3, block.blockOrigin());
                ps.setTimestamp(4, Timestamp.from(block.startAt()));
                ps.setTimestamp(5, Timestamp.from(block.endAt()));
                ps.setBoolean(6, block.forcedConfirmation());
                if (block.externalCalendarEventId() != null) {
                    ps.setString(7, block.externalCalendarEventId());
                } else {
                    ps.setNull(7, Types.VARCHAR);
                }
                if (block.reason() != null) {
                    ps.setString(8, block.reason());
                } else {
                    ps.setNull(8, Types.VARCHAR);
                }
                ps.setBoolean(9, block.active());
                if (block.createdByUserId() != null) {
                    ps.setObject(10, block.createdByUserId());
                } else {
                    ps.setNull(10, Types.OTHER);
                }
                ps.setTimestamp(11, Timestamp.from(block.createdAt()));
                if (block.releasedAt() != null) {
                    ps.setTimestamp(12, Timestamp.from(block.releasedAt()));
                } else {
                    ps.setNull(12, Types.TIMESTAMP_WITH_TIMEZONE);
                }
                ps.executeUpdate();
                return block;
            }
        }, "TemporalBlockRepository.insert");
    }

    public Optional<StoredTemporalBlock> findById(UUID id) {
        String sql = BASE_SELECT + " WHERE id = ?";
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            }
        }, "TemporalBlockRepository.findById");
    }

    public List<StoredTemporalBlock> findActiveOverlapping(UUID resourceId, Instant rangeStart, Instant rangeEnd) {
        String sql = BASE_SELECT + """
            WHERE resource_id = ?
              AND is_active = TRUE
              AND tstzrange(start_at, end_at, '[)') && tstzrange(?, ?, '[)')
            ORDER BY start_at ASC, id ASC
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, resourceId);
                ps.setTimestamp(2, Timestamp.from(rangeStart));
                ps.setTimestamp(3, Timestamp.from(rangeEnd));
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredTemporalBlock> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                    return results;
                }
            }
        }, "TemporalBlockRepository.findActiveOverlapping");
    }

    public StoredTemporalBlock release(UUID id, Instant releasedAt) {
        String sql = """
            UPDATE availability.temporal_block
            SET is_active = FALSE, released_at = ?
            WHERE id = ?
            RETURNING id, resource_id, block_origin, start_at, end_at,
                      forced_confirmation, external_calendar_event_id, reason,
                      is_active, created_by_user_id, created_at, released_at
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setTimestamp(1, Timestamp.from(releasedAt));
                ps.setObject(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("TemporalBlock not found: " + id);
                    }
                    return mapRow(rs);
                }
            }
        }, "TemporalBlockRepository.release");
    }

    private static StoredTemporalBlock mapRow(ResultSet rs) throws SQLException {
        Timestamp rel = rs.getTimestamp("released_at");
        return new StoredTemporalBlock(
            rs.getObject("id", UUID.class),
            rs.getObject("resource_id", UUID.class),
            rs.getString("block_origin"),
            rs.getTimestamp("start_at").toInstant(),
            rs.getTimestamp("end_at").toInstant(),
            rs.getBoolean("forced_confirmation"),
            rs.getString("external_calendar_event_id"),
            rs.getString("reason"),
            rs.getBoolean("is_active"),
            rs.getObject("created_by_user_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rel != null ? rel.toInstant() : null
        );
    }
}
