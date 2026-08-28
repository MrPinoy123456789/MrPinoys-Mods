package pocketdungeons;

/**
 * Keystone level bands, depletion arithmetic and the run clock. No Minecraft
 * imports -- same discipline as {@link DoorMask}, {@link DifficultyProfile} and
 * {@link PayoutMath}, and for the same reason: the clamps are the part that is
 * easy to get subtly wrong and trivial to test with plain {@code javac}.
 *
 * <p>The whole of U7's failure handling collapses to {@link #deplete}: the
 * keystone was spent at the font, so the mod always hands one back. There is no
 * outcome in which a player who owned a key ends up with nothing, and no outcome
 * in which a key reaches zero. A bad night costs levels, never progress.
 */
final class KeystoneMath {

    private KeystoneMath() {}

    /** Every mint runs through this, so no arithmetic elsewhere has to know the bounds. */
    static int clampLevel(int level, int maxLevel) {
        int cap = Math.max(1, maxLevel);
        return Math.max(1, Math.min(cap, level));
    }

    /**
     * The level a keystone comes back at after a failure.
     *
     * <p>The floor is 1, always: a keystone never disappears and never
     * goes to zero.
     *
     * <p>{@code multiplier} arrives from {@link AffixMath#depletionMultiplier} --
     * the {@code max} across the run's whole affix set, never a product -- and is
     * clamped to {@code [1, 2]} here as well, so no caller can double a key twice
     * over by passing a figure the set never produced.
     */
    static int deplete(int level, int amount, int multiplier, int maxLevel) {
        int cost = Math.max(0, amount) * Math.max(1, Math.min(2, multiplier));
        return clampLevel(level - cost, maxLevel);
    }

    /** The level an accepted offer mints at. */
    static int upgrade(int level, int step, int maxLevel) {
        return clampLevel(level + Math.max(0, step), maxLevel);
    }

    /**
     * Loot tier from keystone level: 1-4 is tier 1, 5-9 tier 2, 10 and up tier 3.
     *
     * <p>This is what U7 re-keys off path length. The split is deliberate --
     * the planner decides how <em>big</em> a dungeon is, the keystone decides how
     * <em>hard</em> it is -- so a tuning complaint maps to one of the two without
     * ambiguity, and {@code pathLength} keeps exactly one job: sizing the clock.
     */
    static int lootTier(int level) {
        if (level <= 4) {
            return 1;
        }
        return level <= 9 ? 2 : 3;
    }

    /**
     * How long the run gets, in seconds.
     *
     * <p>Derived from the dungeon's <em>size</em>, not the key's level. That is
     * the Mythic+ shape: a higher key does not shorten the clock, it stiffens what
     * stands between you and the end of it.
     */
    static int timerSeconds(int baseSeconds, int perRoomSeconds, int pathLength) {
        long total = (long) Math.max(0, baseSeconds)
                + (long) Math.max(0, perRoomSeconds) * Math.max(0, pathLength);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** {@code m:ss}, for the boss bar title. Clamped at zero -- over time reads {@code 0:00}. */
    static String formatClock(int secondsRemaining) {
        int seconds = Math.max(0, secondsRemaining);
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }
}
