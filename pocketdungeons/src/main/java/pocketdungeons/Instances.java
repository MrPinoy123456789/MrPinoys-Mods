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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
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
     * PD-44: a game-time expiry alongside the point itself, so a player who
     * disconnects inside and never comes back does not leave an entry for
     * the rest of the process. 24 in-game hours is generous to a genuinely
     * late return while still bounding the map.
     */
    private static final long PENDING_RETURN_TTL_TICKS = 20L * 60 * 60 * 24;

    /**
     * {@code record} is the instance the player disconnected from, kept so the
     * rejoin message can tell a closed dungeon from one that is still open.
     */
    private record PendingReturn(ReturnPoint point, long expiresAtTick, InstanceRecord record) {}

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
        final InstanceRecord record; // the instance they left; null if unknown
        int ticksRemaining;
        PendingJoinRecovery(UUID player, ReturnPoint point, InstanceRecord record, int ticksRemaining) {
            this.player = player;
            this.point = point;
            this.record = record;
            this.ticksRemaining = ticksRemaining;
        }
    }

    /** Game ticks a silent homecoming waits for stragglers before pulling them into the room. */
    private static final long HOMECOMING_STRAGGLER_TICKS = 20L * 30;

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
            MinecraftServer server = player.level().getServer();
            InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
            if (server == null || record == null
                    || !player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                return true;
            }
            // M25: death inside a Pocket2 child ejects to the parent at the
            // door, with the outer run's death penalty still applied. The child
            // is torn down either way; see Pocket2.dieInChild.
            if (record.parentSlot >= 0) {
                Pocket2.dieInChild(server, player, record);
                return false;
            }
            if (record.inFloorLoop()) {
                // J3: death is the only thing that raises omen. A trip starts
                // at five lives; the fifth death ends the run.
                if (Omen.nextDeathFails(record.interval.omen)) {
                    PlaytestJournal.omenRise(server, record, Omen.Source.DEATH, 1,
                            Omen.MAX_OMEN + 1, player.blockPosition());
                    failRunOmen(server, record, player, source);
                    return false;
                }
                record.interval.omen = Omen.add(record.interval.omen, 1);
                PlaytestJournal.omenRise(server, record, Omen.Source.DEATH, 1,
                        record.interval.omen, player.blockPosition());
                OmenBar.deathCue(server, record, record.interval.omen);
            }
            PlaytestJournal.rescue(player, record, source);
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
            if (!(entity instanceof Mob mob) || Lemon.isPart(entity)
                    || !entityLevel.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                return;
            }
            // W7a: the Ancient City's Warden is a real vanilla Warden; it is never scaled.
            if (mob.getType() == EntityTypes.WARDEN) {
                return;
            }
            // W7b: the Wither and Steve set their own health and damage for the party.
            if (mob.entityTags().contains(CapstoneFights.UNSCALED_TAG)) {
                return;
            }
            InstanceRecord record = instanceAt(mob.blockPosition());
            if (record != null) {
                applyMobScale(mob, record.layout.keystoneLevel() + record.floor.levelBonus, Omen.clamp(record.interval.omen));
                // The dungeon\u0027s uniform (design pass 2026-10-09, Q5): the Copper Works crew wears copper.
                DungeonDef uniformOf = TripView.def(record);
                if (uniformOf != null && uniformOf.mobUniform() != null) {
                    MobUniforms.apply(mob, uniformOf.mobUniform());
                }
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
            InstanceRecord left = pending == null ? null : pending.record();
            pendingJoinRecoveries.add(new PendingJoinRecovery(
                    player.getUUID(), point, left, JOIN_RECOVERY_DELAY_TICKS));
        });

        // M45 seam: a player who logged out inside a run and came back. Empty
        // until M46.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                InventorySwap.reconcile(handler.getPlayer()));

        // A haul carried out of a trip that ended while the member was away (a server restart, a
        // grace expiry that missed them) comes home when they next join; the one time message
        // tells a migrated player what scrap is now.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                RunLifecycle.onJoinHaul(server, handler.getPlayer()));

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
            pendingReturns.put(player.getUUID(), new PendingReturn(point, expiresAtTick, record));
        }
        // U8 Stage 1: disconnecting is free. The run stays where it is
        // whether or not anyone is here to watch it; there is nothing to
        // settle on the way out. An owner who drops with the party still
        // inside gets a reconnect grace rather than ending their run.
        RunLifecycle.dropMember(server, record, player.getUUID(), player, "member disconnected", true);
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
        // Each forced chunk is one cell (a cell is exactly a chunk), and it
        // belongs to the slot whose origin is nearest: an instance wanders
        // from its origin in every direction, north and west included, but
        // never anywhere near half a slot pitch.
        Map<Integer, List<BlockPos>> orphanedCells = new HashMap<>();
        for (long packed : forced) {
            int blockX = ChunkPos.getX(packed) * 16;
            int blockZ = ChunkPos.getZ(packed) * 16;
            int col = Math.floorDiv(blockX + slotPitch / 2, slotPitch);
            int row = Math.floorDiv(blockZ + slotPitch / 2, slotPitch);
            if (col < 0 || row < 0 || col >= slotsPerRow) {
                continue; // outside the addressable grid, not one of this mod's tickets
            }
            int slot = row * slotsPerRow + col;
            orphanedCells.computeIfAbsent(slot, s -> new ArrayList<>()).add(
                    new BlockPos(blockX, InstanceRegistry.originForSlot(slot).getY(), blockZ));
        }
        for (Map.Entry<Integer, List<BlockPos>> orphan : orphanedCells.entrySet()) {
            int slot = orphan.getKey();
            PocketDungeonsMod.LOG.warn(
                    "Slot {} was still force-loaded at startup; the previous shutdown "
                            + "was not clean, clearing it now",
                    slot);
            // Claimed until the clear lands, like every other teardown, so no
            // new instance is handed a slot that is still being cleared.
            InstanceRegistry.usedSlots.add(slot);
            // The widest footprint plus every forced cell, so a cell outside
            // that footprint is cleared and its ticket released too.
            InstanceTeardown.teardown(server, slot, InstanceRegistry.originForSlot(slot), null,
                    "startup reconciliation after unclean shutdown", null, orphan.getValue());
        }
    }

    // ---- entry --------------------------------------------------------------

    /**
     * The affixes active on the run this member is currently standing in, or an
     * empty set if they are not in one. Used by {@link SilenceListener} rather
     * than reading a keystone -- the item may be sitting in a chest, and the
     * question is what the run itself is doing right now.
     */
    static Set<String> affixesFor(UUID member) {
        InstanceRecord record = InstanceRegistry.byMember.get(member);
        return record == null ? Set.of() : record.floor.affixes;
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
                                              int keystoneLevel, Set<String> affixes,
                                              String theme, UUID owner) {
        // E (D25): the spine proves solvable for a Pilgrim; the bag only opens
        // bonus rooms now. buildLayout is the untimed/admin path with no
        // party, so the party size is one.
        Set<String> bagTags = BagTags.pilgrim(1);
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
        // An owner back within their reconnect grace: the run is theirs again.
        if (player.getUUID().equals(record.owner) && record.ownerAbsentUntilTick != 0) {
            record.ownerAbsentUntilTick = 0;
            PlaytestJournal.ownerHold(server, record, "resume");
            announce(server, record, player.getName().getString() + " is back. The run goes on.",
                    player.getUUID());
        }

        boolean toStaging = admitsToStaging(record);
        teleport(server, player, PocketDungeonsMod.DUNGEON_LEVEL, admitPosition(record),
                toStaging ? 0.0f : record.layout.entranceYaw(), 0.0f);

        applyTrialOmen(player, record);
        OmenBar.sync(server, record);
        // The bag chest is a permanent station (2026-10-04): make sure it stands.
        if (record.roomCellOrigin != null && !record.visitInstance) {
            ServerLevel dungeonLevel = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            if (dungeonLevel != null) {
                placeBagChestForParty(dungeonLevel, server, record);
            }
        }
    }

    /**
     * Whether {@link #admit} lands a member in the staging room rather than
     * at the layout's entrance: true between floors, when the layout is still
     * the floor just cleared and its entrance is behind the party.
     */
    static boolean admitsToStaging(InstanceRecord record) {
        return RunLifecycle.betweenFloors(record) && record.stagingCellOrigin != null;
    }

    /** Where {@link #admit} puts a member: the staging room's centre between floors, the entrance otherwise. */
    static Vec3 admitPosition(InstanceRecord record) {
        if (admitsToStaging(record)) {
            return Vec3.atBottomCenterOf(record.stagingCellOrigin.offset(
                    RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2));
        }
        return Vec3.atBottomCenterOf(record.layout.entrance());
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
                Set.of(), player.getUUID(), false);
        record.roomCellOrigin = origin;
        record.stagingCellOrigin = CellGeometry.offsetInDirection(
                origin, DoorMask.Direction.SOUTH, RoomGeometry.CELL);
        InstanceRegistry.bySlot.put(slot, record);

        admit(server, record, player);
        for (ServerPlayer companion : companions) {
            admit(server, record, companion);
        }

        // The history screen was summoned at stamp time without a viewer; now
        // that the owner is standing here, refresh it for them.
        DungeonScreen.updateHistory(level, record);

        player.sendSystemMessage(Component.literal(
                PocketDungeonsConfig.hallEnabled()
                        ? "Choose a dungeon. Turn the astrolabe to change act."
                        : "Three doors. Choose one to open a run.").withStyle(ChatFormatting.GOLD));

        return true;
    }

    /**
     * Stamps just the lobby -- the owner's saved room (M2 T2.1) if they have
     * one, {@code entrance_hall} untouched otherwise -- with its one connecting
     * door sealed (T2.3's envelope covers the other three sides; nothing exists
     * to connect to yet) and its three fixed choice-doors ready. No plan yet:
     * {@link #chooseLobbyDoor} is what generates the rest of the run.
     *
     * @return the one-cell layout, or {@code null} if the stamp failed (a
     *         teardown is already queued on that path; it frees the tickets
     *         and the slot when its clear finishes)
     */
    static InstanceLayout stampLobby(MinecraftServer server, ServerLevel level,
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
            hideLockedDoors(level, stagingOrigin, dungeonDir, owner);
            DungeonScreen.summonDoor(level, stagingOrigin, dungeonDir,
                    DungeonScreen.idleContent(level, owner));
            DungeonScreen.summonHistory(level, stagingOrigin, dungeonDir,
                    FloorHistory.board(server, owner));
        } catch (RuntimeException e) {
            // A half-stamped safe or staging room is swept up rather than
            // left for the next allocation to stamp over, the same route
            // buildLayout takes. The teardown's clear releases the tickets
            // and then the slot.
            PocketDungeonsMod.LOG.error("Could not stamp a lobby for {}; clearing whatever was written",
                    owner, e);
            InstanceTeardown.teardown(server, slot, origin, lobbyLayout(origin), "lobby stamp failed",
                    null, List.of(stagingOrigin));
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
        stampSafeRoom(level, server, owner, origin, java.util.Set.of());
    }

    /**
     * {@link #stampSafeRoom}, leaving the bedrock envelope off {@code reservedSides}.
     * The envelope overwrites the neighbouring cell's wall column, so a room
     * stamped beside a cell that already stands (the silent homecoming beside
     * the staging room) must reserve that side or it bricks up the neighbour.
     */
    static void stampSafeRoom(ServerLevel level, MinecraftServer server, UUID owner, BlockPos origin,
                              Set<DoorMask.Direction> reservedSides) {
        boolean placedOwnRoom = RoomStore.place(level, server, owner, origin, 0,
                RandomSource.create(level.getRandom().nextLong()));
        if (!placedOwnRoom) {
            TemplateStamper.place(level, level.getStructureManager(), origin,
                    TemplateStamper.ENTRANCE_HALL, 0, level.getRandom().nextLong());
        }
        // Every door slot sealed, not only the two the room usually connects
        // through: a room captured while standing beside a staging room on
        // another side carries that doorway in its blob. The caller opens the
        // one door this stamp connects through.
        for (DoorMask.Direction side : DoorMask.Direction.values()) {
            CellGeometry.sealDoorOnWall(level, origin, side);
        }
        BedrockEnvelope.applyToCell(level, origin, reservedSides);
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
        stampStagingRoom(level, origin, dungeonDir, Set.of());
    }

    /**
     * {@link #stampStagingRoom}, also leaving the envelope off
     * {@code standingSides}: the sides where another cell of the instance
     * already stands, whose wall column the ring would otherwise replace.
     */
    static void stampStagingRoom(ServerLevel level, BlockPos origin, DoorMask.Direction dungeonDir,
                                 Set<DoorMask.Direction> standingSides) {
        DoorMask.Direction safeDir = CellGeometry.opposite(dungeonDir);
        RoomBuilder.buildShell(level, origin, RoomBuilder.FLOOR);
        RoomBuilder.openDoor(level, origin, mcDirection(safeDir));
        RoomBuilder.sealDoor(level, origin, mcDirection(dungeonDir));
        Set<DoorMask.Direction> reserved = new HashSet<>(standingSides);
        reserved.add(safeDir);
        BedrockEnvelope.applyToCell(level, origin, reserved);
        RoomTemplateGenerator.placeSelectorDoors(level, origin, dungeonDir);
        RoomTemplateGenerator.placeWallLodestone(level, origin);
        RoomTemplateGenerator.placeFurniture(level, origin, dungeonDir, true);
        RoomTemplateGenerator.placeRunStorage(level, origin, dungeonDir);
    }

    /**
     * (M55) Clears the safe room cell and discards its entities. Called at
     * commit time after {@link RunLifecycle#saveRoom} has captured the blob.
     * Sets {@code roomCellOrigin} to null so every safe-room-aware check
     * (saveRoom, bag chest, room protection) knows the safe room is gone.
     *
     * <p>The staging room beside it keeps its wall: the clear's margin is that
     * wall's column, so it runs with every other live cell kept, and the
     * staging room gets its bedrock back on that face. Sealing the staging
     * room's doorway is the caller's.
     */
    static void despawnSafeRoom(ServerLevel level, InstanceRecord record) {
        if (record.roomCellOrigin == null) {
            return;
        }
        clearCells(level, record, List.of(record.roomCellOrigin));
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
        holdCell(level, origin);
        stampSafeRoom(level, server, record.owner, origin, standingSides(record, origin));
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
     * NBT, the same way {@link #selectorDoorStep} and {@link #isCommitLever}
     * identify their own fixtures.
     */
    static BlockPos bagChestPos(BlockPos origin) {
        // The default spot only, where a missing chest is placed (2026-10-04).
        return origin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
    }

    /**
     * The bag chest block: a waxed oxidized copper chest (it swapped places with
     * the ender chest, which is now run storage; playtest 2026-10-02-1). Looked up
     * by id because the copper chests live in a weathering collection, not a field.
     */
    static net.minecraft.world.level.block.Block bagChestBlock() {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace("waxed_oxidized_copper_chest"));
    }

    /**
     * Whether {@code state} is a bag chest: an oxidized copper chest, waxed or
     * not (2026-10-04). Recognised by its block wherever it stands, so the
     * player can mine it and set it down anywhere in their safe room and it is
     * still the bag chest; mining may hand back either variant.
     */
    static boolean isBagChest(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(bagChestBlock()) || state.is(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace("oxidized_copper_chest")));
    }

    /** Whether {@code stack} is a bag chest the player is carrying (see {@link #isBagChest}). */
    static boolean isBagChestItem(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.BlockItem item
                && isBagChest(item.getBlock().defaultBlockState());
    }

    /** Whether {@code pos} is inside {@code record}'s safe room cell. */
    static boolean inSafeRoom(InstanceRecord record, BlockPos pos) {
        return record != null && record.roomCellOrigin != null && inCellBounds(pos, record.roomCellOrigin);
    }

    /**
     * Makes sure the safe room has its bag chest (2026-10-04, owner report:
     * "one spawns in the middle of the room every time I enter"). The chest
     * is part of the room now, saved with it and free to move, so one is
     * placed only when the room has none and the owner is not carrying one:
     * the first visit, or a chest that was lost. It goes at the centre, or
     * the nearest open floor spot to it.
     */
    static void placeBagChestForParty(ServerLevel level, MinecraftServer server, InstanceRecord record) {
        if (record == null || record.roomCellOrigin == null || record.visitInstance) {
            return;
        }
        BlockPos o = record.roomCellOrigin;
        for (BlockPos pos : BlockPos.betweenClosed(o.offset(1, 1, 1),
                o.offset(RoomGeometry.CELL - 2, RoomGeometry.CEILING_Y - 1, RoomGeometry.CELL - 2))) {
            if (isBagChest(level.getBlockState(pos))) {
                return;
            }
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(record.owner);
        if (owner != null) {
            for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
                if (isBagChestItem(owner.getInventory().getItem(i))) {
                    return;
                }
            }
        }
        BlockPos spot = openFloorNear(level, o);
        if (spot != null) {
            RoomBuilder.set(level, spot, bagChestBlock().defaultBlockState());
        }
    }

    /** The open floor spot closest to the room's centre, or {@code null} if the room is full. */
    private static BlockPos openFloorNear(ServerLevel level, BlockPos o) {
        BlockPos centre = bagChestPos(o);
        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                BlockPos pos = o.offset(x, 1, z);
                if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
                    continue;
                }
                int d = Math.abs(pos.getX() - centre.getX()) + Math.abs(pos.getZ() - centre.getZ());
                if (d < bestDistance) {
                    best = pos.immutable();
                    bestDistance = d;
                }
            }
        }
        return best;
    }

    static net.minecraft.core.Direction mcDirection(DoorMask.Direction dir) {
        return switch (dir) {
            case NORTH -> net.minecraft.core.Direction.NORTH;
            case SOUTH -> net.minecraft.core.Direction.SOUTH;
            case EAST -> net.minecraft.core.Direction.EAST;
            case WEST -> net.minecraft.core.Direction.WEST;
        };
    }

    /** A single fixed cell at rotation 0: no keystone and no reward room until a door is chosen. */
    static InstanceLayout lobbyLayout(BlockPos origin) {
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        BlockPos entrance = origin.offset(3, 1, 3);
        // M18: the leave pad marker tracks the stand spot in front of the wall
        // lodestone (the lodestone itself is at origin + (1, 2, 0)); the field
        // is for admin output only.
        BlockPos exitPad = origin.offset(1, 1, 1);
        return new InstanceLayout(origin, geometry, entrance, 0.0f, exitPad, geometry.bounds(),
                0L, 1, 1, 1, false, Set.of(), 0, origin, 0, 0, Set.of(), null, Set.of());
    }

    /**
     * Which of the lobby's three fixed doors (1, 2 or 3) this world position
     * is, for <em>this player's own</em> lobby -- or null if the player is not
     * standing in one that can still choose a door ({@link RunSession#canChooseDoor}),
     * or the position is not a door. Any party member may use a door (design D15;
     * the leader's decide whitelist is checked where the choice is made, so a
     * refused member gets a message rather than a silent click). Positions are computed from the record's origin rather than read
     * back out of the template: the lobby is always stamped at rotation 0, so
     * there is no transform to account for.
     */
    static Integer selectorDoorStep(ServerPlayer player, BlockPos pos) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !RunSession.canChooseDoor(record)) {
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
        if (HallRoom.isHallStaging(record)) {
            return HallRoom.slotAt(player.level().getServer(), record, along);
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
     * wall. Any member may pull it (D15) unless the leader's decide whitelist
     * is on, which {@link RunLifecycle#commitDoor} enforces with a message.
     */
    static boolean isCommitLever(ServerPlayer player, BlockPos pos) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || !RunSession.canChooseDoor(record) || record.stagingCellOrigin == null) {
            return false;
        }
        if (HallRoom.isHallStaging(record)) {
            // The Astrolabe Room's lever stands beside the selected door, wherever that is.
            return record.interval.hallLeverAlong > 0 && RoomTemplateGenerator.hallLeverPos(
                    record.stagingCellOrigin, record.roomDungeonDoor, record.interval.hallLeverAlong).equals(pos);
        }
        return RoomTemplateGenerator.leverPos(record.stagingCellOrigin, record.roomDungeonDoor).equals(pos);
    }

    /**
     * Takes every selector door back out once the trip's dungeon is finished, bulb
     * and all, so the staging room offers only the way home (the HOME lever).
     * Before the dungeon structure waves this hid doors the owner's keystone level
     * could not reach; the level gates are gone, so a staging room now always shows
     * all three doors until the final floor is cleared. Run after every placement of
     * the doors.
     */
    static void hideLockedDoors(ServerLevel level, BlockPos o, DoorMask.Direction wall, UUID owner) {
        if (level == null || o == null || wall == null || owner == null) {
            return;
        }
        // The first staging room of a trip is the Astrolabe Room: its own row replaces the three doors.
        if (HallRoom.armFor(level, o, wall, owner)) {
            return;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(owner);
        if (record == null || !record.interval.finished) {
            return;
        }
        for (int step = 1; step <= 3; step++) {
            RoomTemplateGenerator.hideSelectorDoor(level, o, wall, step);
        }
    }

    /**
     * Whether {@code pos} is a block of the floor history board in the staging room
     * of the run this player is in. A click on it opens the dungeon map. Any member
     * may read it.
     */
    static boolean isHistoryBoard(ServerPlayer player, BlockPos pos) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || record.stagingCellOrigin == null || record.visitInstance
                || !record.inFloorLoop()) {
            return false;
        }
        return RoomTemplateGenerator.isHistoryPanel(record.stagingCellOrigin, record.roomDungeonDoor, pos);
    }

    /**
     * Whether {@code pos} is the go-home lever in the staging room of the run
     * this player is in. Any member's click is claimed, so vanilla never
     * flips the lever, and {@code RunLifecycle.goHome} answers a member the
     * leader's decide whitelist bars with the refusal rather than silence.
     */
    static boolean isHomeLever(ServerPlayer player, BlockPos pos) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null || record.stagingCellOrigin == null || !RunLifecycle.betweenFloors(record)) {
            return false;
        }
        return RoomTemplateGenerator.homeLeverPos(record.stagingCellOrigin, record.roomDungeonDoor).equals(pos);
    }

    /**
     * The room names the node behind {@code offer} leans toward (the node's
     * {@code roomBias}), or an empty list outside a dungeon graph.
     */
    static List<String> nodeRoomBias(Keystone.Offer offer) {
        if (offer == null || offer.door() == null) {
            return List.of();
        }
        DungeonDef def = DungeonDefs.current().byId(offer.dungeonId());
        DungeonDef.Node node = def == null ? null : def.node(offer.nodeId());
        return node == null ? List.of() : node.roomBias();
    }

    /**
     * Dungeon structure W5: the floor behind {@code offer} as the room selector sees it, or
     * {@code null} outside a dungeon graph (an experimental or Endless Mine offer keeps the legacy
     * theme-only room filter).
     */
    static RoomEligibility.Floor roomFloorOf(Keystone.Offer offer, String effectiveThemeId, String roomTheme) {
        if (offer == null || offer.door() == null) {
            return null;
        }
        DungeonDefs defs = DungeonDefs.current();
        DungeonDef def = defs == null ? null : defs.byId(offer.dungeonId());
        DungeonDef.Node node = def == null ? null : def.node(offer.nodeId());
        if (def == null || node == null) {
            return null;
        }
        String borrowedFrom = "";
        if (node.overridesTheme() && !DungeonDef.qualify(node.theme()).equals(DungeonDef.qualify(def.mainTheme()))) {
            for (DungeonDef other : defs.all()) {
                if (DungeonDef.qualify(other.mainTheme()).equals(DungeonDef.qualify(node.theme()))) {
                    borrowedFrom = other.id();
                    break;
                }
            }
        }
        return new RoomEligibility.Floor(def.id(), def.mainTheme(), roomTheme, def.act(),
                def.kind() == DungeonDef.Kind.CAPSTONE, effectiveThemeId, borrowedFrom,
                node.layer() == 1, node.isFinal(), offer.door().sideBranch(),
                def.minNodeRooms(node));
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
        // E (D25): the spine proves solvable for a Pilgrim; the chosen bag
        // only opens bonus rooms now, so the seed no longer reads it. The
        // party still brings its own mob: on floor 0 (HOME phase) every
        // member counts, on later floors (FLOOR_CLEARED phase) only members
        // actually present and online do (M65).
        int livePartySize = record.members.size();
        if (record.phase == RunSession.Phase.FLOOR_CLEARED) {
            livePartySize = countLiveMembers(server, record);
        }
        Set<String> bagTags = BagTags.pilgrim(livePartySize);

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
        // Dungeon structure W4: Feral is not dealt on a dark floor.
        Set<String> baseAffixes = Keystone.dealtAffixes(record.owner, offer);
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

        // M78: a Mine recipe (or a run already flagged Mine) forces the Mine
        // theme on every floor. The recipe tags are cleared after the first
        // commit, so a later floor's preview would otherwise lose the Mine
        // look; record.interval.endlessMine carries the flag forward instead.
        String effectiveThemeId = EndlessMineRules.effectiveTheme(offer.theme(), record, recipePlan);
        final boolean mineFloor = EndlessMineRules.isMine(recipePlan) || EndlessMineRules.isMine(record)
                || EndlessMineRules.isMineOffer(offer);
        if (!effectiveThemeId.equals(offer.theme())) {
            theme = ThemeManifest.current().byId(effectiveThemeId);
        }

        // M66: a second click on the same offer with the same inputs does not
        // farm random entrances. If the existing preview is for the same step
        // and the recipe revision matches, reuse the frozen plan.
        if (record.floor.previewCellOrigin != null
                && record.floor.previewOfferStep == step
                && record.floor.previewDoorKey.equals(doorKey(offer))
                && record.floor.previewRecipePlan != null
                && record.floor.previewRecipePlan.matchesRevision(recipePlan.revision)
                && record.floor.previewRecipePlan.offerLevel == offer.level()) {
            // Same offer, same inputs: the frozen preview is still valid.
            return true;
        }

        // Purge any existing preview before generating the new one. This is
        // a switch, not a cancel: the escrow and recipe tags stay armed so
        // the next preview reuses them without re-consuming the catalyst.
        clearPreview(level, record, false);

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
        // Dungeon structure W2: the node's room bias nudges the same weighted pick
        // Lemon's playtest bias does (PlaytestBias), for this plan only.
        List<String> nodeBias = nodeRoomBias(offer);
        // An Endless Mine floor keeps the legacy theme-only room filter.
        final RoomEligibility.Floor roomFloor = mineFloor
                ? null
                : roomFloorOf(offer, effectiveThemeId, theme == null ? null : theme.meta().roomTheme);
        for (int round = 0; round < 4 && plan == null; round++) {
            long seed = level.getRandom().nextLong();
            final String roomTheme = theme == null ? null : theme.meta().roomTheme;
            LayoutPlanner.Outcome outcome = PlaytestBias.withNodeBias(nodeBias, () -> LayoutPlanner.plan(
                    seed, RoomManifest.current(), PocketDungeonsConfig.planAttemptBudget(),
                    minPath, maxPath,
                    PocketDungeonsConfig.branchProbability(), PocketDungeonsConfig.loopProbability(),
                    PocketDungeonsConfig.maxGridSpan(), roomTheme,
                    dungeonDoor, bagTags, recipePlan,
                    record.interval.floorIndex == 0 ? PocketDungeonsConfig.firstFloorMaxEncounters() : 0,
                    roomFloor));
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
        Set<String> affixes = recipePlan.effectiveAffixes(baseAffixes);
        // Dungeon structure W4: the entrance cell's light and resource nodes, kept for the commit.
        NodeStamper.Context nodeCtx = NodeStamper.contextFor(offer.dungeonId(), offer.nodeId(),
                mineFloor);
        record.floor.previewNodes.clear();
        try {
            LayoutStamper.stampEntranceOnly(level, planOrigin, plan, offer.level(), affixes,
                    effectiveThemeId, LootBands.forFloor(record, offer, mineFloor), nodeCtx, record.floor.previewNodes);
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Preview stamp failed for door {}", step, e);
            clearCells(level, record, List.of(entranceOrigin));
            return false;
        }

        windowPreview(level, record, entranceOrigin, dungeonDoor);

        record.floor.previewPlan = plan;
        record.floor.previewCellOrigin = entranceOrigin;
        record.floor.previewRecipePlan = recipePlan;
        record.floor.previewOfferStep = step;
        record.floor.previewDoorKey = doorKey(offer);
        RunSession.transition(record, RunSession.Phase.PREVIEW);
        PlaytestJournal.doorPreview(server, record, offer.step(), offer.level(), effectiveThemeId, affixes);
        return true;
    }

    /**
     * The seam half of a door preview, once the entrance cell is stamped. The
     * entrance gets a bedrock envelope on every side where nothing stands yet:
     * its template carries open doorways towards cells the commit has not
     * stamped, and the party looks straight through them from the staging
     * room. Then the staging room's door slot and the entrance's staging-side
     * wall become glass windows onto each other.
     */
    static void windowPreview(ServerLevel level, InstanceRecord record, BlockPos entranceOrigin,
                              DoorMask.Direction dungeonDoor) {
        BedrockEnvelope.applyToCell(level, entranceOrigin, standingSides(record, entranceOrigin));
        // Replace the selected door with a glass window. The selector
        // door for this step is at a fixed offset within the door slot; the
        // simplest approach is to fill the entire door slot with glass, since
        // the other two doors are still selector doors and this one is now a
        // window.
        RoomBuilder.windowDoor(level, record.stagingCellOrigin, mcDirection(dungeonDoor));
        // Glass in the entrance cell's wall on the staging side too, so the
        // party can see through into the room. Glass (not open air) so the
        // player cannot walk in before committing.
        RoomBuilder.windowDoor(level, entranceOrigin, mcDirection(CellGeometry.opposite(dungeonDoor)));
        // PD-85: the selector doors stand in front of that slot and cover all
        // but its top course, so the view the party actually gets is a side
        // window beside the doors, cut through both walls. The slot glass
        // stays: it is the seal, and its top course is still a view.
        BlockState glass = Blocks.GLASS.defaultBlockState();
        if (HallRoom.isHallStaging(record)) {
            // The Astrolabe Room (owner, 2026-10-09): the whole 14 by 3 wall behind the doors is the window,
            // in both cells' walls, leaving the copper bulbs in the staging wall's top course alone.
            java.util.Set<Integer> bulbs = HallRoom.bulbAlongs(level.getServer(), record);
            record.floor.previewWallOriginal.clear();
            RoomBuilder.previewWall(level, record.stagingCellOrigin, mcDirection(dungeonDoor), glass,
                    (along, y) -> y == RoomGeometry.DOOR_HEIGHT && bulbs.contains(along), null);
            RoomBuilder.previewWall(level, entranceOrigin, mcDirection(CellGeometry.opposite(dungeonDoor)), glass,
                    (along, y) -> along >= RoomGeometry.DOOR_MIN && along <= RoomGeometry.DOOR_MAX,
                    record.floor.previewWallOriginal);
            return;
        }
        RoomBuilder.previewSideWindow(level, record.stagingCellOrigin, mcDirection(dungeonDoor), glass);
        RoomBuilder.previewSideWindow(level, entranceOrigin, mcDirection(CellGeometry.opposite(dungeonDoor)), glass);
    }

    /**
     * PD-85: hands the staging room's preview side window back to its wall.
     * The entrance cell's half goes with the cell when a preview is cleared;
     * on a commit {@link #connectStagingToEntrance} patches it separately.
     */
    private static void sealPreviewSideWindow(ServerLevel level, BlockPos stagingOrigin,
                                              DoorMask.Direction dungeonDoor) {
        RoomBuilder.previewSideWindow(level, stagingOrigin, mcDirection(dungeonDoor),
                RoomBuilder.shellWallAt(level, stagingOrigin));
    }

    /**
     * (M56) Purges the current preview cell and restores the selector door
     * that was replaced by the window. No-op if no preview is active.
     *
     * <p>M66: also clears the recipe plan and, when {@code refundCatalyst} is
     * true, restores any escrowed catalyst to the owner and clears the armed
     * recipe tags. A cancelled preview does not charge for nothing.
     *
     * <p>F3: {@code refundCatalyst} distinguishes a true cancel (refund the
     * catalyst and drop the recipe tags) from a preview switch (keep the
     * escrow and tags armed, because the next preview reuses them). Switching
     * doors after applying a recipe no longer refunds the catalyst while
     * leaving the recipe effects armed for the committed run.
     */
    static void clearPreview(ServerLevel level, InstanceRecord record, boolean refundCatalyst) {
        if (record.floor.previewCellOrigin == null) {
            return;
        }
        clearCells(level, record, List.of(record.floor.previewCellOrigin));
        // The window filled the door slot itself, and the selector doors stand
        // one block inside the room, so re-placing them never covered it: seal
        // the slot back to wall (the clear already put the bedrock behind it).
        RoomBuilder.sealDoor(level, record.stagingCellOrigin, mcDirection(record.roomDungeonDoor));
        sealPreviewSideWindow(level, record.stagingCellOrigin, record.roomDungeonDoor);
        if (HallRoom.isHallStaging(record)) {
            RoomBuilder.previewWall(level, record.stagingCellOrigin, mcDirection(record.roomDungeonDoor),
                    RoomBuilder.shellWallAt(level, record.stagingCellOrigin), null, null);
        }
        record.floor.previewWallOriginal.clear();
        if (!HallRoom.armFor(level, record.stagingCellOrigin, record.roomDungeonDoor, record.owner)) {
            RoomTemplateGenerator.placeSelectorDoors(level, record.stagingCellOrigin,
                    record.roomDungeonDoor);
            hideLockedDoors(level, record.stagingCellOrigin, record.roomDungeonDoor, record.owner);
        }
        record.floor.previewPlan = null;
        record.floor.previewCellOrigin = null;
        record.floor.previewNodes.clear();
        // M66: clear the recipe plan and, on a true cancel, restore the
        // escrowed catalyst. A switch keeps the escrow and tags armed.
        if (record.floor.previewRecipePlan != null) {
            if (refundCatalyst) {
                restoreEscrowedCatalyst(level.getServer(), record);
            }
            record.floor.previewRecipePlan = null;
        }
        record.floor.previewOfferStep = 0;
        record.floor.previewDoorKey = "";
        // M65: return to the phase that preceded the preview. If the safe
        // room is loaded and floorIndex is 0, that is HOME; otherwise the
        // party is between floors and the phase is FLOOR_CLEARED.
        RunSession.transition(record, record.interval.floorIndex > 0
                ? RunSession.Phase.FLOOR_CLEARED : RunSession.Phase.HOME);
    }

    /**
     * M66: Converts a {@link RunRecipePlan} back to a {@link CompoundTag} for
     * {@link FloorState#recipeTags}, so the generation path that reads
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
    static void restoreEscrowedCatalyst(MinecraftServer server, InstanceRecord record) {
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
        DungeonPlan plan = record.floor.previewPlan;
        if (plan == null || record.floor.previewCellOrigin == null) {
            return false;
        }
        // W8: a content reload can re-deal the doors under an open preview. The floor the party saw
        // belongs to another dungeon node than this door now names, so the preview is dropped.
        if (!record.floor.previewDoorKey.equals(doorKey(offer))) {
            PocketDungeonsMod.LOG.warn("Refusing door commit in slot {}: the preview was for {} but the door is now {}",
                    record.slot, record.floor.previewDoorKey, doorKey(offer));
            clearPreview(level, record, false);
            return false;
        }
        Set<BlockPos> previewNodes = new java.util.HashSet<>(record.floor.previewNodes);

        // M78: the Mine theme forced at preview is carried into commit, and
        // the run is flagged Mine for the rest of its floors. The flag lives
        // on the record so later floors stay Mine after the recipe tags are
        // cleared.
        String effectiveThemeId = EndlessMineRules.effectiveTheme(offer.theme(), record,
                record.floor.previewRecipePlan);
        boolean openingMine = EndlessMineRules.isMine(record.floor.previewRecipePlan)
                || EndlessMineRules.isMineOffer(offer);

        // M2/M3: clear the previous dungeon before generating the next one.
        // PD-13: its force-load tickets go with its cells.
        RunLifecycle.resetForNextDungeon(server, record);

        DoorMask.Direction dungeonDoor = record.roomDungeonDoor;
        boolean ominousRolled = false;
        Set<String> affixes = Keystone.dealtAffixes(record.owner, offer);

        // M66: apply recipe effects from the frozen preview plan, not from
        // re-read recipe tags. The preview resolved the affix set; commit
        // uses the same set the player saw.
        if (record.floor.previewRecipePlan != null) {
            affixes = record.floor.previewRecipePlan.effectiveAffixes(affixes);
        }

        // A floor turns ominous by chance, rolled now that the door is chosen:
        // the more omen the party has banked this interval, the likelier.
        if (!affixes.contains(AffixIds.OMINOUS)
                && level.getRandom().nextDouble() < Omen.ominousChance(record.interval.omen)) {
            affixes = new java.util.LinkedHashSet<>(affixes);
            affixes.add(AffixIds.OMINOUS);
            ominousRolled = true;
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
            // The staging room counts as a standing neighbour, so the floor's
            // envelope leaves the entrance's staging side alone instead of
            // bricking the staging room's wall with bedrock.
            layout = LayoutStamper.stampBehindLobby(level, planOrigin, plan, offer.level(), affixes,
                    null, effectiveThemeId, Set.of(record.stagingCellOrigin),
                    LootBands.forFloor(record, offer, openingMine || record.interval.endlessMine),
                    NodeStamper.contextFor(offer.dungeonId(), offer.nodeId(), openingMine || record.interval.endlessMine));
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Commit stamp failed behind the staging room at {}",
                    record.stagingCellOrigin.toShortString(), e);
            List<BlockPos> attempted = new ArrayList<>(geometry.cellOrigins());
            attempted.remove(record.stagingCellOrigin);
            clearCells(level, record, attempted);
            return false;
        }

        connectStagingToEntrance(level, record.stagingCellOrigin, geometry.cellOrigin(plan.entrance()),
                dungeonDoor);
        if (!record.floor.previewWallOriginal.isEmpty()) {
            // The wide window comes out: the staging wall is wall again, bar the doorway lane the commit
            // just opened, and the entrance cell gets back the blocks the glass replaced.
            RoomBuilder.previewWall(level, record.stagingCellOrigin, mcDirection(dungeonDoor),
                    RoomBuilder.shellWallAt(level, record.stagingCellOrigin),
                    (along, y) -> along >= RoomGeometry.DOOR_MIN && along <= RoomGeometry.DOOR_MAX, null);
            RoomBuilder.restoreWall(level, record.floor.previewWallOriginal);
            record.floor.previewWallOriginal.clear();
        }

        // The new floor's state replaces the cleared one's wholesale: its
        // completions, pad edges, spawner cues, reward chests, grace window
        // and the preview that chose it all go with it.
        FloorState next = new FloorState();
        next.affixes = affixes;
        next.theme = effectiveThemeId;
        next.chosenStep = offer.step();
        next.chosenLevel = offer.level();
        next.doorTaken = true;
        next.freeDoor = offer.free();
        // M66: the recipe plan is consumed. The catalyst escrow is cleared
        // (the catalyst is permanently spent on successful commit). Store
        // the recipe plan on the floor for the generation path to read
        // during the run (compass study list, etc.).
        next.recipeTags = record.floor.previewRecipePlan != null
                ? recipeTagsFromPlan(record.floor.previewRecipePlan) : null;
        // The cell to room map, so any position on this floor names its room
        // (the journal, the context snapshot, Lemon).
        next.rooms = FloorRooms.of(plan);
        next.startedAtTick = server.overworld().getGameTime();
        // M66: populate the completion study list for the compass recipe.
        // The list is the run's room names, which the completion line
        // reports when the compass effect is active.
        for (Map.Entry<PlanCell, DungeonPlan.PlacedRoom> placed : plan.rooms().entrySet()) {
            DungeonPlan.PlacedRoom room = placed.getValue();
            RoomManifest.Entry entry = LayoutStamper.entryAt(RoomManifest.current(), plan, placed.getKey());
            if (entry != null && entry.meta.content != null && !entry.meta.content.isBlank()) {
                next.situations.add(entry.meta.content);
            } else {
                next.situations.add(room.name());
            }
        }
        // Dungeon structure W4: the entrance cell stamped at preview registered its nodes then.
        next.nodes.addAll(previewNodes);
        record.startFloor(layout, next);
        // M78: a Mine recipe flags the interval Mine on the first commit and
        // the flag persists for the rest of the interval.
        if (openingMine) {
            record.interval.endlessMine = true;
        }
        // Dungeon structure W2: the trip now stands at the node behind this door.
        // An Endless Mine and an operator's experimental offer stay outside the graph.
        if (offer.door() != null && !openingMine && !record.interval.endlessMine) {
            record.interval.dungeonId = offer.dungeonId();
            record.interval.nodeId = offer.nodeId();
            record.interval.path.add(offer.nodeId());
        }
        RunSession.transition(record, RunSession.Phase.ACTIVE);

        if (ominousRolled) {
            for (UUID member : record.members.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                if (player != null) {
                    player.sendSystemMessage(Component.literal(
                            "The omen you carried tips the floor: this one is Ominous.")
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
                }
            }
        }

        // J3: a capstone dungeon's final floor opens two levels harder (it
        // used to start with omen on it, D16a reworked).
        if (offer.door() != null && !openingMine && !record.interval.endlessMine) {
            record.floor.levelBonus += CapstoneStart.levelBonus(
                    DungeonDefs.current().byId(offer.dungeonId()), offer.nodeId());
        }

        // M11: a zone whose capstone is the boss gets its one proof encounter.
        // Dungeon structure W2: inside a dungeon the capstone boss waits on the final
        // floor only, not on every floor of a multi floor capstone dungeon.
        if (ZoneRules.bossCapstone(effectiveThemeId)
                && (offer.door() == null || TripDoors.isFinal(
                        DungeonDefs.current().byId(offer.dungeonId()), offer.nodeId()))) {
            BossContent.spawn(level, layout.terminal(), offer.level());
        }

        RoomTemplateGenerator.clearBulbs(level, record.stagingCellOrigin, record.roomDungeonDoor);
        DungeonScreen.updateDoor(level, record, DungeonScreen.runContent(level, record));

        for (UUID member : record.members.keySet()) {
            ServerPlayer inside = server.getPlayerList().getPlayer(member);
            if (inside == null) {
                continue;
            }
            clearTrialOmen(inside);
            applyTrialOmen(inside, record);
        }

        // M78: publish the Mine rules at the commitment surface the first time
        // a Mine run opens, so the owner understands the risk (no final floor,
        // previous floors close) and the reward (an escalating haul, banked
        // whenever they go home) before descending. The spatial room movement stays silent, the same as the
        // ordinary loop.
        if (openingMine) {
            ServerPlayer owner = server.getPlayerList().getPlayer(record.owner);
            if (owner != null) {
                owner.sendSystemMessage(Component.literal(EndlessMineRules.mineStartMessage())
                        .withStyle(ChatFormatting.AQUA));
            }
        }
        return true;
    }

    /** {@code dungeonId/nodeId} of the dungeon door behind {@code offer}, or an empty string outside the graph. */
    static String doorKey(Keystone.Offer offer) {
        return offer == null || offer.door() == null ? "" : offer.dungeonId() + "/" + offer.nodeId();
    }

    /**
     * The seam half of a commit, once the floor stands: the preview windows in
     * the staging room's dungeon wall and the entrance's staging-side wall
     * become one walkable doorway, the selector doors give way to the
     * post-selection double doors, and the way home from this checkpoint
     * comes out. Nothing here writes bedrock or clears a margin.
     */
    static void connectStagingToEntrance(ServerLevel level, BlockPos stagingOrigin, BlockPos entranceOrigin,
                                         DoorMask.Direction dungeonDoor) {
        RoomBuilder.openDoor(level, stagingOrigin, mcDirection(dungeonDoor));
        RoomBuilder.openDoor(level, entranceOrigin, mcDirection(CellGeometry.opposite(dungeonDoor)));
        // PD-85: the side window was only for looking; the doorway replaces
        // it. The entrance half copies the course beside it, since a themed
        // cell's wall is not the staging room's shell.
        sealPreviewSideWindow(level, stagingOrigin, dungeonDoor);
        RoomBuilder.sealPreviewSideWindowLikeBeside(level, entranceOrigin,
                mcDirection(CellGeometry.opposite(dungeonDoor)));
        RoomTemplateGenerator.clearSelectorDoors(level, stagingOrigin, dungeonDoor);
        HallRoom.dismantle(level, stagingOrigin, dungeonDoor);
        RoomTemplateGenerator.placePostSelectionDoors(level, stagingOrigin, dungeonDoor);
        // The choosing is over, and so is the way home from this checkpoint.
        RoomTemplateGenerator.clearHomeControl(level, stagingOrigin, dungeonDoor);
        DungeonScreen.clearHome(level, stagingOrigin, dungeonDoor);
    }

    // ---- quit: reset to lobby ----------------------------------------------

    /**
     * Resets a live run back to its lobby state without ejecting anyone:
     * pulls every member into the safe room, clears the dungeon cells beyond
     * it, seals and bedrocks all four walls, re-arms the selector doors and
     * furniture, resets the door screen to idle, and returns the record to
     * {@code HOME}. Called by {@link RunLifecycle#quitDoor}
     * so {@code /dungeon quit} is a "pick a new door" action, not an eject.
     *
     * <p>The room stays in its current cell (it has not moved, since the run
     * was not completed). The keystone penalty is applied by the caller
     * before this runs; this method only handles the physical reset and the
     * record state.
     */
    static void resetToLobby(MinecraftServer server, InstanceRecord record) {
        resetToLobby(server, record, false);
    }

    /**
     * {@link #resetToLobby(MinecraftServer, InstanceRecord)}, for a caller
     * that has just saved the standing safe room itself and refused on a
     * failure ({@link RunLifecycle#quitDoor}), so it is not saved twice.
     *
     * <p>Otherwise a standing safe room is saved here, before anything is
     * cleared. If that save fails the room is left exactly where it stands,
     * neither cleared nor re-stamped from the older blob on disk, and the
     * staging room is rebuilt beside it.
     */
    static void resetToLobby(MinecraftServer server, InstanceRecord record, boolean roomAlreadySaved) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        // M56: clear any active door preview before resetting. This is a
        // true cancel, so the escrowed catalyst is refunded and the armed
        // recipe tags are dropped.
        clearPreview(level, record, true);
        // F9: transition to HOME regardless of whether the dungeon level is
        // loaded. clearPreview may have already transitioned a PREVIEW run
        // to HOME or FLOOR_CLEARED; an ACTIVE run stayed ACTIVE because
        // there was no preview to clear. Without this, the phase is
        // stranded and canChooseDoor rejects every later door selection.
        if (record.phase != RunSession.Phase.HOME) {
            RunSession.transition(record, RunSession.Phase.HOME);
        }
        if (level == null) {
            return;
        }

        // A safe room still standing (quit before commit) is saved before
        // anything is cleared, so it can be re-stamped at the slot origin.
        BlockPos standingRoom = record.roomCellOrigin;
        boolean roomSaved = standingRoom == null || roomAlreadySaved
                || RunLifecycle.saveRoom(level, server, record);
        if (!roomSaved) {
            PocketDungeonsMod.LOG.error("Room save for {} failed during a lobby reset; "
                    + "leaving the room standing at {}", record.owner, standingRoom);
        }

        // Clear every cell the run has standing, wherever it is: the floor,
        // the staging room, liminal cells, homecoming leftovers, and a saved
        // standing room (re-stamped at the slot origin below). An unsaved room
        // is kept where it stands. Their tickets go with them.
        Set<BlockPos> clearing = record.liveCells();
        if (!roomSaved) {
            clearing.remove(standingRoom);
        }
        clearCells(level, record, clearing);
        record.leftBehind.clear();
        record.homecoming = null;
        record.stagingCellOrigin = null;

        // Re-stamp the safe room at the slot origin from RoomStore, or keep
        // an unsaved room where it stands and rebuild the staging beside it.
        BlockPos safeOrigin = record.origin;
        DoorMask.Direction dungeonDir = DoorMask.Direction.SOUTH;
        if (roomSaved) {
            clearCellSync(level, safeOrigin, List.of());
            holdCell(level, safeOrigin);
            stampSafeRoom(level, server, record.owner, safeOrigin);
            record.roomCellOrigin = safeOrigin;
            record.roomDungeonDoor = dungeonDir;
        } else {
            safeOrigin = standingRoom;
            dungeonDir = record.roomDungeonDoor;
        }

        // Stamp a fresh staging room adjacent to the safe room.
        BlockPos stagingOrigin = CellGeometry.offsetInDirection(safeOrigin, dungeonDir, RoomGeometry.CELL);
        holdCell(level, stagingOrigin);
        stampStagingRoom(level, stagingOrigin, dungeonDir);
        RoomBuilder.openDoor(level, safeOrigin, mcDirection(dungeonDir));
        BedrockEnvelope.clearFace(level, safeOrigin, dungeonDir);
        record.stagingCellOrigin = stagingOrigin;
        // Summon fresh screens at the staging room.
        hideLockedDoors(level, stagingOrigin, dungeonDir, record.owner);
        DungeonScreen.summonDoor(level, stagingOrigin, dungeonDir,
                DungeonScreen.idleContent(level, record.owner));
        DungeonScreen.summonHistory(level, stagingOrigin, dungeonDir,
                FloorHistory.board(level.getServer(), record.owner));

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

        // The bag chest is part of the room; place one only if it is missing.
        placeBagChestForParty(level, server, record);

        // Clear trial omen from every member.
        for (UUID member : record.members.keySet()) {
            ServerPlayer inside = server.getPlayerList().getPlayer(member);
            if (inside != null) {
                clearTrialOmen(inside);
            }
        }

        // The interval ends: the record stands at home with a fresh
        // interval and floor, and no homecoming left to wait for.
        record.homecoming = null;
        record.beginInterval(lobbyLayout(safeOrigin));
        // The interval is fresh now, so the staging room is a first staging room again.
        if (record.stagingCellOrigin != null) {
            HallRoom.armFor(level, record.stagingCellOrigin, record.roomDungeonDoor, record.owner);
            DungeonScreen.updateDoor(level, record, DungeonScreen.idleContent(level, record.owner));
        }
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
     *
     * <p>A kept cell's own bedrock is kept with it where the margin reaches
     * it outside this cell: its over-ceiling row along the seam and the two
     * ring columns at either end of the seam. Its ring on the face towards
     * this cell sits in this cell's wall column and goes with the clear;
     * {@link #clearCells} puts it back.
     */
    static void clearCellSync(ServerLevel level, BlockPos origin, List<BlockPos> keepCells) {
        // M61: clear below any lower story a cell may own. Over-clearing a
        // single-story cell is harmless: the extra volume is dungeon void.
        int maxOffset = RoomGeometry.storyOffset(RoomGeometry.MAX_SPAN_Y);
        for (int x = -1; x <= RoomGeometry.CELL; x++) {
            for (int y = -1 - maxOffset; y <= RoomGeometry.CEILING_Y + 1; y++) {
                for (int z = -1; z <= RoomGeometry.CELL; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    boolean ownFootprint = x >= 0 && x < RoomGeometry.CELL && z >= 0 && z < RoomGeometry.CELL;
                    if (ownFootprint ? CellGeometry.insideAnyCell(pos, keepCells)
                            : CellGeometry.inAnyShell(pos, keepCells)) {
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
                e -> !(e instanceof ServerPlayer) && !Lemon.isPart(e)
                        && !CellGeometry.insideAnyCell(e.blockPosition(), keepCells))) {
            entity.discard();
        }
    }

    // ---- live cells: clears, envelopes and tickets ---------------------------

    /**
     * Clears {@code cells} of {@code record}'s instance with every other cell
     * it has standing kept, puts each kept neighbour's bedrock back on the face
     * that looked into a cleared cell, and releases the cleared cells' tickets.
     *
     * <p>What a kept neighbour's doorway on that face becomes is the caller's
     * call: sealed when nothing will stand there again, left open when a cell
     * is about to be stamped in the cleared spot. Either way the ring means a
     * doorway left open shows bedrock, never the void.
     */
    static void clearCells(ServerLevel level, InstanceRecord record, Collection<BlockPos> cells) {
        Set<BlockPos> clearing = new LinkedHashSet<>(cells);
        List<BlockPos> keep = new ArrayList<>(record.liveCells());
        keep.removeAll(clearing);
        for (BlockPos cell : clearing) {
            clearCellSync(level, cell, keep);
        }
        for (BlockPos kept : keep) {
            for (DoorMask.Direction face : DoorMask.Direction.values()) {
                if (clearing.contains(CellGeometry.offsetInDirection(kept, face, RoomGeometry.CELL))) {
                    BedrockEnvelope.applyFace(level, kept, face, storyDepth(record, kept));
                }
            }
        }
        releaseCells(level, clearing, keep);
    }

    /**
     * How far below its floor a cell's ring has to reach: the layout's deepest
     * lower story for one of its cells, nothing for a staging room or a room.
     */
    private static int storyDepth(InstanceRecord record, BlockPos cell) {
        if (record.layout != null && record.layout.geometry() != null
                && record.layout.geometry().cellOrigins().contains(cell)) {
            return record.layout.geometry().storyFloorOffset();
        }
        return 0;
    }

    /**
     * The sides of {@code origin} where another cell of the instance already
     * stands. An envelope applied to {@code origin} has to leave these off: its
     * ring would land in that neighbour's wall column and replace it.
     */
    static Set<DoorMask.Direction> standingSides(InstanceRecord record, BlockPos origin) {
        Set<BlockPos> live = record.liveCells();
        Set<DoorMask.Direction> sides = new HashSet<>();
        for (DoorMask.Direction face : DoorMask.Direction.values()) {
            BlockPos beside = CellGeometry.offsetInDirection(origin, face, RoomGeometry.CELL);
            if (!beside.equals(origin) && live.contains(beside)) {
                sides.add(face);
            }
        }
        return sides;
    }

    /** Force-loads the chunk under a cell about to be stamped: writes to an unloaded chunk are dropped. */
    static void holdCell(ServerLevel level, BlockPos cell) {
        level.setChunkForced(cell.getX() >> 4, cell.getZ() >> 4, true);
    }

    /**
     * Drops the force-load ticket under each of {@code cleared}, unless one of
     * {@code stillLive} sits in the same chunk. A ticket is a flag per chunk,
     * not a count, so releasing a chunk a live cell still needs would release
     * it for that cell too. A cell is exactly one chunk ({@link PlanGeometry}).
     */
    static void releaseCells(ServerLevel level, Collection<BlockPos> cleared, Collection<BlockPos> stillLive) {
        Set<ChunkPos> held = new HashSet<>();
        for (BlockPos cell : stillLive) {
            held.add(new ChunkPos(cell.getX() >> 4, cell.getZ() >> 4));
        }
        for (BlockPos cell : cleared) {
            if (!held.contains(new ChunkPos(cell.getX() >> 4, cell.getZ() >> 4))) {
                level.setChunkForced(cell.getX() >> 4, cell.getZ() >> 4, false);
            }
        }
    }

    /** Cancelling a death leaves the player standing at zero hearts; this resets them so they live. */
    private static void revive(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.removeAllEffects();
        player.clearFire();
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Whether a failed dungeon sends the party back to the Home room (owner ruling 2026-10-09: reopening
     * the lobby after a fail was annoying) instead of closing the run and sending everyone out. True for a
     * floor-loop keystone run that still has its staging room to regroup in; admin, untimed and visit runs
     * keep the old exit.
     */
    static boolean failReturnsHome(InstanceRecord record) {
        return record.isKeystoneRun() && record.stagingCellOrigin != null && !record.visitInstance;
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
    static void rescue(ServerPlayer player, InstanceRecord record) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }

        revive(player);

        // PD-74: a mob that was chasing the player (endermen especially) must
        // not follow into the staging room.
        clearMobTargets(server, record);

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
        //
        // PD-167: with a room to regroup in, the rescue is a detach and a
        // re-admit, not a departure. Going through dropMember there let the
        // leadership rule end the run for the whole party whenever the owner
        // died, at any lives count; under Blood Doors a death spends one life
        // and nothing else. Only a rescue with no room to return to (admin,
        // untimed, visit) is still a real exit and keeps the leadership rule.
        if (hadRoom) {
            detach(server, record, player.getUUID(), player);
        } else {
            RunLifecycle.dropMember(server, record, player.getUUID(), player, "death rescue");
        }

        // dropMember's leadership branch purges the whole record synchronously
        // (InstanceRegistry.bySlot.remove), so this is how rescue tells
        // whether the run it was standing in still exists to be teleported
        // back into.
        boolean stillLive = InstanceRegistry.bySlot.get(slot) == record;
        if (stillLive && hadRoom) {
            // PD-63: this branch teleports the player straight back into the
            // same live instance they were just detached from, not out of
            // it: dropMember's detach() above already removed them from
            // record.members, InstanceRegistry.byMember and Trial Omen.
            // Left alone, they would land back inside a dungeon that no
            // longer knows they exist: onTick's member loop only iterates
            // record.members, so the spawner-clear cue and the exit pad's
            // completion check would silently ignore them from this point
            // on, even though they are
            // standing right there. Re-admitting mirrors what admit() does
            // for a fresh entry, reusing the same point captured above
            // (before detach cleared it) rather than the player's current
            // in-dungeon position, which would make a bad return point.
            if (point != null) {
                record.members.put(player.getUUID(), point);
            }
            InstanceRegistry.byMember.put(player.getUUID(), record);
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
                stillLive
                        // 2026-10-04 (owner report): the old line, "The dungeon throws
                        // you out. You keep everything you were carrying.", read as
                        // being ejected and said too much.
                        ? "You come to at the Doors, with the feeling of a bad omen."
                        : "You come to outside the dungeon.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        // U8 Stage 1: dying inside costs nothing. The walk back is already
        // the cost; a penalty on top would double-charge it.
        RunLifecycle.returnKeystone(server, record, player.getUUID(), player, Keystones.Outcome.NO_CHANGE);
        if (stillLive) {
            announce(server, record, player.getName().getString() + " fell and came to at the Doors.",
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
     * A death at the last life fails the run: everyone is sent home (the Home room, see
     * {@link #failReturnsHome}) with what they carry, unbanked floors pay nothing, and the dungeon's finish is not
     * paid (J2). Keystone level and home room are untouched.
     */
    static void failRunOmen(MinecraftServer server, InstanceRecord record, ServerPlayer deadPlayer,
                                  net.minecraft.world.damagesource.DamageSource source) {
        clearMobTargets(server, record);
        FloorHistory.failed(server, record, deadPlayer, source);
        // A failed dungeon keeps half of every member's haul, present or detached, and loses the rest.
        for (UUID member : new ArrayList<>(record.members.keySet())) {
            RunLifecycle.bankHaul(server, record, member, RunLifecycle.BankContext.FAIL);
        }
        if (failReturnsHome(record)) {
            // Back to the Home room with the pack as carried, the party intact, and the doors re-armed. If
            // the owner later leaves the lobby the leader-left rule closes it for everyone.
            for (UUID member : new ArrayList<>(record.members.keySet())) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                if (player != null) {
                    revive(player);
                }
                RunLifecycle.returnKeystone(server, record, member, player, Keystones.Outcome.NO_CHANGE);
            }
            PlaytestJournal.runFailed(server, record, deadPlayer, source);
            announce(server, record, "The dungeon claims you. You are back home with what you carry.", null);
            resetToLobby(server, record);
            return;
        }
        for (UUID member : new ArrayList<>(record.members.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                eject(server, record, player);
            } else {
                detach(server, record, member, null);
            }
            RunLifecycle.returnKeystone(server, record, member, player, Keystones.Outcome.NO_CHANGE);
        }
        PlaytestJournal.runFailed(server, record, deadPlayer, source);
        announce(server, record, "The dungeon claims you. You keep what you carry.", null);
        InstanceTeardown.purge(server, record, "omen fail", deadPlayer.getUUID());
    }

    /**
     * PD-191 (owner ruling 2026-10-09): the owner of a dungeon leaves it to join another party, which
     * counts as failing it. Every member's haul banks at the fail share, everyone is sent home, and the
     * run closes; the keystone is untouched, exactly as a fifth death.
     */
    static void failRunLeft(MinecraftServer server, InstanceRecord record, ServerPlayer leaver) {
        failRunLeft(server, record, leaver, "left to join another party.");
    }

    /** {@link #failRunLeft(MinecraftServer, InstanceRecord, ServerPlayer)} with the leaver's reason ("left the dungeon."). */
    static void failRunLeft(MinecraftServer server, InstanceRecord record, ServerPlayer leaver, String why) {
        clearMobTargets(server, record);
        RunLifecycle.settleLeaderLeft(server, record, leaver.getUUID());
        announce(server, record, leaver.getName().getString()
                + " " + why + " The dungeon fails; you keep what you carry.", leaver.getUUID());
        for (UUID member : new ArrayList<>(record.members.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                eject(server, record, player);
            } else {
                detach(server, record, member, null);
            }
            RunLifecycle.returnKeystone(server, record, member, player, Keystones.Outcome.NO_CHANGE);
        }
        InstanceTeardown.purge(server, record, "owner left to join another party", leaver.getUUID());
    }

    /**
     * Rising omen sends a small loot-less wave after each member who is still in
     * the dungeon. The number of mobs scales with the new omen value, and they
     * are tagged so their drops are suppressed.
     */
    static void spawnOmenWave(MinecraftServer server, InstanceRecord record, int omen) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null || record.phase != RunSession.Phase.ACTIVE) {
            return;
        }
        EntityType<?>[] waveTypes = { EntityTypes.ZOMBIE, EntityTypes.SKELETON,
                EntityTypes.SPIDER, EntityTypes.CREEPER };
        int count = Math.max(1, Omen.clamp(omen));
        Random random = new Random(server.getTickCount());
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player == null || !player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                continue;
            }
            for (int i = 0; i < count; i++) {
                EntityType<?> type = waveTypes[random.nextInt(waveTypes.length)];
                if (!(type.create(level, EntitySpawnReason.COMMAND) instanceof Mob mob)) {
                    continue;
                }
                Vec3 pos = randomSpawnNear(player, level, random, 5, 10);
                if (pos == null) {
                    continue;
                }
                mob.setPos(pos.x, pos.y, pos.z);
                mob.setTarget(player);
                mob.addTag("pocketdungeons_omen_wave");
                applyMobScale(mob, record.layout.keystoneLevel() + record.floor.levelBonus, Omen.clamp(record.interval.omen));
                level.addFreshEntity(mob);
            }
        }
    }

    private static Vec3 randomSpawnNear(ServerPlayer player, ServerLevel level, Random random,
                                        int minRadius, int maxRadius) {
        Vec3 center = player.position();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = minRadius + random.nextDouble() * (maxRadius - minRadius);
            int x = (int) Math.round(center.x + Math.cos(angle) * distance);
            int z = (int) Math.round(center.z + Math.sin(angle) * distance);
            int y = (int) Math.round(center.y);
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
                continue;
            }
            if (!level.getBlockState(pos.below()).isSolidRender()) {
                continue;
            }
            return Vec3.atBottomCenterOf(pos);
        }
        return null;
    }

    /**
     * Clears every mob's hold on the members before they are pulled out: the
     * plain target, persistent anger on a neutral mob (Enderman, wolf, piglin
     * and the like), and the brain memories a brain driven mob hunts with.
     * Only mobs targeting or angry at a member are touched.
     */
    private static void clearMobTargets(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        Set<UUID> members = new HashSet<>(record.members.keySet());
        AABB box = new AABB(level.getWorldBorder().getMinX(), level.getMinY(),
                level.getWorldBorder().getMinZ(),
                level.getWorldBorder().getMaxX(), level.getMaxY(),
                level.getWorldBorder().getMaxZ());
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, e -> huntsMember(e, members))) {
            mob.setTarget(null);
            if (mob instanceof NeutralMob neutral) {
                neutral.stopBeingAngry();
            }
            mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            mob.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
        }
    }

    /** Whether this mob is targeting, or holding anger against, one of {@code members}. */
    private static boolean huntsMember(Mob mob, Set<UUID> members) {
        if (mob.getTarget() instanceof ServerPlayer p && members.contains(p.getUUID())) {
            return true;
        }
        if (mob instanceof NeutralMob neutral && neutral.getPersistentAngerTarget() != null
                && members.contains(neutral.getPersistentAngerTarget().getUUID())) {
            return true;
        }
        var brain = mob.getBrain();
        if (brain.hasMemoryValue(MemoryModuleType.ANGRY_AT)
                && brain.getMemory(MemoryModuleType.ANGRY_AT).filter(members::contains).isPresent()) {
            return true;
        }
        return brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                && brain.getMemory(MemoryModuleType.ATTACK_TARGET)
                        .filter(t -> members.contains(t.getUUID())).isPresent();
    }

    /**
     * M43.2: the one primitive for detaching a member from an instance's
     * registry footprint, used by {@link #eject}, {@link RunLifecycle#dropMember},
     * and the offline-member branch inside {@code InstanceTeardown.purge},
     * all of which used to independently
     * hand-roll their own subset of this. Room save, {@code members}/
     * {@code byMember} removal, and clearing {@code onPad} happen
     * unconditionally; Trial Omen cleanup only when {@code player} is
     * online, since an offline member has no live {@code ServerPlayer} to
     * clear it against.
     *
     * @return the member's return point, or {@code null} if they had none
     */
    static ReturnPoint detach(MinecraftServer server, InstanceRecord record, UUID member, ServerPlayer player) {
        // Before anything else, while the room is still exactly as they left it.
        RunLifecycle.saveRoomIfOwner(server, record, member);
        PressureSources.forget(member);
        OmenBar.detach(record, member);
        ReturnPoint point = record.members.remove(member);
        record.guests.remove(member);
        InstanceRegistry.byMember.remove(member);
        record.floor.onPad.remove(member);
        if (player != null) {
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
     * M65: whether every online member in the dungeon dimension is inside
     * the safe room during a silent homecoming. The cleanup clears the old
     * staging room and every old floor cell, so a member anywhere else
     * (still in the staging room, or back on an old floor looting) has not
     * crossed. An offline member or one in another dimension does not
     * block; an empty party (everyone disconnected) is treated as crossed so
     * the cleanup is not held forever, and the M63 recovery path handles
     * their return.
     */
    private static boolean allMembersCrossed(MinecraftServer server, InstanceRecord record) {
        if (record.members.isEmpty()) {
            return true;
        }
        if (record.homecoming == null || record.homecoming.oldStagingCellOrigin() == null
                || record.roomCellOrigin == null) {
            return true;
        }
        AABB roomBounds = CellGeometry.cellBounds(record.roomCellOrigin);
        for (UUID member : record.members.keySet()) {
            ServerPlayer memberPlayer = server.getPlayerList().getPlayer(member);
            if (memberPlayer == null
                    || !memberPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                continue;
            }
            if (!roomBounds.contains(Vec3.atCenterOf(memberPlayer.blockPosition()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The bounded fallback for {@link #allMembersCrossed}: once a homecoming
     * has waited {@link #HOMECOMING_STRAGGLER_TICKS}, every online member in
     * the dungeon dimension who is still outside the safe room is moved into
     * it, the way {@code RunLifecycle.resetForNextDungeon} pulls stragglers
     * into the staging room. One idle member cannot hold the old floor and
     * the next staging room open indefinitely.
     */
    private static void pullHomecomingStragglers(MinecraftServer server, InstanceRecord record) {
        if (record.roomCellOrigin == null) {
            return;
        }
        AABB roomBounds = CellGeometry.cellBounds(record.roomCellOrigin);
        for (UUID member : record.members.keySet()) {
            ServerPlayer memberPlayer = server.getPlayerList().getPlayer(member);
            if (memberPlayer == null
                    || !memberPlayer.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)
                    || roomBounds.contains(Vec3.atCenterOf(memberPlayer.blockPosition()))) {
                continue;
            }
            teleport(server, memberPlayer, PocketDungeonsMod.DUNGEON_LEVEL,
                    Vec3.atBottomCenterOf(record.layout.entrance()), record.layout.entranceYaw(), 0.0f);
        }
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
        if (level != null) {
            completeHomecomingCleanup(server, level, record);
        }
    }

    /** {@link #completeHomecomingCleanup(MinecraftServer, InstanceRecord)} in a given level. */
    static void completeHomecomingCleanup(MinecraftServer server, ServerLevel level, InstanceRecord record) {

        // Release everything the run has standing except the room: the old
        // floor, the old staging room, and the liminal cells the floor
        // advances left behind. The room is kept, bedrock ring included, and
        // gets its ring back on every face that looked into a cleared cell.
        Set<BlockPos> leftovers = record.liveCells();
        leftovers.remove(record.roomCellOrigin);
        clearCells(level, record, leftovers);
        record.leftBehind.clear();
        record.homecoming = null;

        // Set up the new staging room in the old one's cell. The room's
        // dungeon door direction (record.roomDungeonDoor) points back toward
        // the old staging room, and the next floor extends on past it.
        DoorMask.Direction newDungeonDir = record.roomDungeonDoor;
        BlockPos newStagingOrigin = CellGeometry.offsetInDirection(
                record.roomCellOrigin, newDungeonDir, RoomGeometry.CELL);
        holdCell(level, newStagingOrigin);
        stampStagingRoom(level, newStagingOrigin, newDungeonDir);
        RoomBuilder.openDoor(level, record.roomCellOrigin, mcDirection(newDungeonDir));
        BedrockEnvelope.clearFace(level, record.roomCellOrigin, newDungeonDir);
        record.stagingCellOrigin = newStagingOrigin;
        record.roomDungeonDoor = newDungeonDir;

        // Summon fresh screens at the new staging room.
        hideLockedDoors(level, newStagingOrigin, newDungeonDir, record.owner);
        DungeonScreen.summonDoor(level, newStagingOrigin, newDungeonDir,
                DungeonScreen.idleContent(level, record.owner));
        DungeonScreen.summonHistory(level, newStagingOrigin, newDungeonDir,
                FloorHistory.board(level.getServer(), record.owner));
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
        if (RunSession.awaitingFirstDoor(record) && record.members.isEmpty()) {
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

            if (rejoinOwnedInstance(server, player, pending.point)) {
                continue;
            }

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
            boolean closed = pending.record == null || pending.record.tearingDown
                    || InstanceRegistry.bySlot.get(pending.record.slot) != pending.record;
            player.sendSystemMessage(Component.literal(closed
                            ? "Your dungeon was closed while you were away."
                            : "You were returned to where you entered the dungeon.")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * The rejoin half of free re-entry: a player who logs back in while their
     * own run is still live and reenterable goes straight back into it rather
     * than out to their return point. They logged out in the dungeon
     * dimension and are still in it, with their survival inventory still
     * stashed, so the re-admit is a teleport within the dimension and no
     * inventory swap fires. {@code point} is the return point saved at
     * disconnect; it replaces the in-dungeon position {@code admit} just
     * recorded, so a later exit still leads back outside.
     *
     * @return whether the player was re-admitted
     */
    static boolean rejoinOwnedInstance(MinecraftServer server, ServerPlayer player, ReturnPoint point) {
        PlaytestJournal.hintEnter(player.getUUID(), "rejoin");
        if (!RunLifecycle.reenterOwnedInstance(player)) {
            return false;
        }
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record != null && point != null) {
            record.members.put(player.getUUID(), point);
        }
        return true;
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
                            AffixMath.parse(entry.keystoneAffix()),
                            AffixManifest.current().definitions()));
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
            // M25: a Pocket2 child has its own countdown and none of the outer
            // run's lifecycle: no keystone expiry, no grace window, no
            // spawner-clear gate, no completion pad. Everything about it lives
            // in Pocket2.tickChild.
            if (record.parentSlot >= 0) {
                Pocket2.tickChild(server, record, now, interval);
                continue;
            }

            // R6: an owner who lost their connection has a grace to come back
            // in; past it the run ends for the party still inside.
            if (record.ownerAbsentUntilTick != 0 && RunLifecycle.watchOwnerGrace(server, record, now)) {
                continue;
            }

            // M65: silent homecoming cleanup. After returnToSafe stamps
            // the saved room behind the final staging door and opens it,
            // the party walks through physically. Once all members have
            // left the old staging room, release the old floor cells and
            // the old staging room, then set up the new staging room
            // adjacent to the room.
            if (record.homecoming != null) {
                if (allMembersCrossed(server, record)) {
                    completeHomecomingCleanup(server, record);
                } else if (now - record.homecoming.sinceTick() >= HOMECOMING_STRAGGLER_TICKS) {
                    // The cleanup itself runs on the next watch tick, once
                    // the moved members read as inside the room.
                    pullHomecomingStragglers(server, record);
                }
                // Skip the rest of the loop for this record while
                // cleanup is pending: the run is not active, the grace
                // window does not apply, and the member sweep below
                // would interfere with the crossing detection.
                OmenBar.sync(server, record);
                continue;
            }

            // U8 Stage 1: the two end conditions, both independent of membership.
            if (record.isKeystoneRun()) {
                // There is no clock: omen is the penalty. A bad floor (high
                // omen) means fewer chests and no level up at the safe
                // visit, and only a voluntary quit depletes the keystone.
                if (!record.floor.completed.isEmpty() && record.floor.expiresAtTick == 0) {
                    record.floor.expiresAtTick = now + PocketDungeonsConfig.rewardRoomGraceSeconds() * 20L;
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
                if (record.floor.expiresAtTick != 0 && hasActiveMember(server, record)) {
                    record.floor.expiresAtTick = now + PocketDungeonsConfig.rewardRoomGraceSeconds() * 20L;
                }
                if (record.floor.expiresAtTick != 0 && now >= record.floor.expiresAtTick) {
                    InstanceTeardown.purge(server, record, "reward room grace elapsed");
                    continue;
                }
            }

            // M22: the spawner-cleared cue. Watched here, once per cell per
            // run: the moment every trial spawner in a cell sits at COOLDOWN,
            // each member standing inside that cell hears it. A divergence
            // from the plan's "one line per call site": there is no per-cell
            // clear event to hook, so this is a small watcher on the same
            // interval the rest of onTick uses.
            if (record.isKeystoneRun() && record.floor.completed.isEmpty()) {
                watchSpawnerClears(server, record);
                // Dungeon structure W7a: the Spawner Dungeon's brood and the Ancient City's Warden.
                CapstoneFights.tick(server, record);
            }
            // Repainted every watch tick, and every member who is in the
            // dungeon dimension is put back on it: whichever way they came in
            // (invited mid-run, re-entered, walked back into the dimension),
            // they see it within one interval.
            OmenBar.sync(server, record);

            for (UUID member : new ArrayList<>(record.members.keySet())) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                if (player == null) {
                    RunLifecycle.dropMember(server, record, member, null, "member offline", true);
                    continue;
                }
                // An admin teleport or any other route out of the dimension counts
                // as leaving -- otherwise a member who is not here holds the party
                // open indefinitely.
                if (!player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                    RunLifecycle.dropMember(server, record, member, player, "member left the dimension");
                    continue;
                }
                if (player.getY() < record.origin.getY() - PocketDungeonsConfig.voidGuardDepth()) {
                    // Rooms are sealed boxes, so this should not happen -- but the
                    // alternative to a guard is an unrecoverable fall through the void.
                    BlockPos entrance = record.layout.entrance();
                    player.teleportTo(player.level(), entrance.getX() + 0.5, entrance.getY(),
                            entrance.getZ() + 0.5, Set.of(), record.layout.entranceYaw(), 0.0f, false);
                    continue;
                }
                PlaytestJournal.roomEntered(player, record);

                // An edge, not a state: without the onPad set a player standing
                // still on the pad after completing would be ejected on the very
                // next watcher tick, with no chance to open a vault.
                // M21: the room's leave pad is gone; only the terminal pad's
                // stand-on check remains.
                boolean onPad = isOnExitPad(player, record);
                boolean stepped = onPad && record.floor.onPad.add(member);
                if (!onPad) {
                    record.floor.onPad.remove(member);
                }
                if (stepped) {
                    if (record.isKeystoneRun() && !record.untimed) {
                        // A dungeon pad: the terminal cell's. First contact ends
                        // the run; every contact after that does nothing at all.
                        // It used to fall through to RunLifecycle.exit() once completed was
                        // set, so stepping off the pad and back on -- trivially
                        // easy while looting the chests standing right there --
                        // threw the player out of the dungeon.
                        if (!record.floor.completed.contains(member)) {
                            RunLifecycle.completeRun(server, record, player);
                        }
                    } else {
                        // /dungeon admin build and untimed runs, where the
                        // dungeon pad is still the way out. An untimed run
                        // never enters ACTIVE, so completeRun would refuse it.
                        RunLifecycle.exit(player, RunLifecycle.ExitReason.EXIT_PAD);
                    }
                }
            }
        }
    }

    // ---- teardown -----------------------------------------------------------

    /**
     * M22: fires the spawner-cleared cue for a run, and records the
     * completion gate's reading for the omen bar. A cell counts as cleared
     * when every trial spawner in it sits at {@code COOLDOWN}, the same
     * predicate {@code TrialContent.countCleared} uses; the cue goes to each
     * member standing inside that cell the moment it first reaches that state.
     * A deliberate small watcher: there is no per-cell clear event to hook.
     */
    private static void watchSpawnerClears(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null || record.layout.trialSpawners().isEmpty()) {
            return;
        }
        // The per-cell grouping is built once per floor, not on every watch
        // tick. It lives on the floor state, so a new floor starts without it.
        if (record.floor.spawnerCellsByCell == null) {
            Map<PlanCell, List<BlockPos>> byCell = new HashMap<>();
            for (BlockPos pos : record.layout.trialSpawners()) {
                PlanCell cell = record.layout.geometry().cellAt(pos);
                if (cell != null) {
                    byCell.computeIfAbsent(cell, c -> new ArrayList<>()).add(pos);
                }
            }
            record.floor.spawnerCellsByCell = byCell;
        }
        // The completion gate's reading, for the omen bar: every trial
        // spawner on the floor, the same count RunLifecycle.completeRun gates
        // the pad on. Progress lives on the bar, not in chat.
        Set<BlockPos> gatedSpawners = TrialContent.activeSpawners(record.layout, level);
        TrialContent.releaseTamed(level, gatedSpawners);
        record.floor.spawnersTotal = gatedSpawners.size();
        record.floor.spawnersCleared = TrialContent.countCleared(level, gatedSpawners);
        for (Map.Entry<PlanCell, List<BlockPos>> e : record.floor.spawnerCellsByCell.entrySet()) {
            PlanCell cell = e.getKey();
            if (record.floor.clearedCells.contains(cell)) {
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
            record.floor.clearedCells.add(cell);
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
        Vec3 at = Vec3.atCenterOf(below);
        // A lodestone in the safe room or the staging room is the player's
        // own decoration or the room menu, never a pad. The lobby layout is
        // the safe room itself, so this also covers HOME.
        if (record.roomCellOrigin != null && CellGeometry.cellBounds(record.roomCellOrigin).contains(at)) {
            return false;
        }
        if (record.stagingCellOrigin != null && CellGeometry.cellBounds(record.stagingCellOrigin).contains(at)) {
            return false;
        }
        return record.layout.bounds().contains(at);
    }

    /** The 16x7x16 box of a single fixed-offset room, for the reward and selector rooms. */
    /**
     * The owner of whichever live instance's room currently occupies {@code pos},
     * or {@code null} if {@code pos} is not inside anyone's room right now (M2
     * T2.2). Deliberately checks {@link InstanceRecord#roomCellOrigin} rather
     * than any cell in the instance -- the quarry cells are covered separately by
     * {@link #dungeonRecordAt} (M31).
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
     * and {@link FloorState#completed} is still empty; once the first
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
        applyMobScale(mob, keystoneLevel, 0);
    }

    static void applyMobScale(Mob mob, int keystoneLevel, int omen) {
        double omenBonus = Omen.clamp(omen) * PocketDungeonsConfig.omenDangerScalePerOmen();
        applyMobScaleBonus(mob, DifficultyProfile.mobScale(keystoneLevel,
                PocketDungeonsConfig.mobScalePerLevel(),
                PocketDungeonsConfig.mobScaleBase()) - 1.0 + omenBonus);
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
                ominous ? Set.of(AffixIds.OMINOUS) : Set.of(), null, null);
        if (layout == null) {
            // Slot release is deferred to the clear buildLayout already queued
            // for whatever it wrote -- see buildLayout's note.
            return -1;
        }

        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), layout,
                Set.of(), null, false);
        InstanceRegistry.bySlot.put(slot, record);
        return slot;
    }

    /**
     * Opens the operator's hand-authoring shell for {@code /dungeon admin
     * buildroom}: one empty cell in the dungeon dimension, stamped with just
     * {@link RoomBuilder#buildShell}: no doors, no keystone, no
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
            // Same as stampLobby: sweep the partial shell through a teardown,
            // which releases the ticket and the slot once its clear finishes.
            PocketDungeonsMod.LOG.error("Could not stamp a build room for {}; clearing whatever was written",
                    player.getUUID(), e);
            InstanceTeardown.teardown(server, slot, origin, lobbyLayout(origin), "build room stamp failed");
            return -1;
        }
        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), lobbyLayout(origin),
                Set.of(), player.getUUID(), false, true);
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
     * Purges every live instance {@code owner} has open right now: their
     * lobby, an active run, and any visit instance of their
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

    /**
     * M76: the operating-envelope summary {@code /dungeon admin diagnostics}
     * prints. One line per metric against its cap, plus the heap, chunk and
     * entity counts an operator needs to judge how close the server is to its
     * limits. No player-facing output, no external telemetry: this is the
     * one command an operator runs instead of attaching a profiler.
     */
    static List<String> adminDiagnostics(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        int live = InstanceRegistry.liveInstanceCount();
        int visits = InstanceRegistry.visitCount();
        int previews = InstanceRegistry.previewCount();
        int queuedClears = InstanceTeardown.pendingClearCountForTesting();
        int used = InstanceRegistry.usedSlots.size();
        lines.add("instances: " + live + "/"
                + capOrUnlimited(PocketDungeonsConfig.maxConcurrentInstances()));
        lines.add("visits: " + visits + "/"
                + capOrUnlimited(PocketDungeonsConfig.maxConcurrentVisits()));
        lines.add("previews: " + previews + "/"
                + capOrUnlimited(PocketDungeonsConfig.maxConcurrentPreviews()));
        lines.add("queued clears: " + queuedClears);
        lines.add("used slots: " + used);

        Runtime rt = Runtime.getRuntime();
        long usedHeap = rt.totalMemory() - rt.freeMemory();
        lines.add("heap: " + (usedHeap / 1024 / 1024) + " MB used / "
                + (rt.maxMemory() / 1024 / 1024) + " MB max");

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level != null) {
            it.unimi.dsi.fastutil.longs.LongSet forced = level.getChunkSource().getForceLoadedChunks();
            int entityCount = level.getEntitiesOfClass(
                    net.minecraft.world.entity.Entity.class,
                    new AABB(level.getWorldBorder().getMinX(), level.getMinY(),
                            level.getWorldBorder().getMinZ(),
                            level.getWorldBorder().getMaxX(), level.getMaxY(),
                            level.getWorldBorder().getMaxZ()),
                    e -> !(e instanceof ServerPlayer)).size();
            lines.add("dungeon chunks force-loaded: " + forced.size());
            lines.add("dungeon entities (excl. players): " + entityCount);
        } else {
            lines.add("dungeon level: not loaded");
        }
        return lines;
    }

    private static String capOrUnlimited(int cap) {
        return cap > 0 ? String.valueOf(cap) : "unlimited";
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

        InventorySwap.liftCursorBeforeCrossing(server, player, dimension);
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
        InventorySwap.liftCursorBeforeCrossing(server, player, target.dimension());
        player.teleport(new TeleportTransition(target, dest, Vec3.ZERO,
                spawn.yaw(), spawn.pitch(), TeleportTransition.DO_NOTHING));
    }
}
