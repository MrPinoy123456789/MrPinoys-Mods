package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Payoff rooms (plan 2026-10-17): dead-end side rooms that cash in a capability tag found earlier on the same
 * floor. The selector favours them when the tag really is available upstream
 * ({@link RoomSelector#PAYOFF_WEIGHT_FACTOR}), and the main path never requires them, so a shut bonus room is a
 * sign, not a dead end.
 *
 * <p>Every room is authored at rotation 0 with NORTH as its single door, like the spur family. Owner rules: no
 * hopper mechanisms (the hopper interface was the worst room of a session, PD-186), no dialog screens, and no
 * blocks that drop gold as decoration.
 */
final class PayoffSpecs {

    private static final Direction DOOR = Direction.NORTH;

    private PayoffSpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(gildedVault());
        specs.add(masonsShortcut());
        specs.add(windlift());
        return specs;
    }

    /**
     * The payoff rooms' situation handlers. Each room is pure template, so a handler only owns the cell, which
     * keeps the corridor role dispatch from stripping the cache; the Gilded Vault also arms its vault.
     */
    static void registerHandlers() {
        Situations.register("gilded_vault", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> {
            // Always plain: the omen relief life is the Barred Vault's alone.
            SpurVault.apply(level, o, false, SpurVault.GILDED_KEY, SpurVault.GILDED_LOOT_TABLE);
            return null;
        });
        // Pure template rooms: the handler only owns the cell, so the corridor pass leaves the cache alone.
        Situations.register("masons_shortcut", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> null);
        Situations.register("windlift", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> null);
    }

    // ---- helpers ---------------------------------------------------------------------

    /**
     * Places a chest facing {@code facing} with its loot table set. A chest placed in decor, rather than listed
     * on the spec, is not part of the library's walk-to check: the caches of Mason's Shortcut and Windlift are
     * meant to be out of reach on foot.
     */
    static void placeChest(ServerLevel level, BlockPos o, int x, int y, int z, Direction facing, String tablePath) {
        RoomDsl.put(level, o, x, y, z, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
        BlockEntity be = level.getBlockEntity(o.offset(x, y, z));
        if (be instanceof RandomizableContainer container) {
            container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,
                    Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, tablePath)));
        }
    }

    /** The table both side caches pay (smaller than the Gilded Vault). */
    static final String CACHE_TABLE = "vaults/spur_payoff";

    // ---- 1. Gilded Vault (gold) ---------------------------------------------------------

    /**
     * A vanilla vault on a dais behind iron bars, dressed with the theme accent and lit candles. It takes one gold
     * ingot and pays a better reward; each party member opens it once with their own ingot. No spawner and no
     * spec chest. {@code requires: ["gold"]} on the manifest makes it a spur room: a shut bonus when nobody
     * found gold upstream.
     */
    private static RoomSpec gildedVault() {
        return new RoomSpec("gilded_vault", EnumSet.of(DOOR))
                .decor((level, o) -> {
                    for (int x = 7; x <= 9; x++) {
                        RoomDsl.put(level, o, x, 1, 11, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                    }
                    RoomDsl.put(level, o, 8, 1, 10,
                            Blocks.VAULT.defaultBlockState().setValue(VaultBlock.FACING, Direction.NORTH));
                    for (int y = 1; y <= 2; y++) {
                        RoomDsl.put(level, o, 7, y, 10, Blocks.IRON_BARS.defaultBlockState());
                        RoomDsl.put(level, o, 9, y, 10, Blocks.IRON_BARS.defaultBlockState());
                    }
                    // The accent trim either side of the dais, and candles on its corners.
                    RoomDsl.box(level, o, 5, 1, 12, 6, 1, 12, RoomDsl.ACCENT);
                    RoomDsl.box(level, o, 10, 1, 12, 11, 1, 12, RoomDsl.ACCENT);
                    RoomDsl.put(level, o, 7, 2, 11, RoomDsl.candle(3));
                    RoomDsl.put(level, o, 9, 2, 11, RoomDsl.candle(3));
                });
    }

    // ---- 2. Mason's Shortcut (blocks) -----------------------------------------------------

    /**
     * A cache the player can see but only reach by placing blocks. A raised walkway (top surface y 4) is reached
     * by a stair of full blocks from the west; across a gap of five blocks stands a far platform (top y 4) with
     * the chest. The gap floor is ordinary floor at y 1 with a side passage past the walkway on both sides, and a
     * fall from the walkway is three blocks, so it never hurts. Pillaring three blocks next to the platform also
     * works, which is intended: it is still spending blocks. The chest is placed in decor so the library's
     * walk-to check does not require it to be reachable on foot.
     */
    private static RoomSpec masonsShortcut() {
        return new RoomSpec("masons_shortcut", EnumSet.of(DOOR))
                .decor((level, o) -> {
                    // The stair: full blocks, one block rise per step (x 3, 4, then the walkway from x 5).
                    RoomDsl.box(level, o, 3, 1, 4, 3, 1, 6, RoomDsl.WALL);
                    RoomDsl.box(level, o, 4, 1, 4, 4, 2, 6, RoomDsl.WALL);
                    // The walkway, top surface y 4, five blocks short of the far platform.
                    RoomDsl.box(level, o, 5, 1, 4, 10, 3, 6, RoomDsl.WALL);
                    RoomDsl.box(level, o, 5, 3, 4, 10, 3, 4, RoomDsl.ACCENT);
                    // The far platform at the back wall, top surface y 4, the chest on it.
                    RoomDsl.box(level, o, 5, 1, 12, 10, 3, 14, RoomDsl.WALL);
                    RoomDsl.box(level, o, 5, 3, 12, 10, 3, 12, RoomDsl.ACCENT);
                    placeChest(level, o, 8, 4, 13, Direction.NORTH, CACHE_TABLE);
                    RoomDsl.put(level, o, 3, 5, 3, RoomDsl.LAMP);
                    RoomDsl.put(level, o, 12, 5, 3, RoomDsl.LAMP);
                    RoomDsl.put(level, o, 3, 5, 10, RoomDsl.LAMP);
                    RoomDsl.put(level, o, 12, 5, 10, RoomDsl.LAMP);
                });
    }

    // ---- 3. Windlift (wind charge) ---------------------------------------------------------

    /**
     * A cache on a high ledge that a wind charge jump reaches. The ledge fills y 1 to 3 at the back (top surface
     * y 4) with two blocks of headroom above it; one distinct block on the floor at its foot marks the launch
     * spot, in tuff so the room reads as a trial chamber, where wind charges come from. No signs. Dropping off
     * the ledge is a three block fall and nothing in the room needs the charge to get back; pillaring three
     * blocks also reaches it, which is intended. The chest is placed in decor for the same reason as Mason's.
     */
    private static RoomSpec windlift() {
        return new RoomSpec("windlift", EnumSet.of(DOOR))
                .decor((level, o) -> {
                    RoomDsl.box(level, o, 5, 1, 12, 10, 3, 14, Blocks.TUFF_BRICKS.defaultBlockState());
                    RoomDsl.box(level, o, 5, 3, 12, 10, 3, 12, Blocks.POLISHED_TUFF.defaultBlockState());
                    placeChest(level, o, 8, 4, 13, Direction.NORTH, CACHE_TABLE);
                    // The launch spot: one chiseled tuff in the floor row at the foot of the ledge, tuff brick trim.
                    RoomDsl.put(level, o, 8, 0, 11, Blocks.CHISELED_TUFF.defaultBlockState());
                    RoomDsl.put(level, o, 7, 0, 11, Blocks.TUFF_BRICKS.defaultBlockState());
                    RoomDsl.put(level, o, 9, 0, 11, Blocks.TUFF_BRICKS.defaultBlockState());
                    RoomDsl.put(level, o, 8, 0, 10, Blocks.TUFF_BRICKS.defaultBlockState());
                    RoomDsl.put(level, o, 3, 5, 3, RoomDsl.LAMP);
                    RoomDsl.put(level, o, 12, 5, 3, RoomDsl.LAMP);
                    RoomDsl.put(level, o, 3, 5, 10, RoomDsl.LAMP);
                    RoomDsl.put(level, o, 12, 5, 10, RoomDsl.LAMP);
                });
    }
}
