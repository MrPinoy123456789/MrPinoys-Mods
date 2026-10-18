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
}
