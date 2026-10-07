package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The rubble doorway: a door slot plugged with fallen stone
 * ({@link ConnectorType#RUBBLE}) that only a blast clears. A lever-less
 * {@link Ordeal}: the objective is to set off an explosion at the rubble (TNT,
 * or a creeper lured to it), there is no danger, and the resolution is the
 * rubble vanishing.
 *
 * <p><strong>The blast that clears rubble hurts nothing.</strong> When any
 * explosion in the dungeon reaches an armed rubble plug,
 * {@code ServerExplosionMixin} asks {@link #blast}, and on a yes it breaks no
 * blocks and hurts and pushes no entity: the bang and the smoke are the
 * whole effect, and the rubble goes on the next Ordeal tick. An explosion that
 * reaches no rubble is untouched.
 *
 * <p>The planner only plugs a door off the entrance-to-staging spine, at most
 * one per floor ({@link RoomSelector#pickRubbleEdges}), so a plug is always a
 * bonus door the main path does not depend on. The slot is in the wall ring,
 * so the shell rule already keeps pickaxes and stray blasts off the stone.
 *
 * <p><strong>Two kinds.</strong> {@link #KIND} is the doorway plug.
 * {@link #FLOOR} seals a two-story room (2026-10-02): rubble over the upper
 * floor's drop shaft and ladder hole, so the lower story (its reward, and in
 * {@code blaze_cellar} its encounter) opens only to a blast. The planner seals
 * every two-story cell ({@link RoomSelector#pickSealedCells}), since the way
 * through the cell is always the upper story. A sealed lower story's spawners
 * are left out of the floor's
 * clear gate until the seal breaks ({@link #hidesSpawner}), so a sealed room
 * never holds a floor shut. Clearing a floor seal puts back what the rubble
 * replaced, the ladder's top rung included, so the climb out still works.
 */
public final class RubbleOrdeal extends Ordeal<RubbleOrdeal.State> {

    /** The doorway plug. */
    static final RubbleOrdeal KIND = new RubbleOrdeal(false);
    /** The floor seal over a two-story room's way down. */
    static final RubbleOrdeal FLOOR = new RubbleOrdeal(true);

    /** The blocks a rubble pile is made of, mixed per position. */
    static final List<BlockState> RUBBLE = List.of(
            Blocks.COBBLESTONE.defaultBlockState(),
            Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
            Blocks.COBBLED_DEEPSLATE.defaultBlockState(),
            Blocks.TUFF.defaultBlockState());

    /** How far past an explosion's radius a rubble block still counts as reached. */
    private static final double REACH = 1.5;

    /** One plug: its blocks, what each replaced, its cell, and whether a blast has reached it. */
    static final class State {
        final List<BlockPos> rubble;
        final Map<BlockPos, BlockState> replaced;
        final BlockPos cellOrigin;
        boolean blasted;

        State(List<BlockPos> rubble, Map<BlockPos, BlockState> replaced, BlockPos cellOrigin) {
            this.rubble = List.copyOf(rubble);
            this.replaced = Map.copyOf(replaced);
            this.cellOrigin = cellOrigin.immutable();
        }
    }

    /** Whether this kind is the floor seal rather than the doorway plug. */
    private final boolean floor;

    private RubbleOrdeal(boolean floor) {
        super(floor ? "rubble_floor" : "rubble", 10,
                floor ? "Break through the rubble in the floor to the story below"
                        : "Clear the rubble from the doorway",
                floor ? "None; the story below is sealed" : "None; the way on is blocked",
                "Set off an explosion at the rubble: TNT, or a creeper");
        this.floor = floor;
    }

    /**
     * Seals a two-story room's way down: every upper-floor block (y 0) over an
     * open shaft or a ladder hole (air or ladder at y 0 and at y -1) becomes
     * rubble. Rotation agnostic: the holes are found, not assumed. Runs after
     * the stamp's return-path check, which reads the room as built.
     */
    static void sealFloor(ServerLevel level, BlockPos cellOrigin) {
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                BlockPos top = cellOrigin.offset(x, 0, z);
                if (passable(level.getBlockState(top)) && passable(level.getBlockState(top.below()))) {
                    level.setBlock(top, rubbleAt(top), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static boolean passable(BlockState state) {
        return state.isAir() || state.is(Blocks.LADDER);
    }

    /** The rubble block for {@code pos}, the same one every time for the same position. */
    static BlockState rubbleAt(BlockPos pos) {
        int pick = Math.floorMod(Long.hashCode(pos.asLong() * 0x9E3779B97F4A7C15L), RUBBLE.size());
        return RUBBLE.get(pick);
    }

    static boolean isRubble(BlockState state) {
        for (BlockState rubble : RUBBLE) {
            if (state.is(rubble.getBlock())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The doorway kind scans the cell's four door slots for rubble; the floor
     * kind scans the upper floor for rubble over a shaft, remembering a ladder
     * under it so the hole gets its top rung back. Rotation agnostic, like
     * every Ordeal.
     */
    @Override
    State arm(ServerLevel level, BlockPos cellOrigin) {
        List<BlockPos> found = new ArrayList<>();
        Map<BlockPos, BlockState> replaced = new HashMap<>();
        if (floor) {
            for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
                for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                    BlockPos top = cellOrigin.offset(x, 0, z);
                    BlockState below = level.getBlockState(top.below());
                    if (isRubble(level.getBlockState(top)) && passable(below)) {
                        found.add(top.immutable());
                        if (below.is(Blocks.LADDER)) {
                            replaced.put(top.immutable(), below);
                        }
                    }
                }
            }
            return found.isEmpty() ? null : new State(found, replaced, cellOrigin);
        }
        for (DoorMask.Direction wall : DoorMask.Direction.values()) {
            for (int i = RoomGeometry.DOOR_MIN; i <= RoomGeometry.DOOR_MAX; i++) {
                for (int y = 1; y <= RoomGeometry.DOOR_HEIGHT; y++) {
                    BlockPos pos = ConnectorGeometry.wallPos(cellOrigin, wall, i, y);
                    if (isRubble(level.getBlockState(pos))) {
                        found.add(pos.immutable());
                    }
                }
            }
        }
        return found.isEmpty() ? null : new State(found, replaced, cellOrigin);
    }

    /** Stale once none of the rubble is left and no blast explains it: the cell was cleared or restamped. */
    @Override
    boolean stale(ServerLevel level, State state) {
        if (state.blasted) {
            return false;
        }
        for (BlockPos pos : state.rubble) {
            if (isRubble(level.getBlockState(pos))) {
                return false;
            }
        }
        return true;
    }

    @Override
    boolean objectiveMet(State state) {
        return state.blasted;
    }

    @Override
    String resolve(ServerLevel level, BlockPos cellOrigin, State state) {
        for (BlockPos pos : state.rubble) {
            if (isRubble(level.getBlockState(pos))) {
                level.setBlock(pos, state.replaced.getOrDefault(pos, Blocks.AIR.defaultBlockState()),
                        Block.UPDATE_ALL);
                level.sendParticles(ParticleTypes.POOF, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        6, 0.3, 0.3, 0.3, 0.02);
            }
        }
        return floor ? "The floor gives way: the story below is open." : "The rubble gives way.";
    }

    /**
     * An explosion went off at {@code center} with {@code radius} in
     * {@code level}. Marks every armed plug it reaches as blasted and returns
     * whether there was one: the caller then breaks nothing and hurts nobody.
     */
    public static boolean blast(ServerLevel level, Vec3 center, float radius) {
        double reach = radius + REACH;
        Predicate<State> reached = state -> {
            for (BlockPos pos : state.rubble) {
                if (center.distanceTo(Vec3.atCenterOf(pos)) <= reach) {
                    state.blasted = true;
                    return true;
                }
            }
            return false;
        };
        boolean door = Ordeals.anyUnresolved(KIND, level, reached);
        boolean seal = Ordeals.anyUnresolved(FLOOR, level, reached);
        return door || seal;
    }

    /** Whether {@code pos} is part of an armed, uncleared plug or seal, for the mining hint. */
    static boolean isArmedRubble(ServerLevel level, BlockPos pos) {
        return Ordeals.anyUnresolved(KIND, level, state -> state.rubble.contains(pos))
                || Ordeals.anyUnresolved(FLOOR, level, state -> state.rubble.contains(pos));
    }

    /**
     * Whether the trial spawner at {@code pos} is under an unbroken floor seal:
     * inside a sealed cell's footprint and below its upper floor. Such a
     * spawner is left out of the floor's clear gate until the seal breaks.
     */
    static boolean hidesSpawner(ServerLevel level, BlockPos pos) {
        return Ordeals.anyUnresolved(FLOOR, level, state -> {
            BlockPos o = state.cellOrigin;
            return pos.getY() < o.getY()
                    && pos.getX() >= o.getX() && pos.getX() < o.getX() + RoomGeometry.CELL
                    && pos.getZ() >= o.getZ() && pos.getZ() < o.getZ() + RoomGeometry.CELL;
        });
    }
}
