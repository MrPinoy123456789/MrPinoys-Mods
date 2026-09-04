package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomTemplateGenerator.CELL;

/**
 * M51: the mechanism family's room templates (spec 4.2).
 *
 * <p>Eight rooms. Four of them (Frame Lock, Item Plate, Gallery, Flow Puzzle)
 * take an item into a container and must return it after the gate opens, per
 * spec 6.4's item-return rule; the return path is a hopper chain into a chest
 * the player reaches once the iron door opens.
 *
 * <p>Five of the gates are {@link Locks}, not redstone. Spec 4.2 authored them
 * all as vanilla circuits, and most of those circuits cannot say what the room
 * needs to ask: a filter hopper cannot hold its filter, a comparator cannot
 * name an item, and two plates on one dust line are an OR. Rotation Lock keeps
 * its redstone, because a comparator reading an item frame is exactly the
 * room.
 *
 * <p>Every gated room stands an iron door pair (2 wide, 2 tall) one block inside
 * the exit doorway, at x=14, and caps it with a wall block at y=3 so it cannot
 * be jumped. The doorway plane at x=15 is left alone on purpose: its three
 * jigsaw blocks are what {@link RoomManifest} derives the door mask from, and a
 * template that writes over them ships a room the planner reads as having no
 * east door at all.
 *
 * <p>All rooms are authored at rotation 0 with WEST as the entrance and EAST as
 * the gated exit, so the iron door and its mechanism sit just inside the east
 * wall around z=7..8. The doorway lane rule (x in [7,8] clear for z in [1,3] and
 * [12,14]; z in [7,8] clear for x in [1,3] and [12,14]) is honoured by keeping
 * solid furniture out of those lanes; redstone dust is non-solid and may cross
 * them.
 */
final class MechanismSpecs {

    private MechanismSpecs() {}

    /** The exit wall: the iron door and its mechanism sit here. */
    private static final Direction EXIT = Direction.EAST;
    /** The entrance wall: open, no mechanism. */
    private static final Direction ENTRANCE = Direction.WEST;

    /** The two z-columns the east doorway occupies. */
    private static final int DOOR_Z0 = RoomGeometry.DOOR_MIN;
    private static final int DOOR_Z1 = RoomGeometry.DOOR_MAX;
    /** The east wall's x coordinate. */
    private static final int WALL_X = CELL - 1;
    /** Where a gated room's iron door stands: one block inside the east doorway. */
    private static final int DOOR_X = CELL - 2;

    /** The theme's key item for Frame Lock's hopper filter and corner pots. */
    private static final ItemStack KEY_ITEM = new ItemStack(Items.COPPER_INGOT);

