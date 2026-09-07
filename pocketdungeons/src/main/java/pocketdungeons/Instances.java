package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.LongSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Slot allocation, party membership, entry, exit, and teardown. Section 9's
 * fixed grid with a free-list: slots are reused, so the region footprint on disk
 * is bounded by peak concurrency rather than by how many dungeons have ever been
 * run.
 */
final class Instances {

    // Slot allocation and the byMember/bySlot/usedSlots lookup maps live on
    // InstanceRegistry (M9 C3 #2). Party pre-registration, invites and their
    // kick-confirmation state live on PartyService (M9 C3 #3).

    /** Return points for players who left the world while inside, keyed by UUID. */
    private static final Map<UUID, PendingReturn> pendingReturns = new HashMap<>();

    /**
     * PD-50: the last {@link InstanceRecord#roomCellOrigin} a member's record
     * carried at the moment {@link #detach} removed them from
     * {@link InstanceRegistry#byMember}.
     *
     * <p>{@code detach} runs, and clears {@code byMember}, <em>before</em>
     * {@link #eject} teleports the player: see this class's javadoc on
     * {@code eject} ("by then this player has already been removed from the
     * record"). {@code InventorySwap}'s leaving branch is driven by that same
     * teleport (the dimension-change event, or the tick sweep at worst one
     * tick later), and it looks the player's room up through
     * {@code InstanceRegistry.byMember} to find a container to deliver their
     * void inventory to. By the time it runs, that lookup is already gone, so
     * every void-side inventory was silently falling through to the "drop at
     * their feet" branch instead of reaching the room. This map is the fix:
     * {@code detach} writes the origin here before clearing {@code byMember},
     * and {@link #consumeLastRoomCellOrigin} reads and forgets it, so a stale
     * entry cannot outlive the one delivery it was written for.
     */
    private static final Map<UUID, BlockPos> lastRoomCellOrigin = new HashMap<>();

    /**
     * PD-50: the room origin {@link #detach} last saw for {@code member},
     * consumed once. Returns {@code null} if none was recorded, which is the
     * existing behaviour for a player with no room at all (an untimed or
     * admin-built run) and for anyone who was never detached.
     */
    static BlockPos consumeLastRoomCellOrigin(UUID member) {
        return lastRoomCellOrigin.remove(member);
    }

    /**
     * PD-44: a game-time expiry alongside the point itself, so a player who
     * disconnects inside and never comes back does not leave an entry for
     * the rest of the process. 24 in-game hours is generous to a genuinely
     * late return while still bounding the map.
     */
    private static final long PENDING_RETURN_TTL_TICKS = 20L * 60 * 60 * 24;

    private record PendingReturn(ReturnPoint point, long expiresAtTick) {}

    /**
     * Recovery teleports queued from the JOIN handler, delayed rather than
     * fired synchronously. JOIN fires as part of the connection handshake
     * itself -- issuing a dimension-change teleport in that exact window
     * races the client's own initial world load and is what caused the
     * "stuck on Loading terrain..." login hang (see docs/PLAN.md). A short delay
     * lets that initial load settle first.
     */
    /** Longer than any plausible run; {@code clearTrialOmen} is what actually ends it. */
    private static final int TRIAL_OMEN_TICKS = 20 * 60 * 90;

    private static final int JOIN_RECOVERY_DELAY_TICKS = 20;
    private static final List<PendingJoinRecovery> pendingJoinRecoveries = new ArrayList<>();

    // Teardown's own PendingClear queue lives on InstanceTeardown now (M9 C3 #4).

    private static int tickCounter;

    private Instances() {}

    private static final class PendingJoinRecovery {
        final UUID player;
        final ReturnPoint point; // null -> world spawn
        int ticksRemaining;
        PendingJoinRecovery(UUID player, ReturnPoint point, int ticksRemaining) {
            this.player = player;
            this.point = point;
            this.ticksRemaining = ticksRemaining;
        }
    }

