package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PD-67: drives Hold the Plate in code. Stand on the plate for
 * {@link #HOLD_SECONDS} seconds and the exit's iron door opens; step off and
 * the count resets.
 *
 * <p>The room used to carry a hopper clock, a comparator and a repeater meant
 * to open the door, but the diodes faced the wrong way, the plate was wired to
 * nothing and the clock had no lock, so it never worked. A redstone circuit
 * also cannot tell a player from a mob on the plate or show progress. The
 * template now carries only the plate; the door is placed at runtime by
 * {@link LayoutStamper} on the exit side (PD-66), so this handler finds it by
 * scanning the cell when the count completes.
 *
 * <p>Same shape as {@link CollapsingBridgeHandler}: arm at stamp time, a slow
 * tick, dropped on teardown or once the cell is gone.
 */
final class HoldThePlateHandler {

    /** Seconds a player must stand on the plate without stepping off. */
    static final int HOLD_SECONDS = 30;
    /** Ticks between evaluations. */
    private static final int PERIOD = 10;
    private static final int HOLD_TICKS = HOLD_SECONDS * 20;

    /** One armed room: its plate and how long a player has held it. */
    private record Plate(ServerLevel level, BlockPos plate, BlockPos cellOrigin, int heldTicks) {
        Plate withHeld(int ticks) {
            return new Plate(level, plate, cellOrigin, ticks);
        }
    }

    /** Keyed by cell origin: one plate per Hold the Plate cell. */
    private static final Map<BlockPos, Plate> ACTIVE = new LinkedHashMap<>();

    private HoldThePlateHandler() {}

    /** Wires the evaluation tick. Call once from {@link TrialContent#warmUp()}. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERIOD != 0 || ACTIVE.isEmpty()) {
                return;
            }
            tick();
        });
    }

    /** Arms the cell at {@code cellOrigin}: finds its stone pressure plate, rotation-agnostic. */
    static void arm(ServerLevel level, BlockPos cellOrigin) {
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                BlockPos pos = cellOrigin.offset(x, 1, z);
                if (level.getBlockState(pos).is(Blocks.STONE_PRESSURE_PLATE)) {
                    ACTIVE.put(cellOrigin.immutable(), new Plate(level, pos.immutable(), cellOrigin.immutable(), 0));
                    return;
                }
            }
        }
    }

    /** Drops one cell's plate. Called from teardown. */
    static void clear(BlockPos cellOrigin) {
        ACTIVE.remove(cellOrigin);
    }

    private static void tick() {
        ACTIVE.entrySet().removeIf(entry -> {
            Plate plate = entry.getValue();
            if (!plate.level().getBlockState(plate.plate()).is(Blocks.STONE_PRESSURE_PLATE)) {
                return true; // the cell is gone
            }
            List<ServerPlayer> on = playersOn(plate);
            if (on.isEmpty()) {
                if (plate.heldTicks() > 0) {
                    ACTIVE.put(entry.getKey(), plate.withHeld(0));
                }
                return false;
            }
            int held = plate.heldTicks() + PERIOD;
            if (held >= HOLD_TICKS) {
                openExit(plate, on);
                return true; // done: the door stays open
            }
            int left = (HOLD_TICKS - held + 19) / 20;
            Component line = Component.literal("Hold the plate: " + left + "s until the door opens")
                    .withStyle(ChatFormatting.GOLD);
            for (ServerPlayer player : on) {
                player.sendOverlayMessage(line);
            }
            if (held % 100 == 0) {
                plate.level().playSound(null, plate.plate(), SoundEvents.STONE_PRESSURE_PLATE_CLICK_ON,
                        SoundSource.BLOCKS, 0.6f, 0.8f + 0.4f * held / HOLD_TICKS);
            }
            ACTIVE.put(entry.getKey(), plate.withHeld(held));
            return false;
        });
    }

    private static List<ServerPlayer> playersOn(Plate plate) {
        BlockPos p = plate.plate();
        AABB box = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 0.5, p.getZ() + 1);
        return plate.level().getPlayers(player -> !player.isSpectator() && player.getBoundingBox().intersects(box));
    }

    /** Opens every closed iron door in the cell (the gate is a pair of leaves). */
    private static void openExit(Plate plate, List<ServerPlayer> on) {
        ServerLevel level = plate.level();
        boolean opened = false;
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                BlockPos pos = plate.cellOrigin().offset(x, 1, z);
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.IRON_DOOR) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                        && !state.getValue(DoorBlock.OPEN)) {
                    ((DoorBlock) state.getBlock()).setOpen(null, level, state, pos, true);
                    opened = true;
                }
            }
        }
        Component line = Component.literal(opened ? "The door grinds open." : "The plate holds. The way on is already open.")
                .withStyle(ChatFormatting.GREEN);
        for (ServerPlayer player : on) {
            player.sendOverlayMessage(line);
        }
    }
}
