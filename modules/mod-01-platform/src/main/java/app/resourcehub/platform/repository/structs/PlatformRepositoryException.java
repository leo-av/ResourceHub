package app.resourcehub.platform.repository.structs;

public class PlatformRepositoryException extends RuntimeException {

    private final String sqlState;

    public PlatformRepositoryException(String message, Throwable cause, String sqlState) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public PlatformRepositoryException(String message) {
        super(message);
        this.sqlState = null;
    }

    public String getSqlState() {
        return sqlState;
    }
}
