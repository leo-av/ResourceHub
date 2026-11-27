package app.resourcehub.availability.repository.structs;

public class AvailabilityRepositoryException extends RuntimeException {

    private final String sqlState;

    public AvailabilityRepositoryException(String message, Throwable cause, String sqlState) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public AvailabilityRepositoryException(String message) {
        super(message);
        this.sqlState = null;
    }

    public String getSqlState() {
        return sqlState;
    }
}
