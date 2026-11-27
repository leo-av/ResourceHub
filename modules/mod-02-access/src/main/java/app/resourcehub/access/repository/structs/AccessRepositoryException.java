package app.resourcehub.access.repository.structs;

public class AccessRepositoryException extends RuntimeException {

    private final String sqlState;

    public AccessRepositoryException(String message, Throwable cause, String sqlState) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public AccessRepositoryException(String message) {
        super(message);
        this.sqlState = null;
    }

    public String getSqlState() {
        return sqlState;
    }
}
