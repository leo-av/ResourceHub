package app.resourcehub.platform.repository.logic.local;

import app.resourcehub.platform.repository.base.PostgresRepository;
import app.resourcehub.platform.repository.structs.StoredOrganization;
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

public class OrganizationRepository extends PostgresRepository {

    private static final String BASE_SELECT = """
        SELECT id, slug, name, status, initial_time_zone_id, db_host, db_port,
               db_name, current_schema_version, created_at, updated_at, archived_at
        FROM platform_catalog.organization
        """;

    public OrganizationRepository() {
        super();
    }

    public OrganizationRepository(DataSource dataSource) {
        super(dataSource);
    }

    public Optional<StoredOrganization> findBySlug(String slug) {
        String sql = BASE_SELECT + " WHERE slug = ?";
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, slug);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            }
        }, "OrganizationRepository.findBySlug");
    }

    public Optional<StoredOrganization> findById(UUID id) {
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
        }, "OrganizationRepository.findById");
    }

    public List<StoredOrganization> findAll() {
        String sql = BASE_SELECT + " ORDER BY created_at ASC, slug ASC";
        return executeWithConnection(conn -> {
            List<StoredOrganization> results = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
            return List.copyOf(results);
        }, "OrganizationRepository.findAll");
    }

    public StoredOrganization insert(StoredOrganization organization) {
        String sql = """
            INSERT INTO platform_catalog.organization (
                id, slug, name, status, initial_time_zone_id, db_host, db_port,
                db_name, current_schema_version, created_at, updated_at, archived_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, organization.id());
                ps.setString(2, organization.slug());
                ps.setString(3, organization.name());
                ps.setString(4, organization.status());
                ps.setString(5, organization.initialTimeZoneId());
                ps.setString(6, organization.dbHost());
                ps.setInt(7, organization.dbPort());
                ps.setString(8, organization.dbName());
                ps.setString(9, organization.currentSchemaVersion());
                ps.setTimestamp(10, Timestamp.from(organization.createdAt()));
                ps.setTimestamp(11, Timestamp.from(organization.updatedAt()));
                if (organization.archivedAt() != null) {
                    ps.setTimestamp(12, Timestamp.from(organization.archivedAt()));
                } else {
                    ps.setNull(12, Types.TIMESTAMP_WITH_TIMEZONE);
                }
                ps.executeUpdate();
                return organization;
            }
        }, "OrganizationRepository.insert");
    }

    private static StoredOrganization mapRow(ResultSet rs) throws SQLException {
        Timestamp archivedTs = rs.getTimestamp("archived_at");
        Instant archivedAt = archivedTs != null ? archivedTs.toInstant() : null;
        return new StoredOrganization(
            rs.getObject("id", UUID.class),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getString("initial_time_zone_id"),
            rs.getString("db_host"),
            rs.getInt("db_port"),
            rs.getString("db_name"),
            rs.getString("current_schema_version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            archivedAt
        );
    }
}
