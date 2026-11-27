package app.resourcehub.resources.repository.logic.local;

import app.resourcehub.resources.repository.base.PostgresRepository;
import app.resourcehub.resources.repository.structs.StoredResource;
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

public class ResourceRepository extends PostgresRepository {

    private static final String BASE_SELECT = """
        SELECT id, resource_type_id, code, name, description, status, capacity,
               requires_approval, allow_professional_approval, parent_id, root_id,
               hierarchy_path::text AS hierarchy_path_text, depth, linked_user_id,
               created_at, updated_at
        FROM resources.resource
        """;

    public ResourceRepository() {
        super();
    }

    public ResourceRepository(DataSource dataSource) {
        super(dataSource);
    }

    public StoredResource insert(StoredResource resource) {
        String sql = """
            INSERT INTO resources.resource (
                id, resource_type_id, code, name, description, status, capacity,
                requires_approval, allow_professional_approval, parent_id, root_id,
                hierarchy_path, depth, linked_user_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::public.ltree, ?, ?, ?, ?)
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, resource.id());
                ps.setObject(2, resource.resourceTypeId());
                ps.setString(3, resource.code());
                ps.setString(4, resource.name());
                if (resource.description() != null) {
                    ps.setString(5, resource.description());
                } else {
                    ps.setNull(5, Types.VARCHAR);
                }
                ps.setString(6, resource.status());
                ps.setInt(7, resource.capacity());
                ps.setBoolean(8, resource.requiresApproval());
                ps.setBoolean(9, resource.allowProfessionalApproval());
                if (resource.parentId() != null) {
                    ps.setObject(10, resource.parentId());
                } else {
                    ps.setNull(10, Types.OTHER);
                }
                ps.setObject(11, resource.rootId());
                ps.setString(12, resource.hierarchyPath());
                ps.setShort(13, resource.depth());
                if (resource.linkedUserId() != null) {
                    ps.setObject(14, resource.linkedUserId());
                } else {
                    ps.setNull(14, Types.OTHER);
                }
                ps.setTimestamp(15, Timestamp.from(resource.createdAt()));
                ps.setTimestamp(16, Timestamp.from(resource.updatedAt()));
                ps.executeUpdate();
                return resource;
            }
        }, "ResourceRepository.insert");
    }

    public Optional<StoredResource> findById(UUID id) {
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
        }, "ResourceRepository.findById");
    }

    public Optional<StoredResource> findByCode(String code) {
        String sql = BASE_SELECT + " WHERE code = ?";
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            }
        }, "ResourceRepository.findByCode");
    }

    public List<StoredResource> findAll() {
        String sql = BASE_SELECT + " ORDER BY created_at ASC, id ASC";
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<StoredResource> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
                return results;
            }
        }, "ResourceRepository.findAll");
    }

    public List<StoredResource> findByStatus(String status) {
        String sql = BASE_SELECT + " WHERE status = ? ORDER BY created_at ASC, id ASC";
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, status);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredResource> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                    return results;
                }
            }
        }, "ResourceRepository.findByStatus");
    }

    public StoredResource updateStatus(UUID id, String status, Instant updatedAt) {
        String sql = """
            UPDATE resources.resource
            SET status = ?, updated_at = ?
            WHERE id = ?
            RETURNING id, resource_type_id, code, name, description, status, capacity,
                      requires_approval, allow_professional_approval, parent_id, root_id,
                      hierarchy_path::text AS hierarchy_path_text, depth, linked_user_id,
                      created_at, updated_at
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setTimestamp(2, Timestamp.from(updatedAt));
                ps.setObject(3, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("Resource not found: " + id);
                    }
                    return mapRow(rs);
                }
            }
        }, "ResourceRepository.updateStatus");
    }

    private static StoredResource mapRow(ResultSet rs) throws SQLException {
        return new StoredResource(
            rs.getObject("id", UUID.class),
            rs.getObject("resource_type_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getInt("capacity"),
            rs.getBoolean("requires_approval"),
            rs.getBoolean("allow_professional_approval"),
            rs.getObject("parent_id", UUID.class),
            rs.getObject("root_id", UUID.class),
            rs.getString("hierarchy_path_text"),
            rs.getShort("depth"),
            rs.getObject("linked_user_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