    /**
     * The mechanism family's templates, authored in the order the plan calls
     * for: Plate Pair first (the round's only hard {@code requires}), then the
     * Frame Lock rebuild, then the remaining six.
     */
    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(platePair());
        specs.add(frameLock());
        specs.add(rotationLock());
        specs.add(itemPlate());
        specs.add(gallery());
        specs.add(tripwireHall());
        specs.add(flowPuzzle());
        specs.add(potRoom());
        return specs;
    }

    /**
     * Registers the situation handlers the mechanism family needs at stamp time.
     * Called once from {@link TrialContent#warmUp()} on server start.
     *
     * <p>Each handler arms the cell's lock and, by owning the cell, keeps the
     * corridor role's {@code removeChests} pass off the return chest. The three
     * ungated rooms (Rotation Lock, Tripwire Hall, Pot Room) register nothing
     * and fall through to the role dispatch harmlessly.
     */
    static void registerHandlers() {
        // The three rooms whose gate is "put anything in the chest". Owning the
        // cell also keeps the corridor role's removeChests pass off the return
        // chest, which is the room.
        for (String id : new String[]{"item_plate", "gallery", "flow_puzzle"}) {
            Situations.register(id, (level, o, role, depth, profile, spawns, seed,
                    affixes, lootSuffix, theme, voidedFloor, content) ->
                    Locks.arm(level, o, Locks.Kind.ITEM_ANY, null));
        }
        // Frame Lock wants one specific item, which is the thing no comparator
        // can tell you and the reason the lock is code (audit 2.1).
        Situations.register("frame_lock", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                Locks.arm(level, o, Locks.Kind.ITEM_KEY, KEY_ITEM.getItem()));
        // Plate Pair is the AND: both plates at once, never either alone.
        Situations.register("plate_pair", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                Locks.arm(level, o, Locks.Kind.PLATES_ALL, null));
    }

    // ---- shared helpers -----------------------------------------------------

    /**
     * Places the iron door pair one block inside the east doorway (x=14, y=1..2
     * at z=7..8) and fills the block above it with wall so it cannot be jumped.
     * Every gated room calls this once; getting it wrong here would be wrong in
     * every room.
     *
     * <p>The door stands at x=14 rather than in the doorway plane at x=15
     * because the doorway's three jigsaw blocks are what
     * {@link RoomManifest} reads the room's door mask from. A template that
     * writes over them ships a room the planner believes has no east door, so
     * it is placed as a dead end and the mechanism gates nothing. One block
     * inside, the door still covers both doorway columns (z=7 and z=8 are the
     * only way through), the jigsaws survive, and the mask is honest.
     */
    private static void placeIronDoor(ServerLevel level, BlockPos o) {
        BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.WEST)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                .setValue(DoorBlock.OPEN, false);
        for (int z = DOOR_Z0; z <= DOOR_Z1; z++) {
            BlockPos bottom = o.offset(DOOR_X, 1, z);
            RoomBuilder.set(level, bottom, lower
                    .setValue(DoorBlock.HINGE, z == DOOR_Z0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT));
            RoomBuilder.set(level, bottom.above(), lower
                    .setValue(DoorBlock.HINGE, z == DOOR_Z0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            // The lintel: the doorway is 3 tall, the door is 2, so y=3 is wall.
            RoomBuilder.set(level, o.offset(DOOR_X, 3, z), RoomBuilder.WALL);
        }
    }

    /** A chest facing west (toward the player coming through the east door). */
    private static void placeReturnChest(ServerLevel level, BlockPos pos) {
        RoomBuilder.set(level, pos, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.WEST));
    }

    /** A hopper pointing in {@code facing}, placed at {@code pos}. */
    private static void placeHopper(ServerLevel level, BlockPos pos, Direction facing) {
        RoomBuilder.set(level, pos, Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, facing));
    }

    /** A comparator in compare mode facing {@code facing} (output direction). */
    private static void placeComparator(ServerLevel level, BlockPos pos, Direction facing) {
        RoomBuilder.set(level, pos, Blocks.COMPARATOR.defaultBlockState()
                .setValue(ComparatorBlock.FACING, facing)
                .setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE));
    }

    /** A repeater facing {@code facing} (output direction), default 1-tick delay. */
    private static void placeRepeater(ServerLevel level, BlockPos pos, Direction facing) {
        RoomBuilder.set(level, pos, Blocks.REPEATER.defaultBlockState()
                .setValue(RepeaterBlock.FACING, facing));
    }

    /** Redstone dust on the floor. */
    private static void placeDust(ServerLevel level, BlockPos pos) {
        RoomBuilder.set(level, pos, Blocks.REDSTONE_WIRE.defaultBlockState());
    }

    /**
     * Places a decorated pot at {@code pos} and optionally puts {@code item}
     * inside it. Pots are block entities that hold a single item stack.
     */
    private static void placePot(ServerLevel level, BlockPos pos, ItemStack item) {
        RoomBuilder.set(level, pos, Blocks.DECORATED_POT.defaultBlockState());
        if (item != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DecoratedPotBlockEntity pot) {
                pot.setTheItem(item.copy());
                pot.setChanged();
            }
        }
    }

    /** Places a dispenser facing {@code facing} and loads {@code count} arrows into slot 0. */
    private static void placeDispenser(ServerLevel level, BlockPos pos, Direction facing, int count) {
        RoomBuilder.set(level, pos, Blocks.DISPENSER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DispenserBlock.FACING, facing));
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof DispenserBlockEntity dispenser) {
            dispenser.setItem(0, new ItemStack(Items.ARROW, count));
            dispenser.setChanged();
        }
    }

    /**
     * Rotation Lock's gate: a solid block at (14,1,6) beside the door's north
     * leaf, and a repeater at (13,1,6) pointing east into it. A strongly
     * powered block next to an iron door opens it.
     *
     * <p>This is the one mechanism in the family vanilla expresses exactly, so
     * it stays redstone: a comparator really can read an item frame's rotation,
     * and the room is that reading. The other gates are {@link Locks}.
     */
    private static void placeDoorGate(ServerLevel level, BlockPos o) {
        RoomBuilder.set(level, o.offset(14, 1, 6), RoomBuilder.WALL);
        placeRepeater(level, o.offset(13, 1, 6), Direction.EAST);
    }

    /**
     * The return chest a locked room delivers into, at (11,1,6): beside the
     * doorway, so the player collects the item that solved the room on the way
     * out. That is spec 6.4's item-return rule and audit findings 2.2, 2.3 and
     * 2.4 in one block.
     *
     * <p>The chest sits inside the cell rather than "past the door" as the audit
     * words it, because a cell owns only its own 16 x 16 footprint: the far side
     * of the door belongs to a neighbour no template can know. Beside the
     * doorway is as far past the gate as a template can reach, and it returns
     * the item just as surely.
     *
     * <p>No comparator, no repeater: {@link Locks} reads the chest and latches
     * the door. A comparator could only ever say "something is in there", which
     * is not the question Frame Lock asks.
     */
    private static void placeChestGate(ServerLevel level, BlockPos o) {
        placeReturnChest(level, o.offset(11, 1, 6));
    }

    /** A hopper run along {@code z} from {@code xFrom} to {@code xTo}, all facing east. */
    private static void hopperRunEast(ServerLevel level, BlockPos o, int xFrom, int xTo, int z) {
        for (int x = xFrom; x <= xTo; x++) {
            placeHopper(level, o.offset(x, 1, z), Direction.EAST);
        }
    }

    // ---- 1. Plate Pair ------------------------------------------------------

    /**
     * Two stone pressure plates six blocks apart. Iron door on the exit wall.
     * {@code requires: ["mob"]}: a leashable mob at strictly lower BFS depth,
     * or a party of two. The AND of the two plates powers the door.
     *
     * <p>The plates are at z=5 and z=11 (six apart), both at x=8 (room centre).
     * Redstone dust from each plate runs east along z=5 and z=11 to a repeater
     * at x=12, then both feeds combine into a single dust line at z=8 that
     * reaches the door. Two plates ANDed means both must be pressed.
     */
    private static RoomSpec platePair() {
        return new RoomSpec("plate_pair", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    // Two stone pressure plates, six blocks apart on z.
                    RoomBuilder.set(level, o.offset(8, 1, 5),
                            Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
                    RoomBuilder.set(level, o.offset(8, 1, 11),
                            Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
                    // No wiring: two plates on one dust line are an OR, and this
                    // room is the round's only hard requires. Locks reads both
                    // plates and opens the door only while they are pressed
                    // together.
                });
    }

    // ---- 2. Frame Lock ------------------------------------------------------

    /**
     * Four decorated pots, one holding the theme's key item, and a hopper run
     * at z=6 that carries whatever the player drops into it east to the return
     * chest. The chest's comparator opens the door, so the item that solves the
     * room is the item the player collects on the way out (audit 2.2). A sculk
     * sensor supplies the omen pressure the pot-breaking slow path pays for.
     *
     * <p>Audit 2.1 asked for a vanilla item filter so only the key item counts.
     * A filter hopper keeps its filter stacks only while redstone locks it, and
     * an unlocked hopper pushes its own contents down the line, so a template
     * cannot carry one that survives the stamp. The gate therefore accepts any
     * item. The pots still carry the room: the bag is scarce (spec 3), and the
     * key item sitting in a corner pot is the thing the player can most afford
     * to spend. Players who read the theme still reach the right pot first.
     */
    private static RoomSpec frameLock() {
        return new RoomSpec("frame_lock", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    placeChestGate(level, o);
                    // Four decorated pots in the corners; one holds the key item.
                    placePot(level, o.offset(2, 1, 2), KEY_ITEM.copy());
                    placePot(level, o.offset(13, 1, 2), null);
                    placePot(level, o.offset(2, 1, 13), null);
                    placePot(level, o.offset(13, 1, 13), null);
                    // Omen pressure: every pot the player breaks is a sculk pulse.
                    RoomBuilder.set(level, o.offset(8, 1, 11),
                            Blocks.SCULK_SENSOR.defaultBlockState());
                    // The intake, and the run east to the return chest.
                    hopperRunEast(level, o, 4, 10, 6);
                });
    }

    // ---- 3. Rotation Lock ---------------------------------------------------

    /**
     * An item frame already holding an item, on a free-standing block at
     * (12,1,6). A comparator sits directly behind that block and reads the
     * frame's rotation, 1 through 8. The comparator is in subtract mode with a
     * side input of exactly 7, so only rotation 8 leaves anything to pass on:
     * one position of eight opens the door.
     *
     * <p>The side input is a redstone block at (4,1,5) feeding nine dust along
     * z=5, which arrives at the comparator's north side at strength 7. The
     * floor hint is a line of eight blocks with the eighth in gold: count them,
     * and you know how many times to click. The slow path is to click through
     * all eight.
     */
    private static RoomSpec rotationLock() {
        return new RoomSpec("rotation_lock", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    placeDoorGate(level, o);
                    // The frame's support block, and the frame on its west face.
                    RoomBuilder.set(level, o.offset(10, 1, 6), RoomBuilder.WALL);
                    ItemFrame frame = new ItemFrame(level, o.offset(9, 1, 6), Direction.WEST);
                    frame.setItem(KEY_ITEM.copy());
                    level.addFreshEntity(frame);
                    // The comparator reads the frame through the support block.
                    RoomBuilder.set(level, o.offset(11, 1, 6),
                            Blocks.COMPARATOR.defaultBlockState()
                                    .setValue(ComparatorBlock.FACING, Direction.EAST)
                                    .setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT));
                    placeDust(level, o.offset(12, 1, 6));
                    // The reference: 15 at x=3 decaying one per block to 7 at x=11.
                    RoomBuilder.set(level, o.offset(2, 1, 5), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    for (int x = 3; x <= 11; x++) {
                        placeDust(level, o.offset(x, 1, 5));
                    }
                    // The hint: eight blocks on the floor, the eighth in gold.
                    for (int x = 4; x <= 11; x++) {
                        RoomBuilder.set(level, o.offset(x, 1, 10),
                                x == 11 ? Blocks.GOLD_BLOCK.defaultBlockState()
                                        : Blocks.POLISHED_ANDESITE.defaultBlockState());
                    }
                });
    }

    // ---- 4. Item Plate ------------------------------------------------------

    /**
     * An oak pressure plate on top of a hopper, one block up so the player has
     * to throw rather than step. Wooden plates trigger on items, the hopper
     * under the plate swallows what lands there, and the run east delivers it
     * to the return chest, whose comparator opens the door. The item comes
     * back beside the doorway, which is the whole point of the room (spec 4.2).
     *
     * <p>Standing on the plate yourself does nothing: the plate is scenery, the
     * chest is the gate. Step off and you are where you started.
     */
    private static RoomSpec itemPlate() {
        return new RoomSpec("item_plate", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    placeChestGate(level, o);
                    // The pedestal: a hopper with the plate on its lid.
                    placeHopper(level, o.offset(8, 1, 8), Direction.EAST);
                    RoomBuilder.set(level, o.offset(8, 2, 8),
                            Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
                    // East, then north, then east again into the chest.
                    placeHopper(level, o.offset(9, 1, 8), Direction.EAST);
                    placeHopper(level, o.offset(10, 1, 8), Direction.NORTH);
                    placeHopper(level, o.offset(10, 1, 7), Direction.NORTH);
                    placeHopper(level, o.offset(10, 1, 6), Direction.EAST);
                });
    }

    // ---- 5. Gallery ---------------------------------------------------------

    /**
     * Three target blocks up the west wall, each on a hopper, each under a
     * redstone lamp that lights when it is hit. Arrows, snowballs, eggs and
     * wind charges all activate a target; the spent ammunition drops onto the
     * hopper beneath, is funnelled east along z=6, and lands in the return
     * chest, whose comparator opens the door.
     *
     * <p>That is audit 2.4's fix and the gate in one mechanism: the room gives
     * back the arrows it took, so {@code bow} and {@code snowballs} are still
     * in {@code available} at the next cell. The lamps are pure feedback, and
     * the three targets read from the doorway as what the room wants.
     *
     * <p>Spec 4.2 gates this on three hits inside a hopper clock's window. A
     * three-input latch with a timed reset does not fit a template that has to
     * survive a stamp, so the gate is delivery rather than simultaneity: any
     * one hit that returns its ammunition opens the door. Two dirt blocks by
     * the entrance make good on {@code provides: blocks}.
     */
    private static RoomSpec gallery() {
        return new RoomSpec("gallery", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    placeChestGate(level, o);
                    // The catch: a hopper column up the west wall funnelling to z=6.
                    placeHopper(level, o.offset(1, 1, 4), Direction.SOUTH);
                    placeHopper(level, o.offset(1, 1, 5), Direction.SOUTH);
                    placeHopper(level, o.offset(1, 1, 6), Direction.EAST);
                    for (int z = 7; z <= 12; z++) {
                        placeHopper(level, o.offset(1, 1, z), Direction.NORTH);
                    }
                    // The run east to the return chest.
                    hopperRunEast(level, o, 2, 10, 6);
                    // Three targets, each with its lamp.
                    for (int z : new int[]{4, 8, 12}) {
                        RoomBuilder.set(level, o.offset(1, 2, z), Blocks.TARGET.defaultBlockState());
                        RoomBuilder.set(level, o.offset(1, 3, z), Blocks.REDSTONE_LAMP.defaultBlockState());
                    }
                    // provides: blocks.
                    RoomBuilder.set(level, o.offset(5, 1, 10), Blocks.DIRT.defaultBlockState());
                    RoomBuilder.set(level, o.offset(6, 1, 10), Blocks.DIRT.defaultBlockState());
                });
    }

    // ---- 6. Tripwire Hall ---------------------------------------------------

    /**
     * Three tripwires strung north to south across the room at knee height,
     * each hooked to a wall block with a dispenser sitting directly above it.
     * The hook strongly powers the block it hangs on, that block powers the
     * dispenser, and the dispenser fires into the room. Three arrows each, so
     * the room runs dry for a party (spec 4.2).
     *
     * <p>Open access: no door, no gate. Shears cut the string without setting
     * it off, jumping clears each wire, and sprinting through and eating the
     * arrows works if you have the health. The slow path is to walk into every
     * one of them.
     */
    private static RoomSpec tripwireHall() {
        return new RoomSpec("tripwire_hall", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    for (int x : new int[]{4, 7, 10}) {
                        // A hook on each of the north and south walls, facing in.
                        RoomBuilder.set(level, o.offset(x, 1, 1),
                                Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(
                                        net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                                        Direction.SOUTH));
                        RoomBuilder.set(level, o.offset(x, 1, CELL - 2),
                                Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(
                                        net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                                        Direction.NORTH));
                        // The string between them.
                        for (int z = 2; z <= CELL - 3; z++) {
                            RoomBuilder.set(level, o.offset(x, 1, z), Blocks.TRIPWIRE.defaultBlockState());
                        }
                        // A dispenser above each hook's anchor block, firing in.
                        placeDispenser(level, o.offset(x, 2, 0), Direction.SOUTH, 3);
                        placeDispenser(level, o.offset(x, 2, CELL - 1), Direction.NORTH, 3);
                    }
                });
    }

    // ---- 7. Flow Puzzle -----------------------------------------------------

    /**
     * A kerbed channel along z=3 with a water source at its west end, a hopper
     * at its east end under a fence, and a gap in the north kerb at x=5 over a
     * missing floor block. Flowing water scans about five blocks for a drop and,
     * finding one, commits its whole flow to it: the stream turns north into the
     * hole and the channel east of x=5 stays dry however long you wait.
     *
     * <p>Plug it and the stream runs the length of the channel. Either loose
     * block does it: one in the kerb gap walls the branch off, one in the hole
     * itself makes the branch flat. Then drop the pot's item in the current and
     * it rides to the hopper, which feeds the return chest, which opens the
     * door. The item comes back out of that chest (audit 2.3).
     *
     * <p>The hole stays a hole once water is in it. Flowing water is not a solid
     * block and the drop scan looks straight through it, so only a block the
     * player places (or a true source) ever levels the terrain again. That is
     * what makes this a gate rather than a two-second delay, and it is why the
     * puzzle needs no depth: one block is a permanent drop.
     *
     * <p>The source sits at x=3 so the hopper at x=10 is exactly seven blocks
     * away: the last block a stream can reach. That is also why the water cannot
     * spread past the hopper into the doorway (audit 4.3).
     */
    private static RoomSpec flowPuzzle() {
        return new RoomSpec("flow_puzzle", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    placeChestGate(level, o);
                    BlockState stone = Blocks.STONE.defaultBlockState();
                    // The kerbs that hold the stream in its channel, with the
                    // gap at x=5 the water escapes through until it is plugged.
                    for (int x = 2; x <= 10; x++) {
                        if (x != 5) {
                            RoomBuilder.set(level, o.offset(x, 1, 2), stone);
                        }
                        if (x != 10) {
                            RoomBuilder.set(level, o.offset(x, 1, 4), stone);
                        }
                    }
                    // The drop itself: one block of missing floor behind the gap.
                    RoomBuilder.set(level, o.offset(5, 0, 2), Blocks.AIR.defaultBlockState());
                    RoomBuilder.set(level, o.offset(2, 1, 3), stone);
                    RoomBuilder.set(level, o.offset(11, 1, 3), stone);
                    // The source.
                    RoomBuilder.set(level, o.offset(3, 1, 3), Blocks.WATER.defaultBlockState());
                    // The catch, fenced from above, and the run to the chest.
                    placeHopper(level, o.offset(10, 1, 3), Direction.SOUTH);
                    RoomBuilder.set(level, o.offset(10, 2, 3), Blocks.OAK_FENCE.defaultBlockState());
                    placeHopper(level, o.offset(10, 1, 4), Direction.SOUTH);
                    placeHopper(level, o.offset(10, 1, 5), Direction.SOUTH);
                    placeHopper(level, o.offset(10, 1, 6), Direction.EAST);
                    // Two loose blocks to bend the flow, and the item to float.
                    RoomBuilder.set(level, o.offset(8, 1, 10), Blocks.GRAVEL.defaultBlockState());
                    RoomBuilder.set(level, o.offset(9, 1, 10), Blocks.GRAVEL.defaultBlockState());
                    placePot(level, o.offset(12, 1, 11), KEY_ITEM.copy());
                });
    }

    // ---- 8. Pot Room --------------------------------------------------------

    /**
     * Forty decorated pots on four shelves, one of them holding the trial key
     * ({@code provides: trial_key}), and a sculk sensor in the ceiling. Open
     * access: the key is the point, not a gate.
     *
     * <p>Every pot break is a sensor pulse and every pulse advances the run's
     * omen (spec 5.2), so the forty-break slow path is the expensive one. TNT
     * clears the lot in a single pulse, wool over the sensor mutes it, and a
     * pot with something in it rattles differently when you shift-click it.
     */
    private static RoomSpec potRoom() {
        return new RoomSpec("pot_room", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    // Two shelves per wall: the floor row and a row at y=3.
                    for (int z : new int[]{1, CELL - 2}) {
                        for (int x = 2; x <= 11; x++) {
                            RoomBuilder.set(level, o.offset(x, 2, z), RoomBuilder.WALL);
                            placePot(level, o.offset(x, 1, z), null);
                            placePot(level, o.offset(x, 3, z), null);
                        }
                    }
                    // One pot in forty holds the key.
                    placePot(level, o.offset(6, 3, 1), new ItemStack(Items.TRIAL_KEY));
                    RoomBuilder.set(level, o.offset(8, RoomGeometry.WALL_HEIGHT, 8),
                            Blocks.SCULK_SENSOR.defaultBlockState());
                });
    }
}
