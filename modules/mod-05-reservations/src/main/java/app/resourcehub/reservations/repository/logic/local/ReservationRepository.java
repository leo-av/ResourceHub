package app.resourcehub.reservations.repository.logic.local;

import app.resourcehub.reservations.repository.base.PostgresRepository;
import app.resourcehub.reservations.repository.structs.ReservationsRepositoryException;
import app.resourcehub.reservations.repository.structs.StoredReservation;
import app.resourcehub.reservations.repository.structs.StoredReservationAggregate;
import app.resourcehub.reservations.repository.structs.StoredReservationLine;
import java.sql.Connection;
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

public class ReservationRepository extends PostgresRepository {

    private static final String HEADER_COLUMNS = """
        id, requester_id, holder_id, origin_scope, status, pending_reason, expires_at,
        series_id, series_occurrence_index, total_amount_minor, currency_code, payment_status,
        attendance_status, attendance_marked_by_user_id, attendance_marked_at,
        cancellation_source, caused_by_block_id, version, created_at, updated_at
        """;

    private static final String LINE_COLUMNS = """
        id, reservation_id, line_number, resource_id, resource_root_id,
        start_at, end_at, requested_units, is_exclusive, status, line_amount_minor, currency_code
        """;

    public ReservationRepository() {
        super();
    }

    public ReservationRepository(DataSource dataSource) {
        super(dataSource);
    }

