package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Collapsing Bridge Ordeal.
 *
 * <ul>
 *   <li>Objective: cross to the far side.</li>
 *   <li>Danger: each plank segment drops away shortly after a player stands on it.</li>
 *   <li>Resolution: the lever on the far side locks every segment out, a
 *       permanent bridge for the way back and for the rest of the party.</li>
 * </ul>
 *
 * <p>The lever and its lamp are placed at runtime on the exit side
 * ({@code LayoutStamper.applyDirectionalGates}), because the layout can turn
 * the room round and a baked lever would then sit at the entrance. Armed from
 * there too, after the lever exists.
 *
 * <p>The template places sticky pistons with oak planks forming the bridge.
 * If the template was captured while retracting, the block positions may be
 * {@code moving_piston} states; {@link #arm} normalises those back to sticky
 * pistons before it scans. Observers cannot detect a player standing on a block
 * (they detect block state changes, not entities), so the collapse is driven
 * here in code: each tick, for every armed bridge segment, check whether a
 * player's bounding box overlaps the plank position. If so, start a collapse
 * timer. When the timer fires, retract the pistons (set EXTENDED=false, remove
 * the piston heads, move the planks back to the retracted position). After a
 * re-extend delay, reverse the operation so the player can backtrack.
 *
 * <p>{@link Ordeals} runs it: arm at stamp time, scan once for the blocks
 * (rotation-agnostic), evaluate on a slow tick, clear on teardown.
 */
final class CollapsingBridgeOrdeal extends Ordeal<CollapsingBridgeOrdeal.Bridge> {

    static final CollapsingBridgeOrdeal INSTANCE = new CollapsingBridgeOrdeal();

    /**
     * PD-147: crimson, not oak. The bridge hangs over a lava floor, and lava
     * lights oak; the first party through found it burnt through.
     */
    private static final BlockState PLANK = Blocks.CRIMSON_PLANKS.defaultBlockState();

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

    /** One cell's bridge: its segments and the far-side lever, if placed. */
    record Bridge(List<Segment> segments, BlockPos lever) {}

    private CollapsingBridgeOrdeal() {
        super("collapsing_bridge", PERIOD, "cross to the far side",
                "the planks drop away under you", "the far-side lever locks the bridge in place");
    }

    /**
     * Arms the bridge at {@code cellOrigin} by normalising any moving-piston
     * blocks, scanning for sticky pistons and pairing them into segments. Each
     * piston faces toward its partner; the plank is two blocks in the facing
     * direction from the piston (past the head). Rotation-agnostic: the scan
     * reads the world, not authored coordinates.
     */
    @Override
    Bridge arm(ServerLevel level, BlockPos cellOrigin) {
        normaliseMovingPistons(level, cellOrigin);
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
            return null;
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
        if (segments.isEmpty()) {
            return null;
        }
        for (Segment seg : segments) {
            // PD-147: an unpowered extended piston retracts on the next
            // neighbour update (lava, a placed block), pulling its plank, so the
            // bridge arrived part collapsed. A redstone block under each base
            // keeps it extended; the collapse is code, not the piston.
            level.setBlock(seg.pistonA().below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), STAMP_FLAGS);
            level.setBlock(seg.pistonB().below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), STAMP_FLAGS);
            reextend(level, seg);
        }
        return new Bridge(List.copyOf(segments), Ordeals.findLever(level, cellOrigin));
    }

    /**
     * PD-123: a template captured while the bridge is retracting stores
     * {@code moving_piston} blocks instead of sticky pistons. Turn the piston
     * bases back into retracted sticky pistons and clear the moved blocks so the
     * bridge can be re-extended cleanly.
     *
     * <p>This must not rely on already-converted neighbours, so every position
     * is classified against the original set of moving_piston blocks before any
     * block is changed.
     */
    private static void normaliseMovingPistons(ServerLevel level, BlockPos cellOrigin) {
        List<MovingPiston> moving = new ArrayList<>();
        Set<BlockPos> movingSet = new HashSet<>();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = cellOrigin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.MOVING_PISTON)) {
                        Direction facing = state.getValue(BlockStateProperties.FACING);
                        moving.add(new MovingPiston(pos.immutable(), facing));
                        movingSet.add(pos.immutable());
                    }
                }
            }
        }
        for (MovingPiston mp : moving) {
            BlockPos behind = mp.pos.relative(mp.facing.getOpposite());
            boolean isBase = !movingSet.contains(behind);
            if (isBase) {
                level.setBlock(mp.pos, Blocks.STICKY_PISTON.defaultBlockState()
                        .setValue(PistonBaseBlock.FACING, mp.facing)
                        .setValue(PistonBaseBlock.EXTENDED, false), STAMP_FLAGS);
            } else {
                level.setBlock(mp.pos, Blocks.AIR.defaultBlockState(), STAMP_FLAGS);
            }
        }
    }

    private record MovingPiston(BlockPos pos, Direction facing) {}

    @Override
    BlockPos lever(Bridge bridge) {
        return bridge.lever();
    }

    @Override
    Bridge tickDanger(ServerLevel level, BlockPos cellOrigin, Bridge bridge) {
        List<Segment> updated = new ArrayList<>(bridge.segments().size());
        for (Segment seg : bridge.segments()) {
            updated.add(evaluate(level, seg));
        }
        return new Bridge(updated, bridge.lever());
    }

    /** Whether the cell has been torn down. No piston left means no cell left. */
    @Override
    boolean stale(ServerLevel level, Bridge bridge) {
        Segment first = bridge.segments().getFirst();
        return !level.getBlockState(first.pistonA()).is(Blocks.STICKY_PISTON);
    }

    /** Every dropped segment comes back, and nothing drops again: the tick stops with the Ordeal. */
    @Override
    String resolve(ServerLevel level, BlockPos cellOrigin, Bridge bridge) {
        for (Segment seg : bridge.segments()) {
            if (seg.collapsed()) {
                reextend(level, seg);
            }
        }
        return "The bridge locks in place.";
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
        level.setBlock(seg.headA(), PLANK, STAMP_FLAGS);
        level.setBlock(seg.headB(), PLANK, STAMP_FLAGS);
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
        level.setBlock(seg.plankA(), PLANK, STAMP_FLAGS);
        level.setBlock(seg.plankB(), PLANK, STAMP_FLAGS);
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
