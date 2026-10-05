package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomTemplateGenerator.HORIZONTALS;

/**
 * Dungeon structure W7a: the rooms of the Act 1 and Act 2 capstone dungeons.
 *
 * <ul>
 *   <li>{@code brood_chamber}: the Spawner Dungeon's boss room and terminal cell. Four classic
 *       spawners on mossy plinths round the exit pad; {@code CapstoneFights} gives each its mob
 *       and gates the pad on them.</li>
 *   <li>{@code sculk_causeway}, {@code sculk_nave}: Ancient City halls strewn with sculk sensors
 *       and shriekers. Every one of them raises omen on that dungeon.</li>
 *   <li>{@code warden_hall}: the Ancient City's boss room and terminal cell. Sensors and
 *       shriekers ring the exit pad; reaching the pad quietly is the whole fight.</li>
 * </ul>
 *
 * <p>Every position here is chosen against three constraints, so the room survives all four
 * rotations: the doorway lanes (x or z 7 to 8 within three blocks of a wall) stay clear, the 2x2
 * pad at 7 to 8 stays clear, and nothing stands where {@code TrialContent.placeCompletionChests}
 * or the vault chests land on a terminal cell (the nine points whose coordinates are 4, 8 or 12,
 * and the blocks beside and above them). Sculk shriekers are placed with {@code can_summon}
 * false, so vanilla never raises a Warden from them; the only Warden is {@code CapstoneFights}'.
 */
final class CapstoneSpecs {

    private CapstoneSpecs() {}

    /** The brood spawners, in cell coordinates at rotation 0. All clear of lanes, pad and chest spots. */
    static final BlockPos[] BROOD_SPAWNERS = {
            new BlockPos(5, 1, 5), new BlockPos(10, 1, 5), new BlockPos(6, 1, 10), new BlockPos(10, 1, 10)
    };

