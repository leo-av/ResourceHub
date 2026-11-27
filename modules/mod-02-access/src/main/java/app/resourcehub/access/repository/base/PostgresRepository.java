package app.resourcehub.access.repository.base;

import app.resourcehub.access.repository.structs.AccessRepositoryException;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

public abstract class PostgresRepository {

    private final DataSource dataSource;

    protected PostgresRepository() {
        this.dataSource = null;
    }

    protected PostgresRepository(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        this.dataSource = dataSource;
    }

    public boolean isConfigured() {
        return dataSource != null;
    }

    protected <T> T executeWithConnection(SqlWork<T> work, String operation) {
        if (dataSource == null) {
            throw new AccessRepositoryException("DataSource not configured for " + operation);
        }
        try (Connection connection = dataSource.getConnection()) {
            return work.execute(connection);
        } catch (SQLException ex) {
            throw new AccessRepositoryException(
                "PostgreSQL failure during " + operation + ": " + ex.getMessage(),
                ex,
                ex.getSQLState()
            );
        }
    }

    @FunctionalInterface
    protected interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
