package app.resourcehub.traceability.repository.base;

import app.resourcehub.traceability.repository.structs.TraceabilityRepositoryException;
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
            throw new TraceabilityRepositoryException("DataSource not configured for " + operation);
        }
        try (Connection connection = dataSource.getConnection()) {
            return work.execute(connection);
        } catch (SQLException ex) {
            throw new TraceabilityRepositoryException(
                "PostgreSQL failure during " + operation + ": " + ex.getMessage(),
                ex,
                ex.getSQLState()
            );
        }
    }

    protected <T> T executeAutonomousTransaction(SqlWork<T> work, String operation) {
        if (dataSource == null) {
            throw new TraceabilityRepositoryException("DataSource not configured for " + operation);
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException ex) {
            throw new TraceabilityRepositoryException(
                "PostgreSQL failure during autonomous " + operation + ": " + ex.getMessage(),
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
