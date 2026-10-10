package pocketdungeons;

import net.minecraft.world.level.Level;

/**
 * Where the dungeon dimension's own world rules apply (trial spawners ignore natural spawn rules, fire does not
 * spread). One predicate so the mixins agree, and so a game test, which has no dungeon dimension, can switch the
 * rules on in the level it has.
 */
public final class DungeonWorldRules {

    /** Test hook: treat every level as the dungeon dimension. Never set outside a test. */
    public static volatile boolean everywhere;

    private DungeonWorldRules() {}

    public static boolean applies(Level level) {
        return everywhere || level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL);
    }
}
