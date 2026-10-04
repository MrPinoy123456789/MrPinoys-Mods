package pocketdungeons;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * M45 (SITUATIONS_SPEC 6.1): the closed tool-tag vocabulary that a
 * {@code dungeon_room}'s {@code provides} and {@code requires} arrays draw
 * from.
 *
 * <p>Tags, not item ids. {@code water} is satisfied by a water bucket or by a
 * bag carrying one; {@code mob} is satisfied by any upstream room that spawns
 * a leashable mob, or by a party of two or more. What satisfies a tag is the
 * selector's business (spec 6.2 and 6.6); this class only says which spellings
 * exist.
 *
 * <p><strong>Closed on purpose.</strong> An open vocabulary cannot be tested:
 * a typo in a datapack would silently make a room unselectable forever, and
 * nothing would ever say so. {@link #validate} turns that into a load-time
 * rejection with the room and the offending tag named, which
 * {@code /dungeon admin manifest list} reads back.
 */
final class SituationTags {

    /** Placeable blocks: a stack the player can bridge, pillar or plug with. */
    static final String BLOCKS = "blocks";

    /** A water source, from a bucket or a bag. */
    static final String WATER = "water";

    /** A lava source. */
    static final String LAVA = "lava";

    /** A lead, for moving a leashable mob. */
    static final String LEAD = "lead";

    /** A leashable mob, from an upstream room or from a second party member. */
    static final String MOB = "mob";

    /** A trial key, for a vault. */
    static final String TRIAL_KEY = "trial_key";

    /** A boat. */
    static final String BOAT = "boat";

    /** Gold, as currency or as a piglin bribe. */
    static final String GOLD = "gold";

    /** Snowballs, for a button or a pressure plate out of reach. */
    static final String SNOWBALLS = "snowballs";

    /** Shears. */
    static final String SHEARS = "shears";

    /** An ender pearl. */
    static final String PEARL = "pearl";

    /** A wind charge. */
    static final String WIND_CHARGE = "wind_charge";

    /** Milk, for clearing an effect. */
    static final String MILK = "milk";

    /** A bow, for a target out of throwing range. */
    static final String BOW = "bow";

    /** Redstone components: a signal a door or a piston needs. */
    static final String REDSTONE = "redstone";

    /**
     * A blast: TNT the party carries, or creepers a room upstream spawns. It
     * clears a rubble doorway ({@link RubbleOrdeal}); the planner only seals a
     * door with rubble when this is reachable before it.
     */
    static final String EXPLOSIVE = "explosive";

    /**
     * Every tag above, in declaration order. Iteration order is stable so the
     * rejection message reads the same on every run.
     */
    static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            BLOCKS, WATER, LAVA, LEAD, MOB, TRIAL_KEY, BOAT, GOLD, SNOWBALLS,
            SHEARS, PEARL, WIND_CHARGE, MILK, BOW, REDSTONE, EXPLOSIVE)));

    private SituationTags() {}

    /** Whether {@code tag} is one of the sixteen. Null and blank are not. */
    static boolean isKnown(String tag) {
        return tag != null && ALL.contains(tag);
    }

    /**
     * Rejects a room whose tag list carries a spelling this vocabulary does not
     * have.
     *
     * @param roomName the room the tags came from, for the message
     * @param tags     a {@code provides} or {@code requires} list; null or empty is fine
     * @throws IllegalArgumentException naming the room and the first bad tag
     */
    static void validate(String roomName, List<String> tags) {
        if (tags == null) {
            return;
        }
        for (String tag : tags) {
            if (!isKnown(tag)) {
                throw new IllegalArgumentException("room " + roomName + ": unknown situation tag \""
                        + tag + "\"; known tags are " + sorted());
            }
        }
    }

    /** The vocabulary, alphabetically, for a message a datapack author reads. */
    private static String sorted() {
        return String.join(", ", ALL.stream().sorted().toList());
    }
}
