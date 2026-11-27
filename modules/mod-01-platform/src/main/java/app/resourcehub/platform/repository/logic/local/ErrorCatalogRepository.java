package app.resourcehub.platform.repository.logic.local;

import app.resourcehub.platform.repository.base.PostgresRepository;
import app.resourcehub.platform.repository.structs.StoredErrorCatalogItem;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public class ErrorCatalogRepository extends PostgresRepository {

    private static final String BASE_SELECT = """
        SELECT id, code, numeric_value, family_code, module_code, http_status,
               default_message, is_active, created_at, updated_at
        FROM platform_catalog.error_catalog
        """;

    public ErrorCatalogRepository() {
        super();
    }

    public ErrorCatalogRepository(DataSource dataSource) {
        super(dataSource);
    }

    public List<StoredErrorCatalogItem> findAllActive() {
        String sql = BASE_SELECT + " WHERE is_active = TRUE ORDER BY numeric_value ASC";
        return executeQueryList(sql, "ErrorCatalogRepository.findAllActive");
    }

    public List<StoredErrorCatalogItem> findAll() {
        String sql = BASE_SELECT + " ORDER BY numeric_value ASC";
        return executeQueryList(sql, "ErrorCatalogRepository.findAll");
    }

    public Optional<StoredErrorCatalogItem> findByCode(String code) {
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
        }, "ErrorCatalogRepository.findByCode");
    }

    private List<StoredErrorCatalogItem> executeQueryList(String sql, String operation) {
        return executeWithConnection(conn -> {
            List<StoredErrorCatalogItem> items = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapRow(rs));
                }
            }
            return List.copyOf(items);
        }, operation);
    }

    private static StoredErrorCatalogItem mapRow(ResultSet rs) throws SQLException {
        return new StoredErrorCatalogItem(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getInt("numeric_value"),
            rs.getShort("family_code"),
            rs.getShort("module_code"),
            rs.getShort("http_status"),
            rs.getString("default_message"),
            rs.getBoolean("is_active"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
