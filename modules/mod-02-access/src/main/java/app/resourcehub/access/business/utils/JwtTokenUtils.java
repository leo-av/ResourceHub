package app.resourcehub.access.business.utils;

import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class JwtTokenUtils {

    private static final String HEADER_JSON = "{\"alg\":\"EdDSA\",\"typ\":\"JWT\"}";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private JwtTokenUtils() {
        throw new UnsupportedOperationException("Utility JWT EdDSA helper");
    }

    public static KeyPair generateEd25519KeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            return generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Ed25519 algorithm not available", ex);
        }
    }

    public static String signAccessToken(
        ActorContext actor,
        Instant issuedAt,
        Instant expiresAt,
        PrivateKey privateKey
    ) {
        if (actor == null || issuedAt == null || expiresAt == null || privateKey == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "JWT signing parameters must not be null");
        }
        StringBuilder payload = new StringBuilder("{");
        appendJsonStringField(payload, "sub", actor.userId().toString(), false);
        appendJsonStringField(payload, "org", actor.organizationId().toString(), true);
        appendJsonStringField(payload, "type", actor.actorType().name(), true);
        appendJsonStringField(payload, "role", actor.actorRole(), true);
        appendJsonStringField(payload, "scope", actor.operationalScope().name(), true);
        if (actor.linkedPersonResourceId() != null) {
            appendJsonStringField(payload, "lpr", actor.linkedPersonResourceId().toString(), true);
        }
        appendJsonNumberField(payload, "iat", issuedAt.getEpochSecond(), true);
        appendJsonNumberField(payload, "exp", expiresAt.getEpochSecond(), true);
        appendJsonStringField(payload, "jti", UuidV7Utils.generate().toString(), true);
        payload.append("}");

        String encodedHeader = URL_ENCODER.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = URL_ENCODER.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String signature = URL_ENCODER.encodeToString(signer.sign());
            return signingInput + "." + signature;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to sign EdDSA JWT", ex);
        }
    }

    public static ActorContext verifyAndParse(String accessToken, PublicKey publicKey, Instant now) {
        if (accessToken == null || accessToken.isBlank() || publicKey == null || now == null) {
            throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Missing or blank access token");
        }
        String[] parts = accessToken.split("\\.");
        if (parts.length != 3) {
            throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Malformed JWT structure");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] sigBytes;
        try {
            sigBytes = URL_DECODER.decode(parts[2]);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(sigBytes)) {
                throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Invalid JWT signature");
            }
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Invalid JWT signature");
        }

        try {
            String headerJson = new String(URL_DECODER.decode(parts[0]), StandardCharsets.UTF_8);
            Map<String, String> header = parseFlatJson(headerJson);
            if (!"EdDSA".equals(header.get("alg"))) {
                throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Unsupported JWT algorithm");
            }
            String payloadJson = new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
            Map<String, String> claims = parseFlatJson(payloadJson);

            long expEpoch = Long.parseLong(requireClaim(claims, "exp"));
            if (!Instant.ofEpochSecond(expEpoch).isAfter(now)) {
                throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "JWT expired");
            }

            UUID userId = UUID.fromString(requireClaim(claims, "sub"));
            UUID organizationId = UUID.fromString(requireClaim(claims, "org"));
            ActorType actorType = ActorType.valueOf(requireClaim(claims, "type"));
            String actorRole = requireClaim(claims, "role");
            OperationalScope scope = OperationalScope.valueOf(requireClaim(claims, "scope"));
            String lprRaw = claims.get("lpr");
            UUID linkedPersonResourceId = (lprRaw == null || lprRaw.isBlank()) ? null : UUID.fromString(lprRaw);

            return new ActorContext(
                userId,
                organizationId,
                actorType,
                actorRole,
                scope,
                linkedPersonResourceId,
                Set.of()
            );
        } catch (AccessBusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Invalid JWT claims");
        }
    }

    public static String generateOpaqueRefreshToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String requireClaim(Map<String, String> claims, String key) {
        String value = claims.get(key);
        if (value == null || value.isBlank()) {
            throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Missing JWT claim " + key);
        }
        return value;
    }

    private static void appendJsonStringField(StringBuilder sb, String key, String value, boolean comma) {
        if (comma) {
            sb.append(',');
        }
        sb.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
    }

    private static void appendJsonNumberField(StringBuilder sb, String key, long value, boolean comma) {
        if (comma) {
            sb.append(',');
        }
        sb.append('"').append(key).append("\":").append(value);
    }

    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') {
                out.append('\\').append(ch);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static Map<String, String> parseFlatJson(String json) {
        String trimmed = json == null ? "" : json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        Map<String, String> result = new LinkedHashMap<>();
        if (inner.isEmpty()) {
            return result;
        }
        int idx = 0;
        int len = inner.length();
        while (idx < len) {
            while (idx < len && (inner.charAt(idx) == ',' || Character.isWhitespace(inner.charAt(idx)))) {
                idx++;
            }
            if (idx >= len) {
                break;
            }
            if (inner.charAt(idx) != '"') {
                throw new IllegalArgumentException("Expected quote for key");
            }
            int keyEnd = findClosingQuote(inner, idx + 1);
            String key = inner.substring(idx + 1, keyEnd);
            idx = keyEnd + 1;
            while (idx < len && (Character.isWhitespace(inner.charAt(idx)) || inner.charAt(idx) == ':')) {
                idx++;
            }
            if (idx < len && inner.charAt(idx) == '"') {
                int valEnd = findClosingQuote(inner, idx + 1);
                String val = unescapeJson(inner.substring(idx + 1, valEnd));
                result.put(key, val);
                idx = valEnd + 1;
            } else {
                int valStart = idx;
                while (idx < len && inner.charAt(idx) != ',' && !Character.isWhitespace(inner.charAt(idx))) {
                    idx++;
                }
                result.put(key, inner.substring(valStart, idx));
            }
        }
        return result;
    }

    private static int findClosingQuote(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == '"') {
                return i;
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    private static String unescapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length()) {
                out.append(textChar(value.charAt(++i)));
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static char textChar(char escaped) {
        return switch (escaped) {
            case '"', '\\', '/' -> escaped;
            default -> escaped;
        };
    }
}