    public StoredReservationAggregate insertAggregate(StoredReservation header, List<StoredReservationLine> lines) {
        if (header == null || lines == null || lines.isEmpty()) {
            throw new ReservationsRepositoryException("Reservation aggregate requires header and at least one line");
        }
        String insertHeaderSql = "INSERT INTO reservations.reservation (" + HEADER_COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String insertLineSql = "INSERT INTO reservations.reservation_line (" + LINE_COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        return executeWithConnection(conn -> {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement psHeader = conn.prepareStatement(insertHeaderSql)) {
                    bindHeader(psHeader, header);
                    psHeader.executeUpdate();
                }
                try (PreparedStatement psLine = conn.prepareStatement(insertLineSql)) {
                    for (StoredReservationLine line : lines) {
                        bindLine(psLine, line);
                        psLine.executeUpdate();
                    }
                }
                conn.commit();
                return new StoredReservationAggregate(header, List.copyOf(lines));
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        }, "ReservationRepository.insertAggregate");
    }

    public Optional<StoredReservationAggregate> findById(UUID reservationId) {
        String headerSql = "SELECT " + HEADER_COLUMNS + " FROM reservations.reservation WHERE id = ?";
        String linesSql = "SELECT " + LINE_COLUMNS
            + " FROM reservations.reservation_line WHERE reservation_id = ? ORDER BY line_number ASC, id ASC";
        return executeWithConnection(conn -> {
            Optional<StoredReservation> headerOpt;
            try (PreparedStatement psHeader = conn.prepareStatement(headerSql)) {
                psHeader.setObject(1, reservationId);
                try (ResultSet rs = psHeader.executeQuery()) {
                    headerOpt = rs.next() ? Optional.of(mapHeader(rs)) : Optional.empty();
                }
            }
            if (headerOpt.isEmpty()) {
                return Optional.empty();
            }
            List<StoredReservationLine> lines = new ArrayList<>();
            try (PreparedStatement psLines = conn.prepareStatement(linesSql)) {
                psLines.setObject(1, reservationId);
                try (ResultSet rs = psLines.executeQuery()) {
                    while (rs.next()) {
                        lines.add(mapLine(rs));
                    }
                }
            }
            return Optional.of(new StoredReservationAggregate(headerOpt.get(), lines));
        }, "ReservationRepository.findById");
    }

    public List<StoredReservation> findByHolderId(UUID holderId, int limit, int offset) {
        String sql = "SELECT " + HEADER_COLUMNS + """
            FROM reservations.reservation
            WHERE holder_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, holderId);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredReservation> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(mapHeader(rs));
                    }
                    return results;
                }
            }
        }, "ReservationRepository.findByHolderId");
    }

    public long countByHolderId(UUID holderId) {
        String sql = "SELECT COUNT(*) FROM reservations.reservation WHERE holder_id = ?";
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, holderId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }, "ReservationRepository.countByHolderId");
    }

    public List<StoredReservationLine> findActiveLinesOverlapping(
        UUID resourceId,
        Instant rangeStart,
        Instant rangeEnd
    ) {
        String sql = "SELECT " + LINE_COLUMNS + """
            FROM reservations.reservation_line
            WHERE resource_id = ?
              AND status = 'ACTIVE'
              AND tstzrange(start_at, end_at, '[)') && tstzrange(?::timestamptz, ?::timestamptz, '[)')
            ORDER BY start_at ASC, id ASC
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, resourceId);
                ps.setTimestamp(2, Timestamp.from(rangeStart));
                ps.setTimestamp(3, Timestamp.from(rangeEnd));
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredReservationLine> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(mapLine(rs));
                    }
                    return results;
                }
            }
        }, "ReservationRepository.findActiveLinesOverlapping");
    }

    public Optional<StoredReservation> updateStatus(
        UUID reservationId,
        String newStatus,
        String pendingReason,
        Instant expiresAt,
        String cancellationSource,
        UUID causedByBlockId,
        int expectedVersion,
        Instant updatedAt
    ) {
        String updateHeaderSql = """
            UPDATE reservations.reservation
            SET status = ?,
                pending_reason = ?,
                expires_at = ?,
                cancellation_source = ?,
                caused_by_block_id = ?,
                version = version + 1,
                updated_at = ?
            WHERE id = ? AND version = ?
            RETURNING """ + HEADER_COLUMNS;

        String releaseLinesSql = """
            UPDATE reservations.reservation_line
            SET status = 'RELEASED'
            WHERE reservation_id = ? AND status = 'ACTIVE'
            """;

        return executeWithConnection(conn -> {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Optional<StoredReservation> updatedOpt;
                try (PreparedStatement ps = conn.prepareStatement(updateHeaderSql)) {
                    ps.setString(1, newStatus);
                    setNullableString(ps, 2, pendingReason);
                    setNullableTimestamp(ps, 3, expiresAt);
                    setNullableString(ps, 4, cancellationSource);
                    setNullableUuid(ps, 5, causedByBlockId);
                    ps.setTimestamp(6, Timestamp.from(updatedAt));
                    ps.setObject(7, reservationId);
                    ps.setInt(8, expectedVersion);
                    try (ResultSet rs = ps.executeQuery()) {
                        updatedOpt = rs.next() ? Optional.of(mapHeader(rs)) : Optional.empty();
                    }
                }
                if (updatedOpt.isPresent() && isTerminalReleaseStatus(newStatus)) {
                    releaseActiveLines(conn, releaseLinesSql, reservationId);
                }
                conn.commit();
                return updatedOpt;
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        }, "ReservationRepository.updateStatus");
    }

    private static boolean isTerminalReleaseStatus(String status) {
        return "CANCELLED".equals(status) || "REJECTED".equals(status) || "EXPIRED".equals(status);
    }

    private static void releaseActiveLines(Connection conn, String sql, UUID reservationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, reservationId);
            ps.executeUpdate();
        }
    }

    private static void bindHeader(PreparedStatement ps, StoredReservation h) throws SQLException {
        ps.setObject(1, h.id());
        ps.setObject(2, h.requesterId());
        ps.setObject(3, h.holderId());
        ps.setString(4, h.originScope());
        ps.setString(5, h.status());
        setNullableString(ps, 6, h.pendingReason());
        setNullableTimestamp(ps, 7, h.expiresAt());
        setNullableUuid(ps, 8, h.seriesId());
        if (h.seriesOccurrenceIndex() != null) {
            ps.setInt(9, h.seriesOccurrenceIndex());
        } else {
            ps.setNull(9, Types.INTEGER);
        }
        ps.setLong(10, h.totalAmountMinor());
        ps.setString(11, h.currencyCode());
        ps.setString(12, h.paymentStatus());
        setNullableString(ps, 13, h.attendanceStatus());
        setNullableUuid(ps, 14, h.attendanceMarkedByUserId());
        setNullableTimestamp(ps, 15, h.attendanceMarkedAt());
        setNullableString(ps, 16, h.cancellationSource());
        setNullableUuid(ps, 17, h.causedByBlockId());
        ps.setInt(18, h.version());
        ps.setTimestamp(19, Timestamp.from(h.createdAt()));
        ps.setTimestamp(20, Timestamp.from(h.updatedAt()));
    }

    private static void bindLine(PreparedStatement ps, StoredReservationLine line) throws SQLException {
        ps.setObject(1, line.id());
        ps.setObject(2, line.reservationId());
        ps.setShort(3, line.lineNumber());
        ps.setObject(4, line.resourceId());
        ps.setObject(5, line.resourceRootId());
        ps.setTimestamp(6, Timestamp.from(line.startAt()));
        ps.setTimestamp(7, Timestamp.from(line.endAt()));
        ps.setInt(8, line.requestedUnits());
        ps.setBoolean(9, line.exclusive());
        ps.setString(10, line.status());
        ps.setLong(11, line.lineAmountMinor());
        ps.setString(12, line.currencyCode());
    }

    private static StoredReservation mapHeader(ResultSet rs) throws SQLException {
        Timestamp expiresTs = rs.getTimestamp("expires_at");
        Timestamp attMarkedTs = rs.getTimestamp("attendance_marked_at");
        Integer occIndex = rs.getObject("series_occurrence_index", Integer.class);
        return new StoredReservation(
            rs.getObject("id", UUID.class),
            rs.getObject("requester_id", UUID.class),
            rs.getObject("holder_id", UUID.class),
            rs.getString("origin_scope"),
            rs.getString("status"),
            rs.getString("pending_reason"),
            expiresTs != null ? expiresTs.toInstant() : null,
            rs.getObject("series_id", UUID.class),
            occIndex,
            rs.getLong("total_amount_minor"),
            rs.getString("currency_code"),
            rs.getString("payment_status"),
            rs.getString("attendance_status"),
            rs.getObject("attendance_marked_by_user_id", UUID.class),
            attMarkedTs != null ? attMarkedTs.toInstant() : null,
            rs.getString("cancellation_source"),
            rs.getObject("caused_by_block_id", UUID.class),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static StoredReservationLine mapLine(ResultSet rs) throws SQLException {
        return new StoredReservationLine(
            rs.getObject("id", UUID.class),
            rs.getObject("reservation_id", UUID.class),
            rs.getShort("line_number"),
            rs.getObject("resource_id", UUID.class),
            rs.getObject("resource_root_id", UUID.class),
            rs.getTimestamp("start_at").toInstant(),
            rs.getTimestamp("end_at").toInstant(),
            rs.getInt("requested_units"),
            rs.getBoolean("is_exclusive"),
            rs.getString("status"),
            rs.getLong("line_amount_minor"),
            rs.getString("currency_code")
        );
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }

    private static void setNullableUuid(PreparedStatement ps, int index, UUID value) throws SQLException {
        if (value != null) {
            ps.setObject(index, value);
        } else {
            ps.setNull(index, Types.OTHER);
        }
    }

    private static void setNullableTimestamp(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value != null) {
            ps.setTimestamp(index, Timestamp.from(value));
        } else {
            ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        }
    }
}
