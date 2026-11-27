package app.resourcehub.access.repository.structs;

public enum RefreshTokenRotationOutcome {
    ROTATED,
    NOT_FOUND,
    EXPIRED,
    REUSE_DETECTED
}
