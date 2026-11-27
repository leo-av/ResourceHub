package app.resourcehub.traceability.repository.structs;

public class TraceabilityRepositoryException extends RuntimeException {

    private final String sqlState;

    public TraceabilityRepositoryException(String message) {
        super(message);
        this.sqlState = null;
    }

    public TraceabilityRepositoryException(String message, Throwable cause, String sqlState) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public String getSqlState() {
        return sqlState;
    }
}
