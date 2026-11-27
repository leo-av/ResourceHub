package app.resourcehub.availability.repository.logic.local;

import app.resourcehub.availability.repository.base.PostgresRepository;
import app.resourcehub.availability.repository.structs.StoredRecurringSchedule;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

public class WeeklyScheduleRepository extends PostgresRepository {

    private static final String BASE_SELECT = """
        SELECT id, resource_id, day_of_week,
               to_char(start_local_time, 'HH24:MI:SS') AS start_time_txt,
               to_char(end_local_time, 'HH24:MI:SS') AS end_time_txt,
               valid_from, valid_until, created_at
        FROM availability.recurring_schedule
        """;

    public WeeklyScheduleRepository() {
        super();
    }

    public WeeklyScheduleRepository(DataSource dataSource) {
        super(dataSource);
    }

    public List<StoredRecurringSchedule> replaceForResource(UUID resourceId, List<StoredRecurringSchedule> slots) {
        String deleteSql = "DELETE FROM availability.recurring_schedule WHERE resource_id = ?";
        String insertSql = """
            INSERT INTO availability.recurring_schedule (
                id, resource_id, day_of_week, start_local_time, end_local_time,
                valid_from, valid_until, created_at
            ) VALUES (?, ?, ?, ?::time, ?::time, ?, ?, ?)
            """;
        return executeWithConnection(conn -> {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement deletePs = conn.prepareStatement(deleteSql)) {
                    deletePs.setObject(1, resourceId);
                    deletePs.executeUpdate();
                }
                try (PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                    for (StoredRecurringSchedule slot : slots) {
                        insertPs.setObject(1, slot.id());
                        insertPs.setObject(2, slot.resourceId());
                        insertPs.setShort(3, slot.dayOfWeek());
                        insertPs.setString(4, slot.startLocalTime());
                        insertPs.setString(5, slot.endLocalTime());
                        if (slot.validFrom() != null) {
                            insertPs.setDate(6, Date.valueOf(slot.validFrom()));
                        } else {
                            insertPs.setNull(6, Types.DATE);
                        }
                        if (slot.validUntil() != null) {
                            insertPs.setDate(7, Date.valueOf(slot.validUntil()));
                        } else {
                            insertPs.setNull(7, Types.DATE);
                        }
                        insertPs.setTimestamp(8, Timestamp.from(slot.createdAt()));
                        insertPs.addBatch();
                    }
                    insertPs.executeBatch();
                }
                conn.commit();
                return List.copyOf(slots);
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        }, "WeeklyScheduleRepository.replaceForResource");
    }

    public List<StoredRecurringSchedule> findByResourceId(UUID resourceId) {
        String sql = BASE_SELECT + " WHERE resource_id = ? ORDER BY day_of_week ASC, start_local_time ASC";
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, resourceId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredRecurringSchedule> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                    return results;
                }
            }
        }, "WeeklyScheduleRepository.findByResourceId");
    }

    private static StoredRecurringSchedule mapRow(ResultSet rs) throws SQLException {
        Date vf = rs.getDate("valid_from");
        Date vu = rs.getDate("valid_until");
        LocalDate validFrom = vf != null ? vf.toLocalDate() : null;
        LocalDate validUntil = vu != null ? vu.toLocalDate() : null;
        return new StoredRecurringSchedule(
            rs.getObject("id", UUID.class),
            rs.getObject("resource_id", UUID.class),
            rs.getShort("day_of_week"),
            rs.getString("start_time_txt"),
            rs.getString("end_time_txt"),
            validFrom,
            validUntil,
            rs.getTimestamp("created_at").toInstant()
        );
    }
}
