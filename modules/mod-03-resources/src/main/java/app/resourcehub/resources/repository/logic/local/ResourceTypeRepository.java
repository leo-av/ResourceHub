package app.resourcehub.resources.repository.logic.local;

import app.resourcehub.resources.repository.base.PostgresRepository;
import app.resourcehub.resources.repository.structs.StoredResourceType;
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

public class ResourceTypeRepository extends PostgresRepository {

    private static final String BASE_SELECT = """
        SELECT id, code, name, description, nature_code, is_active, created_at, updated_at
        FROM resources.resource_type
        """;

    public ResourceTypeRepository() {
        super();
    }

    public ResourceTypeRepository(DataSource dataSource) {
        super(dataSource);
    }

    public StoredResourceType insert(StoredResourceType resourceType) {
        String sql = """
            INSERT INTO resources.resource_type (
                id, code, name, description, nature_code, is_active, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, resourceType.id());
                ps.setString(2, resourceType.code());
                ps.setString(3, resourceType.name());
                if (resourceType.description() != null) {
                    ps.setString(4, resourceType.description());
                } else {
                    ps.setNull(4, Types.VARCHAR);
                }
                ps.setString(5, resourceType.natureCode());
                ps.setBoolean(6, resourceType.active());
                ps.setTimestamp(7, Timestamp.from(resourceType.createdAt()));
                ps.setTimestamp(8, Timestamp.from(resourceType.updatedAt()));
                ps.executeUpdate();
                return resourceType;
            }
        }, "ResourceTypeRepository.insert");
    }

    public Optional<StoredResourceType> findById(UUID id) {
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
        }, "ResourceTypeRepository.findById");
    }

    public Optional<StoredResourceType> findByCode(String code) {
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
        }, "ResourceTypeRepository.findByCode");
    }

    public List<StoredResourceType> findAll() {
        String sql = BASE_SELECT + " ORDER BY created_at ASC, id ASC";
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<StoredResourceType> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
                return results;
            }
        }, "ResourceTypeRepository.findAll");
    }

    public StoredResourceType updateActive(UUID id, boolean active, Instant updatedAt) {
        String sql = """
            UPDATE resources.resource_type
            SET is_active = ?, updated_at = ?
            WHERE id = ?
            RETURNING id, code, name, description, nature_code, is_active, created_at, updated_at
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBoolean(1, active);
                ps.setTimestamp(2, Timestamp.from(updatedAt));
                ps.setObject(3, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("ResourceType not found: " + id);
                    }
                    return mapRow(rs);
                }
            }
        }, "ResourceTypeRepository.updateActive");
    }

    private static StoredResourceType mapRow(ResultSet rs) throws SQLException {
        return new StoredResourceType(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("nature_code"),
            rs.getBoolean("is_active"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
