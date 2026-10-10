package pocketdungeons;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Where the dungeon dimension's own world rules apply (trial spawners ignore natural spawn rules, fire does not
 * spread). One predicate so the mixins agree, and so a game test, which has no dungeon dimension, can switch the
 * rules on in the level it has.
 *
 * <p>{@link #inDungeon} is the one plain "is this the dungeon dimension" question, with no test hook; every
 * production site asks it instead of comparing against {@code PocketDungeonsMod.DUNGEON_LEVEL} itself
 * ({@code DimensionCheckTest} fails when a raw comparison appears elsewhere). {@link #applies} is the same
 * question plus the {@link #everywhere} hook, for the mixins whose game tests need the rules in an ordinary level.
 */
public final class DungeonWorldRules {

    /** Test hook: treat every level as the dungeon dimension. Never set outside a test. */
    public static volatile boolean everywhere;

    private DungeonWorldRules() {}

    /** True when the level is the dungeon dimension. No test hook. */
    public static boolean inDungeon(Level level) {
        return inDungeon(level.dimension());
    }

    /** True when the dimension key is the dungeon dimension. No test hook. */
    public static boolean inDungeon(ResourceKey<Level> dimension) {
        return dimension.equals(PocketDungeonsMod.DUNGEON_LEVEL);
    }

    public static boolean applies(Level level) {
        return everywhere || inDungeon(level);
    }
}
