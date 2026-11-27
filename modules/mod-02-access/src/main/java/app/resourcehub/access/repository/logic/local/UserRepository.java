package app.resourcehub.access.repository.logic.local;

import app.resourcehub.access.repository.base.PostgresRepository;
import app.resourcehub.access.repository.structs.StoredIdentityAccount;
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

public class UserRepository extends PostgresRepository {

    private static final String BASE_SELECT = """
        SELECT id, role_code, status, linked_person_resource_id,
               created_at, updated_at, deleted_at
        FROM access.identity_account
        """;

    public UserRepository() {
        super();
    }

    public UserRepository(DataSource dataSource) {
        super(dataSource);
    }

    public StoredIdentityAccount insert(StoredIdentityAccount account) {
        String sql = """
            INSERT INTO access.identity_account (
                id, role_code, status, linked_person_resource_id, created_at, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, account.id());
                ps.setString(2, account.roleCode());
                ps.setString(3, account.status());
                if (account.linkedPersonResourceId() != null) {
                    ps.setObject(4, account.linkedPersonResourceId());
                } else {
                    ps.setNull(4, Types.OTHER);
                }
                ps.setTimestamp(5, Timestamp.from(account.createdAt()));
                ps.setTimestamp(6, Timestamp.from(account.updatedAt()));
                if (account.deletedAt() != null) {
                    ps.setTimestamp(7, Timestamp.from(account.deletedAt()));
                } else {
                    ps.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE);
                }
                ps.executeUpdate();
                return account;
            }
        }, "UserRepository.insert");
    }

    public Optional<StoredIdentityAccount> findById(UUID id) {
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
        }, "UserRepository.findById");
    }

    public StoredIdentityAccount updateStatus(UUID id, String status, Instant updatedAt, Instant deletedAt) {
        String sql = """
            UPDATE access.identity_account
            SET status = ?, updated_at = ?, deleted_at = ?
            WHERE id = ?
            RETURNING id, role_code, status, linked_person_resource_id, created_at, updated_at, deleted_at
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setTimestamp(2, Timestamp.from(updatedAt));
                if (deletedAt != null) {
                    ps.setTimestamp(3, Timestamp.from(deletedAt));
                } else {
                    ps.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
                }
                ps.setObject(4, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("Identity account not found: " + id);
                    }
                    return mapRow(rs);
                }
            }
        }, "UserRepository.updateStatus");
    }

    public List<StoredIdentityAccount> findAll() {
        String sql = BASE_SELECT + " ORDER BY created_at ASC, id ASC";
        return executeWithConnection(conn -> {
            List<StoredIdentityAccount> results = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
            return List.copyOf(results);
        }, "UserRepository.findAll");
    }

    private static StoredIdentityAccount mapRow(ResultSet rs) throws SQLException {
        Timestamp deletedTs = rs.getTimestamp("deleted_at");
        Instant deletedAt = deletedTs != null ? deletedTs.toInstant() : null;
        return new StoredIdentityAccount(
            rs.getObject("id", UUID.class),
            rs.getString("role_code"),
            rs.getString("status"),
            rs.getObject("linked_person_resource_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            deletedAt
        );
    }
}
