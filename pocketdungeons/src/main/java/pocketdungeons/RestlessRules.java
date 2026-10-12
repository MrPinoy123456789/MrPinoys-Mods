package pocketdungeons;

import java.util.Map;
import java.util.Set;

/**
 * Restless (design pass 2026-10-09, Q8; PD-188; widened by affix workshop 2026-10-11 row 5): a slain mob
 * may rise again where it fell, once. Fire keeps it down. Pure, so {@code RestlessRulesTest} runs with plain
 * {@code javac}.
 *
 * <p>Every hostile mob can rise, not only the undead. What stands up is decided by data held here as plain
 * maps: an undead mob rises as itself; any other mob rises as the floor theme's stand-in
 * ({@link #THEME_RISE}) or, on a theme without one, as the stand-in for its kind ({@link #KIND_RISE}), or
 * as a zombie. Every risen type is a vanilla mob that spawns server-side with no client mod. Bosses and
 * other mobs that never rise are in {@link #NEVER_RISE}; the callers also keep out any mob carrying a boss,
 * finale or wave tag (see {@link Restless}).
 */
final class RestlessRules {

    private RestlessRules() {}

    /** The undead that rise as themselves: the walking dead and the skeletons, not the bosses. */
    static final Set<String> UNDEAD = Set.of("minecraft:zombie", "minecraft:husk", "minecraft:drowned",
            "minecraft:zombie_villager", "minecraft:skeleton", "minecraft:stray", "minecraft:bogged",
            "minecraft:wither_skeleton", "minecraft:zombified_piglin");

    /** Mobs that never rise: bosses and the peaceful, which are not a floor's mobs to raise. */
    static final Set<String> NEVER_RISE = Set.of("minecraft:wither", "minecraft:ender_dragon", "minecraft:warden",
            "minecraft:elder_guardian", "minecraft:giant", "minecraft:villager", "minecraft:wandering_trader",
            "minecraft:iron_golem", "minecraft:snow_golem", "minecraft:armor_stand", "minecraft:player");

    /**
     * A living mob rises as this on a floor of the theme (the theme id without its namespace). A theme not
     * listed here falls through to {@link #KIND_RISE}.
     */
    static final Map<String, String> THEME_RISE = Map.of(
            "ossuary", "minecraft:skeleton",
            "frostworks", "minecraft:stray",
            "drowned_vault", "minecraft:drowned",
            "prismarine", "minecraft:drowned",
            "basalt_foundry", "minecraft:wither_skeleton",
            "wither_keep", "minecraft:wither_skeleton",
            "blackstone", "minecraft:zombified_piglin",
            "infestation", "minecraft:silverfish",
            "rootworks", "minecraft:bogged",
            "ender_archive", "minecraft:endermite");

    /** The stand-in for a kind of living mob on a theme with no stand-in of its own. */
    static final Map<String, String> KIND_RISE = Map.ofEntries(
            Map.entry("minecraft:wolf", "minecraft:skeleton"),
            Map.entry("minecraft:spider", "minecraft:cave_spider"),
            Map.entry("minecraft:cave_spider", "minecraft:cave_spider"),
            Map.entry("minecraft:silverfish", "minecraft:silverfish"),
            Map.entry("minecraft:endermite", "minecraft:endermite"),
            Map.entry("minecraft:enderman", "minecraft:endermite"),
            Map.entry("minecraft:blaze", "minecraft:skeleton"),
            Map.entry("minecraft:breeze", "minecraft:skeleton"),
            Map.entry("minecraft:phantom", "minecraft:skeleton"),
            Map.entry("minecraft:vex", "minecraft:skeleton"),
            Map.entry("minecraft:ghast", "minecraft:skeleton"),
            Map.entry("minecraft:piglin", "minecraft:zombified_piglin"),
            Map.entry("minecraft:piglin_brute", "minecraft:zombified_piglin"),
            Map.entry("minecraft:hoglin", "minecraft:zombified_piglin"),
            Map.entry("minecraft:zoglin", "minecraft:zombified_piglin"),
            Map.entry("minecraft:guardian", "minecraft:drowned"));

    /** What a living mob with no other entry rises as. */
    static final String DEFAULT_RISE = "minecraft:zombie";

    /** Ticks between a death and the rise: long enough to see it coming. */
    static final int RISE_DELAY_TICKS = 40;

    static boolean isUndead(String entityTypeId) {
        return UNDEAD.contains(entityTypeId);
    }

    /**
     * The mob type that rises where {@code entityTypeId} fell on a floor of {@code theme} (null or any
     * namespace is fine), or {@code null} when that mob never rises.
     */
    static String riseType(String theme, String entityTypeId) {
        if (entityTypeId == null || NEVER_RISE.contains(entityTypeId)) {
            return null;
        }
        if (isUndead(entityTypeId)) {
            return entityTypeId;
        }
        String themeKey = theme == null ? "" : theme.substring(theme.indexOf(':') + 1);
        String themed = THEME_RISE.get(themeKey);
        if (themed != null) {
            return themed;
        }
        return KIND_RISE.getOrDefault(entityTypeId, DEFAULT_RISE);
    }

    /**
     * Whether a death raises the mob: it is a mob that can rise, has not risen already, is not exempt (a
     * boss, a finale or wave mob, a pet), did not die to fire, and {@code roll} (a draw in {@code [0, 1)})
     * falls under {@code chance}.
     */
    static boolean shouldRise(String theme, String entityTypeId, boolean alreadyRisen, boolean exempt,
                              boolean diedToFire, double chance, double roll) {
        return riseType(theme, entityTypeId) != null && !alreadyRisen && !exempt && !diedToFire && roll < chance;
    }

    /** {@link #shouldRise(String, String, boolean, boolean, boolean, double, double)} for a plain, unthemed mob. */
    static boolean shouldRise(String entityTypeId, boolean alreadyRisen, boolean diedToFire, double chance,
                              double roll) {
        return shouldRise(null, entityTypeId, alreadyRisen, false, diedToFire, chance, roll);
    }
}
