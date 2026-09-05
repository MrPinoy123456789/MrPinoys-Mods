package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the Collapsing Bridge room's piston segments.
 *
 * <p>The template places sticky pistons in the extended state with oak planks
 * forming the bridge. Observers cannot detect a player standing on a block
 * (they detect block state changes, not entities), so the collapse is driven
 * here in code: each tick, for every armed bridge segment, check whether a
 * player's bounding box overlaps the plank position. If so, start a collapse
 * timer. When the timer fires, retract the pistons (set EXTENDED=false, remove
 * the piston heads, move the planks back to the retracted position). After a
 * re-extend delay, reverse the operation so the player can backtrack.
 *
 * <p>Follows the same pattern as {@link Locks}: arm at stamp time, scan once
 * for the blocks (rotation-agnostic), evaluate on a slow tick, clear on
 * teardown.
 */
final class CollapsingBridgeHandler {

    private static final int STAMP_FLAGS = net.minecraft.world.level.block.Block.UPDATE_CLIENTS
            | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS;

    /** Ticks the player must stand on a segment before it collapses. */
    private static final int COLLAPSE_DELAY = 30;
    /** Ticks before a collapsed segment re-extends. */
    private static final int REEXTEND_DELAY = 60;
    /** Ticks between evaluations. */
    private static final int PERIOD = 2;

    /** One segment: a pair of pistons and their planks, plus a collapse timer. */
    private record Segment(BlockPos pistonA, BlockPos headA, BlockPos plankA,
                           BlockPos pistonB, BlockPos headB, BlockPos plankB,
                           int collapseTimer, int reextendTimer, boolean collapsed) {
        Segment withTimers(int collapse, int reextend, boolean collapsed) {
            return new Segment(pistonA, headA, plankA, pistonB, headB, plankB,
                    collapse, reextend, collapsed);
        }
    }

    /** One cell's bridge: the level it lives in and its segments. */
    private record Bridge(ServerLevel level, List<Segment> segments) {}

    /** Keyed by cell origin: one bridge per collapsing bridge cell. */
    private static final Map<BlockPos, Bridge> ACTIVE = new LinkedHashMap<>();

    private CollapsingBridgeHandler() {}

