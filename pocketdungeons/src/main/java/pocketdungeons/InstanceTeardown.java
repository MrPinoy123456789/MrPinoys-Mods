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
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ends a run: purge, and the tick-spread block clear it funnels into.
 * Fourth of {@code Instances}' six M9 C3 extractions; it was already its own
 * async state machine ({@link PendingClear}) before this move, which is what
 * made it a clean cut.
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

    /**
     * The level teardowns write to in place of the dungeon dimension, for a
     * gametest (whose server has no dungeon dimension) that tears down cells it
     * stamped in its own level. Set and cleared within one synchronous test
     * body, so no tick ever sees it; {@code null} everywhere else.
     */
    static ServerLevel levelForTesting;

    private InstanceTeardown() {}

    private static ServerLevel dungeonLevel(MinecraftServer server) {
        return levelForTesting != null ? levelForTesting : server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
    }

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
     * (M63) The recoverable record for a teardown whose last-chance room save
     * did not land.
     *
     * <p>The teardown still proceeds. There is no version of this where the
     * cell is kept: the run is over, the members have been ejected, and holding
     * the slot open for a save that has already failed leaks the slot the way
     * {@link #abandonClears} exists to prevent. What the operator gets instead
     * is this line naming the owner, plus the guarantee that
     * {@link RoomStore#save} leaves the previously saved room untouched when it
     * fails, so {@code /dungeon admin baserestore} still has somewhere to
     * recover from. What is lost is at most this run's decorating, never the
     * room.
     */
    private static void warnRoomNotSaved(InstanceRecord record, String reason) {
        PocketDungeonsMod.LOG.error("Could not save {}'s room before tearing down slot {} ({}); "
                        + "their previously saved room is still on disk and is what they will get "
                        + "on re-entry. Changes made during this run are lost.",
                record.owner, record.slot, reason);
    }

    static void purge(MinecraftServer server, InstanceRecord record,
                      String reason, UUID excludeFromStraySweep) {
        // Set before a single member is ejected: see InstanceRecord.tearingDown.
        record.tearingDown = true;
        // M25: a Pocket2 child dies with its parent. Purged first so the
        // child's members are returned to the door while the parent still
        // exists to receive them.
        purgeChildren(server, record, reason);
        // Safety net: eject has almost certainly saved already, but this is
        // the last chance to read the room.
        // Synchronous because teardown (below) queues a PendingClear that
        // will erase the room cell; a deferred save could race with it
        // (PD-8).
        ServerLevel purgeLevel = dungeonLevel(server);
        if (purgeLevel != null
                && !RunLifecycle.saveRoomIfOwnerSync(purgeLevel, server, record, record.owner)) {
            warnRoomNotSaved(record, reason);
        }
        // Every cell the instance has standing, captured before any field is
        // nulled: the room, the staging room, a preview, the floor, liminal
        // cells and homecoming leftovers. The teardown clears exactly these
        // and releases exactly their tickets.
        List<BlockPos> cells = new ArrayList<>(record.liveCells());
        // PD-34: null the room origin on the record itself. The member loop
        // right after this calls Instances.eject, which calls the deferred
        // RunLifecycle.saveRoomIfOwner; with roomCellOrigin still set, that
        // deferred save used to fire on a later tick and race the PendingClear
        // queued at the end of this method, overwriting the good synchronous
        // save above with a partially (or fully) cleared room. Both
        // saveRoomIfOwner and saveRoomIfOwnerSync already no-op on a null
        // roomCellOrigin, so this alone closes the race.
        record.roomCellOrigin = null;
        record.stagingCellOrigin = null;
        for (UUID member : new ArrayList<>(record.members.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                PlaytestJournal.hintLeave(member, "purge", record);
                Instances.eject(server, record, player);
                player.sendSystemMessage(Component.literal("Your dungeon has closed.")
                        .withStyle(ChatFormatting.GRAY));
            } else {
                // M43.2: detach handles onPad too, which this branch used to
                // miss.
                Instances.detach(server, record, member, null);
            }
            // U8 Stage 1: a purge, a shutdown or a crash is the server's fault and
            // never costs anything.
            RunLifecycle.returnKeystone(server, record, member, player, Keystones.Outcome.NO_CHANGE);
        }
        // Run storage comes home with its owner, after the eject above.
        RunStorage.returnAll(server, record);
        OmenBar.close(record);
        if (record.untimed) {
            PocketDungeonsMod.LOG.info("UNTIMED dungeon in slot {} closed ({}), after {}s",
                    record.slot, reason,
                    (server.overworld().getGameTime() - record.createdAtTick) / 20L);
        }
        InstanceRegistry.bySlot.remove(record.slot);
        teardown(server, record.slot, record.origin, record.layout, reason, excludeFromStraySweep, cells);
    }

    static void teardown(MinecraftServer server, int slot, BlockPos origin,
                         InstanceLayout layout, String reason) {
        teardown(server, slot, origin, layout, reason, null, List.of());
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
                         Collection<BlockPos> extraCells) {
        // Every cell to clear: the layout's (or, with no layout, the widest
        // footprint a slot may hold) plus the cells standing outside it, each
        // once. A purge passes everything the instance has standing.
        Set<BlockPos> cellSet = new LinkedHashSet<>(layout != null
                ? layout.geometry().cellOrigins()
                : InstanceRegistry.maximalCellOrigins(origin));
        cellSet.addAll(extraCells);
        List<BlockPos> cellOrigins = new ArrayList<>(cellSet);

        // The cell-keyed subsystems go first: they hold positions in a dungeon
        // that is about to stop existing, and neither can tell a torn-down cell
        // from a cell whose player has simply walked away.
        for (BlockPos cellOrigin : cellOrigins) {
            Locks.clear(cellOrigin);
            OmenSources.clear(cellOrigin);
            Ordeals.clear(cellOrigin);
        }

        ServerLevel level = dungeonLevel(server);
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
        // registered. Its cells above are the maximum footprint a layout is
        // allowed to occupy rather than a guess: it is bounded by the span
        // budget, it stays well inside the slot pitch, and the tick budget
        // makes the extra volume cheap.
        AABB bounds = layout != null ? layout.bounds() : InstanceRegistry.maximalBounds(origin);
        int storyReach = RoomGeometry.storyOffset(RoomGeometry.MAX_SPAN_Y);
        for (BlockPos cellOrigin : cellOrigins) {
            bounds = bounds.minmax(CellGeometry.cellBounds(cellOrigin).expandTowards(0, -storyReach, 0));
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

    /**
     * (M63) Queues a clear without standing up an {@link InstanceRecord} or a
     * real dungeon, so {@code TeardownGameTest} can drive
     * {@link #processClears} and {@link #drainClears} on a gametest server,
     * which never has {@code pocketdungeons:void} (DISCOVERIES trap 18).
     *
     * <p>The seam is the queue, not the behaviour: what these two build is a
     * {@link PendingClear} identical to the one {@link #purge} builds, so the
     * slot-release path under test is the production one.
     */
    static void enqueueClearForTesting(int slot, AABB bounds, List<BlockPos> cellOrigins, String reason) {
        pendingClears.add(new PendingClear(slot, bounds, cellOrigins, reason));
    }

    /** (M63) How many clears are still queued. See {@link #enqueueClearForTesting}. */
    static int pendingClearCountForTesting() {
        return pendingClears.size();
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
        ServerLevel level = dungeonLevel(server);
        if (level == null) {
            abandonClears("the dungeon level is not loaded");
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
                Entity.class, clear.bounds, e -> !(e instanceof ServerPlayer) && !Lemon.isPart(e))) {
            entity.discard();
        }
        for (BlockPos cellOrigin : clear.cellOrigins) {
            level.setChunkForced(cellOrigin.getX() >> 4, cellOrigin.getZ() >> 4, false);
        }
        InstanceRegistry.usedSlots.remove(clear.slot);
        PocketDungeonsMod.LOG.info("Closed dungeon slot {} ({})", clear.slot, clear.reason);
    }

    /**
     * Gives up on every queued clear, releasing the slots they were holding.
     *
     * <p>M63: dropping the block-writing work when there is no level to write
     * it into is correct, but dropping the bookkeeping with it is not. A
     * {@code PendingClear} is the only thing that ever removes its slot from
     * {@link InstanceRegistry#usedSlots}, so a queue cleared without this leaks
     * one slot per abandoned teardown, permanently:
     * {@link InstanceRegistry#allocateSlot} walks past a slot that no live
     * instance owns and no future teardown will ever free. The blocks are
     * unreachable anyway once the level is gone, so releasing the slot costs
     * nothing and is the only outcome that leaves the registry honest.
     */
    private static void abandonClears(String why) {
        for (PendingClear clear : pendingClears) {
            InstanceRegistry.usedSlots.remove(clear.slot);
            PocketDungeonsMod.LOG.warn("Abandoned the clear for dungeon slot {} ({}): {}; "
                    + "the slot has been released", clear.slot, clear.reason, why);
        }
        pendingClears.clear();
    }

    /** Runs every queued clear to completion at once, ignoring the tick budget. */
    static void drainClears(MinecraftServer server) {
        ServerLevel level = dungeonLevel(server);
        if (level == null) {
            abandonClears("the dungeon level is not loaded");
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
            // M61: the clear also reaches below any lower story a cell may own.
            // Over-clearing a single-story cell is harmless: the extra volume is
            // dungeon void (air or bedrock this stamp placed).
            final int maxOffset = RoomGeometry.storyOffset(RoomGeometry.MAX_SPAN_Y);
            final int sizeX = RoomGeometry.CELL + 2;
            final int sizeY = RoomGeometry.CEILING_Y + 3 + maxOffset;
            final int sizeZ = RoomGeometry.CELL + 2;
            final int cellVolume = sizeX * sizeY * sizeZ;
            int written = 0;
            while (written < budget && cell < cellOrigins.size()) {
                BlockPos origin = cellOrigins.get(cell);
                int x = index / (sizeY * sizeZ);
                int rest = index % (sizeY * sizeZ);
                int z = rest / sizeY;
                int y = rest % sizeY;
                RoomBuilder.set(level, origin.offset(x - 1, y - 1 - maxOffset, z - 1), AIR);
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
