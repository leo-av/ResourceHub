package app.resourcehub.availability.repository.logic.local;

import app.resourcehub.availability.repository.base.PostgresRepository;
import app.resourcehub.availability.repository.structs.StoredCalendarException;
import java.sql.Date;
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

public class CalendarExceptionRepository extends PostgresRepository {

    private static final String BASE_SELECT = """
        SELECT id, resource_id, exception_date, exception_type,
               start_at, end_at, reason, created_by_user_id, created_at
        FROM availability.availability_exception
        """;

    public CalendarExceptionRepository() {
        super();
    }

    public CalendarExceptionRepository(DataSource dataSource) {
        super(dataSource);
    }

    public StoredCalendarException insert(StoredCalendarException exception) {
        String sql = """
            INSERT INTO availability.availability_exception (
                id, resource_id, exception_date, exception_type,
                start_at, end_at, reason, created_by_user_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, exception.id());
                ps.setObject(2, exception.resourceId());
                ps.setDate(3, Date.valueOf(exception.exceptionDate()));
                ps.setString(4, exception.exceptionType());
                ps.setTimestamp(5, Timestamp.from(exception.startAt()));
                ps.setTimestamp(6, Timestamp.from(exception.endAt()));
                if (exception.reason() != null) {
                    ps.setString(7, exception.reason());
                } else {
                    ps.setNull(7, Types.VARCHAR);
                }
                ps.setObject(8, exception.createdByUserId());
                ps.setTimestamp(9, Timestamp.from(exception.createdAt()));
                ps.executeUpdate();
                return exception;
            }
        }, "CalendarExceptionRepository.insert");
    }

    public Optional<StoredCalendarException> findById(UUID id) {
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
        }, "CalendarExceptionRepository.findById");
    }

    public List<StoredCalendarException> findByResourceId(UUID resourceId) {
        String sql = BASE_SELECT + """
            WHERE resource_id = ?
            ORDER BY exception_date ASC, start_at ASC, id ASC
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, resourceId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredCalendarException> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                    return results;
                }
            }
        }, "CalendarExceptionRepository.findByResourceId");
    }

    public List<StoredCalendarException> findOverlapping(UUID resourceId, Instant rangeStart, Instant rangeEnd) {
        String sql = BASE_SELECT + """
            WHERE resource_id = ?
              AND tstzrange(start_at, end_at, '[)') && tstzrange(?, ?, '[)')
            ORDER BY start_at ASC, id ASC
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, resourceId);
                ps.setTimestamp(2, Timestamp.from(rangeStart));
                ps.setTimestamp(3, Timestamp.from(rangeEnd));
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredCalendarException> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                    return results;
                }
            }
        }, "CalendarExceptionRepository.findOverlapping");
    }

    private static StoredCalendarException mapRow(ResultSet rs) throws SQLException {
        return new StoredCalendarException(
            rs.getObject("id", UUID.class),
            rs.getObject("resource_id", UUID.class),
            rs.getDate("exception_date").toLocalDate(),
            rs.getString("exception_type"),
            rs.getTimestamp("start_at").toInstant(),
            rs.getTimestamp("end_at").toInstant(),
            rs.getString("reason"),
            rs.getObject("created_by_user_id", UUID.class),
            rs.getTimestamp("created_at").toInstant()
        );
    }
}
