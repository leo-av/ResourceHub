package app.resourcehub.access.repository.logic.local;

import app.resourcehub.access.repository.base.PostgresRepository;
import app.resourcehub.access.repository.structs.StoredPersonalData;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

public class PersonalDataRepository extends PostgresRepository {

    private static final String BASE_SELECT = """
        SELECT user_id, email, full_name, phone, password_hash,
               calendar_sync_consent, calendar_sync_consent_at, updated_at
        FROM access.personal_data
        """;

    public PersonalDataRepository() {
        super();
    }

    public PersonalDataRepository(DataSource dataSource) {
        super(dataSource);
    }

    public StoredPersonalData upsert(StoredPersonalData data) {
        String sql = """
            INSERT INTO access.personal_data (
                user_id, email, full_name, phone, password_hash,
                calendar_sync_consent, calendar_sync_consent_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET
                email = EXCLUDED.email,
                full_name = EXCLUDED.full_name,
                phone = EXCLUDED.phone,
                password_hash = COALESCE(EXCLUDED.password_hash, access.personal_data.password_hash),
                calendar_sync_consent = EXCLUDED.calendar_sync_consent,
                calendar_sync_consent_at = EXCLUDED.calendar_sync_consent_at,
                updated_at = EXCLUDED.updated_at
            RETURNING user_id, email, full_name, phone, password_hash,
                      calendar_sync_consent, calendar_sync_consent_at, updated_at
            """;
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, data.userId());
                ps.setString(2, data.email());
                ps.setString(3, data.fullName());
                if (data.phone() != null) {
                    ps.setString(4, data.phone());
                } else {
                    ps.setNull(4, Types.VARCHAR);
                }
                if (data.passwordHash() != null) {
                    ps.setString(5, data.passwordHash());
                } else {
                    ps.setNull(5, Types.VARCHAR);
                }
                ps.setBoolean(6, data.calendarSyncConsent());
                if (data.calendarSyncConsentAt() != null) {
                    ps.setTimestamp(7, Timestamp.from(data.calendarSyncConsentAt()));
                } else {
                    ps.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE);
                }
                ps.setTimestamp(8, Timestamp.from(data.updatedAt()));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return mapRow(rs);
                }
            }
        }, "PersonalDataRepository.upsert");
    }

    public Optional<StoredPersonalData> findByUserId(UUID userId) {
        String sql = BASE_SELECT + " WHERE user_id = ?";
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            }
        }, "PersonalDataRepository.findByUserId");
    }

    public Optional<StoredPersonalData> findByEmailIgnoreCase(String email) {
        String sql = BASE_SELECT + " WHERE LOWER(email) = LOWER(?)";
        return executeWithConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            }
        }, "PersonalDataRepository.findByEmailIgnoreCase");
    }

    public Map<UUID, StoredPersonalData> findByUserIds(Set<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        String sql = BASE_SELECT + " WHERE user_id = ANY(?)";
        return executeWithConnection(conn -> {
            Array uuidArray = conn.createArrayOf("uuid", userIds.toArray(UUID[]::new));
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setArray(1, uuidArray);
                try (ResultSet rs = ps.executeQuery()) {
                    Map<UUID, StoredPersonalData> found = new LinkedHashMap<>();
                    while (rs.next()) {
                        StoredPersonalData row = mapRow(rs);
                        found.put(row.userId(), row);
                    }
                    return found;
                }
            } finally {
                uuidArray.free();
            }
        }, "PersonalDataRepository.findByUserIds");
    }

    private static StoredPersonalData mapRow(ResultSet rs) throws SQLException {
        Timestamp consentAt = rs.getTimestamp("calendar_sync_consent_at");
        return new StoredPersonalData(
            rs.getObject("user_id", UUID.class),
            rs.getString("email"),
            rs.getString("full_name"),
            rs.getString("phone"),
            rs.getString("password_hash"),
            rs.getBoolean("calendar_sync_consent"),
            consentAt != null ? consentAt.toInstant() : null,
            rs.getTimestamp("updated_at").toInstant()
        );
    }
}
