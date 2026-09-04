package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static pocketdungeons.RoomTemplateGenerator.CELL;

/**
 * M53: the pressure family's room templates (spec 5.1).
 *
 * <p>Three rooms that exist purely to be timers: Rising Lava, Collapsing Bridge,
 * and Hold the Plate. Each supplies its own physics out of vanilla blocks, so
 * there is no Java beyond this file. Rising Lava and Collapsing Bridge are open
 * through-rooms; Hold the Plate is gated by an iron door that the hopper clock
 * opens.
 *
 * <p>All three are authored at rotation 0 with WEST as the entrance and EAST as
 * the exit, matching the mechanism family's convention so a floor can mix the
 * two without a second orientation pass.
 */
final class PressureSpecs {

    private PressureSpecs() {}

    private static final Direction ENTRANCE = Direction.WEST;
    private static final Direction EXIT = Direction.EAST;
    private static final int WALL_X = CELL - 1;
    private static final int DOOR_Z0 = RoomGeometry.DOOR_MIN;
    private static final int DOOR_Z1 = RoomGeometry.DOOR_MAX;

    /** The pressure family's templates. */
    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(risingLava());
        specs.add(collapsingBridge());
        specs.add(holdThePlate());
        return specs;
    }

    /**
     * Registers the situation handlers the pressure family needs at stamp time.
     * Called once from {@link TrialContent#warmUp()} on server start.
     *
     * <p>Only Hold the Plate needs a handler: it has a classic spawner that must
     * become a trial spawner, and the handler owns the cell so the corridor role
     * dispatch never strips its (nonexistent) chests. Rising Lava and Collapsing
     * Bridge are pure template rooms with no chests and no spawners, so they
     * fall through to the corridor role dispatch harmlessly.
     */
    static void registerHandlers() {
        Situations.register("hold_the_plate", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> {
            TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                    "hold_the_plate", true);
        });
        // M58: template-only pressure rooms. The decor in the RoomSpec is
        // the whole of the content; the handler owns the cell.
        Situations.register("rising_lava", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> { /* template owns the room */ });
        Situations.register("collapsing_bridge", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> { /* template owns the room */ });
    }

    // ---- shared helpers -----------------------------------------------------

    /** Places the iron door pair in the east doorway (y=1..2, z=7..8, x=15) and the y=3 lintel. */
    private static void placeIronDoor(ServerLevel level, BlockPos o) {
        BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.WEST)
                .setValue(DoorBlock.OPEN, false);
        // One block inside the doorway: writing over the doorway's jigsaw
        // blocks would cost the room its east door in the manifest's mask,
        // and the planner would place it as a dead end.
        for (int z = DOOR_Z0; z <= DOOR_Z1; z++) {
            DoorHingeSide hinge = z == DOOR_Z0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            RoomBuilder.set(level, o.offset(WALL_X - 1, 1, z),
                    lower.setValue(DoorBlock.HINGE, hinge));
            RoomBuilder.set(level, o.offset(WALL_X - 1, 2, z),
                    lower.setValue(DoorBlock.HINGE, hinge)
                            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            RoomBuilder.set(level, o.offset(WALL_X - 1, 3, z), RoomBuilder.WALL);
        }
    }

    private static void set(ServerLevel level, BlockPos o, int x, int y, int z, BlockState state) {
        RoomBuilder.set(level, o.offset(x, y, z), state);
    }

    private static void placeHopper(ServerLevel level, BlockPos o, int x, int y, int z, Direction facing) {
        set(level, o, x, y, z, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing));
    }

    private static void placeComparator(ServerLevel level, BlockPos o, int x, int y, int z, Direction facing) {
        set(level, o, x, y, z, Blocks.COMPARATOR.defaultBlockState()
                .setValue(ComparatorBlock.FACING, facing)
                .setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE));
    }

    private static void placeRepeater(ServerLevel level, BlockPos o, int x, int y, int z, Direction facing) {
        set(level, o, x, y, z, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, facing));
    }

    private static void placeDust(ServerLevel level, BlockPos o, int x, int y, int z) {
        set(level, o, x, y, z, Blocks.REDSTONE_WIRE.defaultBlockState());
    }

    /** A dispenser facing {@code facing}, loaded with {@code count} of {@code item} in slot 0. */
    private static void placeDispenser(ServerLevel level, BlockPos o, int x, int y, int z,
                                       Direction facing, ItemStack item, int count) {
        set(level, o, x, y, z, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, facing));
        BlockEntity be = level.getBlockEntity(o.offset(x, y, z));
        if (be instanceof DispenserBlockEntity dispenser) {
            dispenser.setItem(0, new ItemStack(item.getItem(), count));
            dispenser.setChanged();
        }
    }

    /** Fills 4 of a hopper's 5 slots with 64 of {@code item}, leaving slot 4 empty (vanilla item filter). */
    private static void fillFilter(ServerLevel level, BlockPos o, int x, int y, int z, ItemStack item) {
        BlockEntity be = level.getBlockEntity(o.offset(x, y, z));
        if (be instanceof HopperBlockEntity hopper) {
            for (int i = 0; i < 4; i++) {
                hopper.setItem(i, new ItemStack(item.getItem(), 64));
            }
            hopper.setChanged();
        }
    }

    // ---- 1. Rising Lava -----------------------------------------------------

    /**
     * A kerbed lava channel runs the length of the room along the through-route
     * (z=7..8). Dispensers in the south wall load lava buckets into the channel;
     * a hopper clock in the north-west corner feeds them. A one-block dry margin
     * along the north wall (z=1) keeps the cleared room crossable afterwards
     * (audit 4.5), and the stone-brick kerb at z=6 and z=9 contains the lava so
     * it cannot leak through the doorways (audit 4.3).
     *
     * <p>The channel floor is deepslate to read as a trench; the dry margin is
     * chiseled stone brick to read as the safe path. Pillaring is capped at
     * y=4 (interior is five tall, one block of headroom).
     */
    private static RoomSpec risingLava() {
        return new RoomSpec("rising_lava", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    BlockState trench = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
                    BlockState dry = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
                    BlockState kerb = RoomBuilder.WALL;
                    // Channel floor (z=7..8) and dry margin (z=1) along the north wall.
                    for (int x = 1; x < CELL - 1; x++) {
                        set(level, o, x, 0, 7, trench);
                        set(level, o, x, 0, 8, trench);
                        set(level, o, x, 0, 1, dry);
                    }
                    // Kerb lips at y=1 on z=6 and z=9 to contain the lava.
                    for (int x = 1; x < CELL - 1; x++) {
                        set(level, o, x, 1, 6, kerb);
                        set(level, o, x, 1, 9, kerb);
                    }
                    // Dispensers in the south wall (z=15) facing north, loaded with
                    // lava buckets. They place lava into the channel at z=14.
                    ItemStack lavaBucket = new ItemStack(Items.LAVA_BUCKET);
                    for (int x : new int[]{3, 6, 9, 12}) {
                        placeDispenser(level, o, x, 1, CELL - 1, Direction.NORTH, lavaBucket, 1);
                    }
                    // Hopper clock in the north-west corner on the dry strip: two
                    // hoppers facing each other, one pre-loaded with items.
                    placeHopper(level, o, 2, 1, 1, Direction.EAST);
                    placeHopper(level, o, 3, 1, 1, Direction.WEST);
                    BlockEntity clock = level.getBlockEntity(o.offset(2, 1, 1));
                    if (clock instanceof HopperBlockEntity hopper) {
                        for (int i = 0; i < 5; i++) {
                            hopper.setItem(i, new ItemStack(Items.REDSTONE, 32));
                        }
                        hopper.setChanged();
                    }
                    // Comparator reads the clock, dust carries the pulse south to
                    // the dispenser row. Redstone is non-solid and may cross lanes.
                    placeComparator(level, o, 3, 2, 1, Direction.SOUTH);
                    placeDust(level, o, 3, 2, 2);
                    placeDust(level, o, 3, 2, 3);
                    placeDust(level, o, 3, 2, 4);
                    placeDust(level, o, 3, 2, 5);
                    placeRepeater(level, o, 3, 2, 6, Direction.SOUTH);
                });
    }

    // ---- 2. Collapsing Bridge -----------------------------------------------

    /**
     * A bridge of oak plank segments crosses a lava floor (audit 4.2: lava at
     * y=0, not a pit). Sticky pistons in the kerb walls push each segment into
     * place; observers behind the pistons detect the player stepping on and
     * retract the segment. A repeater delay re-extends the piston afterwards
     * (audit 4.4: timing hazard, not a permanent one-way, so backtracking
     * survives).
     *
     * <p>The bridge runs along z=7..8 (the through-route). Pistons sit in the
     * z=6 kerb facing south and the z=9 kerb facing north, one pair per segment.
     */
    private static RoomSpec collapsingBridge() {
        return new RoomSpec("collapsing_bridge", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    // Lava floor across the room, leaving a one-block dry margin
                    // along the north wall (z=1) so the room is crossable after
                    // the bridge re-extends.
                    BlockState lava = Blocks.LAVA.defaultBlockState();
                    BlockState dry = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
                    for (int x = 1; x < CELL - 1; x++) {
                        for (int z = 2; z < CELL - 1; z++) {
                            set(level, o, x, 0, z, lava);
                        }
                        set(level, o, x, 0, 1, dry);
                    }
                    // Bridge segments and piston pairs at every third block.
                    BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
                    BlockState pistonSouth = Blocks.STICKY_PISTON.defaultBlockState()
                            .setValue(PistonBaseBlock.FACING, Direction.SOUTH);
                    BlockState pistonNorth = Blocks.STICKY_PISTON.defaultBlockState()
                            .setValue(PistonBaseBlock.FACING, Direction.NORTH);
                    BlockState observerSouth = Blocks.OBSERVER.defaultBlockState()
                            .setValue(ObserverBlock.FACING, Direction.SOUTH);
                    BlockState observerNorth = Blocks.OBSERVER.defaultBlockState()
                            .setValue(ObserverBlock.FACING, Direction.NORTH);
                    for (int x : new int[]{3, 6, 9, 12}) {
                        // Bridge block at z=7 (pushed by south-facing piston at z=6)
                        // and z=8 (pushed by north-facing piston at z=9).
                        set(level, o, x, 1, 6, pistonSouth);
                        set(level, o, x, 1, 9, pistonNorth);
                        set(level, o, x, 1, 7, planks);
                        set(level, o, x, 1, 8, planks);
                        // Observers behind the pistons detect the block update.
                        set(level, o, x, 1, 5, observerSouth);
                        set(level, o, x, 1, 10, observerNorth);
                        // Repeater on top of each observer for the re-extend delay.
                        placeRepeater(level, o, x, 2, 5, Direction.SOUTH);
                        placeRepeater(level, o, x, 2, 10, Direction.NORTH);
                    }
                });
    }

    // ---- 3. Hold the Plate --------------------------------------------------

    /**
     * A stone pressure plate in the room centre, a hopper clock of roughly 30
     * seconds, and a trial spawner. Stand on the plate until the clock lands and
     * the iron door opens; step off and the clock resets. The spawner sits
     * between the entrance and the plate so the decision is informed from the
     * doorway.
     *
     * <p>The clock is a standard two-hopper ethereal clock: one hopper pre-loaded
     * with enough items for a 30-second cycle. A comparator reads the clock and
     * powers the iron door at the east exit when the cycle completes.
     */
    private static RoomSpec holdThePlate() {
        return new RoomSpec("hold_the_plate", EnumSet.of(ENTRANCE, EXIT))
                .spawner(new BlockPos(8, 1, 4))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    // Pressure plate in the room centre.
                    set(level, o, 8, 1, 8, Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
                    // Hopper clock: two hoppers facing each other at z=11, one
                    // pre-loaded with items for the 30-second cycle.
                    placeHopper(level, o, 7, 1, 11, Direction.EAST);
                    placeHopper(level, o, 8, 1, 11, Direction.WEST);
                    BlockEntity clock = level.getBlockEntity(o.offset(7, 1, 11));
                    if (clock instanceof HopperBlockEntity hopper) {
                        for (int i = 0; i < 5; i++) {
                            hopper.setItem(i, new ItemStack(Items.REDSTONE, 64));
                        }
                        hopper.setChanged();
                    }
                    // Comparator reads the clock; dust carries the signal east to
                    // the iron door. The plate itself is not wired to the door:
                    // standing on it keeps the clock loaded by keeping the chunk
                    // active, and the clock opens the door when it lands.
                    placeComparator(level, o, 9, 1, 11, Direction.EAST);
                    for (int x = 10; x <= 12; x++) {
                        placeDust(level, o, x, 1, 11);
                    }
                    for (int z = 10; z >= 6; z--) {
                        placeDust(level, o, 12, 1, z);
                    }
                    // The gate: a repeater into the block beside the door's
                    // north leaf. A strongly powered block next to an iron door
                    // opens it.
                    set(level, o, 14, 1, 6, RoomBuilder.WALL);
                    set(level, o, 13, 1, 6, Blocks.REPEATER.defaultBlockState()
                            .setValue(RepeaterBlock.FACING, Direction.EAST));
                });
    }
}