    /** Wires the evaluation tick. Call once from onInitialize. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERIOD != 0 || ACTIVE.isEmpty()) {
                return;
            }
            tick();
        });
    }

    /**
     * Arms the bridge at {@code cellOrigin} by scanning for sticky pistons
     * and pairing them into segments. Each piston faces toward its partner;
     * the plank is two blocks in the facing direction from the piston (past
     * the head). Rotation-agnostic: the scan reads the world, not authored
     * coordinates.
     */
    static void arm(ServerLevel level, BlockPos cellOrigin) {
        List<BlockPos> pistons = new ArrayList<>();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = cellOrigin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.STICKY_PISTON)) {
                        pistons.add(pos.immutable());
                    }
                }
            }
        }
        if (pistons.isEmpty()) {
            return;
        }
        // Pair pistons that face each other. The head is one block in the
        // facing direction; the plank is one more block beyond that.
        List<Segment> segments = new ArrayList<>();
        List<BlockPos> unpaired = new ArrayList<>(pistons);
        while (!unpaired.isEmpty()) {
            BlockPos pa = unpaired.removeLast();
            Direction facingA = level.getBlockState(pa).getValue(PistonBaseBlock.FACING);
            BlockPos headA = pa.relative(facingA);
            BlockPos plankA = headA.relative(facingA);
            // Find the opposing piston: faces back toward pa.
            BlockPos pb = null;
            for (int i = 0; i < unpaired.size(); i++) {
                BlockPos candidate = unpaired.get(i);
                Direction facingC = level.getBlockState(candidate).getValue(PistonBaseBlock.FACING);
                if (facingC == facingA.getOpposite()) {
                    pb = candidate;
                    unpaired.remove(i);
                    break;
                }
            }
            if (pb == null) {
                continue;
            }
            Direction facingB = level.getBlockState(pb).getValue(PistonBaseBlock.FACING);
            BlockPos headB = pb.relative(facingB);
            BlockPos plankB = headB.relative(facingB);
            segments.add(new Segment(pa, headA, plankA, pb, headB, plankB, 0, 0, false));
        }
        if (!segments.isEmpty()) {
            ACTIVE.put(cellOrigin.immutable(), new Bridge(level, segments));
        }
    }

    /** Drops one cell's bridge. Called from teardown. */
    static void clear(BlockPos cellOrigin) {
        ACTIVE.remove(cellOrigin);
    }

    /** Whether the cell still has an armed bridge. For tests and teardown. */
    static boolean isArmed(BlockPos cellOrigin) {
        return ACTIVE.containsKey(cellOrigin);
    }

    private static void tick() {
        ACTIVE.entrySet().removeIf(entry -> {
            Bridge bridge = entry.getValue();
            List<Segment> segments = bridge.segments();
            if (segments.isEmpty() || stale(bridge)) {
                return true;
            }
            List<Segment> updated = new ArrayList<>(segments.size());
            for (Segment seg : segments) {
                updated.add(evaluate(bridge.level(), seg));
            }
            ACTIVE.put(entry.getKey(), new Bridge(bridge.level(), updated));
            return false;
        });
    }

    /** Whether the cell has been torn down. No piston left means no cell left. */
    private static boolean stale(Bridge bridge) {
        Segment first = bridge.segments().getFirst();
        return !bridge.level().getBlockState(first.pistonA()).is(Blocks.STICKY_PISTON);
    }

    private static Segment evaluate(ServerLevel level, Segment seg) {
        if (seg.collapsed()) {
            int timer = seg.reextendTimer() - 1;
            if (timer <= 0) {
                reextend(level, seg);
                return seg.withTimers(0, 0, false);
            }
            return seg.withTimers(0, timer, true);
        }
        // Extended: check if a player is standing on either plank.
        boolean playerOn = playerStandingOn(level, seg.plankA())
                || playerStandingOn(level, seg.plankB());
        int timer = playerOn ? seg.collapseTimer() + 1 : Math.max(0, seg.collapseTimer() - 1);
        if (timer >= COLLAPSE_DELAY) {
            collapse(level, seg);
            return seg.withTimers(0, REEXTEND_DELAY, true);
        }
        return seg.withTimers(timer, 0, false);
    }

    private static boolean playerStandingOn(ServerLevel level, BlockPos plankPos) {
        // The player stands on top of the plank: feet at plankPos.y + 1.
        AABB box = new AABB(
                plankPos.getX(), plankPos.getY() + 1, plankPos.getZ(),
                plankPos.getX() + 1, plankPos.getY() + 3, plankPos.getZ() + 1);
        return !level.getPlayers(p -> p.getBoundingBox().intersects(box)).isEmpty();
    }

    private static void collapse(ServerLevel level, Segment seg) {
        // Retract pistons: set EXTENDED=false, remove heads, move planks to
        // the head position (retracted, on the piston), clear the bridge.
        setPistonExtended(level, seg.pistonA(), false);
        setPistonExtended(level, seg.pistonB(), false);
        level.setBlock(seg.headA(), Blocks.AIR.defaultBlockState(), STAMP_FLAGS);
        level.setBlock(seg.headB(), Blocks.AIR.defaultBlockState(), STAMP_FLAGS);
        level.setBlock(seg.plankA(), Blocks.AIR.defaultBlockState(), STAMP_FLAGS);
        level.setBlock(seg.plankB(), Blocks.AIR.defaultBlockState(), STAMP_FLAGS);
        level.setBlock(seg.headA(), Blocks.OAK_PLANKS.defaultBlockState(), STAMP_FLAGS);
        level.setBlock(seg.headB(), Blocks.OAK_PLANKS.defaultBlockState(), STAMP_FLAGS);
        level.playSound(null, seg.plankA(), SoundEvents.PISTON_CONTRACT,
                SoundSource.BLOCKS, 0.5f, 0.5f);
    }

    private static void reextend(ServerLevel level, Segment seg) {
        // Re-extend: place heads, move planks back to bridge position, set
        // EXTENDED=true, clear the retracted plank position.
        Direction facingA = level.getBlockState(seg.pistonA()).getValue(PistonBaseBlock.FACING);
        Direction facingB = level.getBlockState(seg.pistonB()).getValue(PistonBaseBlock.FACING);
        level.setBlock(seg.headA(), Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, facingA)
                .setValue(PistonHeadBlock.TYPE, PistonType.STICKY), STAMP_FLAGS);
        level.setBlock(seg.headB(), Blocks.PISTON_HEAD.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, facingB)
                .setValue(PistonHeadBlock.TYPE, PistonType.STICKY), STAMP_FLAGS);
        level.setBlock(seg.plankA(), Blocks.OAK_PLANKS.defaultBlockState(), STAMP_FLAGS);
        level.setBlock(seg.plankB(), Blocks.OAK_PLANKS.defaultBlockState(), STAMP_FLAGS);
        setPistonExtended(level, seg.pistonA(), true);
        setPistonExtended(level, seg.pistonB(), true);
        level.playSound(null, seg.plankA(), SoundEvents.PISTON_EXTEND,
                SoundSource.BLOCKS, 0.5f, 0.5f);
    }

    private static void setPistonExtended(ServerLevel level, BlockPos pos, boolean extended) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.STICKY_PISTON) && state.getValue(PistonBaseBlock.EXTENDED) != extended) {
            level.setBlock(pos, state.setValue(PistonBaseBlock.EXTENDED, extended), STAMP_FLAGS);
        }
    }
}
