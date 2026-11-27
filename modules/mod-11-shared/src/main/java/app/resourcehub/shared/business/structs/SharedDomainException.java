package app.resourcehub.shared.business.structs;

public class SharedDomainException extends RuntimeException {

    private final ErrorCode errorCode;

    public SharedDomainException(ErrorCode errorCode, String message) {
        super(validateMessage(message));
        if (errorCode == null || errorCode == ErrorCode.OK) {
            throw new IllegalArgumentException("errorCode must be a non-null error ErrorCode");
        }
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    private static String validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message;
    }
}
