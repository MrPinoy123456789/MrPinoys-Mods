package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;

/**
 * The Rising Lava Ordeal, a trash compactor.
 *
 * <ul>
 *   <li>Objective: reach the lever at the exit.</li>
 *   <li>Danger: while players are in the room, lava spreads inward from both
 *       side walls toward the centre on a timer; when nobody is in it, the
 *       lava recedes from the centre outward until the room is dry.</li>
 *   <li>Resolution: the lever drains every block of lava for good.</li>
 * </ul>
 *
 * <p>The spread axis is perpendicular to the through-route, read at arm time
 * from where the lever is: a lever on an east or west wall means the route
 * runs along x and lava spreads along z; on a north or south wall, the other
 * way round. The lever and its lamp are placed at runtime on the exit side
 * ({@code LayoutStamper.applyDirectionalGates}), because the layout can turn
 * the room round and a baked lever would then sit at the entrance.
 *
 * <p>Lava is placed at y=1 (on top of the floor) as static source blocks
 * with no neighbour updates, so it does not flow. This gives precise control
 * over the spread and keeps lava from leaking through doorways.
 *
 * <p>Ordeals migration (2026-09-30): until then the room's situation handler
 * never armed this, so the lava never spread in a real run (PD-91).
 */
final class RisingLavaOrdeal extends Ordeal<RisingLavaOrdeal.LavaRoom> {

    static final RisingLavaOrdeal INSTANCE = new RisingLavaOrdeal();

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

    record LavaRoom(BlockPos lever, boolean alongX, int spread, int timer) {
        LavaRoom with(int spread, int timer) {
            return new LavaRoom(lever, alongX, spread, timer);
        }
    }

    private RisingLavaOrdeal() {
        super("rising_lava", PERIOD, "reach the lever at the exit",
                "lava closing in from both side walls", "the lever drains the lava");
    }

    @Override
    LavaRoom arm(ServerLevel level, BlockPos cellOrigin) {
        BlockPos lever = Ordeals.findLever(level, cellOrigin);
        if (lever == null) {
            return null;
        }
        int lx = lever.getX() - cellOrigin.getX();
        boolean alongX = (lx <= 1 || lx >= RoomGeometry.CELL - 2);
        return new LavaRoom(lever, alongX, 0, 0);
    }

    @Override
    boolean stale(ServerLevel level, LavaRoom room) {
        return !level.getBlockState(room.lever()).is(Blocks.LEVER);
    }

    @Override
    BlockPos lever(LavaRoom room) {
        return room.lever();
    }

    @Override
    LavaRoom tickDanger(ServerLevel level, BlockPos origin, LavaRoom room) {
        boolean playersPresent = !Ordeals.playersIn(level, origin).isEmpty();
        int timer = room.timer() + PERIOD;
        int spread = room.spread();
        if (playersPresent) {
            if (timer >= SPREAD_INTERVAL && spread < MAX_SPREAD) {
                spread++;
                placeLavaRow(level, origin, room.alongX(), spread);
                timer = 0;
            }
        } else {
            if (timer >= RECEDE_INTERVAL && spread > 0) {
                removeLavaRow(level, origin, room.alongX(), spread);
                spread--;
                timer = 0;
            }
        }
        return room.with(spread, timer);
    }

    @Override
    String resolve(ServerLevel level, BlockPos origin, LavaRoom room) {
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                removeLava(level, origin.offset(x, 1, z));
            }
        }
        return "The lava drains away.";
    }

    private static void placeLavaRow(ServerLevel level, BlockPos o, boolean alongX, int spread) {
        if (alongX) {
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

    private static void removeLavaRow(ServerLevel level, BlockPos o, boolean alongX, int spread) {
        if (alongX) {
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
