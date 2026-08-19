package smalltalk.identity;

import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * SPEC.md section 13: a second UUID-hash-derived, zero-storage value,
 * reusing exactly the trick {@link IdentityDeriver} already uses for names.
 * Nothing is written to disk -- {@link #dayOfYear} recomputes the same
 * answer forever from the villager's UUID.
 *
 * <p>Leap day is intentionally never assigned, so there is no once-every-four-
 * years edge case to handle.
 */
public final class Birthday {

    private static final long TICKS_PER_DAY = 24000L;

    private Birthday() {}

    /** Returns a deterministic day-of-year in {@code [1, 365]} for the given UUID. */
    public static int dayOfYear(UUID villagerId) {
        long seed = IdentityDeriver.mix(villagerId.getMostSignificantBits())
                ^ IdentityDeriver.mix(villagerId.getLeastSignificantBits() * 0x9E3779B97F4A7C15L);
        return (int) Long.remainderUnsigned(IdentityDeriver.mix(seed), 365L) + 1;
    }

    /** Whether today (in this world) is the villager's derived birthday. */
    public static boolean isToday(UUID villagerId, ServerLevel level) {
        return dayOfYear(villagerId) == currentDayOfYear(level);
    }

    /** The current day-of-year in this world, in {@code [1, 365]}. */
    public static int currentDayOfYear(ServerLevel level) {
        long day = Math.floorDiv(level.getOverworldClockTime(), TICKS_PER_DAY);
        return (int) Math.floorMod(day, 365L) + 1;
    }
}
