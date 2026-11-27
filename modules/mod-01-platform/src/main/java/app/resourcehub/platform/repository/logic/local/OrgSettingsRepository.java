package app.resourcehub.platform.repository.logic.local;

import app.resourcehub.platform.repository.base.PostgresRepository;
import app.resourcehub.platform.repository.structs.StoredOrganizationSettings;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public class OrgSettingsRepository extends PostgresRepository {

    private static final String SELECT_SINGLETON = """
        SELECT singleton_key, organization_id, time_zone_id, default_currency_code,
               max_resource_depth, series_max_horizon_days, series_max_occurrences,
               waitlist_max_entries, waitlist_offer_window_minutes,
               approval_expiration_window_hours, attendance_auto_complete_window_hours,
               allow_standard_user_space_booking, updated_at
        FROM org_config.organization_settings
        WHERE singleton_key = TRUE
        """;

    public OrgSettingsRepository() {
        super();
    }

    public OrgSettingsRepository(DataSource dataSource) {
        super(dataSource);
    }

    public Optional<StoredOrganizationSettings> findSingleton() {
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_SINGLETON);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }, "OrgSettingsRepository.findSingleton");
    }

    private static StoredOrganizationSettings mapRow(ResultSet rs) throws SQLException {
        return new StoredOrganizationSettings(
            rs.getBoolean("singleton_key"),
            rs.getObject("organization_id", UUID.class),
            rs.getString("time_zone_id"),
            rs.getString("default_currency_code").trim(),
            rs.getShort("max_resource_depth"),
            rs.getInt("series_max_horizon_days"),
            rs.getInt("series_max_occurrences"),
            rs.getInt("waitlist_max_entries"),
            rs.getInt("waitlist_offer_window_minutes"),
            rs.getInt("approval_expiration_window_hours"),
            rs.getInt("attendance_auto_complete_window_hours"),
            rs.getBoolean("allow_standard_user_space_booking"),
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