    private static final BlockState MOSSY = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    private static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState WEB = Blocks.COBWEB.defaultBlockState();
    private static final BlockState SCULK = Blocks.SCULK.defaultBlockState();
    private static final BlockState SENSOR = Blocks.SCULK_SENSOR.defaultBlockState();
    private static final BlockState SHRIEKER = Blocks.SCULK_SHRIEKER.defaultBlockState();
    private static final BlockState CATALYST = Blocks.SCULK_CATALYST.defaultBlockState();
    private static final BlockState TILES = Blocks.DEEPSLATE_TILES.defaultBlockState();
    private static final BlockState POLISHED = Blocks.POLISHED_DEEPSLATE.defaultBlockState();

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(broodChamber());
        specs.add(sculkCauseway());
        specs.add(sculkNave());
        specs.add(wardenHall());
        return specs;
    }

    // ---- Spawner Dungeon ---------------------------------------------------------------

    /** Boss room: four classic spawners round the pad on mossy plinths, cobwebs in the corners. West door. */
    private static RoomSpec broodChamber() {
        return new RoomSpec("brood_chamber", EnumSet.of(Direction.WEST))
                .exitPad()
                .decor((level, o) -> {
                    for (BlockPos spawner : BROOD_SPAWNERS) {
                        // A 3x3 mossy plinth with the cage block in the middle.
                        ResourceBiomeSpecs.fill(level, o, spawner.getX() - 1, 0, spawner.getZ() - 1,
                                spawner.getX() + 1, 0, spawner.getZ() + 1, MOSSY);
                        RoomBuilder.set(level, o.offset(spawner), Blocks.SPAWNER.defaultBlockState());
                        if (level.getBlockEntity(o.offset(spawner)) instanceof SpawnerBlockEntity block) {
                            block.setEntityId(EntityTypes.ZOMBIE, level.getRandom());
                        }
                    }
                    // Old cobble heaped in the four corners, webs above it.
                    for (int[] corner : new int[][]{{2, 2}, {13, 2}, {2, 13}, {13, 13}}) {
                        RoomBuilder.set(level, o.offset(corner[0], 1, corner[1]), COBBLE);
                        RoomBuilder.set(level, o.offset(corner[0], 4, corner[1]), WEB);
                    }
                });
    }

    // ---- Ancient City ------------------------------------------------------------------

    /** A straight sculk hall, west to east: sculk underfoot, sensors flanking the path, one shrieker. */
    private static RoomSpec sculkCauseway() {
        return new RoomSpec("sculk_causeway", EnumSet.of(Direction.WEST, Direction.EAST))
                .decor((level, o) -> {
                    for (int x = 4; x <= 11; x++) {
                        for (int z = 4; z <= 11; z++) {
                            if ((x * 7 + z * 3) % 4 != 0) {
                                RoomBuilder.set(level, o.offset(x, 0, z), SCULK);
                            }
                        }
                    }
                    for (int[] at : new int[][]{{5, 5}, {10, 5}, {5, 10}, {10, 10}}) {
                        RoomBuilder.set(level, o.offset(at[0], 1, at[1]), SENSOR);
                    }
                    RoomBuilder.set(level, o.offset(8, 1, 3), SHRIEKER);
                    pillar(level, o, 6, 6);
                    pillar(level, o, 9, 9);
                });
    }

    /** A four door sculk nave: a tiled dais, a catalyst at each end of the cross, sensors and two shriekers. */
    private static RoomSpec sculkNave() {
        return new RoomSpec("sculk_nave", HORIZONTALS)
                .decor((level, o) -> {
                    ResourceBiomeSpecs.fill(level, o, 5, 0, 5, 10, 0, 10, TILES);
                    for (int x = 2; x <= 13; x++) {
                        for (int z = 2; z <= 13; z++) {
                            boolean lane = (x == 7 || x == 8) || (z == 7 || z == 8);
                            if (!lane && (x * 5 + z * 11) % 3 == 0) {
                                RoomBuilder.set(level, o.offset(x, 0, z), SCULK);
                            }
                        }
                    }
                    RoomBuilder.set(level, o.offset(2, 1, 2), CATALYST);
                    RoomBuilder.set(level, o.offset(13, 1, 13), CATALYST);
                    for (int[] at : new int[][]{{5, 5}, {10, 10}, {5, 10}, {10, 5}}) {
                        RoomBuilder.set(level, o.offset(at[0], 1, at[1]), SENSOR);
                    }
                    RoomBuilder.set(level, o.offset(6, 1, 6), SHRIEKER);
                    RoomBuilder.set(level, o.offset(9, 1, 9), SHRIEKER);
                });
    }

    /**
     * Boss room: the pad on a tiled dais, sensors close round it, shriekers at the diagonals,
     * catalysts in the far corners. West door. Nothing here is a fight; every step is a question.
     */
    private static RoomSpec wardenHall() {
        return new RoomSpec("warden_hall", EnumSet.of(Direction.WEST))
                .exitPad()
                .decor((level, o) -> {
                    ResourceBiomeSpecs.fill(level, o, 4, 0, 4, 11, 0, 11, TILES);
                    for (int x = 2; x <= 13; x++) {
                        for (int z = 2; z <= 13; z++) {
                            boolean pad = x >= 6 && x <= 9 && z >= 6 && z <= 9;
                            if (!pad && (x * 3 + z * 7) % 5 == 0) {
                                RoomBuilder.set(level, o.offset(x, 0, z), SCULK);
                            }
                        }
                    }
                    // The generator lays the lodestone pad after this decor, over the dais.
                    for (int[] at : new int[][]{{6, 6}, {9, 9}, {6, 9}, {9, 6}}) {
                        RoomBuilder.set(level, o.offset(at[0], 1, at[1]), SENSOR);
                    }
                    for (int[] at : new int[][]{{5, 5}, {10, 10}, {5, 10}, {10, 5}}) {
                        RoomBuilder.set(level, o.offset(at[0], 1, at[1]), SHRIEKER);
                    }
                    RoomBuilder.set(level, o.offset(2, 1, 2), CATALYST);
                    RoomBuilder.set(level, o.offset(13, 1, 13), CATALYST);
                });
    }

    /** A four high polished pillar. */
    private static void pillar(ServerLevel level, BlockPos o, int x, int z) {
        for (int y = 1; y <= 4; y++) {
            RoomBuilder.set(level, o.offset(x, y, z), POLISHED);
        }
    }

    // ---- handlers ------------------------------------------------------------------------

    private static boolean handlersRegistered;

    /** Registers the brood chamber situation. Called once from {@link TrialContent#warmUp()}. */
    static void registerHandlers() {
        if (handlersRegistered) {
            return;
        }
        handlersRegistered = true;
        Situations.register("brood_chamber", (level, o, role, depth, profile, spawns, seed, affixes, lootSuffix,
                                             theme, voidedFloor, content) -> {
            CapstoneFights.configureBroodRoom(level, o);
            return null;
        });
    }
}
