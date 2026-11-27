package app.resourcehub.shared.business.structs;

import java.util.UUID;

public record UUIDv7(UUID value) {

    public UUIDv7 {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new SharedDomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "UUID must conform to RFC 9562 version 7 and variant 2");
        }
    }
}
