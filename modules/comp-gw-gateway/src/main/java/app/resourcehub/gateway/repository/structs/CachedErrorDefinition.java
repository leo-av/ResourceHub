package app.resourcehub.gateway.repository.structs;

public record CachedErrorDefinition(
    String code,
    int numericValue,
    short httpStatus,
    String defaultMessage
) {
}
