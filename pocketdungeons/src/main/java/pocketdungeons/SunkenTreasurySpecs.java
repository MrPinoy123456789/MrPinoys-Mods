package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomDsl.AIR;
import static pocketdungeons.RoomDsl.box;
import static pocketdungeons.RoomDsl.column;
import static pocketdungeons.RoomDsl.put;

/**
 * Sunken Treasury rooms: a strongroom encounter, a galleon hold dead end and the
 * admiral's grand vault for the final floor. Authored at rotation 0 in room-local
 * coordinates; the west and east door lanes (z 7 to 8 for x 1 to 3 and 12 to 14)
 * are kept clear. The dais centre of the strongroom is carved out so the manifest
 * can place its gold ore node there.
 */
final class SunkenTreasurySpecs {

    private SunkenTreasurySpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(treasuryStrongroom());
        specs.add(galleonHold());
        specs.add(admiralsVault());
        return specs;
    }

    /**
     * Treasury Strongroom (straight, west east): a raised dais with gold corner
     * posts holds the strongroom's prize in the middle of the room, alcove
     * pilasters and stacked strongboxes line the side walls. The dais centre is
     * a carved pocket the manifest fills with gold ore.
     */
    private static RoomSpec treasuryStrongroom() {
        return new RoomSpec("treasury_strongroom", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(12, 1, 14))
                .spawns(new BlockPos(3, 1, 5), new BlockPos(12, 1, 5), new BlockPos(3, 1, 10),
                        new BlockPos(12, 1, 10))
                .decor((level, o) -> {
                    BlockState bricks = Blocks.PRISMARINE_BRICKS.defaultBlockState();
                    BlockState dark = Blocks.DARK_PRISMARINE.defaultBlockState();
                    BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
                    BlockState barrel = Blocks.BARREL.defaultBlockState();
                    BlockState mossy = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
                    BlockState lamp = Blocks.SEA_LANTERN.defaultBlockState();
                    // The dais: a raised 3x3 platform with gold posts at its corners.
                    box(level, o, 7, 1, 7, 9, 1, 9, bricks);
                    put(level, o, 8, 1, 8, AIR);
                    put(level, o, 7, 2, 7, gold);
                    put(level, o, 9, 2, 7, gold);
                    put(level, o, 7, 2, 9, gold);
                    put(level, o, 9, 2, 9, gold);
                    // Alcove pilasters along both side walls, strongboxes in the bays.
                    for (int z : new int[]{1, 14}) {
                        for (int x : new int[]{1, 4, 11, 14}) {
                            column(level, o, x, 1, 3, z, dark);
                        }
                    }
                    put(level, o, 2, 1, 1, barrel);
                    put(level, o, 3, 1, 1, barrel);
                    put(level, o, 2, 2, 1, barrel);
                    put(level, o, 12, 1, 1, barrel);
                    put(level, o, 13, 1, 1, barrel);
                    put(level, o, 13, 2, 1, barrel);
                    put(level, o, 2, 1, 14, barrel);
                    put(level, o, 3, 1, 14, barrel);
                    put(level, o, 13, 1, 14, barrel);
                    // Salt-stained floor patches and four lamps over the floor.
                    put(level, o, 2, 0, 6, mossy);
                    put(level, o, 5, 0, 12, mossy);
                    put(level, o, 10, 0, 3, mossy);
                    put(level, o, 13, 0, 9, mossy);
                    put(level, o, 5, 5, 5, lamp);
                    put(level, o, 10, 5, 5, lamp);
                    put(level, o, 5, 5, 10, lamp);
                    put(level, o, 10, 5, 10, lamp);
                });
    }

    /**
     * Galleon Hold (dead end, west door): the cargo hold of a sunken galleon.
     * Hull ribs climb both long walls, deck beams run overhead, and the last of
     * the cargo sits stacked between them.
     */
    private static RoomSpec galleonHold() {
        return new RoomSpec("galleon_hold", EnumSet.of(Direction.WEST))
                .chests(new BlockPos(13, 1, 2), new BlockPos(2, 1, 12))
                .spawns(new BlockPos(4, 1, 5), new BlockPos(10, 1, 5), new BlockPos(5, 1, 11),
                        new BlockPos(11, 1, 8))
                .decor((level, o) -> {
                    BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
                    BlockState logX = log.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
                    BlockState planks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
                    BlockState barrel = Blocks.BARREL.defaultBlockState();
                    BlockState prismarine = Blocks.PRISMARINE.defaultBlockState();
                    BlockState lamp = Blocks.SEA_LANTERN.defaultBlockState();
                    // Hull ribs up both long walls and two deck beams overhead.
                    for (int z : new int[]{1, 14}) {
                        for (int x : new int[]{2, 5, 9, 12}) {
                            column(level, o, x, 1, 4, z, log);
                        }
                    }
                    box(level, o, 1, 5, 3, 14, 5, 3, logX);
                    box(level, o, 1, 5, 12, 14, 5, 12, logX);
                    // Ribs on the stern wall, where the hold narrows.
                    column(level, o, 14, 1, 4, 5, log);
                    column(level, o, 14, 1, 4, 11, log);
                    // Cargo: two barrel piles and loose crates.
                    box(level, o, 3, 1, 3, 4, 1, 4, barrel);
                    put(level, o, 3, 2, 3, barrel);
                    box(level, o, 11, 1, 10, 12, 1, 11, barrel);
                    put(level, o, 12, 2, 11, barrel);
                    put(level, o, 5, 1, 4, planks);
                    put(level, o, 10, 1, 3, planks);
                    put(level, o, 4, 1, 9, planks);
                    put(level, o, 9, 1, 12, planks);
                    // Barnacled floor and two lamps between the beams.
                    put(level, o, 2, 0, 8, prismarine);
                    put(level, o, 7, 0, 2, prismarine);
                    put(level, o, 12, 0, 6, prismarine);
                    put(level, o, 6, 0, 13, prismarine);
                    put(level, o, 6, 5, 7, lamp);
                    put(level, o, 9, 5, 7, lamp);
                });
    }

    /**
     * Admiral's Vault (straight, west east): the final floor's grand vault. A two
     * tier dais crowned with gold rises in the middle and the great vault door
     * fills the north wall, its wheel glowing. Ore is studded into the south
     * wall through manifest nodes.
     */
    private static RoomSpec admiralsVault() {
        return new RoomSpec("admirals_vault", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 13))
                .spawns(new BlockPos(3, 1, 4), new BlockPos(12, 1, 4), new BlockPos(3, 1, 11),
                        new BlockPos(12, 1, 11))
                .decor((level, o) -> {
                    BlockState dark = Blocks.DARK_PRISMARINE.defaultBlockState();
                    BlockState bricks = Blocks.PRISMARINE_BRICKS.defaultBlockState();
                    BlockState planks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
                    BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
                    BlockState barrel = Blocks.BARREL.defaultBlockState();
                    BlockState lamp = Blocks.SEA_LANTERN.defaultBlockState();
                    // The grand dais: a dark lower tier, a brick upper tier with gold
                    // corners, and a gold crown on top.
                    box(level, o, 5, 1, 5, 10, 1, 10, dark);
                    box(level, o, 6, 2, 6, 9, 2, 9, bricks);
                    put(level, o, 6, 2, 6, gold);
                    put(level, o, 9, 2, 6, gold);
                    put(level, o, 6, 2, 9, gold);
                    put(level, o, 9, 2, 9, gold);
                    box(level, o, 7, 3, 7, 8, 3, 8, gold);
                    // The great vault door: a brick frame, oak leaves, a glowing wheel.
                    box(level, o, 5, 1, 1, 10, 4, 1, bricks);
                    box(level, o, 6, 1, 1, 9, 3, 1, planks);
                    put(level, o, 7, 2, 1, lamp);
                    put(level, o, 8, 2, 1, lamp);
                    // Pilasters flanking the doorways and spilled strongboxes.
                    for (int x : new int[]{1, 14}) {
                        column(level, o, x, 1, 4, 4, dark);
                        column(level, o, x, 1, 4, 11, dark);
                    }
                    put(level, o, 2, 1, 1, barrel);
                    put(level, o, 3, 1, 1, barrel);
                    put(level, o, 12, 1, 1, barrel);
                    put(level, o, 13, 1, 1, barrel);
                    // Lamps over the dais.
                    put(level, o, 5, 5, 5, lamp);
                    put(level, o, 10, 5, 5, lamp);
                    put(level, o, 5, 5, 10, lamp);
                    put(level, o, 10, 5, 10, lamp);
                });
    }
}
