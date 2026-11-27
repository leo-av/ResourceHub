package app.resourcehub.shared.business.utils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

public final class UuidV7Utils {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7Utils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static UUID generate() {
        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);

        long timestampMillis = Instant.now().toEpochMilli();
        long msb = (timestampMillis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L; // RFC 9562 version 7
        msb |= ((randomBytes[0] & 0x0FL) << 8) | (randomBytes[1] & 0xFFL);

        long lsb = 0L;
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (randomBytes[i] & 0xFFL);
        }
        lsb &= 0x3FFF_FFFF_FFFF_FFFFL;
        lsb |= 0x8000_0000_0000_0000L; // RFC 9562 variant 2

        return new UUID(msb, lsb);
    }
}
