package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
    private static final Map<UUID, ReturnPoint> pendingReturns = new HashMap<>();

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
        });

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
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            PartyService.clearFor(player.getUUID());
            InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
            if (record == null) {
                return;
            }
            ReturnPoint point = record.members.get(player.getUUID());
            if (point != null) {
                pendingReturns.put(player.getUUID(), point);
            }
            // U8 Stage 1: disconnecting is free. The run keeps running, on its
            // own clock, whether or not anyone is here to watch it -- there is
            // nothing to settle on the way out any more.
            RunLifecycle.dropMember(server, record, player.getUUID(), player, "member disconnected");
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
            ReturnPoint point = pendingReturns.remove(player.getUUID());
            pendingJoinRecoveries.add(new PendingJoinRecovery(
                    player.getUUID(), point, JOIN_RECOVERY_DELAY_TICKS));
        });

        // Without this the manifest stays empty until an operator runs
        // `admin manifest reload` by hand, which means every /dungeon on a
        // freshly started server silently gets the static fallback. This is the
        // one load that RoomManifest.register()'s own /reload listener cannot
        // cover -- startup reloads resources before SERVER_STARTED fires, so
        // that listener sees a null server and defers to this call instead.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ThemeManifest.load(server);
            AdventureGraphs.load(server);
            RoomManifest manifest = RoomManifest.load(server);
            if (!manifest.rejections().isEmpty()) {
                PocketDungeonsMod.LOG.error("{} dungeon room(s) were rejected at startup; "
                        + "run /dungeon admin manifest reload for the reasons",
                        manifest.rejections().size());
            }
            LootTables.validateAtStartup(server);
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
        LayoutPlanner.Outcome outcome = LayoutPlanner.plan(
                seed, RoomManifest.current(), PocketDungeonsConfig.planAttemptBudget(),
                PocketDungeonsConfig.pathLengthMin(), PocketDungeonsConfig.pathLengthMax(),
                PocketDungeonsConfig.branchProbability(), PocketDungeonsConfig.loopProbability(),
                PocketDungeonsConfig.maxGridSpan(), theme);

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
        InstanceRegistry.bySlot.put(slot, record);

        admit(server, record, player);
        for (ServerPlayer companion : companions) {
            admit(server, record, companion);
        }

        // The engine screen was summoned at stamp time without a viewer; now
        // that the owner is standing here, show their actual fuel count.
        DungeonScreen.updateEngine(level, record, player);

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
        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
        try {
            boolean placedOwnRoom = RoomStore.place(level, server, owner, origin, 0,
                    RandomSource.create(level.getRandom().nextLong()));
            if (!placedOwnRoom) {
                TemplateStamper.place(level, level.getStructureManager(), origin,
                        TemplateStamper.ENTRANCE_HALL, 0, level.getRandom().nextLong());
            }
            RoomBuilder.sealDoor(level, origin, mcDirection(lobbyDoorDirection()));
            // All four sides get bedrock here, including the SOUTH dungeon wall:
            // before a door is chosen there is nothing behind that wall but void,
            // so leaving it open let a player who broke through the sealed door
            // fall out. The SOUTH face is cleared in generateBehindLobby, right
            // before the dungeon is actually stamped behind it.
            BedrockEnvelope.applyToCell(level, origin, java.util.Set.of());
            // The MM slot itself has to be sealed explicitly, the same as ee just
            // above -- RoomStore.place stamps the owner's blob exactly as it was
            // captured, and a room saved mid-run (saveRoom on disconnect, or any
            // other leave path while a dungeon was generated behind it) captures
            // that wall genuinely open. Without this, a returning owner's very
            // first lobby stamp would carry that hole straight through: no wall,
            // though now with a bedrock backstop behind it either way.
            RoomBuilder.sealDoor(level, origin, mcDirection(DoorMask.Direction.SOUTH));
            // The selector doors and MM slot sit on the room's *dungeon* wall,
            // not its entrance wall -- lobbyDoorDirection() is the latter (it is
            // where sealDoor/the bedrock envelope's reserved side belong, ee's
            // wall). A fresh record always starts at InstanceRecord's default
            // roomDungeonDoor (SOUTH); stampLobby/createVisitInstance run before
            // the InstanceRecord exists, so that default is named directly here
            // instead, and the two must not drift apart.
            RoomTemplateGenerator.placeSelectorDoors(level, origin, DoorMask.Direction.SOUTH);
            RoomTemplateGenerator.placeWallLodestone(level, origin);
            // M19: the physical selection furniture (bulbs, lever, screens) and
            // the two text_display entities, summoned fresh at every stamp and
            // never captured with the room.
            RoomTemplateGenerator.placeFurniture(level, origin, DoorMask.Direction.SOUTH);
            DungeonScreen.summonDoor(level, origin, DoorMask.Direction.SOUTH, DungeonScreen.idleContent());
            DungeonScreen.summonEngine(level, origin, DoorMask.Direction.SOUTH,
                    DungeonScreen.engineContent(null));
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not stamp a lobby for {}", owner, e);
            level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
            InstanceRegistry.usedSlots.remove(slot);
            return null;
        }
        return lobbyLayout(origin);
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
                0L, 1, 1, 1, false, EnumSet.noneOf(Affix.class), 0, origin, 0, 0, Set.of());
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
        if (record == null || !record.awaitingDoorChoice || !player.getUUID().equals(record.owner)) {
            return null;
        }
        BlockPos o = record.roomCellOrigin;
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
        if (record == null || !record.awaitingDoorChoice || !player.getUUID().equals(record.owner)
                || record.roomCellOrigin == null) {
            return false;
        }
        return RoomTemplateGenerator.leverPos(record.roomCellOrigin, record.roomDungeonDoor).equals(pos);
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
        if (record == null || record.roomCellOrigin == null) {
            return false;
        }
        return RoomTemplateGenerator.enginePos(record.roomCellOrigin, record.roomDungeonDoor).equals(pos);
    }

    /**
     * The generation half of {@code RunLifecycle.chooseOffer}: before planning,
     * any stragglers still in the old dungeon are pulled into the room, the old dungeon cells
     * are cleared, and the room's {@code ee} wall (the entrance from the previous
     * dungeon) is sealed. Then a new shape is planned so its entrance edge lines
     * up with the room's {@code MM} side, and everything except cell 0 (the room)
     * is stamped.
     */
    static boolean generateBehindLobby(MinecraftServer server, ServerLevel level,
                                                InstanceRecord record, Keystone.Offer offer, int step) {
        // M2/M3: clear the previous dungeon before generating the next one.
        RunLifecycle.resetForNextDungeon(server, record);

        long seed = level.getRandom().nextLong();
        DoorMask.Direction dungeonDoor = record.roomDungeonDoor;
        ThemeManifest.Entry theme = ThemeManifest.current().byId(offer.theme());
        LayoutPlanner.Outcome outcome = LayoutPlanner.plan(
                seed, RoomManifest.current(), PocketDungeonsConfig.planAttemptBudget(),
                PocketDungeonsConfig.pathLengthMin(), PocketDungeonsConfig.pathLengthMax(),
                PocketDungeonsConfig.branchProbability(), PocketDungeonsConfig.loopProbability(),
                PocketDungeonsConfig.maxGridSpan(), theme == null ? null : theme.meta().roomTheme,
                dungeonDoor);

        DungeonPlan plan = outcome.plan();
        // The door's elective affix plus whatever the offered level seeds: the run
        // about to be stamped is the run the new key advertises, not just the door.
        EnumSet<Affix> affixes = AffixMath.effective(record.owner, offer.level(), offer.affixes());
        InstanceLayout layout;
        if (plan != null) {
            // The room is physically at record.roomCellOrigin, resolved to grid
            // (0,0). The plan's own cell set may reach negative x/z, so the
            // world origin is shifted back by the minimum cell coordinates.
            int minX = plan.cells().stream().mapToInt(PlanCell::x).min().orElse(0);
            int minZ = plan.cells().stream().mapToInt(PlanCell::z).min().orElse(0);
            BlockPos planOrigin = record.roomCellOrigin.offset(minX * RoomGeometry.CELL, 0, minZ * RoomGeometry.CELL);
            PlanGeometry geometry = PlanGeometry.of(planOrigin, plan.cells());
            forceLoad(level, geometry.chunks(), true);
            // The lobby was bedrocked on all four sides so a player could not fall
            // into the void before choosing a door. Now that a dungeon is actually
            // about to exist behind it, clear that face so the two connect.
            BedrockEnvelope.clearFace(level, record.roomCellOrigin, dungeonDoor);
            try {
                layout = LayoutStamper.stampBehindLobby(level, planOrigin, plan, offer.level(), affixes,
                        record.owner, offer.theme());
            } catch (RuntimeException e) {
                PocketDungeonsMod.LOG.error("Stamping plan behind the room at {} failed",
                        record.roomCellOrigin.toShortString(), e);
                InstanceTeardown.teardown(server, record.slot, record.origin, InstanceLayout.forClearingOnly(planOrigin, geometry),
                        "stamp failed");
                return false;
            }
        } else {
            PocketDungeonsMod.LOG.warn(
                    "Planning failed behind the room for seed {} after {} attempts ({})",
                    seed, outcome.attemptsUsed(), outcome.failureReason());
            return false;
        }

        RoomBuilder.openDoor(level, record.roomCellOrigin, mcDirection(dungeonDoor));
        RoomTemplateGenerator.clearSelectorDoors(level, record.roomCellOrigin, dungeonDoor);
        // M18 9.2: the punched doorway gets physical double doors (Y=1..2) with
        // a wall lintel (Y=3), so mobs from the first dungeon cell cannot walk
        // straight into the room. They are plain vanilla doors the player opens
        // by hand; selectorDoorStep no longer claims clicks once the run is
        // underway, so right-clicking falls through to vanilla.
        RoomTemplateGenerator.placePostSelectionDoors(level, record.roomCellOrigin, dungeonDoor);

        record.layout = layout;
        record.affixes = affixes;
        record.theme = offer.theme();
        record.awaitingDoorChoice = false;
        record.chosenStep = step;
        record.freeDoor = offer.free();

        // M11: a boss-themed run gets its one proof encounter, spawned now so
        // it is already standing in the terminal cell the first time anyone
        // can reach it, never spawned reactively on pad contact.
        AdventureGraph.Node themeNode = AdventureGraphs.current().graph().node(offer.theme());
        if (themeNode != null && themeNode.kind() == AdventureGraph.Kind.BOSS) {
            BossContent.spawn(level, layout.terminal(), offer.level());
        }

        // This InstanceRecord is being reused for a second run behind the same
        // lobby (M3: finish a dungeon, choose again without ever leaving), not
        // replaced. Every piece of state completeRun/the exit-pad watcher use to
        // recognise "this member already finished" belongs to the run that just
        // ended, not the one about to start -- left alone, the exit-pad watcher
        // finds the owner already in record.completed the moment they touch the
        // new terminal pad and routes them through plain exit() instead of
        // completeRun(), which reads as being ejected right at the finish line.
        // record.expiresAtTick carries the same risk on a longer timeline: it is
        // the previous run's reward-room grace deadline, computed from a
        // completion that already happened, and left ticking it can expire
        // *during* the new run and retire the whole record out from under the
        // player. rewardChests stays at its old value for the same reason a
        // stale keystoneReturned would let a second completion slip past
        // returnKeystone's once-only guard.
        record.completed.clear();
        record.keystoneReturned.clear();
        record.onPad.clear();
        record.visited.clear();
        // The spawner-cleared cue is per-run state too (M22): a cell added in
        // the run that just ended must cue again in the next one.
        record.clearedCells.clear();
        record.rewardChests = -1;
        record.expiresAtTick = 0;

        // The previous run's bar, if this record is being reused for a second
        // dungeon behind the same lobby. Dropping the reference without closing it
        // first leaves a dead ServerBossEvent with every member still attached --
        // nothing ticks it again, so it hangs on their screens frozen at whatever
        // the old run's last reading was, beside the new one.
        if (record.timer != null) {
            record.timer.close();
            record.timer = null;
        }
        if (!record.untimed) {
            // M12: door 1 gets its own flat, generous clock instead of the
            // room-count formula doors 2/3 use. "Farming" should not feel
            // like racing.
            int seconds = record.freeDoor ? PocketDungeonsConfig.door1TimerSeconds()
                    : KeystoneMath.timerSeconds(PocketDungeonsConfig.timerBaseSeconds(),
                            PocketDungeonsConfig.timerPerRoomSeconds(), layout.pathLength());
            record.timer = new RunTimer(layout.keystoneLevel(), seconds, layout.roomCount());
        }

        // M19: the run is underway, so the pending door selection is over.
        // The bulbs go dark and the door screen switches to the run context
        // (level, theme, affixes, clock); selectedStep stays 0 until the next
        // lobby re-arms the room.
        record.selectedStep = 0;
        RoomTemplateGenerator.setAllBulbs(level, record.roomCellOrigin, record.roomDungeonDoor, false);
        DungeonScreen.updateDoor(level, record, DungeonScreen.runContent(record));

        // Everything admit() hands a player off the record's layout has to be
        // handed out again here, and this is the only place it can be.
        //
        // <p>Lobby-first entry (M2/M3 3.2.3) inverted the order the old flow had:
        // admit() runs at enterLobby, when the record carries a one-cell lobby
        // layout with no level, no affixes and no clock, and the real run does not
        // exist until a door is chosen -- here. admit()'s two side effects were
        // both written against the old "enter() builds the whole dungeon, then
        // admits" order and are silently skipped by the new one: the boss bar is
        // created with nobody watching it (the clock runs, expires and can end the
        // run without a single player ever having seen it), and an ominous run
        // never grants its Trial Omen. Re-running them for every member standing
        // in the lobby is what puts the two orders back in agreement.
        for (UUID member : record.members.keySet()) {
            ServerPlayer inside = server.getPlayerList().getPlayer(member);
            if (inside == null) {
                continue;
            }
            if (record.timer != null) {
                record.timer.addPlayer(inside);
            }
            // Clear before applying, not instead of: a second run behind the same
            // lobby can be plain where the first was ominous, and applyTrialOmen
            // returns early on a plain run rather than taking the old effect away.
            clearTrialOmen(inside);
            applyTrialOmen(inside, record);
        }
        return true;
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
        for (int x = -1; x <= RoomGeometry.CELL; x++) {
            for (int y = -1; y <= RoomGeometry.CEILING_Y + 1; y++) {
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
        AABB bounds = new AABB(origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
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

        eject(server, record, player);
        player.sendSystemMessage(Component.literal(
                "The dungeon throws you out. You keep everything you were carrying.")
                .withStyle(ChatFormatting.RED));
        // U8 Stage 1: dying inside costs nothing. Under a wall clock the walk
        // back is already the cost; a penalty on top would double-charge it.
        RunLifecycle.returnKeystone(server, record, player.getUUID(), player, Keystones.Outcome.NO_CHANGE);
        announce(server, record, player.getName().getString() + " was thrown out of the dungeon.",
                player.getUUID());

        purgeIfAbandonedLobby(server, record);
    }

    /** Teleports one member to their own return point and drops them from the party. */
    static void eject(MinecraftServer server, InstanceRecord record, ServerPlayer player) {
        // Before the teleport, while the room is still exactly as they left it.
        RunLifecycle.saveRoomIfOwner(server, record, player.getUUID());
        ReturnPoint point = record.members.remove(player.getUUID());
        InstanceRegistry.byMember.remove(player.getUUID());
        record.onPad.remove(player.getUUID());
        if (record.timer != null) {
            record.timer.removePlayer(player);
        }
        clearTrialOmen(player);
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
            if (record.timer != null) {
                record.timer.tick(interval);
            }

            // U8 Stage 1: the two end conditions, both independent of membership.
            if (record.isKeystoneRun()) {
                if (record.timer != null && record.timer.overTime() && record.completed.isEmpty()
                        && !record.timedOutPenaltyApplied) {
                    RunLifecycle.expireTimedOut(server, record);
                }
                // PD-7: timeout no longer closes the dungeon, so a timed out but
                // never completed run still needs a grace window or the slot is
                // held forever. Reuses the same grace config as the post
                // completion window.
                if (record.timedOutPenaltyApplied && record.completed.isEmpty()
                        && record.expiresAtTick == 0) {
                    record.expiresAtTick = now + PocketDungeonsConfig.rewardRoomGraceSeconds() * 20L;
                }
                if (!record.completed.isEmpty() && record.expiresAtTick == 0) {
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
        return record.roomCellOrigin != null
                && CellGeometry.cellBounds(record.roomCellOrigin).contains(Vec3.atCenterOf(below));
    }

    /** The 16x7x16 box of a single fixed-offset room, for the reward and selector rooms. */
    /**
     * The owner of whichever live instance's room currently occupies {@code pos},
     * or {@code null} if {@code pos} is not inside anyone's room right now (M2
     * T2.2). Deliberately checks {@link InstanceRecord#roomCellOrigin} rather
     * than any cell in the instance -- the quarry (every other cell) stays fully
     * breakable by anyone, and a lingering instance is still checked here since
     * it stays in {@code InstanceRegistry.bySlot}.
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
        return record == null ? null : record.roomCellOrigin;
    }

    /**
     * Which wall of the room occupying {@code pos} the selector doors stand on
     * (the record's {@link InstanceRecord#roomDungeonDoor}), or {@code null}
     * outside every room. The direction half of {@link #roomOriginAt}: the
     * furniture protection (M19 19.7) needs it to run its wall-relative
     * coordinate test, and like the origin it shares {@link #roomRecordAt} so
     * the two lookups can never disagree.
     */
    static DoorMask.Direction roomDungeonDoorAt(BlockPos pos) {
        InstanceRecord record = roomRecordAt(pos);
        return record == null ? null : record.roomDungeonDoor;
    }

    private static InstanceRecord roomRecordAt(BlockPos pos) {
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            BlockPos roomOrigin = record.roomCellOrigin;
            if (roomOrigin == null) {
                continue;
            }
            if (pos.getX() >= roomOrigin.getX() && pos.getX() < roomOrigin.getX() + RoomGeometry.CELL
                    && pos.getZ() >= roomOrigin.getZ() && pos.getZ() < roomOrigin.getZ() + RoomGeometry.CELL
                    && pos.getY() >= roomOrigin.getY()
                    && pos.getY() <= roomOrigin.getY() + RoomGeometry.CEILING_Y) {
                return record;
            }
        }
        return null;
    }

    // ---- mob scaling (M10) ---------------------------------------------------

    /** The one shown by {@code AttributeInstance}'s modifier map, stable across reapplications. */
    private static final Identifier MOB_SCALE_ID = Identifier.fromNamespaceAndPath(
            PocketDungeonsMod.MOD_ID, "difficulty_scale");

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
     * +1% (config: {@link PocketDungeonsConfig#mobScalePerLevel()}) per keystone
     * level, on max health, attack damage and movement speed: a level-100 run's
     * mobs land at roughly twice strength. {@code ADD_MULTIPLIED_TOTAL} so the
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
                PocketDungeonsConfig.mobScalePerLevel()) - 1.0);
    }

    /**
     * The bonus-taking half of {@link #applyMobScale}, exposed separately for
     * {@link BossContent}: a boss run's mob wants a steeper bonus than the
     * ordinary {@code mobScalePerLevel} curve, not the same one.
     */
    static void applyMobScaleBonus(Mob mob, double bonus) {
        if (bonus <= 0.0) {
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
    private static void forceLoad(ServerLevel level, List<ChunkPos> chunks, boolean forced) {
        for (ChunkPos chunk : chunks) {
            level.setChunkForced(chunk.x(), chunk.z(), forced);
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
