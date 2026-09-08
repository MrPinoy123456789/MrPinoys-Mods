package pocketdungeons;

import java.util.Set;

/**
 * M27 27.1: the operator-set offer that stands in for door 3 while it is
 * active. Not persisted (the handoff calls for a runtime field, not a save):
 * a restart clears whatever an operator was testing, the same as every other
 * in-memory instance state this mod keeps.
 *
 * <p>No per-player daily reward tracking rides on this yet; that arrives once
 * the feature graduates out of testing.
 */
final class ExperimentalDungeon {

    private ExperimentalDungeon() {}

    /**
     * @param theme     the theme id door 3 offers while this is active.
     * @param affixes   the elective affixes door 3 grants.
     * @param lootLevel the keystone level {@link DifficultyProfile} uses to pick
     *                  loot and mob difficulty for this run; {@code null} keeps
     *                  door 3's normal upgraded level.
     */
    record Offer(String theme, Set<String> affixes, Integer lootLevel) {}

    private static volatile Offer active;

    static Offer current() {
        return active;
    }

    static void set(String theme, Set<String> affixes, Integer lootLevel) {
        active = new Offer(theme, affixes, lootLevel);
    }

    static void clear() {
        active = null;
    }
}
