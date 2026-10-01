package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime every {@link Ordeal} shares: one armed room per cell, one server
 * tick for all of them, one-way levers, protected lever and lamp, and teardown.
 * Before this each Ordeal handler carried its own copy of all of it (an active
 * map, a tick registration, a stale check, a teardown hook).
 *
 * <p>Resolution: a lever Ordeal resolves the moment its lever is pulled
 * ({@link #onUse}, the player's click) or found powered on a tick (anything
 * else that powers it); a lever-less one resolves when
 * {@link Ordeal#objectiveMet}. Either way {@link Ordeal#resolve} runs once, the
 * room hears a chime, its players see the Ordeal's line, and the journal gets
 * an {@code ordeal} event. A resolved room stays armed until teardown so its
 * lever keeps refusing to be pushed back up and its fixtures stay protected.
 */
final class Ordeals {

    /** How often the shared tick runs; each Ordeal still steps on its own {@link Ordeal#period}. */
    private static final int BASE_PERIOD = 2;

    /** One armed room. Mutable: the tick replaces {@link #state} in place. */
    private static final class Armed<S> {
        final Ordeal<S> kind;
        final ServerLevel level;
        final BlockPos origin;
        final long armedAt;
        S state;
        boolean resolved;

        Armed(Ordeal<S> kind, ServerLevel level, BlockPos origin, S state) {
            this.kind = kind;
            this.level = level;
            this.origin = origin;
            this.state = state;
            this.armedAt = level.getGameTime();
        }

        BlockPos lever() {
            return kind.lever(state);
        }

        boolean stale() {
            return kind.stale(level, state);
        }

        /** One tick: resolve if the lever is down or the objective is met, else step the danger. */
        void step(long tick) {
            if (resolved) {
                return;
            }
            BlockPos lever = lever();
            if (lever != null && isPulled(level.getBlockState(lever))) {
                resolveNow();
                return;
            }
            if (tick % kind.period == 0) {
                state = kind.tickDanger(level, origin, state);
            }
            if (kind.objectiveMet(state)) {
                resolveNow();
            }
        }

        void resolveNow() {
            if (resolved) {
                return;
            }
            resolved = true;
            String line = kind.resolve(level, origin, state);
            BlockPos at = lever() != null ? lever() : origin.offset(8, 1, 8);
            level.playSound(null, at, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.7f, 1.2f);
            long seconds = (level.getGameTime() - armedAt) / 20;
            for (ServerPlayer player : playersIn(level, origin)) {
                if (line != null) {
                    player.sendOverlayMessage(Component.literal(line).withStyle(ChatFormatting.GREEN));
                }
                PlaytestJournal.ordealResolved(player, kind.id, seconds);
            }
            PocketDungeonsMod.LOG.info("Ordeal {} at {} resolved", kind.id, origin.toShortString());
        }
    }

    private static final Map<BlockPos, Armed<?>> ACTIVE = new LinkedHashMap<>();
    private static boolean tickRegistered;

    private Ordeals() {}

    /** Wires the shared tick. Call once from {@link TrialContent#warmUp()}. */
    static void register() {
        if (tickRegistered) {
            return;
        }
        tickRegistered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long tick = server.getTickCount();
            if (tick % BASE_PERIOD != 0 || ACTIVE.isEmpty()) {
                return;
            }
            tickAll(tick);
        });
    }

    /**
     * Arms {@code kind} in the cell at {@code cellOrigin}, replacing whatever
     * was armed there (a restamp writes new blocks, so old positions are
     * wrong). A room that lacks what the Ordeal needs is logged and skipped.
     */
    static <S> void arm(Ordeal<S> kind, ServerLevel level, BlockPos cellOrigin) {
        S state = kind.arm(level, cellOrigin);
        if (state == null) {
            PocketDungeonsMod.LOG.warn("Ordeal {} at {} found nothing to arm", kind.id, cellOrigin.toShortString());
            ACTIVE.remove(cellOrigin);
            return;
        }
        ACTIVE.put(cellOrigin.immutable(), new Armed<>(kind, level, cellOrigin.immutable(), state));
    }

    /** Drops the cell's Ordeal. Called from teardown. */
    static void clear(BlockPos cellOrigin) {
        ACTIVE.remove(cellOrigin);
    }

    /** Whether {@code kind} is armed in the cell and not yet resolved. */
    static boolean isActive(Ordeal<?> kind, BlockPos cellOrigin) {
        Armed<?> armed = ACTIVE.get(cellOrigin);
        return armed != null && armed.kind == kind && !armed.resolved;
    }

    /** Whether the cell's Ordeal has been resolved (and not torn down since). */
    static boolean isResolved(BlockPos cellOrigin) {
        Armed<?> armed = ACTIVE.get(cellOrigin);
        return armed != null && armed.resolved;
    }

    private static void tickAll(long tick) {
        Iterator<Armed<?>> it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            Armed<?> armed = it.next();
            if (!armed.level.isLoaded(armed.origin)) {
                continue; // nobody near; look again when they are
            }
            if (armed.stale()) {
                it.remove();
                continue;
            }
            armed.step(tick);
        }
    }

    // ---- the lever -----------------------------------------------------------------

    /**
     * A right-click on {@code pos}. Returns whether it was an Ordeal's lever
     * (the click is then consumed): an unpulled lever is pulled and resolves
     * its Ordeal at once; a pulled one stays down, because an Ordeal is never
     * un-resolved.
     */
    static boolean onUse(ServerPlayer player, BlockPos pos) {
        Armed<?> armed = byLever(pos);
        if (armed == null) {
            return false;
        }
        ServerLevel level = armed.level;
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.LEVER)) {
            return false;
        }
        if (isPulled(state)) {
            player.sendOverlayMessage(Component.literal("Already done. The lever stays down.")
                    .withStyle(ChatFormatting.GRAY));
            return true;
        }
        // Flags 3: neighbours update, so the lamp above lights off the lever.
        level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, true), Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.4f, 0.6f);
        armed.resolveNow();
        return true;
    }

    private static Armed<?> byLever(BlockPos pos) {
        for (Armed<?> armed : ACTIVE.values()) {
            if (pos.equals(armed.lever())) {
                return armed;
            }
        }
        return null;
    }

    private static boolean isPulled(BlockState state) {
        return state.is(Blocks.LEVER) && state.getValue(BlockStateProperties.POWERED);
    }

    /**
     * Whether {@code pos} is an armed Ordeal's lever or its lamp (a redstone
     * lamp touching the lever, lit by it once pulled).
     * Protected from players ({@link RoomProtection}) and explosions
     * ({@link DungeonTools#isShellProtected}): breaking the lever would strand
     * the Ordeal, breaking the lamp would lose the "done" signal.
     */
    static boolean isFixture(BlockPos pos) {
        for (Armed<?> armed : ACTIVE.values()) {
            BlockPos lever = armed.lever();
            if (lever == null) {
                continue;
            }
            if (pos.equals(lever)
                    || (pos.distManhattan(lever) == 1 && armed.level.getBlockState(pos).is(Blocks.REDSTONE_LAMP))) {
                return true;
            }
        }
        return false;
    }

    // ---- shared helpers for the kinds ---------------------------------------------

    /**
     * The Ordeal station: a lever on a wall, {@code facing} away from the block
     * it hangs on, with its lamp directly above. The lamp touches the lever, so
     * vanilla redstone lights it the moment the lever is pulled and keeps it lit.
     */
    static void placeWallLever(ServerLevel level, BlockPos lever, Direction facing) {
        RoomBuilder.set(level, lever, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.WALL)
                .setValue(LeverBlock.FACING, facing));
        RoomBuilder.set(level, lever.above(), Blocks.REDSTONE_LAMP.defaultBlockState());
    }

    /**
     * The Ordeal station on a floor: the lever standing at {@code lever}, and
     * its lamp as the block it stands on, which the pulled lever powers
     * strongly and so lights.
     */
    static void placeFloorLever(ServerLevel level, BlockPos lever) {
        RoomBuilder.set(level, lever.below(), Blocks.REDSTONE_LAMP.defaultBlockState());
        RoomBuilder.set(level, lever, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR));
    }

    /** Players inside the cell's interior. */
    static List<ServerPlayer> playersIn(ServerLevel level, BlockPos o) {
        AABB bounds = new AABB(
                o.getX() + 1, o.getY() + 1, o.getZ() + 1,
                o.getX() + RoomGeometry.CELL - 1, o.getY() + RoomGeometry.CEILING_Y + 1,
                o.getZ() + RoomGeometry.CELL - 1);
        return level.getPlayers(p -> !p.isSpectator() && p.getBoundingBox().intersects(bounds));
    }

    /** The first lever in the cell, scanning the whole interior, or {@code null}. */
    static BlockPos findLever(ServerLevel level, BlockPos cellOrigin) {
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                    BlockPos pos = cellOrigin.offset(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.LEVER)) {
                        return pos.immutable();
                    }
                }
            }
        }
        return null;
    }
}
