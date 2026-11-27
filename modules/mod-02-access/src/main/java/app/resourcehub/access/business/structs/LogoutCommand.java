package app.resourcehub.access.business.structs;

public record LogoutCommand(
    String accessToken,
    String refreshToken
) {
}
