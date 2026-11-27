package app.resourcehub.gateway.business.structs;

import java.time.Instant;
import java.util.List;

public record TranslatedError(
    int httpStatus,
    int numericCode,
    Instant timestamp,
    List<TranslatedErrorDetail> errors
) {

    public TranslatedError {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