    /** Registers instance ticking, death rescue, connection recovery, and lifecycle cleanup handlers. */
    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Ahead of onTick's own interval gate: a queued clear has to keep
            // draining even when no instance is live, or its slot never comes back.
            InstanceTeardown.processClears(server);
            onTick(server);
            processJoinRecoveries(server);
            // M45 seam: empty until M46's stash and swap (spec 11).
            InventorySwap.reconcileAll(server);
        });

        // M45 seam for M46: crossing into or out of the dungeon dimension is
        // the edge a swap is owed on. Verified against fabric-entity-events-v1
        // 5.0.5 in the 26.2 build: the event is
        // ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL and its
        // callback is afterChangeLevel(ServerPlayer, ServerLevel origin,
        // ServerLevel destination).
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(
                (player, origin, destination) -> InventorySwap.reconcile(player));

        // Nobody dies in a dungeon. A killing blow inside an instance ejects the
        // player instead: no death screen, no dropped inventory, no lost XP. The
        // dungeon is new and the loot inside it is not worth someone's survival
        // gear, so the failure state is "thrown out", not "wiped".
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player)) {
                return true;
            }
            InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
            if (record == null || !player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                return true;
            }
            // M25: death inside a Pocket2 child ejects to the parent at the
            // door, with the outer run's death penalty still applied. The child
            // is torn down either way; see Pocket2.dieInChild.
            if (record.parentSlot >= 0) {
                Pocket2.dieInChild(player.level().getServer(), player, record);
                return false;
            }
            // M27.3: deferred until extended dungeons. A checkpoint that
            // respawns the player at the last cleared cell instead of the
            // entrance is not worth building while the current layout is
            // short enough that re-traversing it after a rescue is trivial.
            rescue(player, record);
            return false;
        });

        // M10: mob strength scales with the run's keystone level. ENTITY_LOAD
        // fires when an entity is added to a server level's entity manager,
        // ahead of its first tick. The plan guessed ServerLivingEntityEvents
        // would carry an after-spawn hook; verified against fabric-entity-events-v1
        // it does not (ALLOW_DAMAGE/AFTER_DAMAGE/ALLOW_DEATH/AFTER_DEATH/
        // MOB_CONVERSION only), so this uses fabric-lifecycle-events-v1's
        // ServerEntityEvents.ENTITY_LOAD instead, a module this mod already
        // depends on for ThemeManifest/RoomManifest's own SERVER_STARTED hook.
        ServerEntityEvents.ENTITY_LOAD.register((entity, entityLevel) -> {
            if (!(entity instanceof Mob mob)
                    || !entityLevel.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                return;
            }
            InstanceRecord record = instanceAt(mob.blockPosition());
            if (record != null) {
                applyMobScale(mob, record.layout.keystoneLevel());
            }
        });

        // Leaving the world drops you from the party. The return point is kept so
        // the next login puts you back where you started, and the instance itself
        // survives for whoever is still inside it.
        //
        // PD-12: DISCONNECT fires on Netty's IO thread, not the server thread,
        // for an abrupt disconnect (RunLifecycle.saveRoomIfOwner's javadoc has
        // the full story). The whole body below mutates InstanceRegistry.byMember,
        // pendingReturns, record.members/onPad and, through dropMember, can reach
        // InstanceTeardown.purge, all of which the server thread also reads and
        // writes every tick and on every block break. Deferring the whole thing
        // through server.execute (a no-op wrap if this ever fires on the server
        // thread already) is what makes it safe.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            server.execute(() -> handleDisconnect(server, player));
        });

        // Rejoining inside a purged slot: send them home rather than leaving them
        // standing in an empty void. Queued, not immediate -- see
        // JOIN_RECOVERY_DELAY_TICKS.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            // No keystone to hand over on login any more: the level is already
            // theirs server-side, and the watcher's reconcile pass repaints the
            // remote in their pocket within one interval.
            if (!player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                return;
            }
            if (InstanceRegistry.byMember.containsKey(player.getUUID())) {
                return;
            }
            PendingReturn pending = pendingReturns.remove(player.getUUID());
            ReturnPoint point = pending == null ? null : pending.point();
            pendingJoinRecoveries.add(new PendingJoinRecovery(
                    player.getUUID(), point, JOIN_RECOVERY_DELAY_TICKS));
        });

        // M45 seam: a player who logged out inside a run and came back. Empty
        // until M46.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                InventorySwap.reconcile(handler.getPlayer()));

        // M33: every joining player gets a reminder of their active guided
        // task, and if they are logging back into a dungeon room, the tracker
        // screen is repainted to match. The screen is in-world, not a global
        // sidebar, so a player who logs in in the overworld simply has a task
        // waiting on their next visit to a dungeon.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            Component taskLine = TaskTracker.taskLine(player);
            if (taskLine != null) {
                player.sendSystemMessage(Component.literal("Active task: ").withStyle(ChatFormatting.AQUA)
                        .append(taskLine));
            }
            if (player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                DungeonScreen.refreshTracker(server, player.getUUID());
            }
        });

        // Without this the manifest stays empty until an operator runs
        // `admin manifest reload` by hand, which means every /dungeon on a
        // freshly started server silently gets the static fallback. This is the
        // one load that ContentReload.register()'s own /reload listener cannot
        // cover -- startup reloads resources before SERVER_STARTED fires, so
        // that listener sees a null server and defers to this call instead.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ContentSnapshot snapshot = ContentReload.reload(server);
            RoomManifest manifest = snapshot.rooms();
            if (!manifest.rejections().isEmpty()) {
                PocketDungeonsMod.LOG.error("{} dungeon room(s) were rejected at startup; "
                        + "run /dungeon admin manifest reload for the reasons",
                        manifest.rejections().size());
            }
            RoomManifest anomalyManifest = snapshot.anomalyRooms();
            if (!anomalyManifest.rejections().isEmpty()) {
                PocketDungeonsMod.LOG.error("{} anomaly room(s) were rejected at startup; "
                        + "run /dungeon admin manifest reload for the reasons",
                        anomalyManifest.rejections().size());
            }
            if (!snapshot.valid()) {
                PocketDungeonsMod.LOG.error("Startup content snapshot failed required coverage; "
                        + "dungeon generation will use whatever loaded. Errors: {}", snapshot.errors());
            }
            reconcileAfterUncleanShutdown(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (int slot : new ArrayList<>(InstanceRegistry.usedSlots)) {
                InstanceRecord record = InstanceRegistry.bySlot.get(slot);
                if (record != null) {
                    InstanceTeardown.purge(server, record, "server stopping");
                } else {
                    InstanceTeardown.teardown(server, slot, InstanceRegistry.originForSlot(slot), null, "server stopping");
                }
            }
            // There are no more ticks coming, so the queued clears have to run
            // now. A force-load ticket that never gets released pins its chunks
            // for the rest of the process (docs/PLAN.md's M4 amendment).
            InstanceTeardown.drainClears(server);
        });
    }

    /**
     * The body of the {@code DISCONNECT} handler, run on the server thread via
     * {@code server.execute} (PD-12) rather than directly on the Netty IO
     * thread the event itself fires on for an abrupt disconnect.
     */
    private static void handleDisconnect(MinecraftServer server, ServerPlayer player) {
        PartyService.clearFor(player.getUUID());
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null) {
            return;
        }
        ReturnPoint point = record.members.get(player.getUUID());
        if (point != null) {
            long expiresAtTick = server.overworld().getGameTime() + PENDING_RETURN_TTL_TICKS;
            pendingReturns.put(player.getUUID(), new PendingReturn(point, expiresAtTick));
        }
        // U8 Stage 1: disconnecting is free. The run keeps running, on its
        // own clock, whether or not anyone is here to watch it -- there is
        // nothing to settle on the way out any more.
        RunLifecycle.dropMember(server, record, player.getUUID(), player, "member disconnected");
    }

    /**
     * PD-14: on a clean shutdown, {@code SERVER_STOPPING} tears down and
     * drains every live slot, which releases every force-load ticket the mod
     * ever set. So a dungeon-dimension chunk still force-loaded the moment
     * this runs is a sign the previous shutdown never got that far, a crash
     * or a kill that skipped {@code SERVER_STOPPING}, and in-memory state
     * ({@code bySlot}, {@code usedSlots}) is useless for finding it, since
     * {@link InstanceRecord} is deliberately not persisted. Recovers by
     * mapping every stray forced chunk back to the slot-grid cell it falls
     * in and tearing that slot down through the same budgeted-clear path
     * {@code SERVER_STOPPING}'s own orphan branch already uses for a
     * record-less slot, a no-op sweep of already-air blocks in the far more
     * common case where the previous shutdown was clean.
     */
    private static void reconcileAfterUncleanShutdown(MinecraftServer server) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        LongSet forced = level.getChunkSource().getForceLoadedChunks();
        if (forced.isEmpty()) {
            return;
        }
        int slotPitch = PocketDungeonsConfig.slotPitch();
        int slotsPerRow = PocketDungeonsConfig.slotsPerRow();
        Set<Integer> orphanedSlots = new HashSet<>();
        for (long packed : forced) {
            int blockX = ChunkPos.getX(packed) * 16;
            int blockZ = ChunkPos.getZ(packed) * 16;
            int col = Math.floorDiv(blockX, slotPitch);
            int row = Math.floorDiv(blockZ, slotPitch);
            if (col < 0 || row < 0 || col >= slotsPerRow) {
                continue; // outside the addressable grid, not one of this mod's tickets
            }
            orphanedSlots.add(row * slotsPerRow + col);
        }
        for (int slot : orphanedSlots) {
            PocketDungeonsMod.LOG.warn(
                    "Slot {} was still force-loaded at startup; the previous shutdown "
                            + "was not clean, clearing it now",
                    slot);
            InstanceTeardown.teardown(server, slot, InstanceRegistry.originForSlot(slot), null,
                    "startup reconciliation after unclean shutdown");
        }
    }

    // ---- entry --------------------------------------------------------------

    /**
     * The affixes active on the run this member is currently standing in, or an
     * empty set if they are not in one. Used by {@link SilenceListener} rather
     * than reading a keystone -- the item may be sitting in a chest, and the
     * question is what the run itself is doing right now.
     */
    static Set<Affix> affixesFor(UUID member) {
        InstanceRecord record = InstanceRegistry.byMember.get(member);
        return record == null ? EnumSet.noneOf(Affix.class) : record.affixes;
    }


    /**
     * Plans and stamps a dungeon at {@code origin}.
     *
     * <p>Order matters: the plan is resolved <em>before</em> anything is
     * force-loaded, because the plan is what says which chunks to load. Blocks
     * written into unloaded chunks are silently dropped (section 8.2), so the
     * tickets go down between planning and stamping and come back off on every
     * failure path.
     *
     * <p>When the planner exhausts its retry budget this falls back to
     * {@link StaticLayout} rather than failing the command -- section 7.2 step 8.
     * The fallback is logged at warn and told to the player, because it firing at
     * all means something needs attention and a silent fallback is a bug that
     * takes weeks to get reported.
     *
     * <p><strong>A stamp exception can leave partial geometry in the world</strong>
     * -- {@code LayoutStamper.stamp} writes cell by cell, so a failure on cell 4
     * of 7 (a missing template, a malformed manifest entry) leaves the first three
     * standing. Both failure branches below route through {@code teardown} rather
     * than just releasing the force-load tickets, so that partial geometry is
     * swept up instead of orphaned at an origin some later dungeon will be
     * stamped on top of. Ownership of the slot's release moves with it: neither
     * branch (nor either caller) removes the slot from {@code InstanceRegistry.usedSlots} directly
     * on failure any more -- that happens when the queued clear completes, the
     * same as every other teardown path.
     *
     * @return the layout, or null if stamping itself failed
     */
    static InstanceLayout buildLayout(MinecraftServer server, ServerLevel level,
                                              int slot, BlockPos origin, long seed,
                                              int keystoneLevel, Set<Affix> affixes,
                                              String theme, UUID owner) {
        // M48: seed the solvability pass from the owner's bag. buildLayout is
        // the untimed/admin path with no party, so the party size is one.
        String bagId = owner == null ? "" : DungeonLog.forServer(server).bagOf(owner);
        Set<String> bagTags = BagTags.seed(bagId, 1);
        LayoutPlanner.Outcome outcome = LayoutPlanner.plan(
                seed, RoomManifest.current(), PocketDungeonsConfig.planAttemptBudget(),
                PocketDungeonsConfig.pathLengthMin(), PocketDungeonsConfig.pathLengthMax(),
                PocketDungeonsConfig.branchProbability(), PocketDungeonsConfig.loopProbability(),
                PocketDungeonsConfig.maxGridSpan(), theme, null, bagTags);

        DungeonPlan plan = outcome.plan();
        if (plan != null) {
            PlanGeometry geometry = PlanGeometry.of(origin, plan.cells());
            forceLoad(level, geometry.chunks(), true);
            try {
                return LayoutStamper.stamp(level, origin, plan, keystoneLevel, affixes, owner);
            } catch (RuntimeException e) {
                PocketDungeonsMod.LOG.error("Stamping plan at {} failed; clearing whatever was written",
                        origin.toShortString(), e);
                InstanceTeardown.teardown(server, slot, origin, InstanceLayout.forClearingOnly(origin, geometry),
                        "stamp failed");
                return null;
            }
        }

        PocketDungeonsMod.LOG.warn(
                "Planning failed for seed {} after {} attempts ({}); falling back to StaticLayout",
                seed, outcome.attemptsUsed(), outcome.failureReason());

        InstanceLayout fallback = StaticLayout.layout(origin, keystoneLevel, affixes);
        forceLoad(level, fallback.geometry().chunks(), true);
        try {
            StaticLayout.stamp(level, origin);
            return fallback;
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error(
                    "Stamping the fallback layout at {} failed; clearing whatever was written",
                    origin.toShortString(), e);
            InstanceTeardown.teardown(server, slot, origin, fallback, "fallback stamp failed");
            return null;
        }
    }

    /** Records a member's return point, teleports them in, and indexes them. */
    static void admit(MinecraftServer server, InstanceRecord record, ServerPlayer player) {
        record.members.put(player.getUUID(), new ReturnPoint(
                player.level().dimension(), player.position(),
                player.getYRot(), player.getXRot()));
        InstanceRegistry.byMember.put(player.getUUID(), record);
        pendingReturns.remove(player.getUUID());

        teleport(server, player, PocketDungeonsMod.DUNGEON_LEVEL,
                Vec3.atBottomCenterOf(record.layout.entrance()),
                record.layout.entranceYaw(), 0.0f);

        if (record.timer != null) {
            record.timer.addPlayer(player);
        }
        applyTrialOmen(player, record);
        // M48: a member who has not yet chosen a bag gets the bag chest on
        // entry. Each member picks independently; the chest is a shared
        // station, so this is a no-op once it is already standing.
        if (record.roomCellOrigin != null && !record.visitInstance) {
            ServerLevel dungeonLevel = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (dungeonLevel != null) {
                placeBagChestIfBagless(dungeonLevel, server, player.getUUID(), record.roomCellOrigin);
            }
        }
    }

    /**
     * Grants Trial Omen for an ominous run.
     *
     * <p>The mod grants it <em>directly</em> and never Bad Omen: vanilla's
     * conversion happens on entering a trial chamber <em>structure</em>, and there
     * is no such structure in {@code pocketdungeons:void}. This is also not what
     * makes the run ominous -- the stamper writes the {@code ominous} blockstate
     * itself, which is the load-bearing half. The effect is here so the ominous
     * item spawners a trial spawner drops behave, and so the player can see what
     * they paid for.
     *
     * <p>Duration is deliberately longer than any plausible run, because
     * {@link #clearTrialOmen} takes it away on every way out.
     */
    static void applyTrialOmen(ServerPlayer player, InstanceRecord record) {
        if (!record.layout.ominous()) {
            return;
        }
        player.addEffect(new MobEffectInstance(MobEffects.TRIAL_OMEN,
                TRIAL_OMEN_TICKS, 0, false, false, true));
    }

    /**
     * Takes Trial Omen back on the way out, whatever the reason.
     *
     * <p>A player who walks out of a dungeon still carrying it takes it into the
     * overworld, and that is a real-world effect this mod has no business
     * exporting. Unconditional rather than gated on {@code layout.ominous()}: the
     * cheap call is worth not having to reason about a member who was granted it
     * and then had the flag change under them.
     */
    static void clearTrialOmen(ServerPlayer player) {
        player.removeEffect(MobEffects.TRIAL_OMEN);
    }

    // ---- the lobby (M2/M3 §3.2.3) ---------------------------------------------
    //
    // Retires U8 Stage 3's separate, off-grid, post-completion-only selector
    // room. The lobby is the same shape -- one fixed cell, three fixed interior
    // doors, a leave-lodestone -- but it is now the run's own cell 0, present
    // every time, and its three doors choose the run about to start rather than
    // a reward banked after one already finished. `InstanceRecord.selectorRoom`
    // and every guard that read it were removed in M9 C1, once it was confirmed
    // the field was never set true anywhere.

    /**
     * The absolute compass direction the lobby's one connecting door is placed
     * at, every time -- whatever {@code entrance_hall}'s own un-rotated mask
     * happens to be. Read from the manifest rather than assumed, so this stays
     * correct however that template is authored.
     */
    static DoorMask.Direction lobbyDoorDirection() {
        RoomManifest.Entry entry = RoomManifest.current().byName("entrance_hall");
        int mask = entry != null ? entry.maskAtRotation(0) : DoorMask.NORTH;
        for (DoorMask.Direction dir : DoorMask.Direction.values()) {
            if (DoorMask.hasEdge(mask, dir)) {
                return dir;
            }
        }
        return DoorMask.Direction.NORTH;
    }

    /**
     * Opens (or returns to) this player's lobby: their persistent room,
     * standing alone with no dungeon behind it yet. Companions pre-registered
     * via {@code /dungeon party} come in too, exactly as they would for an
     * already-generated run -- they just wait on the owner's door choice.
     */
    static boolean enterLobby(MinecraftServer server, ServerLevel level, ServerPlayer player,
                                      List<ServerPlayer> companions) {
        int slot = InstanceRegistry.allocateSlot();
        BlockPos origin = InstanceRegistry.originForSlot(slot);

        InstanceLayout layout = stampLobby(server, level, slot, origin, player.getUUID());
        if (layout == null) {
            player.sendSystemMessage(Component.literal(
                    "Your room failed to build. You have not been moved.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), layout,
                EnumSet.noneOf(Affix.class), player.getUUID(), false);
        record.awaitingDoorChoice = true;
        record.roomCellOrigin = origin;
        record.stagingCellOrigin = CellGeometry.offsetInDirection(
                origin, DoorMask.Direction.SOUTH, RoomGeometry.CELL);
        record.phase = RunSession.Phase.HOME;
        InstanceRegistry.bySlot.put(slot, record);

        admit(server, record, player);
        for (ServerPlayer companion : companions) {
            admit(server, record, companion);
        }

        // The engine screen was summoned at stamp time without a viewer; now
        // that the owner is standing here, show their actual fuel count.
        DungeonScreen.updateEngine(level, record, player);
        DungeonScreen.updateTracker(level, record);

        player.sendSystemMessage(Component.literal(
                "Three doors. Choose one to open a run.").withStyle(ChatFormatting.GOLD));
        return true;
    }

    /**
     * Stamps just the lobby -- the owner's saved room (M2 T2.1) if they have
     * one, {@code entrance_hall} untouched otherwise -- with its one connecting
     * door sealed (T2.3's envelope covers the other three sides; nothing exists
     * to connect to yet) and its three fixed choice-doors ready. No plan, no
     * timer: {@link #chooseLobbyDoor} is what generates the rest of the run.
     *
     * @return the one-cell layout, or {@code null} if the stamp failed (the
     *         slot's tickets are already released on that path)
     */
    private static InstanceLayout stampLobby(MinecraftServer server, ServerLevel level,
                                             int slot, BlockPos origin, UUID owner) {
        DoorMask.Direction dungeonDir = DoorMask.Direction.SOUTH;
        BlockPos stagingOrigin = CellGeometry.offsetInDirection(origin, dungeonDir, RoomGeometry.CELL);
        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
        level.setChunkForced(stagingOrigin.getX() >> 4, stagingOrigin.getZ() >> 4, true);
        try {
            // M55: the safe room holds the owner's blob, bag chest and stations.
            stampSafeRoom(level, server, owner, origin);
            // The staging room holds the selector doors, lodestone, furniture.
            stampStagingRoom(level, stagingOrigin, dungeonDir);
            // Connect the two: open the safe room's dungeon wall and clear its
            // bedrock face so the party can walk through.
            RoomBuilder.openDoor(level, origin, mcDirection(dungeonDir));
            BedrockEnvelope.clearFace(level, origin, dungeonDir);
            // M19: the physical selection furniture (bulbs, lever, screens) and
            // the two text_display entities, summoned fresh at every stamp and
            // never captured with the room. They live in the staging room now.
            DungeonScreen.summonDoor(level, stagingOrigin, dungeonDir,
                    DungeonScreen.idleContent(level, owner));
            DungeonScreen.summonEngine(level, stagingOrigin, dungeonDir,
                    DungeonScreen.engineContent(null));
            DungeonScreen.summonTracker(level, stagingOrigin, dungeonDir,
                    DungeonScreen.trackerContent(level.getServer(), owner));
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not stamp a lobby for {}", owner, e);
            level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
            level.setChunkForced(stagingOrigin.getX() >> 4, stagingOrigin.getZ() >> 4, false);
            InstanceRegistry.usedSlots.remove(slot);
            return null;
        }
        return lobbyLayout(origin);
    }

    /**
     * (M43.8) The combined room-shell sequence for visit instances, which
     * stamp the owner's room blob and the selector/furniture in one cell.
     * The main dungeon loop no longer calls this: {@link #stampSafeRoom}
     * and {@link #stampStagingRoom} split the blob and the door furniture
     * into separate cells (M55, spec 12.6). Visit instances keep the old
     * single-cell model because they never generate a dungeon behind them.
     */
    static void stampRoomShell(ServerLevel level, MinecraftServer server, UUID owner, BlockPos origin) {
        boolean placedOwnRoom = RoomStore.place(level, server, owner, origin, 0,
                RandomSource.create(level.getRandom().nextLong()));
        if (!placedOwnRoom) {
            TemplateStamper.place(level, level.getStructureManager(), origin,
                    TemplateStamper.ENTRANCE_HALL, 0, level.getRandom().nextLong());
        }
        RoomBuilder.sealDoor(level, origin, mcDirection(lobbyDoorDirection()));
        BedrockEnvelope.applyToCell(level, origin, java.util.Set.of());
        RoomBuilder.sealDoor(level, origin, mcDirection(DoorMask.Direction.SOUTH));
        RoomTemplateGenerator.placeSelectorDoors(level, origin, DoorMask.Direction.SOUTH);
        RoomTemplateGenerator.placeWallLodestone(level, origin);
        RoomTemplateGenerator.placeFurniture(level, origin, DoorMask.Direction.SOUTH, true);
    }

    /**
     * (M55) Stamps the safe room: the owner's saved room blob (or a fresh
     * entrance hall), with both the entrance and dungeon walls sealed and a
     * full bedrock envelope. No selector doors, no lodestone, no furniture.
     * The safe room is the player's persistent home base: loot, stash, bag
     * chest and stations live here. It despawns while a dungeon is active
     * and re-stamps from {@link RoomStore} on return.
     */
    static void stampSafeRoom(ServerLevel level, MinecraftServer server, UUID owner, BlockPos origin) {
        boolean placedOwnRoom = RoomStore.place(level, server, owner, origin, 0,
                RandomSource.create(level.getRandom().nextLong()));
        if (!placedOwnRoom) {
            TemplateStamper.place(level, level.getStructureManager(), origin,
                    TemplateStamper.ENTRANCE_HALL, 0, level.getRandom().nextLong());
        }
        RoomBuilder.sealDoor(level, origin, mcDirection(lobbyDoorDirection()));
        RoomBuilder.sealDoor(level, origin, mcDirection(DoorMask.Direction.SOUTH));
        BedrockEnvelope.applyToCell(level, origin, java.util.Set.of());
    }

    /**
     * (M55) Stamps the staging room: a blank shell with selector doors,
     * lodestone and furniture on the dungeon wall, but no saved room blob.
     * The {@code safeDir} side is left open (no bedrock, door carved) so the
     * staging room connects to the safe room or the terminal cell beside it.
     * The dungeon wall ({@code dungeonDir}) is sealed and bedrocked until a
     * door is chosen and {@link #generateBehindLobby} clears that face.
     */
    static void stampStagingRoom(ServerLevel level, BlockPos origin, DoorMask.Direction dungeonDir) {
        DoorMask.Direction safeDir = CellGeometry.opposite(dungeonDir);
        RoomBuilder.buildShell(level, origin, RoomBuilder.FLOOR);
        RoomBuilder.openDoor(level, origin, mcDirection(safeDir));
        RoomBuilder.sealDoor(level, origin, mcDirection(dungeonDir));
        BedrockEnvelope.applyToCell(level, origin, java.util.Set.of(safeDir));
        RoomTemplateGenerator.placeSelectorDoors(level, origin, dungeonDir);
        RoomTemplateGenerator.placeWallLodestone(level, origin);
        RoomTemplateGenerator.placeFurniture(level, origin, dungeonDir, true);
    }

    /**
     * (M55) Clears the safe room cell and discards its entities. Called at
     * commit time after {@link RunLifecycle#saveRoom} has captured the blob.
     * Sets {@code roomCellOrigin} to null so every safe-room-aware check
     * (saveRoom, bag chest, room protection) knows the safe room is gone.
     */
    static void despawnSafeRoom(ServerLevel level, InstanceRecord record) {
        if (record.roomCellOrigin == null) {
            return;
        }
        BlockPos origin = record.roomCellOrigin;
        clearCellSync(level, origin, List.of());
        for (Entity leftover : level.getEntitiesOfClass(Entity.class, CellGeometry.cellBounds(origin),
                e -> !(e instanceof ServerPlayer))) {
            leftover.discard();
        }
        record.roomCellOrigin = null;
    }

    /**
     * (M55) Re-stamps the safe room from {@link RoomStore} at the slot origin
     * and reconnects it to the staging room (if one is active). Called on
     * return from a dungeon: the blob is restored, both walls sealed, and a
     * full bedrock envelope applied. If a staging room is active, the safe
     * room's dungeon wall is opened and its bedrock face cleared so the two
     * cells connect.
     */
    static void restoreSafeRoom(ServerLevel level, MinecraftServer server, InstanceRecord record) {
        BlockPos origin = record.origin;
        stampSafeRoom(level, server, record.owner, origin);
        record.roomCellOrigin = origin;
        // If a staging room is active, connect the safe room to it.
        if (record.stagingCellOrigin != null) {
            DoorMask.Direction dungeonDir = record.roomDungeonDoor;
            RoomBuilder.openDoor(level, origin, mcDirection(dungeonDir));
            BedrockEnvelope.clearFace(level, origin, dungeonDir);
        }
    }

    // ---- M48: the bag chest (the player's class selection) -----------------

    /**
     * (M48) The bag chest's fixed spot in the safe room: the centre of the
     * floor at standing height. The room is a 16-wide cell with the selector
     * doors, lodestone and furniture all on the walls, so the centre is open
     * floor at every rotation. Detected by position rather than block-entity
     * NBT, the same way {@link #selectorDoorStep}, {@link #isCommitLever} and
     * {@link #engineTerminalAt} identify their own fixtures.
     */
    static BlockPos bagChestPos(BlockPos origin) {
        return origin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
    }

    /**
     * (M48) Places the bag chest if any member of this instance (owner
     * included) has not yet chosen a bag. The chest is a shared station: each
     * bagless member right-clicks it independently and picks their own bag.
     * A no-op if the chest is already standing, so it is safe to call from
     * every lobby-arming path.
     */
    static void placeBagChestForParty(ServerLevel level, MinecraftServer server, InstanceRecord record) {
        if (record == null || record.roomCellOrigin == null || record.visitInstance) {
            return;
        }
        DungeonLog log = DungeonLog.forServer(server);
        boolean anyBagless = log.bagOf(record.owner).isEmpty();
        if (!anyBagless) {
            for (UUID member : record.members.keySet()) {
                if (log.bagOf(member).isEmpty()) {
                    anyBagless = true;
                    break;
                }
            }
        }
        if (!anyBagless) {
            return;
        }
        BlockPos pos = bagChestPos(record.roomCellOrigin);
        if (!level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
            RoomBuilder.set(level, pos, Blocks.ENDER_CHEST.defaultBlockState());
        }
    }

    /**
     * (M48) Places the bag chest if this one player has not yet chosen a bag.
     * Used from {@link #admit}, where the record exists but the party is still
     * assembling, so the per-party check is not yet meaningful. A no-op if the
     * chest is already standing.
     */
    private static void placeBagChestIfBagless(ServerLevel level, MinecraftServer server,
                                               UUID player, BlockPos origin) {
        if (player == null || !DungeonLog.forServer(server).bagOf(player).isEmpty()) {
            return;
        }
        BlockPos pos = bagChestPos(origin);
        if (!level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
            RoomBuilder.set(level, pos, Blocks.ENDER_CHEST.defaultBlockState());
        }
    }

    /**
     * (M48) Clears the bag chest if it is standing. The chest is transient
     * furniture, not part of the room blob: it must not bake into a
     * {@link RoomStore#capture}, and it has no place in an active run, so this
     * runs before capture in {@code RunLifecycle.saveRoom} and when a door is
     * chosen in {@code generateBehindLobby}.
     */
    static void clearBagChest(ServerLevel level, BlockPos origin) {
        BlockPos pos = bagChestPos(origin);
        if (level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
            RoomBuilder.set(level, pos, RoomBuilder.AIR);
        }
    }

    static net.minecraft.core.Direction mcDirection(DoorMask.Direction dir) {
        return switch (dir) {
            case NORTH -> net.minecraft.core.Direction.NORTH;
            case SOUTH -> net.minecraft.core.Direction.SOUTH;
            case EAST -> net.minecraft.core.Direction.EAST;
            case WEST -> net.minecraft.core.Direction.WEST;
        };
    }

    /** A single fixed cell at rotation 0 -- no keystone, no timer, no reward room, until a door is chosen. */
    static InstanceLayout lobbyLayout(BlockPos origin) {
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        BlockPos entrance = origin.offset(3, 1, 3);
        // M18: the leave pad marker tracks the stand spot in front of the wall
        // lodestone (the lodestone itself is at origin + (1, 2, 0)); the field
        // is for admin output only.
        BlockPos exitPad = origin.offset(1, 1, 1);
        return new InstanceLayout(origin, geometry, entrance, 0.0f, exitPad, geometry.bounds(),
                0L, 1, 1, 1, false, EnumSet.noneOf(Affix.class), 0, origin, 0, 0, Set.of(), null, Set.of());
    }

    /**
     * Which of the lobby's three fixed doors (1, 2 or 3) this world position
     * is, for <em>this player's own</em> lobby -- or null if the player is not
     * standing in one that is still {@link InstanceRecord#awaitingDoorChoice},
     * the position is not a door, or the player is a party member riding along
     * rather than the lobby's owner (only the owner's keystone is on the
     * line). Positions are computed from the record's origin rather than read
     * back out of the template: the lobby is always stamped at rotation 0, so
     * there is no transform to account for.
     */
    static Integer selectorDoorStep(ServerPlayer player, BlockPos pos) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !RunSession.canChooseDoor(record) || !player.getUUID().equals(record.owner)) {
            return null;
        }
        BlockPos o = record.stagingCellOrigin;
        if (o == null) {
            return null;
        }
        int dx = pos.getX() - o.getX();
        int dy = pos.getY() - o.getY();
        int dz = pos.getZ() - o.getZ();
        if (dy != 1 && dy != 2) {
            return null;
        }
        int along;
        switch (record.roomDungeonDoor) {
            case NORTH -> {
                if (dz != 1) return null;
                along = dx;
            }
            case SOUTH -> {
                if (dz != RoomGeometry.CELL - 2) return null;
                along = dx;
            }
            case WEST -> {
                if (dx != 1) return null;
                along = dz;
            }
            case EAST -> {
                if (dx != RoomGeometry.CELL - 2) return null;
                along = dz;
            }
            default -> {
                return null;
            }
        }
        return switch (along) {
            case 7 -> 1;
            case 8 -> 2;
            case 9 -> 3;
            default -> null;
        };
    }

    /**
     * M19 19.3: whether {@code pos} is this player's own lobby's commit lever:
     * the lever block beside the third selector door on the room's dungeon
     * wall. Only the owner may pull it, the same rule as the doors: their
     * keystone is the one on the line.
     */
    static boolean isCommitLever(ServerPlayer player, BlockPos pos) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !RunSession.canChooseDoor(record) || !player.getUUID().equals(record.owner)
                || record.stagingCellOrigin == null) {
            return false;
        }
        return RoomTemplateGenerator.leverPos(record.stagingCellOrigin, record.roomDungeonDoor).equals(pos);
    }

    /**
     * M19 19.6: whether {@code pos} is the engine terminal in this player's
     * room: the respawn anchor on the wall to the left of the selector wall
     * ({@link RoomGeometry#leftOf}). Any member of the room may view or feed
     * it, since fuel is per-player inventory, so this does not require the
     * owner.
     */
    static boolean engineTerminalAt(ServerPlayer player, BlockPos pos) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || record.stagingCellOrigin == null) {
            return false;
        }
        return RoomTemplateGenerator.enginePos(record.stagingCellOrigin, record.roomDungeonDoor).equals(pos);
    }

    /**
     * (M56) Plans the dungeon for door {@code step} and stamps only its
     * entrance cell beyond the staging room's selected door, replacing the
     * selector door with an iron-bars window so the party can see in. The
     * plan and preview cell origin are stored on the record for
     * {@link #commitDoor} to use. If a previous preview is active, it is
     * purged and its door restored first.
     *
     * @return {@code true} if the preview was stamped successfully
     */
    static boolean previewDoor(MinecraftServer server, ServerLevel level,
                               InstanceRecord record, Keystone.Offer offer, int step) {
        // M68: refuse to plan a new floor while a content reload is in flight,
        // so a preview is never built against a half published manifest.
        if (!ContentReload.generationAllowed()) {
            PocketDungeonsMod.LOG.warn("Refusing door preview: content reload in progress");
            return false;
        }
        // M65: the phase must be HOME, FLOOR_CLEARED, or PREVIEW (switching
        // doors during an active preview). Any other phase is a programming
        // error, not a player error. In PREVIEW, clearPreview below purges
        // the old preview and transitions back to HOME or FLOOR_CLEARED
        // before the new preview stamps and transitions to PREVIEW again.
        if (!RunSession.require(record, RunSession.Phase.HOME, RunSession.Phase.FLOOR_CLEARED,
                RunSession.Phase.PREVIEW)) {
            return false;
        }

        DoorMask.Direction dungeonDoor = record.roomDungeonDoor;
        ThemeManifest.Entry theme = ThemeManifest.current().byId(offer.theme());
        String bagId = DungeonLog.forServer(server).bagOf(record.owner);
        // M65: use conservative live capabilities on later floors. On
        // floor 0 (HOME phase), the original bag enum is a fair proof:
        // the party just chose it and has not entered yet. On later
        // floors (FLOOR_CLEARED phase), tools may have been spent, lost
        // or used up, so the bag enum is no longer proof. Use the live
        // party size (members actually present and online) for the mob
        // tag, and keep the bag tags as a conservative baseline: the
        // generator still proves solvability with them, but the live
        // party size reflects who is actually here.
        int livePartySize = record.members.size();
        if (record.phase == RunSession.Phase.FLOOR_CLEARED) {
            livePartySize = countLiveMembers(server, record);
        }
        Set<String> bagTags = BagTags.seed(bagId, livePartySize);

        // M66: read the keystone's recipe tags (without clearing them) and
        // resolve a RunRecipePlan. The plan carries every recipe effect the
        // floor is previewed against. An impossible guarantee (Deep Dark at
        // tier below 3) refuses here, before the catalyst is spent.
        ServerPlayer owner = server.getPlayerList().getPlayer(record.owner);
        CompoundTag recipeTags = new CompoundTag();
        if (owner != null) {
            ItemStack keystone = Keystone.findHeld(owner);
            if (keystone != null) {
                recipeTags = CubeRecipe.recipesOf(keystone);
            }
        }
        EnumSet<Affix> baseAffixes = AffixMath.effective(record.owner, offer.level(), offer.affixes());
        RunRecipePlan.Refusal[] refusal = new RunRecipePlan.Refusal[1];
        long previewSeed = level.getRandom().nextLong();
        RunRecipePlan recipePlan = RunRecipePlan.resolve(previewSeed, offer.level(),
                baseAffixes, bagTags, recipeTags, refusal);
        if (recipePlan == null) {
            // The recipe refuses. Restore the escrowed catalyst and report.
            if (owner != null) {
                ItemStack keystone = Keystone.findHeld(owner);
                if (keystone != null) {
                    CubeRecipe.restoreCatalyst(owner, keystone);
                }
            }
            String reason = refusal[0] != null ? refusal[0].reason : "unknown refusal";
            if (owner != null) {
                owner.sendSystemMessage(Component.literal(reason)
                        .withStyle(ChatFormatting.RED));
            }
            PocketDungeonsMod.LOG.warn("Recipe refused for door {} preview: {}", step, reason);
            return false;
        }

        // M66: a second click on the same offer with the same inputs does not
        // farm random entrances. If the existing preview is for the same step
        // and the recipe revision matches, reuse the frozen plan.
        if (record.previewCellOrigin != null
                && record.previewOfferStep == step
                && record.previewRecipePlan != null
                && record.previewRecipePlan.matchesRevision(recipePlan.revision)
                && record.previewRecipePlan.offerLevel == offer.level()) {
            // Same offer, same inputs: the frozen preview is still valid.
            return true;
        }

        // Purge any existing preview before generating the new one.
        clearPreview(level, record);

        // M66: apply the recipe's path length bonus to the planning bounds.
        int minPath = PocketDungeonsConfig.pathLengthMin() + recipePlan.pathLengthBonus();
        int maxPath = PocketDungeonsConfig.pathLengthMax() + recipePlan.pathLengthBonus();

        // Try up to 4 base seeds; each one runs the full attempt budget
        // inside LayoutPlanner.plan. A single base seed can fail all its
        // attempts on an unlucky room-library interaction, but a different
        // seed reshuffles the shape entirely.
        DungeonPlan plan = null;
        long lastSeed = 0;
        int lastAttempts = 0;
        String lastReason = null;
        for (int round = 0; round < 4 && plan == null; round++) {
            long seed = level.getRandom().nextLong();
            LayoutPlanner.Outcome outcome = LayoutPlanner.plan(
                    seed, RoomManifest.current(), PocketDungeonsConfig.planAttemptBudget(),
                    minPath, maxPath,
                    PocketDungeonsConfig.branchProbability(), PocketDungeonsConfig.loopProbability(),
                    PocketDungeonsConfig.maxGridSpan(), theme == null ? null : theme.meta().roomTheme,
                    dungeonDoor, bagTags, recipePlan);
            plan = outcome.plan();
            lastSeed = outcome.finalSeed();
            lastAttempts = outcome.attemptsUsed();
            lastReason = outcome.failureReason();
        }

        if (plan == null) {
            PocketDungeonsMod.LOG.warn(
                    "Planning failed for door preview after 4 rounds of {} attempts each (last: seed {}, {})",
                    PocketDungeonsConfig.planAttemptBudget(), lastSeed, lastReason);
            return false;
        }

        // The entrance cell is one cell beyond the staging room in the
        // dungeon door direction. The plan's own cell set may reach negative
        // x/z, so the world origin is shifted back by the minimum cell
        // coordinates, then forward one cell in the dungeon direction.
        int minX = plan.cells().stream().mapToInt(PlanCell::x).min().orElse(0);
        int minZ = plan.cells().stream().mapToInt(PlanCell::z).min().orElse(0);
        BlockPos planOrigin = CellGeometry.offsetInDirection(
                record.stagingCellOrigin, dungeonDoor, RoomGeometry.CELL)
                .offset(minX * RoomGeometry.CELL, 0, minZ * RoomGeometry.CELL);
        PlanGeometry geometry = PlanGeometry.of(planOrigin, plan.cells());
        BlockPos entranceOrigin = geometry.cellOrigin(plan.entrance());

        // Force-load the entrance cell's chunk so the stamp lands.
        forceLoad(level, Set.of(entranceOrigin), true);

        // M66: use the recipe plan's effective affixes (ominous, feral added).
        EnumSet<Affix> affixes = recipePlan.effectiveAffixes(baseAffixes);
        try {
            LayoutStamper.stampEntranceOnly(level, planOrigin, plan, offer.level(), affixes,
                    offer.theme());
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Preview stamp failed for door {}", step, e);
            clearCellSync(level, entranceOrigin, List.of(record.stagingCellOrigin));
            forceLoad(level, Set.of(entranceOrigin), false);
            return false;
        }

        // Replace the selected door with a glass window. The selector
        // door for this step is at a fixed offset within the door slot; the
        // simplest approach is to fill the entire door slot with glass, since
        // the other two doors are still selector doors and this one is now a
        // window.
        RoomBuilder.windowDoor(level, record.stagingCellOrigin, mcDirection(dungeonDoor));

        // Place a glass window in the entrance cell's wall on the staging side
        // so the party can see through the staging room's window into the room.
        // The entrance cell's own door faces into the dungeon (toward the next
        // cell); the wall facing the staging room is solid and would block the
        // view without this. Glass (not open air) so the player cannot walk in
        // before committing.
        DoorMask.Direction stagingSide = CellGeometry.opposite(dungeonDoor);
        RoomBuilder.windowDoor(level, entranceOrigin, mcDirection(stagingSide));

        record.previewPlan = plan;
        record.previewCellOrigin = entranceOrigin;
        record.previewRecipePlan = recipePlan;
        record.previewOfferStep = step;
        RunSession.transition(record, RunSession.Phase.PREVIEW);
        return true;
    }

    /**
     * (M56) Purges the current preview cell and restores the selector door
     * that was replaced by the window. No-op if no preview is active.
     *
     * <p>M66: also clears the recipe plan and restores any escrowed catalyst
     * to the owner. A cancelled preview does not charge for nothing.
     */
    static void clearPreview(ServerLevel level, InstanceRecord record) {
        if (record.previewCellOrigin == null) {
            return;
        }
        clearCellSync(level, record.previewCellOrigin, List.of(record.stagingCellOrigin));
        forceLoad(level, Set.of(record.previewCellOrigin), false);
        // Restore the selector door by re-placing all three selector doors.
        // The window was in the full door slot, so this overwrites it.
        RoomTemplateGenerator.placeSelectorDoors(level, record.stagingCellOrigin,
                record.roomDungeonDoor);
        record.previewPlan = null;
        record.previewCellOrigin = null;
        // M66: clear the recipe plan and restore the escrowed catalyst.
        if (record.previewRecipePlan != null) {
            restoreEscrowedCatalyst(level.getServer(), record);
            record.previewRecipePlan = null;
        }
        record.previewOfferStep = 0;
        // M65: return to the phase that preceded the preview. If the safe
        // room is loaded and floorIndex is 0, that is HOME; otherwise the
        // party is between floors and the phase is FLOOR_CLEARED.
        RunSession.transition(record, record.floorIndex > 0
                ? RunSession.Phase.FLOOR_CLEARED : RunSession.Phase.HOME);
    }

    /**
     * M66: Converts a {@link RunRecipePlan} back to a {@link CompoundTag} for
     * {@link InstanceRecord#recipeTags}, so the generation path that reads
     * recipe tags during the run continues to work. The tag set is the plan's
     * active recipe keys, each set to {@code true}.
     */
    private static CompoundTag recipeTagsFromPlan(RunRecipePlan plan) {
        CompoundTag tags = new CompoundTag();
        for (String key : plan.activeRecipes) {
            tags.putBoolean(key, true);
        }
        return tags;
    }

    /**
     * M66: Restores the escrowed catalyst to the record's owner. Called when
     * a preview is cancelled, so a consumed catalyst does not charge for
     * nothing. Finds the owner's held keystone and restores via
     * {@link CubeRecipe#restoreCatalyst}.
     */
    private static void restoreEscrowedCatalyst(MinecraftServer server, InstanceRecord record) {
        ServerPlayer owner = server.getPlayerList().getPlayer(record.owner);
        if (owner == null) {
            return;
        }
        ItemStack keystone = Keystone.findHeld(owner);
        if (keystone != null) {
            CubeRecipe.restoreCatalyst(owner, keystone);
        }
    }

    /**
     * (M56) Commits the current preview: stamps the rest of the dungeon
     * around the already-stamped entrance cell, replaces the window with a
     * walkable doorway, and starts the run. The plan stored by
     * {@link #previewDoor} is re-used so the entrance cell is the exact one
     * the party previewed.
     *
     * @return {@code true} if the dungeon was stamped and the run started
     */
    static boolean commitDoor(MinecraftServer server, ServerLevel level,
                              InstanceRecord record, Keystone.Offer offer, int step) {
        // M68: refuse to stamp a new floor while a content reload is in flight.
        // The preview this commit consumes was reconciled away if its
        // references went stale, so a reload that removed a referenced room
        // leaves nothing to commit.
        if (!ContentReload.generationAllowed()) {
            PocketDungeonsMod.LOG.warn("Refusing door commit: content reload in progress");
            return false;
        }
        // M65: the phase must be PREVIEW. commitDoor consumes the preview
        // and starts the floor; calling it from any other phase is a
        // programming error.
        if (!RunSession.require(record, RunSession.Phase.PREVIEW)) {
            return false;
        }
        DungeonPlan plan = record.previewPlan;
        if (plan == null || record.previewCellOrigin == null) {
            return false;
        }

        // M2/M3: clear the previous dungeon before generating the next one.
        RunLifecycle.resetForNextDungeon(server, record);

        // PD-13: release the previous dungeon's force-load tickets.
        if (record.layout != null) {
            forceLoad(level, record.layout.geometry().chunks(), false);
        }

        DoorMask.Direction dungeonDoor = record.roomDungeonDoor;
        EnumSet<Affix> affixes = AffixMath.effective(record.owner, offer.level(), offer.affixes());

        // M66: apply recipe effects from the frozen preview plan, not from
        // re-read recipe tags. The preview resolved the affix set; commit
        // uses the same set the player saw.
        if (record.previewRecipePlan != null) {
            affixes = record.previewRecipePlan.effectiveAffixes(affixes);
        }

        // Recompute the plan origin the same way previewDoor did.
        int minX = plan.cells().stream().mapToInt(PlanCell::x).min().orElse(0);
        int minZ = plan.cells().stream().mapToInt(PlanCell::z).min().orElse(0);
        BlockPos planOrigin = CellGeometry.offsetInDirection(
                record.stagingCellOrigin, dungeonDoor, RoomGeometry.CELL)
                .offset(minX * RoomGeometry.CELL, 0, minZ * RoomGeometry.CELL);
        PlanGeometry geometry = PlanGeometry.of(planOrigin, plan.cells());
        forceLoad(level, geometry.chunks(), true);

        InstanceLayout layout;
        try {
            // stampBehindLobby skips the entrance cell since it was already
            // stamped by previewDoor. The owner's room overlay is skipped
            // too (the safe room is despawned; the entrance is a dungeon room).
            layout = LayoutStamper.stampBehindLobby(level, planOrigin, plan, offer.level(), affixes,
                    null, offer.theme());
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Commit stamp failed behind the staging room at {}",
                    record.stagingCellOrigin.toShortString(), e);
            List<BlockPos> keepRoom = List.of(record.stagingCellOrigin);
            for (BlockPos cellOrigin : geometry.cellOrigins()) {
                if (cellOrigin.equals(record.stagingCellOrigin)) {
                    continue;
                }
                clearCellSync(level, cellOrigin, keepRoom);
            }
            forceLoad(level, geometry.chunks(), false);
            return false;
        }

        // Clear the staging room's bedrock face AFTER stampBehindLobby,
        // because BedrockEnvelope.apply inside stampBehindLobby places
        // bedrock around all plan cells (including the entrance cell), and
        // the entrance cell's staging-side face has no occupied neighbour
        // in the plan geometry, so bedrock lands there. This clears it.
        BedrockEnvelope.clearFace(level, record.stagingCellOrigin, dungeonDoor);

        // Replace the windows with walkable doorways. Both the staging room's
        // door slot and the entrance cell's staging-side wall had glass windows
        // from previewDoor; both need to become open doorways on commit.
        RoomBuilder.openDoor(level, record.stagingCellOrigin, mcDirection(dungeonDoor));
        BlockPos entranceOrigin = geometry.cellOrigin(plan.entrance());
        RoomBuilder.openDoor(level, entranceOrigin, mcDirection(CellGeometry.opposite(dungeonDoor)));
        RoomTemplateGenerator.clearSelectorDoors(level, record.stagingCellOrigin, dungeonDoor);
        RoomTemplateGenerator.placePostSelectionDoors(level, record.stagingCellOrigin, dungeonDoor);

        record.layout = layout;
        record.affixes = affixes;
        record.theme = offer.theme();
        record.awaitingDoorChoice = false;
        record.chosenStep = step;
        record.freeDoor = offer.free();
        record.previewPlan = null;
        record.previewCellOrigin = null;
        // M66: the recipe plan is consumed. The catalyst escrow is cleared
        // (the catalyst is permanently spent on successful commit). Store
        // the recipe plan on the record for the generation path to read
        // during the run (compass study list, etc.).
        record.recipeTags = record.previewRecipePlan != null
                ? recipeTagsFromPlan(record.previewRecipePlan) : null;
        // M66: populate the completion study list for the compass recipe.
        // The list is the run's room names, which the completion line
        // reports when the compass effect is active.
        record.situations.clear();
        for (DungeonPlan.PlacedRoom room : plan.rooms().values()) {
            RoomManifest.Entry entry = RoomManifest.current().byName(room.name());
            if (entry != null && entry.meta.content != null && !entry.meta.content.isBlank()) {
                record.situations.add(entry.meta.content);
            } else {
                record.situations.add(room.name());
            }
        }
        record.previewRecipePlan = null;
        record.previewOfferStep = 0;
        RunSession.transition(record, RunSession.Phase.ACTIVE);

        // M11: a boss-themed run gets its one proof encounter.
        AdventureGraph.Node themeNode = AdventureGraphs.current().graph().node(offer.theme());
        if (themeNode != null && themeNode.kind() == AdventureGraph.Kind.BOSS) {
            BossContent.spawn(level, layout.terminal(), offer.level());
        }

        record.clearPreviousRunState();

        if (record.timer != null) {
            record.timer.close();
            record.timer = null;
        }
        if (!record.untimed) {
            int seconds = record.freeDoor ? PocketDungeonsConfig.door1TimerSeconds()
                    : KeystoneMath.timerSeconds(PocketDungeonsConfig.timerBaseSeconds(),
                            PocketDungeonsConfig.timerPerRoomSeconds(), layout.pathLength());
            record.timer = new RunTimer(layout.keystoneLevel(), seconds, layout.roomCount());
        }

        record.selectedStep = 0;
        RoomTemplateGenerator.clearBulbs(level, record.stagingCellOrigin, record.roomDungeonDoor);
        DungeonScreen.updateDoor(level, record, DungeonScreen.runContent(level, record));

        for (UUID member : record.members.keySet()) {
            ServerPlayer inside = server.getPlayerList().getPlayer(member);
            if (inside == null) {
                continue;
            }
            if (record.timer != null) {
                record.timer.addPlayer(inside);
            }
            clearTrialOmen(inside);
            applyTrialOmen(inside, record);
        }
        return true;
    }

    // ---- quit: reset to lobby ----------------------------------------------

    /**
     * Resets a live run back to its lobby state without ejecting anyone:
     * pulls every member into the safe room, clears the dungeon cells beyond
     * it, seals and bedrocks all four walls, re-arms the selector doors and
     * furniture, resets the door screen to idle, and returns the record to
     * {@code awaitingDoorChoice}. Called by {@link RunLifecycle#quitDoor}
     * so {@code /dungeon quit} is a "pick a new door" action, not an eject.
     *
     * <p>The room stays in its current cell (it has not moved, since the run
     * was not completed). The keystone penalty is applied by the caller
     * before this runs; this method only handles the physical reset and the
     * record state.
     */
    static void resetToLobby(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        // M56: clear any active door preview before resetting.
        clearPreview(level, record);
        if (level == null) {
            return;
        }

        // Release the current layout's force-load tickets before clearing.
        if (record.layout != null) {
            forceLoad(level, record.layout.geometry().chunks(), false);
        }

        // Clear every cell of the current dungeon and the staging room. The
        // safe room was already despawned at commit time, so there is no
        // safe room cell to keep. If the safe room is still active (quit
        // before commit), keep it.
        List<BlockPos> keepCells = new java.util.ArrayList<>();
        if (record.roomCellOrigin != null) {
            keepCells.add(record.roomCellOrigin);
        }
        if (record.layout != null) {
            for (BlockPos cellOrigin : record.layout.geometry().cellOrigins()) {
                if (keepCells.contains(cellOrigin)) {
                    continue;
                }
                clearCellSync(level, cellOrigin, keepCells);
            }
        }
        // Clear the staging room too, wherever it is.
        if (record.stagingCellOrigin != null && !keepCells.contains(record.stagingCellOrigin)) {
            clearCellSync(level, record.stagingCellOrigin, keepCells);
        }

        // Re-stamp the safe room at the slot origin from RoomStore. If the
        // safe room was still active (quit before commit), it is already
        // standing; save it first so any edits survive the re-stamp.
        BlockPos safeOrigin = record.origin;
        DoorMask.Direction dungeonDir = DoorMask.Direction.SOUTH;
        if (record.roomCellOrigin != null) {
            RunLifecycle.saveRoom(level, server, record);
        }
        clearCellSync(level, safeOrigin, List.of());
        stampSafeRoom(level, server, record.owner, safeOrigin);
        record.roomCellOrigin = safeOrigin;
        record.roomDungeonDoor = dungeonDir;

        // Stamp a fresh staging room adjacent to the safe room.
        BlockPos stagingOrigin = CellGeometry.offsetInDirection(safeOrigin, dungeonDir, RoomGeometry.CELL);
        level.setChunkForced(stagingOrigin.getX() >> 4, stagingOrigin.getZ() >> 4, true);
        stampStagingRoom(level, stagingOrigin, dungeonDir);
        RoomBuilder.openDoor(level, safeOrigin, mcDirection(dungeonDir));
        BedrockEnvelope.clearFace(level, safeOrigin, dungeonDir);
        record.stagingCellOrigin = stagingOrigin;
        // Summon fresh screens at the staging room.
        DungeonScreen.summonDoor(level, stagingOrigin, dungeonDir,
                DungeonScreen.idleContent(level, record.owner));
        DungeonScreen.summonEngine(level, stagingOrigin, dungeonDir,
                DungeonScreen.engineContent(null));
        DungeonScreen.summonTracker(level, stagingOrigin, dungeonDir,
                DungeonScreen.trackerContent(level.getServer(), record.owner));

        // Pull every member into the safe room.
        BlockPos roomCentre = safeOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        for (UUID member : record.members.keySet()) {
            ServerPlayer inside = server.getPlayerList().getPlayer(member);
            if (inside == null) {
                continue;
            }
            if (inside.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)
                    && roomOwnerAt(inside.blockPosition()) == null) {
                teleport(server, inside, PocketDungeonsMod.DUNGEON_LEVEL,
                        net.minecraft.world.phys.Vec3.atBottomCenterOf(roomCentre), 0.0f, 0.0f);
            }
        }

        // M48: re-arm the bag chest for the fresh door choice.
        clearBagChest(level, safeOrigin);
        placeBagChestForParty(level, server, record);
        record.selectedStep = 0;

        // Close the timer and clear trial omen from every member.
        if (record.timer != null) {
            for (UUID member : record.members.keySet()) {
                ServerPlayer inside = server.getPlayerList().getPlayer(member);
                if (inside != null) {
                    record.timer.removePlayer(inside);
                    clearTrialOmen(inside);
                }
            }
            record.timer.close();
            record.timer = null;
        }

        // Reset the record to lobby state.
        record.layout = lobbyLayout(safeOrigin);
        record.affixes = EnumSet.noneOf(Affix.class);
        record.theme = null;
        record.awaitingDoorChoice = true;
        record.chosenStep = 0;
        record.freeDoor = false;
        record.floorIndex = 0;
        record.safeStaging = false;
        record.omen = 0;
        record.floorOmens.clear();
        record.recipeTags = null;
        record.previewRecipePlan = null;
        record.previewOfferStep = 0;
        record.clearPreviousRunState();
    }

    // ---- exit ---------------------------------------------------------------

    /**
     * Clears one cell and the one-block margin around it -- the margin is where
     * the bedrock envelope lives, so a clear that stopped at the cell wall would
     * leave the shell standing.
     *
     * <p>{@code keepCells} are cell origins whose own 16x16 volume must survive.
     * That margin is not empty space: offset {@code CELL} from one cell origin is
     * the <em>neighbouring</em> cell's local 0 -- its wall column. Clearing it
     * unconditionally erases a live neighbour's entire wall, leaving only
     * whatever is written back afterwards (a resealed door slot reads as an
     * inside-out wall: stone exactly where the doorway was, air everywhere
     * else). Any cell that is still standing when this runs has to be named
     * here.
     */
    static void clearCellSync(ServerLevel level, BlockPos origin, List<BlockPos> keepCells) {
        // M61: clear below any lower story a cell may own. Over-clearing a
        // single-story cell is harmless: the extra volume is dungeon void.
        int maxOffset = RoomGeometry.storyOffset(RoomGeometry.MAX_SPAN_Y);
        for (int x = -1; x <= RoomGeometry.CELL; x++) {
            for (int y = -1 - maxOffset; y <= RoomGeometry.CEILING_Y + 1; y++) {
                for (int z = -1; z <= RoomGeometry.CELL; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (CellGeometry.insideAnyCell(pos, keepCells)) {
                        continue;
                    }
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
                                    | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
                }
            }
        }
        AABB bounds = new AABB(origin.getX() - 1, origin.getY() - 1 - maxOffset, origin.getZ() - 1,
                origin.getX() + RoomGeometry.CELL + 1, origin.getY() + RoomGeometry.CEILING_Y + 2,
                origin.getZ() + RoomGeometry.CELL + 1);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, bounds,
                e -> !(e instanceof ServerPlayer))) {
            entity.discard();
        }
    }

    /**
     * Pulls a player out of an instance in place of killing them. Health and
     * status are reset first: cancelling the death leaves them standing at zero
     * hearts, which would just kill them again on the next tick of fire damage.
     *
     * <p>Teleports to the player's room inside the dungeon dimension, not their
     * original entry point. The room is the safe base: the player can regroup
     * and re-enter the dungeon from there. Falls back to {@link #eject} (return
     * point) when the record has no room cell (admin/untimed runs).
     */
    private static void rescue(ServerPlayer player, InstanceRecord record) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }

        player.setHealth(player.getMaxHealth());
        player.removeAllEffects();
        player.clearFire();
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);

        ReturnPoint point = record.members.get(player.getUUID());
        // M55: during dungeon play the safe room is despawned, so the rescue
        // target is the staging room. The hadRoom check uses stagingCellOrigin
        // because that is the cell the player regroups in.
        boolean hadRoom = record.stagingCellOrigin != null && !record.visitInstance;
        int slot = record.slot;

        // PD-26: route through dropMember, the same detach every other exit
        // path uses, instead of a fifth hand-rolled copy that skipped the
        // T2.6 leadership check. An owner who dies mid-run with party members
        // present used to leave the run running without them; dropMember's
        // leadership branch now ends it for everyone, exactly as a voluntary
        // exit or a disconnect already would.
        RunLifecycle.dropMember(server, record, player.getUUID(), player, "death rescue");

        // dropMember's leadership branch purges the whole record synchronously
        // (InstanceRegistry.bySlot.remove), so this is how rescue tells
        // whether the run it was standing in still exists to be teleported
        // back into.
        boolean stillLive = InstanceRegistry.bySlot.get(slot) == record;
        if (stillLive && hadRoom) {
            // PD-63: this branch teleports the player straight back into the
            // same live instance they were just detached from, not out of
            // it: dropMember's detach() above already removed them from
            // record.members, InstanceRegistry.byMember, the timer and
            // Trial Omen. Left alone, they would land back inside a
            // dungeon that no longer knows they exist: onTick's member loop
            // only iterates record.members, so the spawner-clear cue, the
            // exit pad's completion check and the timer would all silently
            // ignore them from this point on, even though they are
            // standing right there. Re-admitting mirrors what admit() does
            // for a fresh entry, reusing the same point captured above
            // (before detach cleared it) rather than the player's current
            // in-dungeon position, which would make a bad return point.
            if (point != null) {
                record.members.put(player.getUUID(), point);
            }
            InstanceRegistry.byMember.put(player.getUUID(), record);
            if (record.timer != null) {
                record.timer.addPlayer(player);
            }
            applyTrialOmen(player, record);
            BlockPos roomCentre = record.stagingCellOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
            teleport(server, player, PocketDungeonsMod.DUNGEON_LEVEL,
                    Vec3.atBottomCenterOf(roomCentre), 0.0f, 0.0f);
        } else if (point != null) {
            // The run ended with them, or there was never a room to stand in
            // (admin build, untimed run, visit instance): the same fallback
            // eject already uses, back to wherever they actually came from.
            teleport(server, player, point.dimension(), point.pos(), point.yaw(), point.pitch());
        } else {
            sendToWorldSpawn(server, player);
        }

        player.sendSystemMessage(Component.literal(
                "The dungeon throws you out. You keep everything you were carrying.")
                .withStyle(ChatFormatting.RED));
        // U8 Stage 1: dying inside costs nothing. Under a wall clock the walk
        // back is already the cost; a penalty on top would double-charge it.
        RunLifecycle.returnKeystone(server, record, player.getUUID(), player, Keystones.Outcome.NO_CHANGE);
        if (stillLive) {
            announce(server, record, player.getName().getString() + " was thrown out of the dungeon.",
                    player.getUUID());
            purgeIfAbandonedLobby(server, record);
        }
    }

    /** Teleports one member to their own return point and drops them from the party. */
    static void eject(MinecraftServer server, InstanceRecord record, ServerPlayer player) {
        ReturnPoint point = detach(server, record, player.getUUID(), player);
        if (point != null) {
            // M6 T6.5: the exit does not consult the room itself. It does not
            // need to. Teleport falls back to sendHome when the return dimension
            // is gone, and by then this player has already been removed from the
            // record, so reenterOwnedInstance puts them back into the run they
            // were walking out of. That is the plan's "no-op when there is nowhere
            // to go", reached without a second code path: on a server with an
            // overworld the return point resolves and the fallback never fires.
            teleport(server, player, point.dimension(), point.pos(), point.yaw(), point.pitch());
        } else {
            sendToWorldSpawn(server, player);
        }
    }

    /**
     * M43.2: the one primitive for detaching a member from an instance's
     * registry footprint, used by {@link #eject}, {@link RunLifecycle#dropMember},
     * and the offline-member branches inside {@code InstanceTeardown.purge}/
     * {@code retireOrPurge}, all four of which used to independently
     * hand-roll their own subset of this. Room save, {@code members}/
     * {@code byMember} removal, and clearing {@code onPad} happen
     * unconditionally; timer and Trial Omen cleanup only when {@code player}
     * is online, since an offline member has no live {@code ServerPlayer} to
     * clear either against (the caller's own {@code timer.close()} or
     * teardown loop accounts for them separately).
     *
     * @return the member's return point, or {@code null} if they had none
     */
    static ReturnPoint detach(MinecraftServer server, InstanceRecord record, UUID member, ServerPlayer player) {
        // Before anything else, while the room is still exactly as they left it.
        RunLifecycle.saveRoomIfOwner(server, record, member);
        // PD-50: cached before byMember loses the association, so the leaving
        // swap that this detach's own teleport is about to trigger can still
        // find where to deliver this player's void inventory. See
        // lastRoomCellOrigin's javadoc.
        // M55: the safe room may be despawned during dungeon play, but the
        // player's inventory was swapped at the safe room's origin (the slot
        // origin). Cache that origin so the leaving swap can find it.
        if (record.origin != null && !record.visitInstance) {
            lastRoomCellOrigin.put(member, record.origin);
        }
        OmenSources.forget(member);
        ReturnPoint point = record.members.remove(member);
        InstanceRegistry.byMember.remove(member);
        record.onPad.remove(member);
        if (player != null) {
            if (record.timer != null) {
                record.timer.removePlayer(player);
            }
            clearTrialOmen(player);
        }
        return point;
    }

    /**
     * M65: counts party members who are online and in the dungeon
     * dimension right now. Used by {@link #previewDoor} to derive
     * conservative live capabilities on later floors: the mob tag
     * (party size >= 2) should reflect who is actually present, not
     * the original party size, since a member who disconnected is
     * not standing on the other plate.
     */
    private static int countLiveMembers(MinecraftServer server, InstanceRecord record) {
        int count = 0;
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null && player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                count++;
            }
        }
        return count;
    }

    /**
     * M65: whether all online members have left the old staging room
     * and entered the safe room during a silent homecoming. A member
     * who is offline, in another dimension, or still in the old
     * staging room has not crossed yet. An empty party (everyone
     * disconnected) is treated as crossed so the cleanup is not held
     * forever; the M63 recovery path handles their return.
     */
    private static boolean allMembersCrossed(MinecraftServer server, InstanceRecord record) {
        if (record.members.isEmpty()) {
            return true;
        }
        if (record.oldStagingCellOrigin == null || record.roomCellOrigin == null) {
            return true;
        }
        net.minecraft.world.phys.AABB oldStagingBounds = CellGeometry.cellBounds(record.oldStagingCellOrigin);
        net.minecraft.world.phys.AABB roomBounds = CellGeometry.cellBounds(record.roomCellOrigin);
        for (UUID member : record.members.keySet()) {
            ServerPlayer memberPlayer = server.getPlayerList().getPlayer(member);
            if (memberPlayer == null) {
                // Offline: does not block cleanup. M63 recovery handles
                // their return.
                continue;
            }
            if (!memberPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                // Not in the dungeon dimension: does not block cleanup.
                continue;
            }
            net.minecraft.world.phys.Vec3 pos = net.minecraft.world.phys.Vec3.atCenterOf(memberPlayer.blockPosition());
            if (oldStagingBounds.contains(pos) && !roomBounds.contains(pos)) {
                // Still in the old staging room, not in the room yet.
                return false;
            }
        }
        return true;
    }

    /**
     * M65: releases the old staging room and old floor cells after all
     * members have crossed into the safe room during a silent
     * homecoming, then sets up the new staging room adjacent to the
     * room. Also summons fresh screens at the new staging room and
     * clears the pending cleanup flag.
     */
    private static void completeHomecomingCleanup(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }

        // Release the old layout's force-load tickets.
        if (record.oldLayoutForCleanup != null) {
            forceLoad(level, record.oldLayoutForCleanup.geometry().chunks(), false);
        }

        // Clear every cell of the old dungeon layout except the old
        // staging room (handled separately below) and the new room
        // (which is now record.roomCellOrigin).
        List<BlockPos> keepCells = new java.util.ArrayList<>();
        if (record.roomCellOrigin != null) {
            keepCells.add(record.roomCellOrigin);
        }
        if (record.oldLayoutForCleanup != null) {
            for (BlockPos cellOrigin : record.oldLayoutForCleanup.geometry().cellOrigins()) {
                // Skip the old staging room (it gets its own cleanup).
                if (record.oldStagingCellOrigin != null && cellOrigin.equals(record.oldStagingCellOrigin)) {
                    continue;
                }
                // Skip the new room (it is the party's actual room now).
                if (record.roomCellOrigin != null && cellOrigin.equals(record.roomCellOrigin)) {
                    continue;
                }
                clearCellSync(level, cellOrigin, keepCells);
            }
        }

        // Clear the old staging room.
        if (record.oldStagingCellOrigin != null) {
            clearCellSync(level, record.oldStagingCellOrigin, keepCells);
            level.setChunkForced(record.oldStagingCellOrigin.getX() >> 4,
                    record.oldStagingCellOrigin.getZ() >> 4, false);
        }

        // Set up the new staging room adjacent to the room, on the
        // opposite side from the old staging room. The room's dungeon
        // door direction (record.roomDungeonDoor) points back toward
        // the old staging room, so the new staging room goes on the
        // opposite side.
        DoorMask.Direction newDungeonDir = record.roomDungeonDoor;
        BlockPos newStagingOrigin = CellGeometry.offsetInDirection(
                record.roomCellOrigin, newDungeonDir, RoomGeometry.CELL);
        level.setChunkForced(newStagingOrigin.getX() >> 4, newStagingOrigin.getZ() >> 4, true);
        stampStagingRoom(level, newStagingOrigin, newDungeonDir);
        RoomBuilder.openDoor(level, record.roomCellOrigin, mcDirection(newDungeonDir));
        BedrockEnvelope.clearFace(level, record.roomCellOrigin, newDungeonDir);
        record.stagingCellOrigin = newStagingOrigin;
        record.roomDungeonDoor = newDungeonDir;

        // Summon fresh screens at the new staging room.
        DungeonScreen.summonDoor(level, newStagingOrigin, newDungeonDir,
                DungeonScreen.idleContent(level, record.owner));
        DungeonScreen.summonEngine(level, newStagingOrigin, newDungeonDir,
                DungeonScreen.engineContent(null));
        DungeonScreen.summonTracker(level, newStagingOrigin, newDungeonDir,
                DungeonScreen.trackerContent(server, record.owner));

        // Clear the cleanup flag and old references.
        record.pendingHomecomingCleanup = false;
        record.oldStagingCellOrigin = null;
        record.oldLayoutForCleanup = null;
    }

    /**
     * Whether anybody on this record is online, standing in the dungeon
     * dimension, and not away from the keyboard. Deliberately stricter than
     * {@code members.isEmpty()} on all three counts: membership survives a
     * disconnect until the sweep in {@link #onTick} notices, it survives an
     * admin teleport out of the dimension until the same sweep does, and a
     * player can sit in a finished dungeon indefinitely without using it.
     */
    private static boolean hasActiveMember(MinecraftServer server, InstanceRecord record) {
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null
                    && player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)
                    && !isAway(player)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code player} has done nothing for {@code afkSeconds}.
     *
     * <p>Vanilla's own idle clock rather than a position check of this mod's
     * own: {@code ServerPlayer.getLastActionTime} is reset by every packet
     * handler that means the player is present at the keyboard, movement and
     * container clicks included, which is exactly the case this has to get
     * right. Somebody sorting the chests they just filled has not moved a
     * block and is plainly not away. It is also what the vanilla
     * {@code player-idle-timeout} property measures, so an operator who has
     * tuned that already knows this number.
     */
    private static boolean isAway(ServerPlayer player) {
        int afkSeconds = PocketDungeonsConfig.afkSeconds();
        if (afkSeconds <= 0) {
            return false; // the check is disabled; presence alone holds the slot
        }
        return Util.getMillis() - player.getLastActionTime() > afkSeconds * 1000L;
    }

    /**
     * An abandoned lobby (M2/M3) -- one that never got a door choice and now
     * has nobody in it -- holds a slot and a force-load ticket for nothing,
     * unlike a real run, which {@code onTick} watches for exactly this reason.
     */
    static void purgeIfAbandonedLobby(MinecraftServer server, InstanceRecord record) {
        if (record.awaitingDoorChoice && record.chosenStep == 0 && record.members.isEmpty()) {
            InstanceTeardown.purge(server, record, "lobby abandoned");
        }
    }

    static void announce(MinecraftServer server, InstanceRecord record,
                                 String message, UUID except) {
        for (UUID member : record.members.keySet()) {
            if (member.equals(except)) {
                continue;
            }
            ServerPlayer other = server.getPlayerList().getPlayer(member);
            if (other != null) {
                other.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    /** Fires queued JOIN-triggered recovery teleports once their delay elapses. */
    private static void processJoinRecoveries(MinecraftServer server) {
        if (pendingJoinRecoveries.isEmpty()) {
            return;
        }
        for (Iterator<PendingJoinRecovery> it = pendingJoinRecoveries.iterator(); it.hasNext(); ) {
            PendingJoinRecovery pending = it.next();
            if (--pending.ticksRemaining > 0) {
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player);
            if (player == null) {
                continue; // disconnected again before the recovery fired
            }
            // The disconnect path clears this too, but a crash or a hard restart
            // fires no disconnect -- the player simply reappears inside the void
            // still carrying it. This is the last door Trial Omen could have walked
            // out through, and it is the one effect this mod must never export.
            clearTrialOmen(player);

            if (pending.point != null) {
                teleport(server, player, pending.point.dimension(), pending.point.pos(),
                        pending.point.yaw(), pending.point.pitch());
            } else {
                // M6 T6.5: no return point means this player has no recorded
                // "outside": a crash before admit(), or a first join straight
                // into the void. World spawn is the wrong answer on a server that
                // has emptied its overworld; sendHome asks for the room first.
                sendHome(server, player);
            }
            player.sendSystemMessage(Component.literal("Your dungeon was closed while you were away.")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    // ---- lifecycle watcher --------------------------------------------------

    /**
     * Repaints every online player's keystone remote from server state.
     *
     * <p>The single refresh path. A login, a completion, a depletion, a remote
     * pulled out of a chest and one handed over by a friend are all the same
     * event as far as this is concerned -- "the label disagrees with the store" --
     * and all are corrected within one watcher interval. That is why there is no
     * item-pickup hook and no mixin: see {@link Keystone#reconcile}.
     *
     * <p>The scan is a {@code CUSTOM_DATA} lookup per slot and the write only
     * fires on an actual mismatch, so the steady state is a few dozen component
     * reads per player per interval.
     */
    private static void reconcileKeystones(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        DungeonLog log = DungeonLog.forServer(server);
        for (ServerPlayer player : players) {
            DungeonLog.Entry entry = log.get(player.getUUID());
            if (entry.keystoneLevel() <= 0) {
                continue;
            }
            Keystone.reconcile(player, entry.keystoneLevel(),
                    AffixMath.effective(player.getUUID(), entry.keystoneLevel(),
                            AffixMath.parse(entry.keystoneAffix())));
        }
    }

    private static void onTick(MinecraftServer server) {
        if (++tickCounter % PocketDungeonsConfig.watchIntervalTicks() != 0) {
            return;
        }

        // Ahead of the instance sweep and outside its empty check: a remote goes
        // stale in the overworld, where there is no instance at all.
        reconcileKeystones(server);

        // PD-44: same reasoning as reconcileKeystones above. A player who
        // disconnected inside and never logged back in leaves an entry with
        // no live instance behind it at all, so this has to run whether or
        // not bySlot is empty.
        long nowForReturns = server.overworld().getGameTime();
        pendingReturns.values().removeIf(pending -> pending.expiresAtTick() <= nowForReturns);

        if (InstanceRegistry.bySlot.isEmpty()) {
            return;
        }

        int interval = PocketDungeonsConfig.watchIntervalTicks();
        long now = server.overworld().getGameTime();
        for (InstanceRecord record : new ArrayList<>(InstanceRegistry.bySlot.values())) {
            // T2.5: a lingering quarry has no timer, no reward grace, and no
            // members to watch for -- it is exempt from every check below until
            // RunLifecycle.enter() purges it as the next run's opening move.
            if (record.lingering) {
                continue;
            }
            // M25: a Pocket2 child has its own countdown and none of the outer
            // run's lifecycle: no keystone expiry, no grace window, no
            // spawner-clear gate, no completion pad. Everything about it lives
            // in Pocket2.tickChild.
            if (record.parentSlot >= 0) {
                Pocket2.tickChild(server, record, now, interval);
                continue;
            }
            if (record.timer != null) {
                record.timer.tick(interval);
            }

            // M65: silent homecoming cleanup. After returnToSafe stamps
            // the saved room behind the final staging door and opens it,
            // the party walks through physically. Once all members have
            // left the old staging room, release the old floor cells and
            // the old staging room, then set up the new staging room
            // adjacent to the room.
            if (record.pendingHomecomingCleanup) {
                if (allMembersCrossed(server, record)) {
                    completeHomecomingCleanup(server, record);
                }
                // Skip the rest of the loop for this record while
                // cleanup is pending: the run is not active, the grace
                // window does not apply, and the member sweep below
                // would interfere with the crossing detection.
                continue;
            }

            // U8 Stage 1: the two end conditions, both independent of membership.
            if (record.isKeystoneRun()) {
                // M65: ordinary-floor timeout depletion is gone. The clock
                // still ticks (for display and the SPEEDRUNNER bounty, now
                // "low-omen completion" rather than "finished before the
                // clock"), but it no longer depletes the keystone. The
                // omen system replaces the clock as the penalty: a bad
                // floor (high omen) means fewer chests and no level up at
                // the safe visit. The quitDoor path still depletes
                // voluntarily; expireTimedOut is no longer called from
                // onTick.
                if (!record.completed.isEmpty() && record.expiresAtTick == 0) {
                    record.expiresAtTick = now + PocketDungeonsConfig.rewardRoomGraceSeconds() * 20L;
                }
                // The grace window counts idleness, not elapsed time since the
                // run ended. Armed once at completion and left to run down, it
                // was a wall clock deadline that fired on whoever was standing
                // in the reward room at the time: finish a dungeon, spend ten
                // minutes sorting the chests you just filled, and get ejected
                // mid-sort with the run long over and nothing to warn you. What
                // the window is actually for is stopping a finished run from
                // holding its slot after everyone has gone, so it restarts for
                // as long as anybody is still in there and only runs down once
                // the instance is empty, or everyone left in it has gone away
                // from the keyboard: standing in a finished dungeon is not the
                // same as using it, and an idle client would otherwise hold the
                // slot for as long as the connection lasted. A disconnect drops
                // them through the member sweep below either way.
                if (record.expiresAtTick != 0 && hasActiveMember(server, record)) {
                    record.expiresAtTick = now + PocketDungeonsConfig.rewardRoomGraceSeconds() * 20L;
                }
                if (record.expiresAtTick != 0 && now >= record.expiresAtTick) {
                    InstanceTeardown.retireOrPurge(server, record, "reward room grace elapsed");
                    continue;
                }
            }

            // M22: the spawner-cleared cue. Watched here, once per cell per
            // run: the moment every trial spawner in a cell sits at COOLDOWN,
            // each member standing inside that cell hears it. A divergence
            // from the plan's "one line per call site": there is no per-cell
            // clear event to hook, so this is a small watcher on the same
            // interval the rest of onTick uses.
            if (record.isKeystoneRun() && record.completed.isEmpty()) {
                watchSpawnerClears(server, record);
            }

            for (UUID member : new ArrayList<>(record.members.keySet())) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                if (player == null) {
                    RunLifecycle.dropMember(server, record, member, null, "member offline");
                    continue;
                }
                // An admin teleport or any other route out of the dimension counts
                // as leaving -- otherwise a member who is not here holds the party
                // open indefinitely.
                if (!player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                    RunLifecycle.dropMember(server, record, member, player, "member left the dimension");
                    continue;
                }
                // A member who reached the run after its clock started -- invited
                // mid-run, re-entered their own live instance, or simply walked
                // back into the dimension -- has no bar until something attaches
                // them to one. Doing it here rather than at each entry point means
                // every route in is covered by construction, including whichever
                // ones get added next: the lobby-first rework already produced one
                // bug of exactly this shape by adding an entry order that admit()
                // had not been written for.
                //
                // <p>Costs a hash lookup per member per interval and nothing else.
                // {@code ServerBossEvent.addPlayer} is a {@code Set.add} whose
                // result gates the packet send, so a member already watching is
                // added again to nothing.
                if (record.timer != null) {
                    record.timer.addPlayer(player);
                }
                PlanCell here = record.layout.geometry().cellAt(player.blockPosition());
                if (here != null && record.visited.add(here) && record.timer != null) {
                    record.timer.notePresence(record.visited.size());
                }
                if (player.getY() < record.origin.getY() - PocketDungeonsConfig.voidGuardDepth()) {
                    // Rooms are sealed boxes, so this should not happen -- but the
                    // alternative to a guard is an unrecoverable fall through the void.
                    BlockPos entrance = record.layout.entrance();
                    player.teleportTo(player.level(), entrance.getX() + 0.5, entrance.getY(),
                            entrance.getZ() + 0.5, Set.of(), record.layout.entranceYaw(), 0.0f, false);
                    continue;
                }
                // M33: the guided Tame a Wolf task has no Fabric event to hook
                // (fabric-api ships none for TamableAnimal#tame, and the
                // one-mixin budget is already spent on CustomClickMixin -- see
                // CONVENTIONS.md), so it is a reconciliation scan like this
                // watcher's other checks rather than an edge: any tick this
                // member has that task active and a wolf they own is standing
                // nearby, progress fires. progress() is idempotent past the
                // task's target, so scanning every watch interval rather than
                // only on a real taming edge costs nothing extra once done.
                if (TaskTracker.activeTask(player) == TaskTracker.Task.TAME_WOLF
                        && !player.level().getEntitiesOfClass(
                                net.minecraft.world.entity.animal.wolf.Wolf.class,
                                player.getBoundingBox().inflate(8),
                                wolf -> wolf.isTame() && wolf.getOwnerReference() != null
                                        && member.equals(wolf.getOwnerReference().getUUID())).isEmpty()) {
                    TaskTracker.progress(player, TaskTracker.Task.TAME_WOLF, 1);
                }

                // An edge, not a state: without the onPad set a player standing
                // still on the pad after completing would be ejected on the very
                // next watcher tick, with no chance to open a vault.
                // M21: the room's leave pad is gone; only the terminal pad's
                // stand-on check remains.
                boolean onPad = isOnExitPad(player, record);
                boolean stepped = onPad && record.onPad.add(member);
                if (!onPad) {
                    record.onPad.remove(member);
                }
                if (stepped) {
                    if (record.isKeystoneRun()) {
                        // A dungeon pad: the terminal cell's. First contact ends
                        // the run; every contact after that does nothing at all.
                        // It used to fall through to RunLifecycle.exit() once completed was
                        // set, so stepping off the pad and back on -- trivially
                        // easy while looting the chests standing right there --
                        // threw the player out of the dungeon.
                        if (!record.completed.contains(member)) {
                            RunLifecycle.completeRun(server, record, player);
                        }
                    } else {
                        // No room and no keystone: /dungeon admin build and
                        // untimed runs, where the dungeon pad is still the way
                        // out because there is no room pad to use instead.
                        RunLifecycle.exit(player, RunLifecycle.ExitReason.EXIT_PAD);
                    }
                }
            }
        }
    }

    // ---- teardown -----------------------------------------------------------

    /**
     * M22: fires the spawner-cleared cue for a run. A cell counts as cleared
     * when every trial spawner in it sits at {@code COOLDOWN}, the same
     * predicate {@code TrialContent.countCleared} uses; the cue goes to each
     * member standing inside that cell the moment it first reaches that state.
     * A deliberate small watcher: there is no per-cell clear event to hook,
     * and {@code clearedCells} is the only new state, in-memory like every
     * other field on the record.
     */
    private static void watchSpawnerClears(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null || record.layout.trialSpawners().isEmpty()) {
            return;
        }
        // The per-cell grouping is built once per layout, not on every watch
        // tick. The layout identity records which layout it came from: this
        // record is reused for a second run behind the same lobby, so a fresh
        // layout must rebuild the map rather than re-scan stale cells.
        if (record.spawnerCellsLayout != record.layout) {
            Map<PlanCell, List<BlockPos>> byCell = new HashMap<>();
            for (BlockPos pos : record.layout.trialSpawners()) {
                PlanCell cell = record.layout.geometry().cellAt(pos);
                if (cell != null) {
                    byCell.computeIfAbsent(cell, c -> new ArrayList<>()).add(pos);
                }
            }
            record.spawnerCellsByCell = byCell;
            record.spawnerCellsLayout = record.layout;
        }
        // Per-spawner progress: detect each trial spawner transitioning to
        // COOLDOWN and announce cleared/total to every member in chat. Runs
        // before the per-cell clear check so the cell-clear cue still fires
        // on the same tick its last spawner is announced.
        // M67: the counter uses every trial spawner on the floor, matching
        // the completion gate in RunLifecycle.completeRun. The threshold
        // (default 0.75) lets the player skip some spawners, but the
        // displayed counter reflects the same denominator the gate uses.
        Set<BlockPos> gatedSpawners = TrialContent.activeSpawners(record.layout, level);
        int totalGated = gatedSpawners.size();
        for (BlockPos pos : record.layout.trialSpawners()) {
            if (record.announcedSpawners.contains(pos)) {
                continue;
            }
            if (level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity spawner
                    && spawner.getState() == TrialSpawnerState.COOLDOWN) {
                record.announcedSpawners.add(pos);
                if (!gatedSpawners.contains(pos)) {
                    continue;
                }
                int cleared = 0;
                for (BlockPos g : gatedSpawners) {
                    if (record.announcedSpawners.contains(g)) {
                        cleared++;
                    }
                }
                Component progress = Component.literal(
                                "Trial spawner cleared: " + cleared + "/" + totalGated)
                        .withStyle(ChatFormatting.AQUA);
                for (UUID member : record.members.keySet()) {
                    ServerPlayer memberPlayer = server.getPlayerList().getPlayer(member);
                    if (memberPlayer != null
                            && memberPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                        memberPlayer.sendSystemMessage(progress);
                    }
                }
            }
        }
        for (Map.Entry<PlanCell, List<BlockPos>> e : record.spawnerCellsByCell.entrySet()) {
            PlanCell cell = e.getKey();
            if (record.clearedCells.contains(cell)) {
                continue;
            }
            boolean cleared = true;
            for (BlockPos pos : e.getValue()) {
                if (!(level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity spawner
                        && spawner.getState() == TrialSpawnerState.COOLDOWN)) {
                    cleared = false;
                    break;
                }
            }
            if (!cleared) {
                continue;
            }
            record.clearedCells.add(cell);
            for (UUID member : record.members.keySet()) {
                ServerPlayer memberPlayer = server.getPlayerList().getPlayer(member);
                if (memberPlayer != null
                        && cell.equals(record.layout.geometry().cellAt(memberPlayer.blockPosition()))) {
                    Chime.spawnerCleared(memberPlayer);
                }
            }
        }
    }

    // ---- teardown -----------------------------------------------------------

    /**
     * Whether the player is standing on an exit pad -- the terminal cell's, or
     * the reward room's, once it exists.
     *
     * <p>Deliberately "the block under you is a lodestone, and you are inside the
     * instance", not a comparison against a recorded coordinate. A single
     * lodestone authored at a cell's centre does not survive rotation -- local
     * {@code (8,·,8)} moves to {@code (7,·,8)} under a 90-degree turn on an
     * even-sized template -- and the exit room is single-door, so it rotates every
     * run. Testing the block itself is rotation-proof and template-agnostic: a
     * future room can put a lodestone anywhere and it just works.
     */
    private static boolean isOnExitPad(ServerPlayer player, InstanceRecord record) {
        BlockPos below = player.blockPosition().below();
        if (!player.level().getBlockState(below).is(Blocks.LODESTONE)) {
            return false;
        }
        if (record.layout.bounds().contains(Vec3.atCenterOf(below))) {
            return true;
        }
        return (record.roomCellOrigin != null
                && CellGeometry.cellBounds(record.roomCellOrigin).contains(Vec3.atCenterOf(below)))
                || (record.stagingCellOrigin != null
                && CellGeometry.cellBounds(record.stagingCellOrigin).contains(Vec3.atCenterOf(below)));
    }

    /** The 16x7x16 box of a single fixed-offset room, for the reward and selector rooms. */
    /**
     * The owner of whichever live instance's room currently occupies {@code pos},
     * or {@code null} if {@code pos} is not inside anyone's room right now (M2
     * T2.2). Deliberately checks {@link InstanceRecord#roomCellOrigin} rather
     * than any cell in the instance -- the quarry cells are covered separately by
     * {@link #dungeonRecordAt} (M31), and a lingering instance is still checked
     * here since it stays in {@code InstanceRegistry.bySlot}.
     */
    static UUID roomOwnerAt(BlockPos pos) {
        InstanceRecord record = roomRecordAt(pos);
        return record == null ? null : record.owner;
    }

    /**
     * The room cell origin of whichever live instance's room occupies {@code pos},
     * or {@code null}. The origin half of {@link #roomOwnerAt}: the shell
     * protection (M18 9.1) needs the origin to run its pure coordinate test, and
     * the two lookups must always agree, so both share {@link #roomRecordAt}.
     */
    static BlockPos roomOriginAt(BlockPos pos) {
        InstanceRecord record = roomRecordAt(pos);
        if (record == null) {
            return null;
        }
        // M55: return whichever cell origin the position is actually in.
        // roomCellOrigin is the safe room; stagingCellOrigin is the staging room.
        if (inCellBounds(pos, record.roomCellOrigin)) {
            return record.roomCellOrigin;
        }
        return record.stagingCellOrigin;
    }

    /**
     * Which wall of the room occupying {@code pos} the selector doors stand on
     * (the record's {@link InstanceRecord#roomDungeonDoor}), or {@code null}
     * outside every room or inside the safe room (which has no dungeon door).
     * The direction half of {@link #roomOriginAt}: the furniture protection
     * (M19 19.7) needs it to run its wall-relative coordinate test, and like
     * the origin it shares {@link #roomRecordAt} so the two lookups can never
     * disagree.
     */
    static DoorMask.Direction roomDungeonDoorAt(BlockPos pos) {
        InstanceRecord record = roomRecordAt(pos);
        if (record == null) {
            return null;
        }
        // The dungeon door direction only applies to the staging room.
        if (inCellBounds(pos, record.stagingCellOrigin)) {
            return record.roomDungeonDoor;
        }
        return null;
    }

    /**
     * M43.4: package-visible rather than {@code private} so a caller that
     * needs more than one of {@link #roomOwnerAt}/{@link #roomOriginAt}/
     * {@link #roomDungeonDoorAt} for the same position (both
     * {@code RoomProtection.beforeBlockBreak} and
     * {@code RitualListener}'s placement check did) can resolve the record
     * once and read every field off it, instead of repeating this same scan
     * once per field.
     */
    static InstanceRecord roomRecordAt(BlockPos pos) {
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (inCellBounds(pos, record.roomCellOrigin)) {
                return record;
            }
            if (inCellBounds(pos, record.stagingCellOrigin)) {
                return record;
            }
        }
        return null;
    }

    /**
     * (M55) Whether {@code pos} is inside the 16x7x16 box of the cell at
     * {@code origin}. Returns {@code false} if {@code origin} is null.
     */
    private static boolean inCellBounds(BlockPos pos, BlockPos origin) {
        if (origin == null) {
            return false;
        }
        return pos.getX() >= origin.getX() && pos.getX() < origin.getX() + RoomGeometry.CELL
                && pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + RoomGeometry.CELL
                && pos.getY() >= origin.getY()
                && pos.getY() <= origin.getY() + RoomGeometry.CEILING_Y;
    }

    /**
     * The instance whose dungeon cells (the quarry, everything but the room)
     * currently occupy {@code pos} and whose run is still active, or
     * {@code null} otherwise (M31 9.2). "Active" means {@code layout} exists
     * and {@link InstanceRecord#completed} is still empty; once the first
     * member completes, this returns {@code null} for every cell of that run
     * and the quarry becomes breakable again, same as it always was. Skips
     * {@link InstanceRecord#roomCellOrigin}: that cell is
     * {@link #roomOwnerAt}'s job, and double-protecting it here would just
     * make the two lookups redundant with each other.
     */
    static InstanceRecord dungeonRecordAt(BlockPos pos) {
        // M40: this used to go through a private dungeonRecordAndCellAt
        // wrapper that did nothing but call dungeonCellLookupAt and unwrap
        // the record, the same lookup dungeonCellOriginAt already calls
        // directly below. Exercised only by DungeonShellProtectionTest, not
        // by any production caller (RoomProtection reads the origin, not
        // the record), so the wrapper added a layer with no reader.
        DungeonCellLookup lookup = dungeonCellLookupAt(pos);
        return lookup == null ? null : lookup.record();
    }

    /**
     * The cell origin of the active dungeon cell occupying {@code pos}, or
     * {@code null} if {@code pos} is not in an active run's dungeon cells
     * (M31 9.2). The origin half of {@link #dungeonRecordAt}: the shell
     * protection needs the origin to run its pure coordinate test against
     * {@link RoomProtection#isShell}, the same way {@link #roomOriginAt}
     * feeds the player room's own shell check.
     */
    static BlockPos dungeonCellOriginAt(BlockPos pos) {
        DungeonCellLookup lookup = dungeonCellLookupAt(pos);
        return lookup == null ? null : lookup.cellOrigin;
    }

    private record DungeonCellLookup(InstanceRecord record, BlockPos cellOrigin) {}

    private static DungeonCellLookup dungeonCellLookupAt(BlockPos pos) {
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            InstanceLayout layout = record.layout;
            if (layout == null) {
                continue;
            }
            PlanGeometry geometry = layout.geometry();
            int y = pos.getY() - geometry.origin().getY();
            // M61: a multi-story room owns volumes below its cell floor, down
            // to storyFloorOffset. The x/z lookup is unchanged (a lower story
            // shares its cell's chunk column), so only the y guard widens.
            if (y < -geometry.storyFloorOffset() || y > RoomGeometry.CEILING_Y) {
                continue;
            }
            PlanCell cell = geometry.cellAt(pos);
            if (cell == null) {
                continue;
            }
            if (record.roomCellOrigin != null
                    && geometry.cellOrigin(cell).equals(record.roomCellOrigin)) {
                continue; // the safe room cell -- roomOwnerAt's job, not this one's
            }
            if (record.stagingCellOrigin != null
                    && geometry.cellOrigin(cell).equals(record.stagingCellOrigin)) {
                continue; // the staging room cell -- roomOwnerAt's job, not this one's
            }
            return new DungeonCellLookup(record, geometry.cellOrigin(cell));
        }
        return null;
    }

    // ---- mob scaling (M10) ---------------------------------------------------

    /** The one shown by {@code AttributeInstance}'s modifier map, stable across reapplications. */
    private static final Identifier MOB_SCALE_ID = Identifier.fromNamespaceAndPath(
            PocketDungeonsMod.MOD_ID, "difficulty_scale");

    /** Separate modifier id for the Breeze HP override, so it does not collide with the level scaler. */
    private static final Identifier BREEZE_HP_ID = Identifier.fromNamespaceAndPath(
            PocketDungeonsMod.MOD_ID, "breeze_hp_override");

    /**
     * The live instance whose bounds contain {@code pos}, or {@code null}. Used
     * for mob scaling rather than a cell/chunk lookup because a mob's spawn
     * position is arbitrary within its cell and every live layout's
     * {@link InstanceLayout#bounds()} is already the exact box teardown itself
     * trusts.
     */
    private static InstanceRecord instanceAt(BlockPos pos) {
        Vec3 centre = Vec3.atCenterOf(pos);
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (record.layout.bounds().contains(centre)) {
                return record;
            }
        }
        return null;
    }

    /**
     * +0.8% (config: {@link PocketDungeonsConfig#mobScalePerLevel()}) per keystone
     * level, on max health, attack damage and movement speed: a level-100 run's
     * mobs land at roughly 1.45x strength. {@code ADD_MULTIPLIED_TOTAL} so the
     * bonus scales whatever base value the mob already has rather than adding a
     * flat number that means nothing on a skeleton and everything on a bee.
     *
     * <p>Package-visible rather than {@code private}: {@link FeralContent}'s
     * wolves are spawned directly at stamp time, before {@code ENTITY_LOAD} would
     * ever find an {@link InstanceRecord} whose layout matches (the record still
     * carries the lobby's tiny one-cell layout at that point), so they cannot
     * rely on the listener below and call this straight from the spawn callback
     * where the run's keystone level is already known.
     *
     * <p>Idempotent under the same modifier id: a repeat call for the same
     * entity (a chunk reload's {@code ENTITY_LOAD} firing again, or the listener
     * and a direct caller both reaching the same mob) replaces the modifier
     * rather than stacking it. Health is topped up only when the mob was at full
     * health <em>before</em> this ran: a fresh spawn always is, and topping it
     * up is what keeps it from reading as already-damaged the instant it
     * appears. A mob re-entering tracking mid-fight is not, and must keep
     * whatever damage it already has.
     */
    static void applyMobScale(Mob mob, int keystoneLevel) {
        applyMobScaleBonus(mob, DifficultyProfile.mobScale(keystoneLevel,
                PocketDungeonsConfig.mobScalePerLevel(),
                PocketDungeonsConfig.mobScaleBase()) - 1.0);
        // Breezes are disproportionately tedious to kill for their threat level:
        // high HP plus high mobility means a player spends most of the fight
        // chasing, not hitting. An additional HP-only cut on top of the level
        // scaling brings them in line with everything else on the spawner roster.
        if (mob.getType() == EntityTypes.BREEZE) {
            double multiplier = PocketDungeonsConfig.breezeHpMultiplier();
            if (multiplier > 0.0 && multiplier != 1.0) {
                boolean wasFullHealth = mob.getHealth() >= mob.getMaxHealth();
                AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
                if (health != null) {
                    health.addOrUpdateTransientModifier(new AttributeModifier(
                            BREEZE_HP_ID, multiplier - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                    if (wasFullHealth) {
                        mob.setHealth(mob.getMaxHealth());
                    }
                }
            }
        }
    }

    /**
     * The bonus-taking half of {@link #applyMobScale}, exposed separately for
     * {@link BossContent}: a boss run's mob wants a steeper bonus than the
     * ordinary {@code mobScalePerLevel} curve, not the same one.
     */
    static void applyMobScaleBonus(Mob mob, double bonus) {
        if (bonus == 0.0) {
            return;
        }
        boolean wasFullHealth = mob.getHealth() >= mob.getMaxHealth();
        boolean scaledHealth = scaleAttribute(mob, Attributes.MAX_HEALTH, bonus);
        scaleAttribute(mob, Attributes.ATTACK_DAMAGE, bonus);
        scaleAttribute(mob, Attributes.MOVEMENT_SPEED, bonus);
        if (scaledHealth && wasFullHealth) {
            mob.setHealth(mob.getMaxHealth());
        }
    }

    private static boolean scaleAttribute(Mob mob, Holder<Attribute> attribute, double bonus) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null) {
            return false;
        }
        instance.addOrUpdateTransientModifier(new AttributeModifier(
                MOB_SCALE_ID, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        return true;
    }

    // ---- admin (spec section 10) --------------------------------------------

    /**
     * Stamps an unowned instance. This is how the build is verified without a
     * client attached -- the console can build one, inspect it with
     * {@code /execute if block}, and purge it again.
     *
     * <p>A memberless record is registered for the built slot so teardown knows
     * the layout it has to clear. {@code InstanceRegistry.bySlot} already documents that it holds
     * instances which momentarily have no members, and nothing purges an empty
     * instance on its own, so the record is stable until an operator purges it.
     *
     * @param seed the layout seed, or null to roll one
     * @return the slot, or -1 if the dimension is missing or stamping failed
     */
    static int adminBuild(MinecraftServer server, Long seed) {
        return adminBuild(server, seed, 0, false);
    }

    /**
     * Same, at a chosen keystone level and ominous flag, so U6's and U7's tier and
     * ominous behaviour can be inspected headlessly without a player, a keystone,
     * or a lodestone.
     */
    static int adminBuild(MinecraftServer server, Long seed, int keystoneLevel, boolean ominous) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return -1;
        }
        int slot = InstanceRegistry.allocateSlot();
        BlockPos origin = InstanceRegistry.originForSlot(slot);

        InstanceLayout layout = buildLayout(server, level, slot, origin,
                seed != null ? seed : level.getRandom().nextLong(), keystoneLevel,
                ominous ? EnumSet.of(Affix.OMINOUS) : EnumSet.noneOf(Affix.class), null, null);
        if (layout == null) {
            // Slot release is deferred to the clear buildLayout already queued
            // for whatever it wrote -- see buildLayout's note.
            return -1;
        }

        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), layout,
                EnumSet.noneOf(Affix.class), null, false);
        InstanceRegistry.bySlot.put(slot, record);
        return slot;
    }

    /**
     * Opens the operator's hand-authoring shell for {@code /dungeon admin
     * buildroom}: one empty cell in the dungeon dimension, stamped with just
     * {@link RoomBuilder#buildShell}: no doors, no timer, no keystone, no
     * protection, and no room-store capture. The cell is a template under
     * construction; {@link #adminSaveRoom} captures it out.
     *
     * <p>One build room per player: an existing one is torn down first,
     * whatever state it is in.
     *
     * @return the slot, or -1 if the dimension is missing or stamping failed,
     *         or -2 if the player is already inside a live instance
     */
    static int adminBuildRoom(MinecraftServer server, ServerPlayer player) {
        if (InstanceRegistry.byMember.containsKey(player.getUUID())) {
            return -2;
        }
        for (InstanceRecord record : new ArrayList<>(InstanceRegistry.bySlot.values())) {
            if (record.adminBuild && player.getUUID().equals(record.owner)) {
                InstanceTeardown.purge(server, record, "replaced by a fresh build room");
            }
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return -1;
        }
        int slot = InstanceRegistry.allocateSlot();
        BlockPos origin = InstanceRegistry.originForSlot(slot);
        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
        try {
            RoomBuilder.buildShell(level, origin, RoomBuilder.FLOOR);
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not stamp a build room for {}", player.getUUID(), e);
            level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
            InstanceRegistry.usedSlots.remove(slot);
            return -1;
        }
        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), lobbyLayout(origin),
                EnumSet.noneOf(Affix.class), player.getUUID(), false, true);
        InstanceRegistry.bySlot.put(slot, record);
        admit(server, record, player);
        // admit lands on the lobby layout's corner entrance; centre the author.
        teleport(server, player, PocketDungeonsMod.DUNGEON_LEVEL,
                Vec3.atBottomCenterOf(RoomBuilder.cellCentre(origin, 0, 0)), 0.0f, 0.0f);
        // Issue the Room Editor Kit automatically on entry.
        RoomEditorKit.issueKit(player);
        return slot;
    }

    /**
     * Captures the player's build room to a hand-authored template file for
     * {@code /dungeon admin saveroom}, returns them to the overworld spawn,
     * and tears the room down. The capture runs first, while the cell is still
     * standing: the teardown queues a tick-spread clear that would otherwise
     * race it (PD-8's shape).
     *
     * @return the path of the written file, or {@code null} if the player has
     *         no build room open or the save failed
     */
    static String adminSaveRoom(MinecraftServer server, ServerPlayer player, String name) {
        InstanceRecord record = null;
        for (InstanceRecord r : InstanceRegistry.bySlot.values()) {
            if (r.adminBuild && player.getUUID().equals(r.owner)) {
                record = r;
                break;
            }
        }
        if (record == null) {
            return null;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return null;
        }
        Path saved = RoomTemplateGenerator.captureRoomToFile(level, record.origin, name,
                player.getName().getString(), player.getUUID());
        if (saved == null) {
            return null;
        }
        // Drop the member before purging so the teardown has nobody to eject
        // (and therefore nothing to teleport back to the old return point),
        // then put the author at world spawn and clear the slot.
        RunLifecycle.dropMember(server, record, player.getUUID(), player, "build room saved");
        sendToWorldSpawn(server, player);
        InstanceTeardown.purge(server, record, "build room saved");
        return saved.toString();
    }

    /** The layout of a built slot, for admin reporting. Null if the slot is unknown. */
    static InstanceLayout adminLayout(int slot) {
        InstanceRecord record = InstanceRegistry.bySlot.get(slot);
        return record != null ? record.layout : null;
    }

    /** Force teardown by slot, whether or not anyone is inside it. */
    static boolean adminPurge(MinecraftServer server, int slot) {
        if (!InstanceRegistry.usedSlots.contains(slot)) {
            return false;
        }
        InstanceRecord record = InstanceRegistry.bySlot.get(slot);
        if (record != null) {
            InstanceTeardown.purge(server, record, "purged by an operator");
        } else {
            InstanceTeardown.teardown(server, slot, InstanceRegistry.originForSlot(slot), null, "purged by an operator");
        }
        return true;
    }

    /**
     * Purges every live instance {@code owner} has open right now -- their
     * lobby, an active run, a lingering quarry, and any visit instance of their
     * room (a stale copy of the room {@code /dungeon admin resetroom} is about
     * to delete, so it has no reason to keep standing). For
     * {@code /dungeon admin resetroom}, ahead of wiping the saved blob: a room
     * still open in the world would otherwise get re-captured out from under
     * the reset the next time its owner leaves it.
     *
     * @return how many slots were purged
     */
    static int adminPurgeByOwner(MinecraftServer server, UUID owner) {
        int purged = 0;
        for (InstanceRecord record : new ArrayList<>(InstanceRegistry.bySlot.values())) {
            if (owner.equals(record.owner)) {
                InstanceTeardown.purge(server, record, "room reset by an operator");
                // PD-5: prevent the deferred saveRoom queued by purge from
                // overwriting the file reset is about to delete. saveRoom
                // checks roomCellOrigin == null at execution time and returns
                // early, so no capture runs after the reset.
                record.roomCellOrigin = null;
                purged++;
            }
        }
        return purged;
    }

    /** One line per live instance, for {@code /dungeon admin list}. */
    static List<String> adminList(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        for (int slot : InstanceRegistry.usedSlots) {
            BlockPos origin = InstanceRegistry.originForSlot(slot);
            InstanceRecord record = InstanceRegistry.bySlot.get(slot);
            if (record == null) {
                lines.add("slot " + slot + " at " + origin.toShortString() + ": unowned (admin build)");
                continue;
            }
            List<String> names = new ArrayList<>();
            for (UUID member : record.members.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                names.add(player != null ? player.getName().getString() : member.toString());
            }
            long ageSeconds = (server.overworld().getGameTime() - record.createdAtTick) / 20L;
            InstanceLayout layout = record.layout;
            String who = names.isEmpty() ? "unowned (admin build)"
                    : String.join(", ", names) + " (" + names.size() + ")";
            lines.add("slot " + slot + " at " + origin.toShortString()
                    + (record.untimed ? " (UNTIMED, never expires)" : "")
                    + (record.adminBuild ? " (BUILD ROOM)" : "")
                    + ": " + who + ", " + ageSeconds + "s old, "
                    + layout.roomCount() + " rooms, path " + layout.pathLength()
                    + ", tier " + layout.lootTier()
                    + (layout.procedural() ? ", seed " + layout.seed() : ", STATIC FALLBACK"));
        }
        return lines;
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * One ticket per occupied cell. Because a slot origin is chunk-aligned
     * ({@code slotPitch} is validated to be a multiple of 16) a cell <em>is</em> a
     * chunk, so this is exact: a sprawling layout holding twelve rooms loads twelve
     * chunks, not its whole bounding box.
     */
    static void forceLoad(ServerLevel level, List<ChunkPos> chunks, boolean forced) {
        for (ChunkPos chunk : chunks) {
            level.setChunkForced(chunk.x(), chunk.z(), forced);
        }
    }

    /**
     * (M56) Force-loads the chunks containing the given cell origins. Used by
     * the door preview to load just the entrance cell's chunk.
     */
    static void forceLoad(ServerLevel level, Set<BlockPos> cellOrigins, boolean forced) {
        for (BlockPos origin : cellOrigins) {
            level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, forced);
        }
    }

    /**
     * Every cross-dimension move in this mod goes through here or
     * {@link #sendToWorldSpawn}, both of which use {@link TeleportTransition}
     * -- the same mechanism vanilla portals and respawn use, not the lower-
     * level {@code Entity.teleportTo(level, x, y, z, ...)} primitive. That
     * primitive does not run the client-side loading-screen handshake a real
     * dimension change needs; calling it directly is what caused players to
     * hang on "Loading terrain..." (sometimes permanently, on their next
     * login) after entering, exiting, or being recovered into a dungeon. See
     * docs/PLAN.md.
     */
    static void teleport(MinecraftServer server, ServerPlayer player,
                                 ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch) {
        ServerLevel target = server.getLevel(dimension);
        if (target == null) {
            PocketDungeonsMod.LOG.warn("Return dimension {} is gone; sending {} home instead",
                    dimension.identifier(), player.getName().getString());
            sendHome(server, player);
            return;
        }

        // M26 26.4: the keystone is a recovery compass by design (VISION.md
        // section 3.1.1, LORE.md section 5), and a recovery compass only
        // reads Player#lastDeathLocation -- it ignores LODESTONE_TRACKER
        // entirely, and lastDeathLocation only reaches the client inside a
        // login/respawn packet, which only fires on an actual dimension
        // change. That rules out re-pointing it live while the player stays
        // in one dimension (room, lobby, run and back are all
        // DUNGEON_LEVEL), so this sets it only at the one moment a fresh
        // packet is guaranteed anyway: crossing into or out of the dungeon
        // dimension. Points at wherever this teleport is taking them on the
        // way in; clears back to vanilla's own spin/last-real-death behaviour
        // on the way out.
        boolean crossingIn = dimension.equals(PocketDungeonsMod.DUNGEON_LEVEL)
                && !player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL);
        boolean crossingOut = !dimension.equals(PocketDungeonsMod.DUNGEON_LEVEL)
                && player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL);
        if (crossingIn) {
            player.setLastDeathLocation(Optional.of(GlobalPos.of(dimension, BlockPos.containing(pos))));
        } else if (crossingOut) {
            player.setLastDeathLocation(Optional.empty());
        }

        player.teleport(new TeleportTransition(target, pos, Vec3.ZERO, yaw, pitch,
                TeleportTransition.DO_NOTHING));
    }

    /**
     * Where a player goes when the place they came from is not an answer any more:
     * the return dimension is gone, or there was never a return point to begin
     * with (M6 T6.5).
     *
     * <p>Preference order, not a mode. The room comes first because on a server
     * whose overworld has been emptied it is the only place a player has, and on
     * a suite server this branch is not reached at all, because the return point
     * resolved and {@link #teleport} never called here. Nothing is tuned twice:
     * both servers run the same order and get the answer that is true for them.
     *
     * <p>The room is reached through {@link RunLifecycle#reenterOwnedInstance},
     * which is already the "put this player back in their own live run" path and
     * already refuses when they are standing in one. The dungeon level is checked
     * first so this cannot recurse: {@code reenterOwnedInstance} admits through
     * {@link #teleport}, and a missing dungeon level there would land straight back
     * here.
     */
    private static void sendHome(MinecraftServer server, ServerPlayer player) {
        if (server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL) != null
                && RunLifecycle.reenterOwnedInstance(player)) {
            return;
        }
        sendToWorldSpawn(server, player);
    }

    static void sendToWorldSpawn(MinecraftServer server, ServerPlayer player) {
        LevelData.RespawnData spawn = server.overworld().getRespawnData();
        ServerLevel target = server.getLevel(spawn.dimension());
        if (target == null) {
            target = server.overworld();
        }
        BlockPos pos = spawn.pos();
        Vec3 dest = Vec3.atBottomCenterOf(pos);
        player.teleport(new TeleportTransition(target, dest, Vec3.ZERO,
                spawn.yaw(), spawn.pitch(), TeleportTransition.DO_NOTHING));
    }
}
