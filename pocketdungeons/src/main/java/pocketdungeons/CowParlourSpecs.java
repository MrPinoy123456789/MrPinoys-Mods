package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Cow Pits Milking Parlour rooms (roadmap T30): the clinical dairy floor. {@code milking_stalls}
 * and {@code specimen_byre} keep numbered specimens in barred stalls and pay the parlour's finite
 * milk (the {@code milking_stalls} and {@code specimen_byre} content handlers stock the churns).
 * {@code zero_bay} is the Big Barn's containment bay: Specimen 0 on a pedestal behind bars, the
 * "do not feed" sign in front of it, attendants in the side cells.
 */
final class CowParlourSpecs {

    private CowParlourSpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(milkingStalls());
        specs.add(specimenByre());
        specs.add(zeroBay());
        return specs;
    }

    private static final BlockState SMOOTH = Blocks.SMOOTH_QUARTZ.defaultBlockState();
    private static final BlockState QUARTZ = Blocks.QUARTZ_BLOCK.defaultBlockState();
    private static final BlockState BRICKS = Blocks.QUARTZ_BRICKS.defaultBlockState();
    private static final BlockState BARS = Blocks.IRON_BARS.defaultBlockState();
    private static final BlockState GLOW = Blocks.GLOWSTONE.defaultBlockState();
    private static final BlockState HAY = Blocks.HAY_BLOCK.defaultBlockState();
    private static final BlockState WEB = Blocks.COBWEB.defaultBlockState();

    /**
     * A row of three stalls behind a barred front: quartz dividers at {@code x=5} and {@code x=10},
     * bars on {@code zFront}, one opening per stall so every spawn point inside is reachable.
     * {@code zBack} is the wall side of the stalls.
     */
    private static void stallRow(net.minecraft.server.level.ServerLevel level, BlockPos o,
                                 int zBack, int zFront) {
        for (int x : new int[]{5, 10}) {
            RoomDsl.box(level, o, x, 1, zBack, x, 3, zFront, QUARTZ);
        }
        RoomDsl.box(level, o, 1, 1, zFront, 14, 2, zFront, BARS);
        RoomDsl.box(level, o, 1, 3, zFront, 14, 3, zFront, QUARTZ);
        for (int gap : new int[]{3, 8, 12}) {
            RoomDsl.box(level, o, gap, 1, zFront, gap, 2, zFront, RoomDsl.AIR);
        }
    }

    /** The white tile floor and the strip lights every parlour room shares. */
    private static void parlourFloor(net.minecraft.server.level.ServerLevel level, BlockPos o) {
        RoomDsl.box(level, o, 1, 0, 1, 14, 0, 14, SMOOTH);
        RoomDsl.box(level, o, 1, 0, 7, 14, 0, 8, BRICKS);
        for (int x : new int[]{3, 7, 11}) {
            RoomDsl.box(level, o, x, 5, 7, x + 1, 5, 8, GLOW);
        }
    }

    /**
     * The Milking Parlour: two rows of milking stalls either side of the aisle, churns standing by.
     * Cows are spawned and named by the {@code milking_stalls} handler, the churns stocked.
     */
    private static RoomSpec milkingStalls() {
        return new RoomSpec("milking_stalls", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(new BlockPos(3, 1, 2), new BlockPos(8, 1, 2), new BlockPos(8, 1, 12), new BlockPos(12, 1, 12))
                .decor((level, o) -> {
                    parlourFloor(level, o);
                    stallRow(level, o, 1, 5);
                    stallRow(level, o, 14, 10);
                    // The churns the handler fills, and a rinsing cauldron at the aisle's end.
                    RoomDsl.put(level, o, 4, 1, 9, Blocks.BARREL.defaultBlockState());
                    RoomDsl.put(level, o, 11, 1, 9, Blocks.BARREL.defaultBlockState());
                    RoomDsl.put(level, o, 13, 1, 6, Blocks.CAULDRON.defaultBlockState());
                    // Bedding in the corner stalls, and where the place is coming apart.
                    RoomDsl.box(level, o, 1, 1, 1, 2, 1, 1, HAY);
                    RoomDsl.put(level, o, 13, 1, 14, HAY);
                    RoomDsl.put(level, o, 1, 4, 1, WEB);
                    RoomDsl.put(level, o, 14, 4, 14, WEB);
                    RoomDsl.put(level, o, 6, 4, 5, WEB);
                });
    }

    /**
     * The Specimen Byre: two holding pens behind bars, a water trough each, quieter than the stalls.
     * Cows are spawned and named by the {@code specimen_byre} handler.
     */
    private static RoomSpec specimenByre() {
        return new RoomSpec("specimen_byre", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(new BlockPos(5, 1, 2), new BlockPos(10, 1, 12))
                .decor((level, o) -> {
                    parlourFloor(level, o);
                    // North pen: barred front at z=5, one gate gap. South pen mirrors it at z=10.
                    RoomDsl.box(level, o, 1, 1, 5, 14, 3, 5, BARS);
                    RoomDsl.box(level, o, 1, 1, 10, 14, 3, 10, BARS);
                    RoomDsl.box(level, o, 5, 1, 5, 5, 3, 5, RoomDsl.AIR);
                    RoomDsl.box(level, o, 10, 1, 10, 10, 3, 10, RoomDsl.AIR);
                    // Corner posts and the pens' furniture.
                    for (int x : new int[]{1, 14}) {
                        for (int z : new int[]{5, 10}) {
                            RoomDsl.box(level, o, x, 1, z, x, 4, z, QUARTZ);
                        }
                    }
                    RoomDsl.put(level, o, 3, 1, 1, Blocks.CAULDRON.defaultBlockState());
                    RoomDsl.put(level, o, 12, 1, 14, Blocks.CAULDRON.defaultBlockState());
                    RoomDsl.box(level, o, 12, 1, 1, 14, 1, 2, HAY);
                    RoomDsl.put(level, o, 2, 1, 14, HAY);
                    // The churn the handler fills, out of the aisle lanes.
                    RoomDsl.put(level, o, 8, 1, 6, Blocks.BARREL.defaultBlockState());
                    RoomDsl.put(level, o, 1, 4, 4, WEB);
                    RoomDsl.put(level, o, 14, 4, 11, WEB);
                });
    }

    /** Local position of the bay's pedestal spawn point (the only spawn point at y 2). */
    static final BlockPos BAY_PEDESTAL_SPAWN = new BlockPos(7, 2, 2);

    /**
     * The Specimen Bay: the Big Barn's containment cell. A barred bay across the north side holds
     * the pedestal cow and two attendants; the sign stands in the aisle facing it. The south half
     * stays open ground, the fight needs it. Cows are spawned by the {@code zero_bay} handler.
     */
    private static RoomSpec zeroBay() {
        return new RoomSpec("zero_bay", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(new BlockPos(3, 1, 2), new BlockPos(12, 1, 2), BAY_PEDESTAL_SPAWN)
                .decor((level, o) -> {
                    parlourFloor(level, o);
                    // The bay: barred front at z=5, cell dividers, a gap each side so every spawn is reachable.
                    RoomDsl.box(level, o, 1, 1, 5, 14, 3, 5, BARS);
                    RoomDsl.box(level, o, 1, 4, 5, 14, 4, 5, QUARTZ);
                    for (int x : new int[]{5, 10}) {
                        RoomDsl.box(level, o, x, 1, 1, x, 4, 5, QUARTZ);
                    }
                    RoomDsl.box(level, o, 4, 1, 5, 4, 2, 5, RoomDsl.AIR);
                    RoomDsl.box(level, o, 8, 1, 5, 8, 2, 5, RoomDsl.AIR);
                    RoomDsl.box(level, o, 11, 1, 5, 11, 2, 5, RoomDsl.AIR);
                    // The pedestal: quartz on glowstone set into the floor, the bay's only lit thing.
                    RoomDsl.box(level, o, 6, 0, 1, 9, 0, 4, GLOW);
                    RoomDsl.box(level, o, 7, 1, 2, 8, 1, 3, QUARTZ);
                    // The sign, just in front of the bars.
                    RoomDsl.put(level, o, 8, 1, 6, Blocks.OAK_SIGN.defaultBlockState()
                            .setValue(BlockStateProperties.ROTATION_16, 0));
                    if (level.getBlockEntity(o.offset(8, 1, 6)) instanceof SignBlockEntity sign) {
                        SignText text = sign.getFrontText()
                                .setMessage(0, Component.literal("Specimen 0"))
                                .setMessage(1, Component.literal("(do not feed)"));
                        sign.setText(text, true);
                    }
                    // Attendant cells get bedding; the aisle side a churn and a cauldron.
                    RoomDsl.put(level, o, 2, 1, 1, HAY);
                    RoomDsl.put(level, o, 13, 1, 1, HAY);
                    RoomDsl.put(level, o, 13, 1, 12, Blocks.BARREL.defaultBlockState());
                    RoomDsl.put(level, o, 3, 1, 12, Blocks.CAULDRON.defaultBlockState());
                    RoomDsl.box(level, o, 12, 1, 13, 13, 1, 13, HAY);
                    RoomDsl.put(level, o, 1, 4, 1, WEB);
                    RoomDsl.put(level, o, 14, 4, 1, WEB);
                    RoomDsl.put(level, o, 6, 4, 5, WEB);
                });
    }
}
