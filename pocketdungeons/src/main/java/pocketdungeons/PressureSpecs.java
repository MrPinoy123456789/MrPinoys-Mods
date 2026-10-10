package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

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
            // PD-67: the plate timer lives in code, not in the template's redstone.
            // Armed after the encounter so the handler sees the room's trial
            // spawners, which raise the plate's waves while it is held.
            BlockPos spawner = TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                    "hold_the_plate", true);
            Ordeals.arm(PlateRelayOrdeal.INSTANCE, level, o);
            return spawner;
        });
        // M58: template-only pressure rooms. The decor in the RoomSpec is
        // the whole of the content; the handler owns the cell.
        Situations.register("rising_lava", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> { return null; });
        // Rising Lava and Collapsing Bridge are Ordeals whose lever sits at
        // the exit, which is only known once the plan's doors are placed:
        // LayoutStamper.applyDirectionalGates places the lever and arms them.
        Situations.register("collapsing_bridge", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> { return null; });
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

// ---- 1. Rising Lava -----------------------------------------------------

// Fixed 2026-09-04: replaced broken dispenser/hopper/redstone circuit
// with code-driven lava spread (RisingLavaOrdeal). Lava now spreads
// from both side walls toward the center while players are present,
// stops when the lever is pulled, and recedes when the room is empty.
// See docs/ROOM_FIXES.md.
/**
 * A trash-compactor corridor. The floor is a deepslate trench. While
 * players are in the room, lava spreads inward from both side walls
 * toward the center. A lever on the far wall stops the lava and drains
 * the room. When no players remain, the lava recedes on its own.
 *
 * <p>The spread and recede are driven by {@link RisingLavaOrdeal} in
 * code, not redstone. Vanilla redstone cannot detect players in a room
 * or place/remove lava on a timer.
 */
private static RoomSpec risingLava() {
    return new RoomSpec("rising_lava", EnumSet.of(ENTRANCE, EXIT))
            .decor((level, o) -> {
                BlockState trench = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
                // Trench floor across the whole interior.
                for (int x = 1; x < CELL - 1; x++) {
                    for (int z = 1; z < CELL - 1; z++) {
                        set(level, o, x, 0, z, trench);
                    }
                }
                // The lever and its lamp are not baked: the layout can turn
                // the room round, so LayoutStamper places them on the exit
                // side at stamp time (Ordeals, 2026-09-30).
            });
}

    // ---- 2. Collapsing Bridge -----------------------------------------------

    /**
     * A bridge of oak plank segments crosses a lava floor. Each segment is a
     * pair of sticky pistons facing each other across a two-block gap, with
     * oak planks pushed into the gap to form the bridge. The pistons
     * telegraph the hazard: the player can see the mechanism that will drop
     * them.
     *
     * <p>The pistons and retracted planks are enclosed by pillars from floor
     * to ceiling, so the player cannot stand on a piston or a retracted
     * plank to bypass the collapse. The only walkable path is through the
     * bridge positions (z=7, z=8).
     *
     * <p>Per segment, the layout at y=1 is:
     * <pre>
     *   z=5  z=6  z=7  z=8  z=9  z=10
     *   P    H    W    W    H    P      (extended: bridge formed)
     *   P    W    .    .    W    P      (collapsed: two-block gap)
     * </pre>
     * P = sticky piston, H = piston head, W = oak plank, . = air.
     *
     * <p>Observers and repeaters cannot detect a player standing on a block
     * (observers detect block state changes, not entities), so the collapse
     * is driven by {@link CollapsingBridgeOrdeal} in code: it checks the
     * player's position each tick, retracts the pistons after a delay, and
     * re-extends after a longer delay so backtracking survives.
     */
    // Fixed 2026-09-04: replaced broken observer/piston redstone with
    // code-driven collapse (CollapsingBridgeOrdeal). Observers cannot detect
    // players. Redesigned to PWSSWP with pillars. See docs/ROOM_FIXES.md.
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
                    // Bridge segments at every third block. Each segment is a
                    // PWSSWP pair: pistons at z=5,10; planks at z=7,8 (extended);
                    // piston heads at z=6,9 (extended). Pillars at z=5,6,9,10
                    // from y=0 to the ceiling block the player from standing on
                    // pistons or retracted planks.
                    BlockState planks = Blocks.CRIMSON_PLANKS.defaultBlockState();
                    BlockState pillar = RoomBuilder.WALL;
                    BlockState pistonSouth = Blocks.STICKY_PISTON.defaultBlockState()
                            .setValue(PistonBaseBlock.FACING, Direction.SOUTH)
                            .setValue(PistonBaseBlock.EXTENDED, true);
                    BlockState pistonNorth = Blocks.STICKY_PISTON.defaultBlockState()
                            .setValue(PistonBaseBlock.FACING, Direction.NORTH)
                            .setValue(PistonBaseBlock.EXTENDED, true);
                    BlockState headSouth = Blocks.PISTON_HEAD.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.SOUTH)
                            .setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.TYPE,
                                    net.minecraft.world.level.block.state.properties.PistonType.STICKY);
                    BlockState headNorth = Blocks.PISTON_HEAD.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.NORTH)
                            .setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.TYPE,
                                    net.minecraft.world.level.block.state.properties.PistonType.STICKY);
                    for (int x : new int[]{3, 6, 9, 12}) {
                        // Piston bases (extended) and heads.
                        set(level, o, x, 1, 5, pistonSouth);
                        set(level, o, x, 1, 6, headSouth);
                        set(level, o, x, 1, 9, headNorth);
                        set(level, o, x, 1, 10, pistonNorth);
                        // Bridge planks in the extended position.
                        set(level, o, x, 1, 7, planks);
                        set(level, o, x, 1, 8, planks);
                        // Pillars at z=5,6,9,10: solid from y=0 (replacing lava,
                        // so pistons have a base) up through y=2 to the ceiling,
                        // blocking the player from standing on pistons or
                        // retracted planks.
                        for (int z : new int[]{5, 6, 9, 10}) {
                            set(level, o, x, 0, z, pillar);
                            for (int y = 2; y <= RoomGeometry.CEILING_Y; y++) {
                                set(level, o, x, y, z, pillar);
                            }
                        }
                    }
                });
    }

    // ---- 3. Hold the Plate --------------------------------------------------

    /**
     * The Plate Relay room. The template carries a centre plate and a trial spawner; at stamp
     * {@link PlateRelayOrdeal} swaps the centre plate for four corner plates, one lit at a time.
     * Stand on the lit plate for {@link PlateRelayOrdeal#CHARGE_SECONDS} seconds to charge it and the
     * light jumps; enough charges open the iron door at the exit. The spawner sits between the
     * entrance and the plates so the decision is informed from the doorway.
     *
     * <p>PD-67: the count lives in {@link PlateRelayOrdeal}, not in redstone.
     * The template used to carry a hopper clock, a comparator, dust and a
     * repeater, but the diodes faced the wrong way (a diode's FACING is its
     * input side), the plate was wired to nothing and the clock had no lock,
     * so the door never opened.
     *
     * <p>The iron door itself is NOT placed here: the layout can rotate the
     * room 180 degrees, which would put a baked door on the entrance side. It
     * is placed at runtime by {@link LayoutStamper#applyDirectionalGates} on
     * the exit side.
     */
    private static RoomSpec holdThePlate() {
        return new RoomSpec("hold_the_plate", EnumSet.of(ENTRANCE, EXIT))
                .spawner(new BlockPos(8, 1, 4))
                .decor((level, o) -> {
                    // Pressure plate in the room centre.
                    set(level, o, 8, 1, 8, Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
                });
    }
}
