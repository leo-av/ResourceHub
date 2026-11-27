package app.resourcehub.access.business.utils;

import java.nio.CharBuffer;
import java.util.Arrays;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public final class Argon2PasswordUtils {

    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19456;
    private static final int ITERATIONS = 2;

    private static final Argon2PasswordEncoder ENCODER = new Argon2PasswordEncoder(
        SALT_LENGTH_BYTES,
        HASH_LENGTH_BYTES,
        PARALLELISM,
        MEMORY_KIB,
        ITERATIONS
    );

    private Argon2PasswordUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String hash(char[] rawPassword) {
        if (rawPassword == null || rawPassword.length == 0) {
            throw new IllegalArgumentException("rawPassword must not be empty");
        }
        CharBuffer buffer = CharBuffer.wrap(rawPassword);
        try {
            return ENCODER.encode(buffer);
        } finally {
            Arrays.fill(rawPassword, '\0');
        }
    }

    public static boolean matches(char[] rawPassword, String encodedHash) {
        if (rawPassword == null || encodedHash == null || encodedHash.isBlank()) {
            return false;
        }
        CharBuffer buffer = CharBuffer.wrap(rawPassword);
        try {
            return ENCODER.matches(buffer, encodedHash);
        } finally {
            Arrays.fill(rawPassword, '\0');
        }
    }

    public static boolean isArgon2idHash(String encodedHash) {
        return encodedHash != null && encodedHash.startsWith("$argon2id$v=19$");
    }
}
