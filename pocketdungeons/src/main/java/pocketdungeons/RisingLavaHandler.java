package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drives the Rising Lava room's trash-compactor hazard.
 *
 * <p>The template places a deepslate trench floor and a lever at the exit.
 * While players are in the room, lava spreads inward from both side walls
 * toward the center on a timer. Pulling the lever drains all lava and marks
 * the room solved. When no players remain, the lava recedes from the center
 * outward until the room is dry.
 *
 * <p>The spread axis is perpendicular to the through-route. The handler
 * detects the axis at stamp time from the lever's position: if the lever is
 * on an east or west wall, the through-route runs along x and lava spreads
 * along z. If the lever is on a north or south wall, the through-route runs
 * along z and lava spreads along x.
 *
 * <p>Lava is placed at y=1 (on top of the floor) as static source blocks
 * with no neighbor updates, so it does not flow. This gives precise control
 * over the spread and prevents lava from leaking through doorways.
 *
 * <p>Follows the same pattern as {@link Locks} and
 * {@link CollapsingBridgeHandler}: arm at stamp time, evaluate on a slow
 * tick, clear on teardown.
 */
final class RisingLavaHandler {

    private static final int STAMP_FLAGS = net.minecraft.world.level.block.Block.UPDATE_CLIENTS
            | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS;

    /** Ticks between each spread step (1 second). */
    private static final int SPREAD_INTERVAL = 20;
    /** Ticks between each recede step (0.5 seconds). */
    private static final int RECEDE_INTERVAL = 10;
    /** Ticks between evaluations. */
    private static final int PERIOD = 2;
    /** Maximum spread: 7 rows from each side covers the full 14-block interior. */
    private static final int MAX_SPREAD = 7;

    private record LavaRoom(ServerLevel level, BlockPos origin, BlockPos lever,
                            boolean alongX, int spread, int timer, boolean solved) {
        LavaRoom with(int spread, int timer, boolean solved) {
            return new LavaRoom(level, origin, lever, alongX, spread, timer, solved);
        }
    }

    private static final Map<BlockPos, LavaRoom> ACTIVE = new LinkedHashMap<>();

    private RisingLavaHandler() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERIOD != 0 || ACTIVE.isEmpty()) {
                return;
            }
            tick();
        });
    }

    /**
     * Arms the lava hazard at {@code cellOrigin} by scanning for the lever
     * and determining the spread axis from its position. Rotation-agnostic:
     * the scan reads the world, not authored coordinates.
     */
    static void arm(ServerLevel level, BlockPos cellOrigin) {
        BlockPos lever = null;
        outer:
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = cellOrigin.offset(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.LEVER)) {
                        lever = pos.immutable();
                        break outer;
                    }
                }
            }
        }
        if (lever == null) {
            PocketDungeonsMod.LOG.warn("Rising Lava at {} found no lever", cellOrigin.toShortString());
            return;
        }
        int lx = lever.getX() - cellOrigin.getX();
        boolean alongX = (lx <= 1 || lx >= RoomGeometry.CELL - 2);
        ACTIVE.put(cellOrigin.immutable(),
                new LavaRoom(level, cellOrigin.immutable(), lever, alongX, 0, 0, false));
    }

    static void clear(BlockPos cellOrigin) {
        ACTIVE.remove(cellOrigin);
    }

    /** Whether the cell still has an armed lava hazard. For tests and teardown. */
    static boolean isArmed(BlockPos cellOrigin) {
        return ACTIVE.containsKey(cellOrigin);
    }

    private static void tick() {
        ACTIVE.entrySet().removeIf(entry -> {
            LavaRoom room = entry.getValue();
            if (stale(room)) {
                return true;
            }
            if (room.solved()) {
                return true;
            }
            BlockState leverState = room.level().getBlockState(room.lever());
            boolean leverPulled = leverState.is(Blocks.LEVER)
                    && leverState.getValue(BlockStateProperties.POWERED);
            if (leverPulled) {
                drainAll(room);
                room.level().playSound(null, room.lever(),
                        SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.5f, 0.5f);
                return true;
            }
            boolean playersPresent = hasPlayers(room);
            int timer = room.timer() + PERIOD;
            int spread = room.spread();
            if (playersPresent) {
                if (timer >= SPREAD_INTERVAL && spread < MAX_SPREAD) {
                    spread++;
                    placeLavaRow(room, spread);
                    timer = 0;
                }
            } else {
                if (timer >= RECEDE_INTERVAL && spread > 0) {
                    removeLavaRow(room, spread);
                    spread--;
                    timer = 0;
                }
            }
            ACTIVE.put(entry.getKey(), room.with(spread, timer, false));
            return false;
        });
    }

    private static boolean stale(LavaRoom room) {
        return !room.level().getBlockState(room.lever()).is(Blocks.LEVER);
    }

    private static boolean hasPlayers(LavaRoom room) {
        BlockPos o = room.origin();
        AABB bounds = new AABB(
                o.getX() + 1, o.getY() + 1, o.getZ() + 1,
                o.getX() + RoomGeometry.CELL - 1, o.getY() + RoomGeometry.CEILING_Y + 1,
                o.getZ() + RoomGeometry.CELL - 1);
        return !room.level().getPlayers(p -> p.getBoundingBox().intersects(bounds)).isEmpty();
    }

    private static void placeLavaRow(LavaRoom room, int spread) {
        BlockPos o = room.origin();
        ServerLevel level = room.level();
        if (room.alongX()) {
            for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
                placeLava(level, o.offset(x, 1, spread));
                placeLava(level, o.offset(x, 1, RoomGeometry.CELL - 1 - spread));
            }
        } else {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                placeLava(level, o.offset(spread, 1, z));
                placeLava(level, o.offset(RoomGeometry.CELL - 1 - spread, 1, z));
            }
        }
        level.playSound(null, o.offset(8, 1, 8),
                SoundEvents.LAVA_POP, SoundSource.BLOCKS, 0.3f, 0.5f);
    }

    private static void removeLavaRow(LavaRoom room, int spread) {
        BlockPos o = room.origin();
        ServerLevel level = room.level();
        if (room.alongX()) {
            for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
                removeLava(level, o.offset(x, 1, spread));
                removeLava(level, o.offset(x, 1, RoomGeometry.CELL - 1 - spread));
            }
        } else {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                removeLava(level, o.offset(spread, 1, z));
                removeLava(level, o.offset(RoomGeometry.CELL - 1 - spread, 1, z));
            }
        }
    }

    private static void drainAll(LavaRoom room) {
        BlockPos o = room.origin();
        ServerLevel level = room.level();
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                removeLava(level, o.offset(x, 1, z));
            }
        }
    }

    private static void placeLava(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.LAVA.defaultBlockState(), STAMP_FLAGS);
        }
    }

    private static void removeLava(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).is(Blocks.LAVA)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), STAMP_FLAGS);
        }
    }
}
