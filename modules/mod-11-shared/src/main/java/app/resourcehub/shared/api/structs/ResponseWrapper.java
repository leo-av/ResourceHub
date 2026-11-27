package app.resourcehub.shared.api.structs;

import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class ResponseWrapper<T> {

    private static final DateTimeFormatter UTC_MILLIS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

    private final int code;
    private final String requestId;
    private final String timestamp;
    private final String description;
    private final T data;
    private final List<ErrorDetail> errors;

    private ResponseWrapper(
            int code,
            String requestId,
            String timestamp,
            String description,
            T data,
            List<ErrorDetail> errors) {
        this.code = code;
        this.requestId = requestId;
        this.timestamp = timestamp;
        this.description = description;
        this.data = data;
        this.errors = errors;
    }

    public static <T> ResponseWrapper<T> ok(String description) {
        return ok(description, null, UUID.randomUUID().toString(), Instant.now());
    }

    public static <T> ResponseWrapper<T> ok(String description, T data) {
        return ok(description, data, UUID.randomUUID().toString(), Instant.now());
    }

    public static <T> ResponseWrapper<T> ok(String description, String requestId, Instant timestamp) {
        return ok(description, null, requestId, timestamp);
    }

    public static <T> ResponseWrapper<T> ok(
            String description,
            T data,
            String requestId,
            Instant timestamp) {
        if (description == null || description.isBlank()) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "ResponseWrapper ok description must not be blank");
        }
        String validatedRequestId = requireRequestId(requestId);
        String formattedTimestamp = formatTimestamp(timestamp);
        return new ResponseWrapper<>(0, validatedRequestId, formattedTimestamp, description, data, null);
    }

    public static <T> ResponseWrapper<T> error(int code, List<ErrorDetail> errors) {
        return error(code, errors, UUID.randomUUID().toString(), Instant.now());
    }

    public static <T> ResponseWrapper<T> error(
            int code,
            List<ErrorDetail> errors,
            String requestId,
            Instant timestamp) {
        if (code <= 0) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "ResponseWrapper error code must be > 0");
        }
        if (errors == null || errors.isEmpty()) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "ResponseWrapper error requires at least one ErrorDetail");
        }
        String validatedRequestId = requireRequestId(requestId);
        String formattedTimestamp = formatTimestamp(timestamp);
        return new ResponseWrapper<>(
                code,
                validatedRequestId,
                formattedTimestamp,
                null,
                null,
                List.copyOf(errors));
    }

    public int code() {
        return code;
    }

    public int getCode() {
        return code;
    }

    public String requestId() {
        return requestId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String timestamp() {
        return timestamp;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String description() {
        return description;
    }

    public String getDescription() {
        return description;
    }

    public T data() {
        return data;
    }

    public T getData() {
        return data;
    }

    public List<ErrorDetail> errors() {
        return errors;
    }

    public List<ErrorDetail> getErrors() {
        return errors;
    }

    public String toJsonSkeleton(Function<T, String> dataSerializer) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"code\":").append(code);
        sb.append(",\"requestId\":").append(quoteJson(requestId));
        sb.append(",\"timestamp\":").append(quoteJson(timestamp));
        if (description != null) {
            sb.append(",\"description\":").append(quoteJson(description));
        }
        if (data != null) {
            String serialized = dataSerializer != null ? dataSerializer.apply(data) : quoteJson(data.toString());
            sb.append(",\"data\":").append(serialized);
        }
        if (errors != null) {
            sb.append(",\"errors\":[");
            for (int i = 0; i < errors.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                ErrorDetail detail = errors.get(i);
                sb.append('{');
                if (detail.field() != null) {
                    sb.append("\"field\":").append(quoteJson(detail.field())).append(',');
                }
                sb.append("\"message\":").append(quoteJson(detail.message())).append('}');
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new SharedDomainException(ErrorCode.VALIDATION_FAILED, "requestId must not be blank");
        }
        return requestId;
    }

    private static String formatTimestamp(Instant timestamp) {
        Instant effective = timestamp != null ? timestamp : Instant.now();
        return UTC_MILLIS_FORMATTER.format(effective);
    }

    private static String quoteJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 4);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else {
                out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResponseWrapper<?> that)) {
            return false;
        }
        return code == that.code
                && Objects.equals(requestId, that.requestId)
                && Objects.equals(timestamp, that.timestamp)
                && Objects.equals(description, that.description)
                && Objects.equals(data, that.data)
                && Objects.equals(errors, that.errors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, requestId, timestamp, description, data, errors);
    }
}
