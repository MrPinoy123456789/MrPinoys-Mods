package pocketdungeons;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** When a wild lodestone collapses and when its members are warned (plan 4.6). Pure. */
final class WildLifespan {

    /** Jitter ceiling in seconds: up to six hours, so lodestones placed together do not all fall together. */
    static final long JITTER_SECONDS = 6L * 3600L;
    /** The last warning, in seconds before the collapse. */
    static final long FINAL_WARNING_SECONDS = 600L;

    private WildLifespan() {}

    /** Created time plus the lifespan plus a jitter seeded by the id, so a restart never changes it. */
    static long expiresAt(long createdAt, int lifespanDays, String id) {
        CRC32 crc = new CRC32();
        crc.update(id.getBytes(StandardCharsets.UTF_8));
        return createdAt + lifespanDays * 86400L + crc.getValue() % (JITTER_SECONDS + 1L);
    }

    /** Which warning is due: 0 none, 1 the long warning, 2 the ten minute warning. */
    static int warningDue(long now, long expiresAt, int warnHours) {
        long left = expiresAt - now;
        if (left <= FINAL_WARNING_SECONDS) {
            return 2;
        }
        return left <= warnHours * 3600L ? 1 : 0;
    }
}
