package app.resourcehub.access.repository.structs;

public record RefreshTokenRotationResult(
    RefreshTokenRotationOutcome outcome,
    StoredRefreshToken existingToken,
    StoredRefreshToken replacementToken
) {
}
