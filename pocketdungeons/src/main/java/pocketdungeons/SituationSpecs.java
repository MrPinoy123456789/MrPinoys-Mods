package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomGeometry.CELL;
import static pocketdungeons.RoomGeometry.WALL_HEIGHT;

/**
 * M74: six situation rooms using private lower stories, not a new layout
 * engine. Three pairs, each admitted or rejected on its own merits.
 *
 * <p>Pair 1: Sump and Ropewalk. Sump is a spanY 2 room whose lower story is a
 * flooded sump with a current the player redirects with loose blocks. Ropewalk
 * is a single story room with a high crossing and a slower lower path.
 *
 * <p>Every room owes doorway readability, two useful solutions, a real slow
 * path, and two of three utility (hazard, weapon, resource). The doorway
 * plane (Trap 20) is never written: walls, seals, lintels, and gates sit one
 * block inside, at x 14 for the east wall in rotation 0.
 */
final class SituationSpecs {

    private SituationSpecs() {}

    /**
     * Registers the situation handlers M74 rooms need at stamp time. Called
     * once from {@link TrialContent#warmUp()} on server start. Combat rooms
     * (sensor_gallery, kennel_crossing, blaze_cellar) place a trial spawner
     * via {@link TrialContent#applyEncounter} with a situation-specific config
     * prefix. Non-combat rooms (sump, ropewalk, sorting_floor) own the cell
     * so the corridor role's removeChests pass does not strip authored
     * chests, exactly as the M51 mechanism handlers do.
     */
    static void registerHandlers() {
        // Non-combat rooms: own the cell, no trial spawner.
        for (String id : new String[]{"sump", "ropewalk"}) {
            Situations.register(id, (level, o, role, depth, profile, spawns, seed,
                    affixes, lootSuffix, theme, voidedFloor, content) -> {
                // Own the cell so removeChests does not strip authored chests.
                return null;
            });
        }
        // PD-133: the sorting floor's comparator read the wrong side and never
        // opened the door. The filter hopper only passes the stick, so "any
        // item in the return chest" is the same question, asked by a Lock.
        Situations.register("sorting_floor", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) -> {
            Locks.arm(level, o, Locks.Kind.ITEM_KEY, Items.STICK);
            return null;
        });
        // Combat rooms: place a trial spawner with a situation-specific config.
        Situations.register("sensor_gallery", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                        "sensor_gallery", true));
        Situations.register("kennel_crossing", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                        "kennel_crossing", false));
        Situations.register("blaze_cellar", (level, o, role, depth, profile, spawns, seed,
                affixes, lootSuffix, theme, voidedFloor, content) ->
                TrialContent.applyEncounter(level, o, spawns, profile.lootTier(), affixes,
                        "blaze_cellar", false));
    }

    /** The M74 situation room templates. */
    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(sump());
        specs.add(ropewalk());
        specs.add(sortingFloor());
        specs.add(sensorGallery());
        specs.add(kennelCrossing());
        specs.add(blazeCellar());
        return specs;
    }

    private static final Direction ENTRANCE = Direction.WEST;
    private static final Direction EXIT = Direction.EAST;

    // ---- Pair 1.1: Sump -----------------------------------------------------

    /**
     * Sump: a spanY 2 room whose lower story is a flooded sump. The upper
     * story is dry; a shaft at (8, z 8) drops the player into water below.
     * A water source at (2, -8, 2) creates a current flowing southeast across
     * the lower floor, pushing the player away from the reward chest at
     * (13, -8, 13). Two loose stone blocks on the upper floor let the player
     * redirect the current (place blocks to bend the flow). A ladder in the
     * NE corner climbs from the lower floor back to the upper floor, so the
     * return path is permanent and needs no tool.
     *
     * <p>Two useful solutions: redirect the current with the loose blocks
     * and swim with the new flow, or swim against the current directly (slow
     * but always works). Two of three: the water is a hazard (current and
     * drowning), a weapon (redirect it to push mobs into the sump), and a
     * resource (water bucket to carry forward).
     *
     * <p>The shaft through the upper floor, filler, and lower ceiling is
     * carved at (8, z 8) so the player can drop in. PD-97: the return path
     * used to be a solid pillar at (8, z 1) described as a staircase, the
     * same defect as PD-78's {@code blaze_cellar}, and
     * {@link ReturnPathValidator} refused every stamp. It is now a ladder
     * column at (14, z 1) from the lower story's first block (y -7) up
     * through the filler and a hole in the upper floor (y 0), hung on the
     * north wall. The NE corner keeps it clear of the water source in the
     * NW corner, of the east doorway at z 7..8, and of the drop shaft.
     */
    private static RoomSpec sump() {
        return new RoomSpec("sump", EnumSet.of(ENTRANCE, EXIT))
                .spanY(2)
                .chests(new BlockPos(13, -8, 13))
                .decor((level, o) -> {
                    BlockState water = Blocks.WATER.defaultBlockState();
                    BlockState air = Blocks.AIR.defaultBlockState();
                    BlockState stone = Blocks.STONE.defaultBlockState();

                    // Lower story: flood the floor with a water source in the
                    // NW corner. The current flows southeast across the sump.
                    // Water sits at y -7 (one block above the lower floor at
                    // y -8) so the player lands in water, not on stone.
                    RoomBuilder.set(level, o.offset(2, -7, 2), water);

                    // Shaft through the upper floor (y 0), filler (y -1, -2),
                    // and lower ceiling (y -3) at (8, z 8). The player drops
                    // from the upper floor into the sump.
                    for (int y = 0; y >= -3; y--) {
                        RoomBuilder.set(level, o.offset(8, y, 8), air);
                    }

                    // The way back up: a ladder in the NE corner from the
                    // lower story's first block through the upper floor,
                    // facing south so it hangs on the north wall at z 0.
                    BlockState ladder = Blocks.LADDER.defaultBlockState()
                            .setValue(LadderBlock.FACING, Direction.SOUTH);
                    for (int y = -7; y <= 0; y++) {
                        RoomBuilder.set(level, o.offset(14, y, 1), ladder);
                    }

                    // Two loose stone blocks on the upper floor for current
                    // redirection. The player carries them down the shaft.
                    RoomBuilder.set(level, o.offset(6, 1, 6), stone);
                    RoomBuilder.set(level, o.offset(10, 1, 10), stone);
                });
    }

    // ---- Pair 1.2: Ropewalk -------------------------------------------------

    /**
     * Ropewalk: a high crossing over a chasm with a slower lower path. The
     * chasm runs north to south at x 7..8 (the room's midline), one block
     * deep at y 0 with pointed dripstone spikes at the bottom. A narrow
     * bridge of oak planks at y 3 spans the chasm at z 1..14, one block
     * wide at x 7. The fast path is walking the bridge. The slow path is
     * dropping to the floor, bridging the chasm with the two loose blocks
     * provided, and climbing back up on the far side.
     *
     * <p>Two useful solutions: walk the bridge (fast, risk of knockback),
     * or drop down and bridge across (slow, safe). Two of three: the chasm
     * is a hazard (fall onto dripstone), a weapon (knock mobs off the
     * bridge), and the loose blocks are a resource (carry forward as
     * building material).
     *
     * <p>The chasm is one block deep (audit 4.2: no deep pits). The bridge
     * is at y 3 so it is visible from the doorway at eye height (y 2). The
     * doorway lanes (x in 7..8 for z in 1..3 and 12..14) are kept clear at
     * floor level so the player enters on solid ground.
     */
    private static RoomSpec ropewalk() {
        return new RoomSpec("ropewalk", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    BlockState air = Blocks.AIR.defaultBlockState();
                    BlockState spike = Blocks.POINTED_DRIPSTONE.defaultBlockState();
                    BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
                    BlockState stone = Blocks.STONE.defaultBlockState();

                    // Chasm at x 7..8, z 4..11 (the middle of the room),
                    // one block deep. The floor at y 0 is removed and
                    // dripstone spikes placed at y -1 (the sub-floor
                    // bedrock layer, which the template can write).
                    for (int x = 7; x <= 8; x++) {
                        for (int z = 4; z <= 11; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z), air);
                            RoomBuilder.set(level, o.offset(x, -1, z), spike);
                        }
                    }

                    // Narrow bridge at y 3, one block wide at x 7, spanning
                    // the chasm from z 1 to z 14. Visible from the doorway.
                    for (int z = 1; z <= 14; z++) {
                        RoomBuilder.set(level, o.offset(7, 3, z), planks);
                    }

                    // Support pillars for the bridge at each end.
                    RoomBuilder.set(level, o.offset(7, 1, 1), planks);
                    RoomBuilder.set(level, o.offset(7, 2, 1), planks);
                    RoomBuilder.set(level, o.offset(7, 1, 14), planks);
                    RoomBuilder.set(level, o.offset(7, 2, 14), planks);

                    // Two loose stone blocks on the floor for the slow path:
                    // the player drops down, bridges the chasm, and climbs
                    // the far side. Placed clear of the doorway lanes.
                    RoomBuilder.set(level, o.offset(3, 1, 3), stone);
                    RoomBuilder.set(level, o.offset(12, 1, 12), stone);
                });
    }

    // ---- Pair 2.1: Sorting Floor --------------------------------------------

    /**
     * Sorting Floor: a water channel routes a returned item through a hopper
     * filter to a chest, and a comparator opens the iron door. A water source
     * at (2, 1, 8) flows east along z 8. The channel splits at (8, 1, 8): the
     * north branch goes to a dead end, the south branch reaches the filter
     * hopper at (13, 1, 10). The player picks up the key item (a stick on a
     * pedestal at (4, 2, 8)), drops it in the water, and places a loose block
     * at (8, 1, 7) to divert the flow south into the filter. Without the
     * block, the item flows north and is lost (the player picks it up and
     * retries). The filter hopper chains east through the wall to a return
     * chest at (14, 1, 10) past the door (item return rule, audit 2.3).
     *
     * <p>Two useful solutions: divert the water with the loose block (fast),
     * or throw the item over the barrier directly onto the hopper (slow,
     * requires finding the right spot). Two of three: the water is a hazard
     * (the player can be washed into the dead end), a weapon (push mobs into
     * the channel), and a resource (water bucket to carry forward). The key
     * item is a stick, which is also a weak weapon.
     *
     * <p>Doorway readability: the water channel is visible from the west
     * entrance at floor level, and the iron door on the east wall is visible
     * through the bars window. The loose blocks sit beside the channel.
     */
    private static RoomSpec sortingFloor() {
        return new RoomSpec("sorting_floor", EnumSet.of(ENTRANCE, EXIT))
                .decor((level, o) -> {
                    BlockState water = Blocks.WATER.defaultBlockState();
                    BlockState air = Blocks.AIR.defaultBlockState();
                    BlockState stone = Blocks.STONE.defaultBlockState();
                    BlockState fence = Blocks.OAK_FENCE.defaultBlockState();

                    // Iron door on the east wall.
                    placeIronDoor(level, o);

                    // PD-144: the channel is placed as water sources only and every
                    // open side is fenced afterwards, so nothing spills past it. The
                    // source starts at x 4 so the doorway lane (x 0..3, z 7..8) keeps
                    // one open column. Source at (4, 1, 8) running east to the split.
                    java.util.List<BlockPos> channel = new ArrayList<>();
                    for (int x = 4; x <= 8; x++) {
                        channel.add(o.offset(x, 1, 8));
                    }
                    // North branch (dead end): (8, 1, 4) to (8, 1, 7).
                    for (int z = 4; z <= 7; z++) {
                        channel.add(o.offset(8, 1, z));
                    }
                    // South branch: (8, 1, 9) and (8, 1, 10), then east to the
                    // filter hopper at (13, 1, 10).
                    for (int z = 9; z <= 10; z++) {
                        channel.add(o.offset(8, 1, z));
                    }
                    for (int x = 9; x <= 12; x++) {
                        channel.add(o.offset(x, 1, 10));
                    }
                    for (BlockPos pos : channel) {
                        RoomBuilder.set(level, pos, water);
                    }
                    for (BlockPos pos : channel) {
                        for (Direction side : Direction.Plane.HORIZONTAL) {
                            BlockPos next = pos.relative(side);
                            if (level.getBlockState(next).isAir()) {
                                RoomBuilder.set(level, next, fence);
                            }
                        }
                    }

                    // Filter hopper at (13, 1, 10) pointing east. It starts empty:
                    // a hopper pre-loaded with sticks fed the return chest at stamp
                    // and the lock read the room as already solved.
                    placeHopper(level, o.offset(13, 1, 10), Direction.EAST);

                    // Return chest past the door at (14, 1, 10).
                    placeReturnChest(level, o.offset(14, 1, 10));

                    // No comparator: the Lock armed in registerHandlers reads
                    // the chest and opens the door (PD-133).

                    // Key item (stick) on a pedestal at (3, 2, 4).
                    placeSolid(level, o.offset(3, 1, 4));
                    placePot(level, o.offset(3, 2, 4),
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK, 1));

                    // Two loose stone blocks for water diversion.
                    RoomBuilder.set(level, o.offset(6, 1, 6), stone);
                    RoomBuilder.set(level, o.offset(10, 1, 12), stone);
                });
    }

    // ---- Pair 2.2: Sensor Gallery -------------------------------------------

    /**
     * Sensor Gallery: three sculk sensors across the room. When all three
     * are activated by vibrations (player footsteps, mob movement, or a
     * thrown snowball), their combined redstone signal opens the iron door.
     * A zombie spawner provides mobs whose own footsteps can activate the
     * sensors, turning the mob presence against them by opening the door
     * for the player. Wool on a sensor mutes it (prevents activation), a
     * stealth tool that also blocks the gate if used carelessly.
     *
     * <p>Two useful solutions: throw snowballs to activate the sensors from
     * a distance (fast, avoids mobs), or wait for mobs to wander onto the
     * sensors and open the door for you (slow, passive). Two of three: the
     * sensors are a hazard (they detect the player, bringing mob attention),
     * a weapon (mob footsteps activate them, turning mob presence into the
     * gate's key), and wool is a resource (carry forward for stealth).
     *
     * <p>The three sensors are at (4, 1, 8), (8, 1, 8), and (12, 1, 8),
     * evenly spaced across the room. Each emits redstone when activated.
     * The signals combine on a dust line at z 6 running east to the door.
     * A zombie spawner at (8, 1, 4) provides the mobs. Two loose wool blocks
     * at (2, 1, 2) and (13, 1, 13) let the player mute sensors for stealth.
     */
    private static RoomSpec sensorGallery() {
        return new RoomSpec("sensor_gallery", EnumSet.of(ENTRANCE, EXIT))
                .spawner(new BlockPos(8, 1, 4))
                .decor((level, o) -> {
                    BlockState sensor = Blocks.SCULK_SENSOR.defaultBlockState();
                    BlockState wool = Blocks.WOOL.white().defaultBlockState();

                    // Iron door on the east wall.
                    placeIronDoor(level, o);

                    // Three sculk sensors across the room at z 8.
                    int[] sensorX = {4, 8, 12};
                    for (int x : sensorX) {
                        RoomBuilder.set(level, o.offset(x, 1, 8), sensor);
                    }

                    // Dust line at z 6 from x 4 to x 14, combining the three
                    // sensor signals. Each sensor at (x, 1, 8) powers dust at
                    // (x, 1, 7) which feeds into the eastward line at z 6.
                    for (int x : sensorX) {
                        placeDust(level, o.offset(x, 1, 7));
                        placeDust(level, o.offset(x, 1, 6));
                    }
                    for (int x = 4; x <= 12; x++) {
                        placeDust(level, o.offset(x, 1, 6));
                    }
                    // PD-133: the line used to run on to x 14 and end beside
                    // nothing. A repeater at x 13 now drives a wall block at
                    // x 14, beside the door's north leaf, the way Rotation
                    // Lock's gate does. FACING is the input side (PD-99).
                    RoomBuilder.set(level, o.offset(DOOR_X, 1, Z0 - 1), RoomBuilder.WALL);
                    RoomBuilder.set(level, o.offset(DOOR_X - 1, 1, Z0 - 1),
                            Blocks.REPEATER.defaultBlockState()
                                    .setValue(RepeaterBlock.FACING, Direction.WEST));

                    // Two loose wool blocks for muting sensors (stealth).
                    RoomBuilder.set(level, o.offset(2, 1, 2), wool);
                    RoomBuilder.set(level, o.offset(13, 1, 13), wool);
                });
    }

    // ---- Pair 3.1: Kennel Crossing -----------------------------------------

    /**
     * Kennel Crossing: wolves behind a fence gate, a steerable contained
     * hazard. A zombie spawner on the far side of the room provides mobs
     * blocking the exit. The player opens the fence gate to release the
     * wolves, which attack the zombies (wolves target hostile mobs in
     * vanilla). A tool-free bypass runs along the south wall: a narrow
     * path at z 14 behind a one-block-high cobblestone wall that the
     * player can jump over but mobs cannot path around easily.
     *
     * <p>Two useful solutions: open the gate and let the wolves clear the
     * zombies (fast, steerable by positioning), or take the bypass path
     * along the south wall (slow, tool-free, always works). Two of three:
     * the wolves are a hazard (they can turn on the player if provoked),
     * a weapon (they attack the zombies), and a resource (tame them with
     * bones for later rooms).
     *
     * <p>The kennel is a fence enclosure at (6..9, 1, 6..10) with a fence
     * gate at (6, 1, 8) facing east. The wolf spawner is at (8, 1, 8), over a
     * grass floor so wolves can spawn at all (PD-98), and spawns inside the
     * pen (spawn range 1); opening the gate lets them out. The
     * zombie spawner is at (12, 1, 8) on the far side. The bypass path is
     * at z 14, walled by cobblestone at z 13 from x 1 to x 14, with a
     * one-block gap at x 7 for the player to jump over.
     */
    private static RoomSpec kennelCrossing() {
        return new RoomSpec("kennel_crossing", EnumSet.of(ENTRANCE, EXIT))
                .spawner(new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
                    BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, Direction.EAST);
                    BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
                    BlockState air = Blocks.AIR.defaultBlockState();

                    // Kennel enclosure: fences at (6..9, 1, 6..10).
                    for (int x = 6; x <= 9; x++) {
                        RoomBuilder.set(level, o.offset(x, 1, 6), fence);
                        RoomBuilder.set(level, o.offset(x, 1, 10), fence);
                    }
                    // West wall with a gate at the centre.
                    RoomBuilder.set(level, o.offset(6, 1, 7), fence);
                    RoomBuilder.set(level, o.offset(6, 1, 8), gate);
                    RoomBuilder.set(level, o.offset(6, 1, 9), fence);
                    // East wall.
                    RoomBuilder.set(level, o.offset(9, 1, 7), fence);
                    RoomBuilder.set(level, o.offset(9, 1, 8), fence);
                    RoomBuilder.set(level, o.offset(9, 1, 9), fence);

                    // PD-98: the pen floor is grass. A trial spawner runs the
                    // mob's own placement rules, and a wolf only spawns on
                    // WOLVES_SPAWNABLE_ON; on stone brick this spawner never
                    // produced a single wolf. With the spawner's range pulled
                    // in to 1 (the kennel_crossing configs), the pack spawns
                    // inside the pen and the gate is the release.
                    BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
                    for (int x = 7; x <= 8; x++) {
                        for (int z = 7; z <= 9; z++) {
                            RoomBuilder.set(level, o.offset(x, 0, z), grass);
                        }
                    }

                    // PD-145: a wolf also needs light above 8 where it spawns, and
                    // the pen sat at light 6 under the ceiling lamps, so on any
                    // theme the spawner produced nothing. Two light blocks over
                    // the pen (invisible, nothing to break or carry off) fix it.
                    BlockState light = Blocks.LIGHT.defaultBlockState();
                    RoomBuilder.set(level, o.offset(7, 3, 7), light);
                    RoomBuilder.set(level, o.offset(8, 3, 9), light);

                    // Bypass path: cobblestone wall at z 13 from x 1 to x 14,
                    // one block high. The player jumps over it at any point
                    // to take the south bypass. A gap at x 7 makes it obvious.
                    for (int x = 1; x <= 14; x++) {
                        if (x == 7) continue;
                        RoomBuilder.set(level, o.offset(x, 1, 13), cobble);
                    }
                });
    }

    // ---- Pair 3.2: Blaze Cellar --------------------------------------------

    /**
     * Blaze Cellar: a spanY 2 room whose lower story holds a blaze spawner.
     * The player drops through a shaft at (8, z 8) into the lower cellar,
     * where a blaze threatens them. Water or snowballs (the player's
     * advantage: snowballs deal 3 damage to blazes, water puts out their
     * fire and damages them) defeat the blaze. A reward chest sits at
     * (13, -8, 13) in the SE corner. A ladder in the NW corner climbs from
     * the lower floor back to the upper floor, so the return path needs no
     * tool.
     *
     * <p>Two useful solutions: use snowballs (if carried from an earlier
     * room or found in the cellar) to damage the blaze from range, or use
     * a water bucket to douse the blaze and its fire. Two of three: the
     * blaze is a hazard (fire damage), a weapon (its fire can hit other
     * mobs if they follow the player down), and the snowballs are a
     * resource (carry forward for other situations).
     *
     * <p>The drop shaft through the upper floor, filler, and lower ceiling
     * is carved at (8, z 8). PD-78: the return path used to be a solid
     * pillar at (8, z 1) that read as a staircase in this comment but had
     * no steps, so {@link ReturnPathValidator} refused every stamp. It is
     * now a ladder column at (1, z 1) from the lower story's first block
     * (y -7) up through the filler and a hole in the upper floor (y 0),
     * hung on the north wall. The corner keeps the hole clear of every
     * doorway and of the drop shaft.
     */
    private static RoomSpec blazeCellar() {
        return new RoomSpec("blaze_cellar", EnumSet.of(ENTRANCE, EXIT))
                .spanY(2)
                .spawner(new BlockPos(8, -8, 8))
                .chests(new BlockPos(13, -8, 13))
                .decor((level, o) -> {
                    BlockState air = Blocks.AIR.defaultBlockState();
                    BlockState snowballPot = Blocks.DECORATED_POT.defaultBlockState();

                    // Shaft through the upper floor (y 0), filler (y -1, -2),
                    // and lower ceiling (y -3) at (8, z 8).
                    for (int y = 0; y >= -3; y--) {
                        RoomBuilder.set(level, o.offset(8, y, 8), air);
                    }

                    // The way back up: a ladder in the NW corner from the
                    // lower story's first block through the upper floor,
                    // facing south so it hangs on the north wall at z 0.
                    BlockState ladder = Blocks.LADDER.defaultBlockState()
                            .setValue(LadderBlock.FACING, Direction.SOUTH);
                    for (int y = -7; y <= 0; y++) {
                        RoomBuilder.set(level, o.offset(1, y, 1), ladder);
                    }

                    // A pot with snowballs on the upper floor, so the player
                    // has the advantage even without carrying snowballs in.
                    placeSolid(level, o.offset(4, 1, 4));
                    placePot(level, o.offset(4, 2, 4),
                            new ItemStack(Items.SNOWBALL, 8));
                });
    }

    // ---- shared helpers -----------------------------------------------------

    /**
     * Where the iron door stands: one block inside the east doorway plane.
     * PD-133: it used to stand at x 15, in the doorway plane itself, which
     * overwrote the east jigsaws, so the manifest read sorting_floor and
     * sensor_gallery as west-only dead ends and their gates led nowhere.
     */
    private static final int DOOR_X = RoomGeometry.CELL - 2;
    private static final int Z0 = RoomGeometry.DOOR_MIN;
    private static final int Z1 = RoomGeometry.DOOR_MAX;

    /**
     * Places the iron door pair one block inside the east doorway (y 1..2 at
     * z 7..8, x 14) and fills the block above it (y 3) with wall so it cannot
     * be jumped. The doorway plane at x 15 is left alone (Trap 20): its
     * jigsaws are what the manifest reads the east door from.
     */
    private static void placeIronDoor(ServerLevel level, BlockPos o) {
        for (int z = Z0; z <= Z1; z++) {
            DoorHingeSide hinge = z == Z0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.FACING, Direction.WEST)
                    .setValue(DoorBlock.HINGE, hinge)
                    .setValue(DoorBlock.OPEN, false);
            BlockPos bottom = o.offset(DOOR_X, 1, z);
            RoomBuilder.set(level, bottom, lower);
            RoomBuilder.set(level, bottom.above(),
                    lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            RoomBuilder.set(level, o.offset(DOOR_X, 3, z), RoomBuilder.WALL);
        }
    }

    /** A chest facing west (toward the player coming through the east door). */
    private static void placeReturnChest(ServerLevel level, BlockPos pos) {
        RoomBuilder.set(level, pos, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.WEST));
    }

    /** A hopper pointing in {@code facing}. */
    private static void placeHopper(ServerLevel level, BlockPos pos, Direction facing) {
        RoomBuilder.set(level, pos, Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, facing));
    }

    /** Redstone dust. */
    private static void placeDust(ServerLevel level, BlockPos pos) {
        RoomBuilder.set(level, pos, Blocks.REDSTONE_WIRE.defaultBlockState());
    }

    /** A solid block to carry redstone or stand on. */
    private static void placeSolid(ServerLevel level, BlockPos pos) {
        RoomBuilder.set(level, pos, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /** A decorated pot at {@code pos}, optionally holding {@code item}. */
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

}
