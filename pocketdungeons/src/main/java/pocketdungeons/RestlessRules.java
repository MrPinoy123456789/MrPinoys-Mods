package pocketdungeons;

import java.util.Set;

/**
 * Restless (design pass 2026-10-09, Q8; PD-188), the affix that replaced Feral in the rolls: a slain undead
 * mob may rise again where it fell, once. Fire keeps it down. Pure, so {@code RestlessRulesTest} runs with
 * plain {@code javac}.
 */
final class RestlessRules {

    private RestlessRules() {}

    /** The undead that can rise: the walking dead and the skeletons, not the bosses. */
    static final Set<String> UNDEAD = Set.of("minecraft:zombie", "minecraft:husk", "minecraft:drowned",
            "minecraft:zombie_villager", "minecraft:skeleton", "minecraft:stray", "minecraft:bogged",
            "minecraft:wither_skeleton", "minecraft:zombified_piglin");

    /** Ticks between a death and the rise: long enough to see it coming. */
    static final int RISE_DELAY_TICKS = 40;

    static boolean isUndead(String entityTypeId) {
        return UNDEAD.contains(entityTypeId);
    }

    /**
     * Whether a death raises the mob: it is undead, has not risen already, did not die to fire, and
     * {@code roll} (a draw in {@code [0, 1)}) falls under {@code chance}.
     */
    static boolean shouldRise(String entityTypeId, boolean alreadyRisen, boolean diedToFire, double chance,
                              double roll) {
        return isUndead(entityTypeId) && !alreadyRisen && !diedToFire && roll < chance;
    }
}
