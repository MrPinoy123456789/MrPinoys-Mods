package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Ends a run: purge, the lingering-quarry retirement path, and the tick-spread
 * block clear both funnel into. Fourth of {@code Instances}' six M9 C3
 * extractions -- already its own async state machine ({@link PendingClear})
 * before this move, which is what made it a clean cut.
 *
 * <p>Calls back into {@code Instances} for two run-lifecycle side effects that
 * stay there ({@link Instances#eject}, {@link Instances#sendToWorldSpawn}) and
 * into {@code RunLifecycle} for two more that moved there in the same M9 C3
 * pass ({@link RunLifecycle#saveRoomIfOwner}, {@link RunLifecycle#returnKeystone}),
 * all four opened from {@code private} to package-visible for exactly this.
 */
final class InstanceTeardown {

    /** Teardowns still writing air. A slot stays allocated until its clear lands. */
    private static final List<PendingClear> pendingClears = new ArrayList<>();

    private InstanceTeardown() {}

    /**
     * M25: tears down every live Pocket2 child of {@code record} before the
     * parent itself is handled. A child never survives its parent: the pocket's
     * door lives in the parent's geometry, so a parent teardown makes the
     * return point meaningless and the child must close with it.
     */
    private static void purgeChildren(MinecraftServer server, InstanceRecord record, String reason) {
        for (InstanceRecord child : new ArrayList<>(InstanceRegistry.bySlot.values())) {
            if (child.parentSlot == record.slot) {
                purge(server, child, "parent torn down: " + reason);
            }
        }
    }

    static void purge(MinecraftServer server, InstanceRecord record, String reason) {
        purge(server, record, reason, null);
    }

    /**
     * The reward-room grace period ran out (T2.5). If this run's room has
     * already moved to the terminal cell (T2.4 completed), the dungeon becomes a
     * lingering quarry instead of a normal purge: force-load tickets are
     * released so it costs no standing tick budget, but the blocks stay
     * standing to be mined. A run with no room at all -- {@code /dungeon admin
     * build} or {@code untimed} -- has nothing to linger, so it falls back to
     * the ordinary purge.
     *
     * <p>The record stays in {@code InstanceRegistry.bySlot} and its slot stays claimed:
     * {@code RunLifecycle.enter()}'s lingering-quarry check is the only way out,
     * bounding this at one lingering dungeon per owner.
     */
    static void retireOrPurge(MinecraftServer server, InstanceRecord record, String reason) {
        // M25: a Pocket2 child dies with its parent, whatever teardown form the
        // parent takes. Purge the children first so their members are returned
        // before the parent's own roster is emptied.
        purgeChildren(server, record, reason);
        // Safety net: eject/dropMember have almost certainly saved already, but a
        // teardown is the last moment the room exists to be read. Synchronous
        // because purge (below) queues a PendingClear that can race a deferred
        // save (PD-8).
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level != null) {
            RunLifecycle.saveRoomIfOwnerSync(level, server, record, record.owner);
        }
        if (!record.isKeystoneRun() || record.roomCellOrigin == null) {
            purge(server, record, reason);
            return;
        }

        for (UUID member : new ArrayList<>(record.members.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                Instances.eject(server, record, player);
            } else {
                record.members.remove(member);
                InstanceRegistry.byMember.remove(member);
            }
            RunLifecycle.returnKeystone(server, record, member, player, Keystones.Outcome.NO_CHANGE);
        }
        if (record.timer != null) {
            record.timer.close();
        }

        if (level != null) {
            for (BlockPos cellOrigin : record.layout.geometry().cellOrigins()) {
                level.setChunkForced(cellOrigin.getX() >> 4, cellOrigin.getZ() >> 4, false);
            }
        }

        record.lingering = true;
        PocketDungeonsMod.LOG.info("Dungeon slot {} is now a lingering quarry for {} ({})",
                record.slot, record.owner, reason);
    }

    static void purge(MinecraftServer server, InstanceRecord record,
                      String reason, UUID excludeFromStraySweep) {
        // M25: a Pocket2 child dies with its parent. Purged first so the
        // child's members are returned to the door while the parent still
        // exists to receive them.
        purgeChildren(server, record, reason);
        // Safety net, as in retireOrPurge: last chance to read the room.
        // Synchronous because teardown (below) queues a PendingClear that
        // will erase the room cell; a deferred save could race with it
        // (PD-8).
        ServerLevel purgeLevel = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (purgeLevel != null) {
            RunLifecycle.saveRoomIfOwnerSync(purgeLevel, server, record, record.owner);
        }
        for (UUID member : new ArrayList<>(record.members.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                Instances.eject(server, record, player);
                player.sendSystemMessage(Component.literal("Your dungeon has closed.")
                        .withStyle(ChatFormatting.GRAY));
            } else {
                record.members.remove(member);
                InstanceRegistry.byMember.remove(member);
            }
            // U8 Stage 1: a purge, a shutdown or a crash is the server's fault and
            // never costs anything. The owner's timeout depletion, if any, already
            // happened in expireTimedOut before this was called.
            RunLifecycle.returnKeystone(server, record, member, player, Keystones.Outcome.NO_CHANGE);
        }
        if (record.timer != null) {
            record.timer.close();
        }
        if (record.untimed) {
            PocketDungeonsMod.LOG.info("UNTIMED dungeon in slot {} closed ({}), after {}s",
                    record.slot, reason,
                    (server.overworld().getGameTime() - record.createdAtTick) / 20L);
        }
        InstanceRegistry.bySlot.remove(record.slot);
        // The room's cell almost never coincides with anywhere layout.geometry()
        // still reaches: completeDungeon/moveRoomToTerminal relocates the room to
        // whatever cell sits behind the terminal, one full dungeon's footprint
        // away from the plan that captured it, and never updates record.layout to
        // match -- the next generateBehindLobby cycle folds it back into a fresh
        // plan's geometry, but a slot that closes before that (an abandoned
        // completed run, a disconnect, an admin purge) tears down with the stale
        // geometry. Passed through as the extra cell exactly like the reward
        // room -- also outside the planned grid -- already is: without it, the
        // room's blocks and bedrock envelope are never cleared, and whatever the
        // slot is handed to next stamps its own layout on top of them.
        teardown(server, record.slot, record.origin, record.layout, reason, excludeFromStraySweep,
                record.roomCellOrigin);
    }

    static void teardown(MinecraftServer server, int slot, BlockPos origin,
                         InstanceLayout layout, String reason) {
        teardown(server, slot, origin, layout, reason, null, null);
    }

    /**
     * Starts a teardown: evict strays now, queue the block clear, and release the
     * slot only once that clear has finished.
     *
     * <p>M0 cleared 7,168 blocks inline. A procedural layout is several times that
     * and a worst-case footprint is far more, so the clear is now spread across
     * ticks under a global budget. Three consequences, all deliberate:
     *
     * <ul>
     *   <li><strong>The slot is not returned to the free-list here.</strong> It
     *       goes back when the clear completes. Returning it immediately would let
     *       the next {@code /dungeon} be handed a slot still half-full of the last
     *       dungeon -- which is only survivable while clearing is instant.</li>
     *   <li><strong>Force-load tickets are released on completion too.</strong>
     *       The chunks have to stay loaded while the clear runs or the writes are
     *       silently dropped, the same trap section 8.2 flags for stamping.</li>
     *   <li><strong>The stray sweep still runs immediately</strong>, keeping its
     *       {@code excludeFromStraySweep} contract exactly as M0's disconnect fix
     *       left it. Nothing about that may be disturbed by the queueing.</li>
     * </ul>
     */
    static void teardown(MinecraftServer server, int slot, BlockPos origin,
                         InstanceLayout layout, String reason, UUID excludeFromStraySweep,
                         BlockPos extraCellOrigin) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            InstanceRegistry.usedSlots.remove(slot);
            PocketDungeonsMod.LOG.info("Closed dungeon slot {} ({})", slot, reason);
            return;
        }

        // One in-flight clear per slot. Without this guard, an operator purging
        // the same slot twice while the first clear is still running (the tick
        // spread means that window is now seconds, not one tick) queues a second
        // PendingClear -- and when the *first* one finishes and frees the slot for
        // reuse, the second keeps running and silently overwrites whatever new
        // instance just got allocated there with air. The stray sweep below is
        // idempotent (nothing to evict a second time, since the first teardown
        // already emptied record.members before this was ever reachable again),
        // so skipping the rest of a duplicate call loses nothing.
        if (isClearing(slot)) {
            PocketDungeonsMod.LOG.warn(
                    "Slot {} already has a clear in progress; ignoring duplicate teardown ({})",
                    slot, reason);
            return;
        }

        // An unknown layout means a slot allocated without a record ever being
        // registered. Clear the maximum footprint a layout is allowed to occupy
        // rather than guessing: it is bounded by the span budget, it stays well
        // inside the slot pitch, and the tick budget makes the extra volume cheap.
        List<BlockPos> cellOrigins = layout != null
                ? new ArrayList<>(layout.geometry().cellOrigins())
                : new ArrayList<>(InstanceRegistry.maximalCellOrigins(origin));
        AABB bounds = layout != null ? layout.bounds() : InstanceRegistry.maximalBounds(origin);

        // The reward room lives outside the planned grid entirely (U8 Stage 2),
        // so it is never part of layout.geometry() -- add its cell explicitly, or
        // a leaked reward room is a permanent scar on that slot.
        if (extraCellOrigin != null) {
            cellOrigins.add(extraCellOrigin);
            bounds = bounds.minmax(CellGeometry.cellBounds(extraCellOrigin));
        }

        // Never clear a slot with someone inside (section 12) -- except the
        // member this teardown was triggered for leaving, whose entity may
        // still be physically present for a disconnecting player. Teleporting
        // them here, mid-disconnect, is what causes the "Loading terrain..."
        // hang on their next login -- their own connection handles their exit.
        List<ServerPlayer> inside = level.getEntitiesOfClass(ServerPlayer.class, bounds);
        for (ServerPlayer stray : inside) {
            if (excludeFromStraySweep != null && stray.getUUID().equals(excludeFromStraySweep)) {
                continue;
            }
            Instances.sendToWorldSpawn(server, stray);
        }

        pendingClears.add(new PendingClear(slot, bounds, cellOrigins, reason));
    }

    static boolean isClearing(int slot) {
        for (PendingClear clear : pendingClears) {
            if (clear.slot == slot) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drains the clear queue under one global per-tick block budget, so several
     * simultaneous teardowns share it rather than stacking -- the same reasoning
     * section 8.3 applies to stamping.
     */
    static void processClears(MinecraftServer server) {
        if (pendingClears.isEmpty()) {
            return;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            pendingClears.clear();
            return;
        }

        int budget = PocketDungeonsConfig.clearBlocksPerTick();
        Iterator<PendingClear> it = pendingClears.iterator();
        while (it.hasNext() && budget > 0) {
            PendingClear clear = it.next();
            budget -= clear.advance(level, budget);
            if (clear.done()) {
                finishClear(server, level, clear);
                it.remove();
            }
        }
    }

    private static void finishClear(MinecraftServer server, ServerLevel level, PendingClear clear) {
        // Sweep after clearing, not before: anything the clear itself shakes
        // loose has to be caught too, or it outlives the instance.
        for (Entity entity : level.getEntitiesOfClass(
                Entity.class, clear.bounds, e -> !(e instanceof ServerPlayer))) {
            entity.discard();
        }
        for (BlockPos cellOrigin : clear.cellOrigins) {
            level.setChunkForced(cellOrigin.getX() >> 4, cellOrigin.getZ() >> 4, false);
        }
        InstanceRegistry.usedSlots.remove(clear.slot);
        PocketDungeonsMod.LOG.info("Closed dungeon slot {} ({})", clear.slot, clear.reason);
    }

    /** Runs every queued clear to completion at once, ignoring the tick budget. */
    static void drainClears(MinecraftServer server) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            pendingClears.clear();
            return;
        }
        for (PendingClear clear : new ArrayList<>(pendingClears)) {
            while (!clear.done()) {
                clear.advance(level, Integer.MAX_VALUE);
            }
            finishClear(server, level, clear);
        }
        pendingClears.clear();
    }

    /**
     * Programmatic clear of one instance, walked cell by cell across ticks.
     * {@code /fill} is unusable here -- 32,768 blocks per command (section 12).
     */
    private static final class PendingClear {
        private static final BlockState AIR = Blocks.AIR.defaultBlockState();

        final int slot;
        final AABB bounds;
        final List<BlockPos> cellOrigins;
        final String reason;

        private int cell;
        private int index;

        PendingClear(int slot, AABB bounds, List<BlockPos> cellOrigins, String reason) {
            this.slot = slot;
            this.bounds = bounds;
            this.cellOrigins = cellOrigins;
            this.reason = reason;
        }

        boolean done() {
            return cell >= cellOrigins.size();
        }

        /** Writes at most {@code budget} blocks; returns how many it actually wrote. */
        int advance(ServerLevel level, int budget) {
            // Clear the cell plus the one-block bedrock envelope (T2.3) around it:
            // sub-floor, over-ceiling, and the outer wall ring. Shared faces between
            // adjacent cells are cleared twice, which is harmless and cheaper than
            // computing the outer hull of the whole geometry.
            final int sizeX = RoomGeometry.CELL + 2;
            final int sizeY = RoomGeometry.CEILING_Y + 3;
            final int sizeZ = RoomGeometry.CELL + 2;
            final int cellVolume = sizeX * sizeY * sizeZ;
            int written = 0;
            while (written < budget && cell < cellOrigins.size()) {
                BlockPos origin = cellOrigins.get(cell);
                int x = index / (sizeY * sizeZ);
                int rest = index % (sizeY * sizeZ);
                int z = rest / sizeY;
                int y = rest % sizeY;
                RoomBuilder.set(level, origin.offset(x - 1, y - 1, z - 1), AIR);
                written++;
                if (++index >= cellVolume) {
                    index = 0;
                    cell++;
                }
            }
            return written;
        }
    }
}
