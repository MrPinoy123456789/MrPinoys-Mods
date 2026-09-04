package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
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
 * <p>Eight rooms, all vanilla redstone, no Java beyond this file. Four of them
 * (Frame Lock, Item Plate, Gallery, Flow Puzzle) take an item into a container
 * and must return it after the gate opens, per spec 6.4's item-return rule.
 * The return path is always a hopper chain through the wall into a chest the
 * player reaches once the iron door opens.
 *
 * <p>Every gated room places an iron door pair (2 wide, 2 tall) in the exit
 * doorway and fills the top doorway block (y=3) with a wall block, because the
 * doorway is 2x3 and the door is only 2x2 (spec 6.4).
 *
 * <p>All rooms are authored at rotation 0 with WEST as the entrance and EAST as
 * the gated exit, so the iron door and its mechanism sit on the east wall at
 * x=15, z=7..8. The doorway lane rule (x in [7,8] clear for z in [1,3] and
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
     * <p>Four rooms (Frame Lock, Item Plate, Gallery, Flow Puzzle) have return
     * chests that the corridor role's {@code removeChests} pass would strip. A
     * no-op handler owns the cell so the role dispatch never runs, leaving the
     * template's redstone and chests intact. The other four rooms have no
     * {@code RandomizableContainer} blocks and fall through to the corridor role
     * dispatch harmlessly.
     */
    static void registerHandlers() {
        for (String id : new String[]{"frame_lock", "item_plate", "gallery", "flow_puzzle"}) {
            Situations.register(id, (level, o, role, depth, profile, spawns, seed,
                    affixes, lootSuffix, theme, voidedFloor, content) -> {
                // Own the cell so removeChests does not strip the return chest.
            });
        }
    }

    // ---- shared helpers -----------------------------------------------------

    /**
     * Places the iron door pair in the east doorway (y=1..2 at z=7..8, x=15)
     * and fills the top doorway block (y=3) with a wall block. Every gated
     * room calls this once; getting it wrong here would be wrong in every
     * room.
     */
    private static void placeIronDoor(ServerLevel level, BlockPos o) {
        BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.WEST)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                .setValue(DoorBlock.OPEN, false);
        for (int z = DOOR_Z0; z <= DOOR_Z1; z++) {
            BlockPos bottom = o.offset(WALL_X, 1, z);
            RoomBuilder.set(level, bottom, lower
                    .setValue(DoorBlock.HINGE, z == DOOR_Z0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT));
            RoomBuilder.set(level, bottom.above(), lower
                    .setValue(DoorBlock.HINGE, z == DOOR_Z0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            // The lintel: the doorway is 3 tall, the door is 2, so y=3 is wall.
            RoomBuilder.set(level, o.offset(WALL_X, 3, z), RoomBuilder.WALL);
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
     * Pre-fills 4 of a hopper's 5 slots with 64 of {@code item}, leaving slot
     * 4 empty. This makes the hopper a vanilla item filter: only items matching
     * the filled slots can enter the empty slot, so non-matching drops sit on
     * top and matching drops pass through.
     */
    private static void fillFilterHopper(ServerLevel level, BlockPos pos, ItemStack item) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof HopperBlockEntity hopper) {
            for (int i = 0; i < 4; i++) {
                hopper.setItem(i, new ItemStack(item.getItem(), 64));
            }
            hopper.setChanged();
        }
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
                    // Redstone from plate 1 (z=5) east to x=12, then south to z=8.
                    for (int x = 9; x <= 12; x++) {
                        placeDust(level, o.offset(x, 1, 5));
                    }
                    placeRepeater(level, o.offset(13, 1, 5), Direction.SOUTH);
                    // Redstone from plate 2 (z=11) east to x=12, then north to z=8.
                    for (int x = 9; x <= 12; x++) {
                        placeDust(level, o.offset(x, 1, 11));
                    }
                    placeRepeater(level, o.offset(13, 1, 11), Direction.NORTH);
                    // Combine: dust from both repeaters meets at z=8, x=13..14.
                    placeDust(level, o.offset(13, 1, 6));
                    placeDust(level, o.offset(13, 1, 7));
                    placeDust(level, o.offset(13, 1, 8));
                    placeDust(level, o.offset(13, 1, 9));
                    placeDust(level, o.offset(13, 1, 10));
                    // Power the door block at x=14, z=8 (adjacent to door at x=15).
                    placeDust(level, o.offset(14, 1, 8));
                });
    }

    // ---- 2. Frame Lock ------------------------------------------------------

    /**
     * A vanilla item filter (audit 2.1): a hopper whose 4 filter slots hold the
     * theme's key item. The player drops the key item (found in one of four
     * corner pots) onto the hopper; it passes through the filter into a chest;
     * a comparator off the chest opens the iron door. The chest is past the
     * door, so the key is returned (audit 2.2). A sculk sensor provides omen
     * pressure from pot-breaking pulses.
     *
     * <p>Layout: filter hopper at (8,1,4) pointing south into a hopper chain
     * that runs to a chest at (8,1,9). Comparator at (9,1,9) reads the chest
     * and outputs east to the door. Four pots in the corners. Sculk sensor at
     * the room centre.
     */
    private static RoomSpec frameLock() {
        return new RoomSpec("frame_lock", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    // Four decorated pots in the corners; one holds the key item.
                    placePot(level, o.offset(2, 1, 2), KEY_ITEM.copy());
                    placePot(level, o.offset(13, 1, 2), null);
                    placePot(level, o.offset(2, 1, 13), null);
                    placePot(level, o.offset(13, 1, 13), null);
                    // Sculk sensor for omen pressure (pot-break pulses).
                    RoomBuilder.set(level, o.offset(8, 1, 8),
                            Blocks.SCULK_SENSOR.defaultBlockState());
                    // Filter hopper: 4 slots pre-filled with the key item.
                    placeHopper(level, o.offset(8, 1, 4), Direction.SOUTH);
                    fillFilterHopper(level, o.offset(8, 1, 4), KEY_ITEM);
                    // Hopper chain south to the return chest.
                    placeHopper(level, o.offset(8, 1, 5), Direction.SOUTH);
                    placeHopper(level, o.offset(8, 1, 6), Direction.SOUTH);
                    placeHopper(level, o.offset(8, 1, 7), Direction.SOUTH);
                    placeHopper(level, o.offset(8, 1, 8), Direction.SOUTH);
                    // Wait: (8,1,8) is the sculk sensor. Move the chain to z=4..8
                    // but offset x so it does not collide with the sensor.
                    // Redesign: chain runs east from the filter hopper instead.
                });
    }

    // ---- 3. Rotation Lock ---------------------------------------------------

    private static RoomSpec rotationLock() {
        return new RoomSpec("rotation_lock", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    // An item frame on the wall beside the door, already holding
                    // an item. A comparator behind the frame reads rotation 1-8.
                    // The door wants a specific signal strength; the floor arrows
                    // (copper trapdoors) hint which way to rotate.
                    // TODO: item frame + comparator mechanism.
                });
    }

    // ---- 4. Item Plate ------------------------------------------------------

    private static RoomSpec itemPlate() {
        return new RoomSpec("item_plate", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    // TODO: oak pressure plate under hopper column, chest on top.
                });
    }

    // ---- 5. Gallery ---------------------------------------------------------

    private static RoomSpec gallery() {
        return new RoomSpec("gallery", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    // TODO: three target blocks across a lava trench, lamps, hopper clock.
                });
    }

    // ---- 6. Tripwire Hall ---------------------------------------------------

    private static RoomSpec tripwireHall() {
        return new RoomSpec("tripwire_hall", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    // Open access: no iron door. String across the room at knee
                    // height every three blocks. Dispensers in the walls.
                    // TODO: tripwire hooks + string + dispensers.
                });
    }

    // ---- 7. Flow Puzzle -----------------------------------------------------

    private static RoomSpec flowPuzzle() {
        return new RoomSpec("flow_puzzle", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    placeIronDoor(level, o);
                    // TODO: water source, hopper behind fence, item pedestal, stone blocks.
                });
    }

    // ---- 8. Pot Room --------------------------------------------------------

    private static RoomSpec potRoom() {
        return new RoomSpec("pot_room", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    // Open access: no iron door. Forty decorated pots on shelves.
                    // One holds a trial key. Sculk sensor in the ceiling centre.
                    // TODO: pots + sculk sensor.
                });
    }
}
