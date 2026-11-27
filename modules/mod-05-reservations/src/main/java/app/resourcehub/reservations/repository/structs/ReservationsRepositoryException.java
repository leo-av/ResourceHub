package app.resourcehub.reservations.repository.structs;

public class ReservationsRepositoryException extends RuntimeException {

    private final String sqlState;

    public ReservationsRepositoryException(String message, Throwable cause, String sqlState) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public ReservationsRepositoryException(String message) {
        super(message);
        this.sqlState = null;
    }

    public String getSqlState() {
        return sqlState;
    }
}
