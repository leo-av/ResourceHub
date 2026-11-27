package app.resourcehub.resources.repository.structs;

public class ResourcesRepositoryException extends RuntimeException {

    private final String sqlState;

    public ResourcesRepositoryException(String message, Throwable cause, String sqlState) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public ResourcesRepositoryException(String message) {
        super(message);
        this.sqlState = null;
    }

    public String getSqlState() {
        return sqlState;
    }
}
