package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Slot allocation, party membership, entry, exit, and teardown. Section 9's
 * fixed grid with a free-list: slots are reused, so the region footprint on disk
 * is bounded by peak concurrency rather than by how many dungeons have ever been
 * run.
 */
final class Instances {

    /** Section 9: not config-exposed -- changing it mid-campaign would relocate
     *  every existing instance's world coordinates. */
    private static final int BASE_Y = 64;

    /** Every member of every live instance resolves to its record. Many-to-one. */
    private static final Map<UUID, InstanceRecord> byMember = new HashMap<>();
    /** Live instances by slot, including any that momentarily have no members. */
    private static final Map<Integer, InstanceRecord> bySlot = new LinkedHashMap<>();
    /** Allocated slots, owned instances and admin builds alike. */
    private static final Set<Integer> usedSlots = new TreeSet<>();

    /** Invitee -> outstanding invitation. */
    private static final Map<UUID, Invite> invites = new HashMap<>();

    /**
     * Companions pre-registered via {@code /dungeon party}, keyed by the
     * leader who will open the dungeon. Consumed (and cleared) the moment that
     * leader runs {@code /dungeon} -- see U3 Stage 5: {@code invite}/{@code join}
     * only work from inside an instance, so this is what lets a party's size be
     * known <em>before</em> stamping, without re-stamping on a later join.
     */
    private static final Map<UUID, Set<UUID>> pendingParty = new HashMap<>();

    /** Return points for players who left the world while inside, keyed by UUID. */
    private static final Map<UUID, ReturnPoint> pendingReturns = new HashMap<>();

    /**
     * Recovery teleports queued from the JOIN handler, delayed rather than
     * fired synchronously. JOIN fires as part of the connection handshake
     * itself -- issuing a dimension-change teleport in that exact window
     * races the client's own initial world load and is what caused the
     * "stuck on Loading terrain..." login hang (see PLAN.md). A short delay
     * lets that initial load settle first.
     */
    /** Longer than any plausible run; {@code clearTrialOmen} is what actually ends it. */
    private static final int TRIAL_OMEN_TICKS = 20 * 60 * 90;

    private static final int JOIN_RECOVERY_DELAY_TICKS = 20;
    private static final List<PendingJoinRecovery> pendingJoinRecoveries = new ArrayList<>();

    /** Teardowns still writing air. A slot stays allocated until its clear lands. */
    private static final List<PendingClear> pendingClears = new ArrayList<>();

    private static int tickCounter;

    private Instances() {}

    private record Invite(UUID leader, int slot, long expiresAtMillis) {}

    /**
     * A staged {@code /dungeon party kick}, waiting on its clickable confirmation.
     *
     * <p>Keyed by the leader and holding the target itself, so the confirm command
     * takes no arguments. That is deliberate: a companion who has logged off still
     * occupies a party slot and still needs removing, and
     * {@code EntityArgument.player()} cannot name someone who is not online.
     * Staging the UUID here sidesteps that entirely.
     *
     * @param target the companion to drop, or {@code null} for "all of them"
     */
    private record PendingKick(UUID target, long expiresAtMillis) {}

    /** Staged kicks awaiting confirmation, keyed by leader. One at a time. */
    private static final Map<UUID, PendingKick> pendingKicks = new HashMap<>();

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
            processClears(server);
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
            InstanceRecord record = byMember.get(player.getUUID());
            if (record == null || !player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                return true;
            }
            rescue(player, record);
            return false;
        });

        // Leaving the world drops you from the party. The return point is kept so
        // the next login puts you back where you started, and the instance itself
        // survives for whoever is still inside it.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            invites.remove(player.getUUID());
            pendingParty.remove(player.getUUID());
            pendingKicks.remove(player.getUUID());
            InstanceRecord record = byMember.get(player.getUUID());
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
            dropMember(server, record, player.getUUID(), player, "member disconnected");
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
            if (byMember.containsKey(player.getUUID())) {
                return;
            }
            ReturnPoint point = pendingReturns.remove(player.getUUID());
            pendingJoinRecoveries.add(new PendingJoinRecovery(
                    player.getUUID(), point, JOIN_RECOVERY_DELAY_TICKS));
        });

        // Without this the manifest stays empty until an operator runs
        // `admin manifest reload` by hand, which means every /dungeon on a
        // freshly started server silently gets the static fallback. M2 left the
        // load explicit on purpose (wiring it to /reload is its own problem), but
        // "explicit" was never meant to include the server's own startup.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RoomManifest manifest = RoomManifest.load(server);
            if (!manifest.rejections().isEmpty()) {
                PocketDungeonsMod.LOG.error("{} dungeon room(s) were rejected at startup; "
                        + "run /dungeon admin manifest reload for the reasons",
                        manifest.rejections().size());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (int slot : new ArrayList<>(usedSlots)) {
                InstanceRecord record = bySlot.get(slot);
                if (record != null) {
                    purge(server, record, "server stopping");
                } else {
                    teardown(server, slot, originForSlot(slot), null, "server stopping");
                }
            }
            // There are no more ticks coming, so the queued clears have to run
            // now. A force-load ticket that never gets released pins its chunks
            // for the rest of the process (PLAN.md's M4 amendment).
            drainClears(server);
        });
    }

    // ---- entry --------------------------------------------------------------

    static boolean hasInstance(ServerPlayer player) {
        return byMember.containsKey(player.getUUID());
    }

    /**
     * Opens a dungeon for this player and their pre-registered party.
     *
     * <p>Returns whether the player actually went in. Every failure path here
     * leaves them standing exactly where they were, and {@link RitualListener}
     * pays a real item for entry -- so "did this work" has to be answerable by
     * the caller rather than inferred from a message the player was sent.
     */
    static boolean enter(ServerPlayer player) {
        return enter(player, 0, Keystone.Affix.NONE);
    }

    /**
     * Spends a keystone from this player's inventory and opens the run it buys,
     * or -- if this player already owns a live instance -- teleports them back
     * into it for free (U8 Stage 1: free re-entry).
     *
     * <p>The stack is shrunk only after {@code enter} has actually succeeded --
     * U4's rule, and it matters more here than it did for an echo shard, because a
     * keystone carries a level somebody has spent several runs earning. The stack
     * reference survives the cross-dimension teleport inside {@code enter}:
     * {@code ServerPlayer.teleport} is {@code aload_0 ... areturn} throughout and
     * never recreates the player, which U4's shipped notes confirmed at bytecode
     * level.
     *
     * @return whether the player went in (fresh or re-entered)
     */
    static boolean enterWithKeystone(ServerPlayer player) {
        if (reenterOwnedInstance(player)) {
            return true;
        }

        ItemStack keystone = Keystone.findHeld(player);
        if (keystone == null) {
            player.sendSystemMessage(Component.literal(
                    "You need a keystone to open a dungeon. Run /dungeon key for your first one.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        int level = Keystone.levelOf(keystone).orElse(1);
        Keystone.Affix affix = Keystone.affixOf(keystone);

        if (!enter(player, level, affix)) {
            return false;
        }
        keystone.shrink(1);
        return true;
    }

    /**
     * The free-re-entry branch of U8 Stage 1: if this player owns a live instance
     * they are not currently standing in, teleport them back rather than opening a
     * new one. Must run <strong>before</strong> any keystone is spent -- re-entering
     * an existing run costs nothing and must not re-roll its layout.
     *
     * @return true if this call was handled here, whatever the outcome
     */
    private static boolean reenterOwnedInstance(ServerPlayer player) {
        if (hasInstance(player)) {
            // Already physically inside something -- their own run, or (in
            // practice, never) someone else's. Let the normal "already in a
            // dungeon" refusal in enter() handle it.
            return false;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        InstanceRecord existing = null;
        for (InstanceRecord candidate : bySlot.values()) {
            if (!candidate.selectorRoom && player.getUUID().equals(candidate.owner)) {
                existing = candidate;
                break;
            }
        }
        if (existing == null) {
            return false;
        }
        admit(server, existing, player);
        player.sendSystemMessage(Component.literal("You step back into your dungeon.")
                .withStyle(ChatFormatting.GOLD));
        return true;
    }

    static boolean enter(ServerPlayer player, int keystoneLevel, Keystone.Affix affix) {
        return enter(player, keystoneLevel, affix, false);
    }

    /**
     * Opens a dungeon with no clock, for {@code /dungeon admin untimed}.
     *
     * <p>The one run that never ends on its own. Spends no keystone, so an
     * operator can walk a level 12 layout without owning a level 12 key, and both
     * its creation and its teardown are logged so it cannot be quietly forgotten.
     */
    static boolean enterUntimed(ServerPlayer player, int keystoneLevel, boolean ominous) {
        return enter(player, Math.max(1, keystoneLevel),
                ominous ? Keystone.Affix.OMINOUS : Keystone.Affix.NONE, true);
    }

    static boolean enter(ServerPlayer player, int keystoneLevel, Keystone.Affix affix,
                         boolean untimed) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        if (hasInstance(player)) {
            player.sendSystemMessage(Component.literal(
                    "You are already in a dungeon. Use /dungeon exit first.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            player.sendSystemMessage(Component.literal(
                    "The dungeon dimension is not loaded. This world needs the "
                            + "Pocket Dungeons data pack enabled.")
                    .withStyle(ChatFormatting.RED));
            PocketDungeonsMod.LOG.error("Dimension {} is missing",
                    PocketDungeonsMod.DUNGEON_LEVEL.identifier());
            return false;
        }

        List<ServerPlayer> companions = resolveParty(server, player);
        int partySize = 1 + companions.size();

        long seed = level.getRandom().nextLong();
        int slot = allocateSlot();
        BlockPos origin = originForSlot(slot);

        // U8 Stage 6: a run is ominous iff its keystone carries the ominous
        // affix -- one source, replacing U6/U7's depth ramp, off-hand bottle and
        // ominousFromLevel threshold.
        boolean ominous = affix == Keystone.Affix.OMINOUS;

        // On failure buildLayout has already queued a clear for whatever partial
        // geometry it wrote and the slot's release happens when that clear
        // finishes -- it is deliberately not freed here. See buildLayout's note.
        InstanceLayout layout = buildLayout(server, level, slot, origin, seed, keystoneLevel, ominous);
        if (layout == null) {
            player.sendSystemMessage(Component.literal(
                    "The dungeon failed to build. You have not been moved.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), layout, affix,
                player.getUUID(), false, untimed);
        bySlot.put(slot, record);

        if (untimed) {
            PocketDungeonsMod.LOG.info(
                    "UNTIMED dungeon opened in slot {} by {} (level {}, {} rooms, seed {}). "
                            + "It will not expire on its own -- /dungeon admin purge {} to close it.",
                    slot, player.getName().getString(), layout.keystoneLevel(),
                    layout.roomCount(), layout.seed(), slot);
        }

        // Always, for every keystone run. The clock is the only thing that ends an
        // ordinary dungeon now, so the sole way to opt out is an operator asking
        // for it explicitly by the command -- and that one is logged and listed.
        if (record.isKeystoneRun() && !record.untimed) {
            record.timer = new RunTimer(layout.keystoneLevel(),
                    KeystoneMath.timerSeconds(PocketDungeonsConfig.timerBaseSeconds(),
                            PocketDungeonsConfig.timerPerRoomSeconds(), layout.pathLength()),
                    layout.roomCount());
        }

        admit(server, record, player);
        for (ServerPlayer companion : companions) {
            admit(server, record, companion);
        }

        player.sendSystemMessage(Component.literal("You step into the dungeon.")
                .withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal(
                layout.roomCount() + " rooms. Nothing here can kill you -- a killing "
                        + "blow throws you out with your inventory intact -- but "
                        + "anything you drop inside is lost. Stand on a lodestone, "
                        + "or run /dungeon exit, to leave.")
                .withStyle(ChatFormatting.GRAY));
        if (!layout.procedural()) {
            player.sendSystemMessage(Component.literal(
                    "The dungeon collapsed into its oldest shape -- four rooms, "
                            + "heading east. Tell an operator: the room library or "
                            + "the planner needs looking at.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (companions.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "Bring someone along with /dungeon invite <player> once you're in, "
                            + "or /dungeon party <player> before you go in to scale the "
                            + "dungeon for your whole group.")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            StringBuilder names = new StringBuilder();
            for (ServerPlayer companion : companions) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(companion.getName().getString());
                companion.sendSystemMessage(Component.literal(
                        "You step into " + player.getName().getString() + "'s dungeon.")
                        .withStyle(ChatFormatting.GOLD));
            }
            player.sendSystemMessage(Component.literal(
                    names + " came in with you. The dungeon is scaled for your party.")
                    .withStyle(ChatFormatting.GRAY));
        }

        if (layout.ominous()) {
            player.sendSystemMessage(Component.literal(
                    "The run is ominous. Every spawner and every vault bites harder, "
                            + "and the payout is worth more for it.")
                    .withStyle(ChatFormatting.DARK_PURPLE));
        }
        if (record.isKeystoneRun()) {
            player.sendSystemMessage(Component.literal(
                    "Keystone [" + layout.keystoneLevel() + "] spent. Clear a trial spawner for a "
                            + "key, spend the key on a vault, and beat the clock for the best "
                            + "reward room. Anything a vault ejects onto the floor is lost when "
                            + "the dungeon closes -- pick it up.")
                    .withStyle(ChatFormatting.GRAY));
        }

        PocketDungeonsMod.LOG.info(
                "Opened dungeon slot {} for {} ({} rooms, tier {}, party {}, keystone {}, "
                        + "ominous {}, seed {})",
                slot, player.getName().getString(), layout.roomCount(),
                layout.lootTier(), partySize, layout.keystoneLevel(), layout.ominous(),
                layout.seed());
        return true;
    }

    /**
     * Consumes and resolves this leader's pre-registered {@code /dungeon party}
     * companions into online, still-eligible players. Registration is
     * one-shot: whether or not the build below succeeds, the reservation is
     * spent.
     */
    private static List<ServerPlayer> resolveParty(MinecraftServer server, ServerPlayer leader) {
        Set<UUID> pending = pendingParty.remove(leader.getUUID());
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        List<ServerPlayer> companions = new ArrayList<>();
        int cap = PocketDungeonsConfig.maxPartyMembers() - 1;
        for (UUID id : pending) {
            if (companions.size() >= cap) {
                break;
            }
            ServerPlayer companion = server.getPlayerList().getPlayer(id);
            if (companion == null || companion.getUUID().equals(leader.getUUID())
                    || hasInstance(companion)) {
                continue;
            }
            companions.add(companion);
        }
        return companions;
    }

    /**
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
     * branch (nor either caller) removes the slot from {@code usedSlots} directly
     * on failure any more -- that happens when the queued clear completes, the
     * same as every other teardown path.
     *
     * @return the layout, or null if stamping itself failed
     */
    private static InstanceLayout buildLayout(MinecraftServer server, ServerLevel level,
                                              int slot, BlockPos origin, long seed,
                                              int keystoneLevel, boolean ominous) {
        LayoutPlanner.Outcome outcome = LayoutPlanner.plan(
                seed, RoomManifest.current(), PocketDungeonsConfig.planAttemptBudget(),
                PocketDungeonsConfig.pathLengthMin(), PocketDungeonsConfig.pathLengthMax(),
                PocketDungeonsConfig.branchProbability(), PocketDungeonsConfig.loopProbability(),
                PocketDungeonsConfig.maxGridSpan());

        DungeonPlan plan = outcome.plan();
        if (plan != null) {
            PlanGeometry geometry = PlanGeometry.of(origin, plan.cells());
            forceLoad(level, geometry.chunks(), true);
            try {
                return LayoutStamper.stamp(level, origin, plan, keystoneLevel, ominous);
            } catch (RuntimeException e) {
                PocketDungeonsMod.LOG.error("Stamping plan at {} failed; clearing whatever was written",
                        origin.toShortString(), e);
                teardown(server, slot, origin, InstanceLayout.forClearingOnly(origin, geometry),
                        "stamp failed");
                return null;
            }
        }

        PocketDungeonsMod.LOG.warn(
                "Planning failed for seed {} after {} attempts ({}); falling back to StaticLayout",
                seed, outcome.attemptsUsed(), outcome.failureReason());

        InstanceLayout fallback = StaticLayout.layout(origin, keystoneLevel, ominous);
        forceLoad(level, fallback.geometry().chunks(), true);
        try {
            StaticLayout.stamp(level, origin);
            return fallback;
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error(
                    "Stamping the fallback layout at {} failed; clearing whatever was written",
                    origin.toShortString(), e);
            teardown(server, slot, origin, fallback, "fallback stamp failed");
            return null;
        }
    }

    /** Records a member's return point, teleports them in, and indexes them. */
    private static void admit(MinecraftServer server, InstanceRecord record, ServerPlayer player) {
        record.members.put(player.getUUID(), new ReturnPoint(
                player.level().dimension(), player.position(),
                player.getYRot(), player.getXRot()));
        byMember.put(player.getUUID(), record);
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
    private static void applyTrialOmen(ServerPlayer player, InstanceRecord record) {
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
    private static void clearTrialOmen(ServerPlayer player) {
        player.removeEffect(MobEffects.TRIAL_OMEN);
    }

    // ---- parties ------------------------------------------------------------

    /**
     * Pre-registers a companion for the <em>next</em> dungeon this leader
     * opens (U3 Stage 5). {@code invite}/{@code join} only work once the
     * leader is already inside, which is too late for the difficulty curve --
     * it is computed once, at stamp time, from the party size known then.
     */
    static void party(ServerPlayer leader, ServerPlayer target) {
        if (hasInstance(leader)) {
            leader.sendSystemMessage(Component.literal(
                    "You are already in a dungeon. Use /dungeon invite instead.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (target.getUUID().equals(leader.getUUID())) {
            leader.sendSystemMessage(Component.literal("You are already coming.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (hasInstance(target)) {
            leader.sendSystemMessage(Component.literal(
                    target.getName().getString() + " is already in a dungeon.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        Set<UUID> companions = pendingParty.computeIfAbsent(leader.getUUID(), k -> new LinkedHashSet<>());
        if (companions.contains(target.getUUID())) {
            leader.sendSystemMessage(Component.literal(
                    target.getName().getString() + " is already pre-registered.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (1 + companions.size() >= PocketDungeonsConfig.maxPartyMembers()) {
            leader.sendSystemMessage(Component.literal(
                    "Your party is full (" + PocketDungeonsConfig.maxPartyMembers() + " players).")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        companions.add(target.getUUID());
        leader.sendSystemMessage(Component.literal(
                target.getName().getString() + " will come with you when you open a dungeon.")
                .withStyle(ChatFormatting.GOLD));
        target.sendSystemMessage(Component.literal(
                leader.getName().getString() + " pre-registered you for their next dungeon. "
                        + "You'll be brought in automatically when they run /dungeon.")
                .withStyle(ChatFormatting.GOLD));
    }

    /**
     * Stages {@code /dungeon party kick}, to be confirmed by clicking.
     *
     * @param target the companion to drop, or {@code null} to drop every one
     */
    static void stageKick(ServerPlayer leader, UUID target, String targetName) {
        Set<UUID> companions = pendingParty.get(leader.getUUID());
        if (companions == null || companions.isEmpty()) {
            leader.sendSystemMessage(Component.literal("You have nobody pre-registered.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (target != null && !companions.contains(target)) {
            leader.sendSystemMessage(Component.literal(
                    targetName + " is not in your party.").withStyle(ChatFormatting.RED));
            return;
        }

        pendingKicks.put(leader.getUUID(), new PendingKick(target,
                System.currentTimeMillis() + PocketDungeonsConfig.inviteTtlSeconds() * 1000L));

        String what = target == null
                ? "all " + companions.size() + " companion" + (companions.size() == 1 ? "" : "s")
                : targetName;
        leader.sendSystemMessage(Component.literal("Remove " + what + " from your party?")
                .withStyle(ChatFormatting.YELLOW));
        leader.sendSystemMessage(Component.literal("[ Confirm ]")
                .withStyle(s -> s.withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent.RunCommand("/dungeon party kickconfirm"))));
    }

    /** Executes whatever {@link #stageKick} staged for this leader. */
    static void confirmKick(ServerPlayer leader) {
        PendingKick kick = pendingKicks.remove(leader.getUUID());
        if (kick == null || kick.expiresAtMillis() < System.currentTimeMillis()) {
            leader.sendSystemMessage(Component.literal(
                    "Nothing to confirm. Run /dungeon party kick again.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Set<UUID> companions = pendingParty.get(leader.getUUID());
        if (companions == null || companions.isEmpty()) {
            leader.sendSystemMessage(Component.literal("You have nobody pre-registered.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        MinecraftServer server = leader.level().getServer();
        int removed;
        if (kick.target() == null) {
            removed = companions.size();
            for (UUID id : new ArrayList<>(companions)) {
                notifyKicked(server, leader, id);
            }
            pendingParty.remove(leader.getUUID());
        } else {
            removed = companions.remove(kick.target()) ? 1 : 0;
            if (removed > 0) {
                notifyKicked(server, leader, kick.target());
            }
            if (companions.isEmpty()) {
                pendingParty.remove(leader.getUUID());
            }
        }

        leader.sendSystemMessage(Component.literal(
                removed == 0 ? "Nobody was removed -- your party had already changed."
                        : "Removed " + removed + " from your party.")
                .withStyle(ChatFormatting.GOLD));
    }

    /** Tells a dropped companion, if they are around to hear it. */
    private static void notifyKicked(MinecraftServer server, ServerPlayer leader, UUID id) {
        if (server == null) {
            return;
        }
        ServerPlayer companion = server.getPlayerList().getPlayer(id);
        if (companion != null) {
            companion.sendSystemMessage(Component.literal(
                    leader.getName().getString() + " removed you from their dungeon party.")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    static void invite(ServerPlayer inviter, ServerPlayer target) {
        InstanceRecord record = byMember.get(inviter.getUUID());
        if (record == null) {
            inviter.sendSystemMessage(Component.literal(
                    "You are not in a dungeon. Run /dungeon first, then invite people in.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (target.getUUID().equals(inviter.getUUID())) {
            inviter.sendSystemMessage(Component.literal("You are already here.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (byMember.containsKey(target.getUUID())) {
            inviter.sendSystemMessage(Component.literal(
                    target.getName().getString() + " is already in a dungeon.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (record.members.size() >= PocketDungeonsConfig.maxPartyMembers()) {
            inviter.sendSystemMessage(Component.literal(
                    "This dungeon is full (" + PocketDungeonsConfig.maxPartyMembers() + " players).")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        invites.put(target.getUUID(), new Invite(inviter.getUUID(), record.slot,
                System.currentTimeMillis() + PocketDungeonsConfig.inviteTtlSeconds() * 1000L));

        inviter.sendSystemMessage(Component.literal(
                "Invited " + target.getName().getString() + ". The invitation lasts two minutes.")
                .withStyle(ChatFormatting.GOLD));
        target.sendSystemMessage(Component.literal(
                inviter.getName().getString() + " invites you into their dungeon. "
                        + "Run /dungeon join " + inviter.getName().getString()
                        + " within two minutes to go in.")
                .withStyle(ChatFormatting.GOLD));
    }

    static void join(ServerPlayer player, ServerPlayer leader) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        if (hasInstance(player)) {
            player.sendSystemMessage(Component.literal(
                    "You are already in a dungeon. Use /dungeon exit first.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        Invite invite = invites.get(player.getUUID());
        if (invite == null || invite.expiresAtMillis() < System.currentTimeMillis()
                || !invite.leader().equals(leader.getUUID())) {
            invites.remove(player.getUUID());
            player.sendSystemMessage(Component.literal(
                    "You have no open invitation from " + leader.getName().getString() + ".")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        InstanceRecord record = bySlot.get(invite.slot());
        if (record == null || !record.members.containsKey(invite.leader())) {
            invites.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("That dungeon has already closed.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (record.members.size() >= PocketDungeonsConfig.maxPartyMembers()) {
            player.sendSystemMessage(Component.literal(
                    "That dungeon is full (" + PocketDungeonsConfig.maxPartyMembers() + " players).")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        invites.remove(player.getUUID());
        admit(server, record, player);

        player.sendSystemMessage(Component.literal("You step into the dungeon.")
                .withStyle(ChatFormatting.GOLD));
        announce(server, record, player.getName().getString() + " joins the dungeon.",
                player.getUUID());
        PocketDungeonsMod.LOG.info("{} joined dungeon slot {}",
                player.getName().getString(), record.slot);
    }

    // ---- selector room (U8 Stage 3) ------------------------------------------

    /**
     * Opens (or returns to) this player's private selector room, where a
     * completed run's door offer is presented (T14). A small, static, single-
     * player instance -- no planner, no rotation, no library lookup -- built on
     * demand through the same slot/force-load/stamp machinery every other
     * instance uses.
     */
    static void enterSelectorRoom(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        InstanceRecord current = byMember.get(player.getUUID());
        if (current != null) {
            if (current.selectorRoom) {
                return; // already there
            }
            player.sendSystemMessage(Component.literal(
                    "You are already in a dungeon. Use /dungeon exit first.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }

        int slot = allocateSlot();
        BlockPos origin = originForSlot(slot);
        level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
        try {
            TemplateStamper.place(level, level.getStructureManager(), origin,
                    TemplateStamper.SELECTOR_ROOM, 0, 0L);
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not stamp a selector room for {}",
                    player.getName().getString(), e);
            level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
            usedSlots.remove(slot);
            player.sendSystemMessage(Component.literal(
                    "The selector room failed to build.").withStyle(ChatFormatting.RED));
            return;
        }

        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(),
                selectorRoomLayout(origin), Keystone.Affix.NONE, player.getUUID(), true);
        bySlot.put(slot, record);
        admit(server, record, player);
        player.sendSystemMessage(Component.literal(
                "Three doors. Choose one, or step onto the lodestone to leave without choosing.")
                .withStyle(ChatFormatting.GOLD));
    }

    /** A single fixed cell at rotation 0 -- no keystone, no timer, no reward room. */
    private static InstanceLayout selectorRoomLayout(BlockPos origin) {
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        BlockPos entrance = origin.offset(8, 1, 3);
        BlockPos exitPad = origin.offset(7, 0, 12);
        return new InstanceLayout(origin, geometry, entrance, 0.0f, exitPad, geometry.bounds(),
                0L, 1, 1, 1, false, false, 0, origin);
    }

    /**
     * Which of a selector room's three fixed doors (1, 2 or 3) this world
     * position is, for <em>this player's own</em> selector instance -- or null
     * if the player is not standing in one, or the position is not a door.
     * Positions are computed from the record's origin rather than read back out
     * of the template: the room is always stamped at rotation 0, so there is no
     * transform to account for.
     */
    static Integer selectorDoorStep(ServerPlayer player, BlockPos pos) {
        InstanceRecord record = byMember.get(player.getUUID());
        if (record == null || !record.selectorRoom) {
            return null;
        }
        int dx = pos.getX() - record.origin.getX();
        int dy = pos.getY() - record.origin.getY();
        int dz = pos.getZ() - record.origin.getZ();
        if (dz != 8 || (dy != 1 && dy != 2)) {
            return null;
        }
        return switch (dx) {
            case 4 -> 1;
            case 8 -> 2;
            case 12 -> 3;
            default -> null;
        };
    }

    /**
     * Settles {@code /dungeon choose} (T13). Validated purely against
     * {@link DungeonLog}'s pending offer -- not against being in the selector
     * room, which is presentation only, so a player who disconnects with the
     * offer message still on screen can accept it later from anywhere.
     *
     * @return whether a door was actually taken
     */
    static boolean chooseOffer(ServerPlayer player, int step) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        if (step < 1 || step > 3) {
            player.sendSystemMessage(Component.literal("Choose 1, 2 or 3.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        DungeonLog log = DungeonLog.forServer(server);
        DungeonLog.Entry entry = log.get(player.getUUID());
        if (entry.pendingOfferLevel() <= 0) {
            player.sendSystemMessage(Component.literal("You have no offer waiting.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        Keystone.Offer[] offers = Keystone.offers(entry.pendingOfferLevel());
        Keystone.Offer offer = offers[step - 1];
        Keystones.grantOffer(server, player.getUUID(), player, offer);
        log.clearPendingOffer(player.getUUID());

        player.sendSystemMessage(Component.literal(
                "Keystone [" + offer.level() + "]"
                        + (offer.affix() == Keystone.Affix.NONE
                                ? "" : " (" + offer.affix().label.toLowerCase() + ")")
                        + " is yours.")
                .withStyle(ChatFormatting.AQUA));

        if (byMember.containsKey(player.getUUID())) {
            exit(player, ExitReason.COMMAND);
        }
        return true;
    }

    // ---- exit ---------------------------------------------------------------

    /**
     * Why a member is leaving. U8 Stage 0 collapses every outcome down to "did
     * this cost anything", and the answer is now always no: {@link #EXIT_PAD}
     * used to be the one that paid, but paying now happens once, at
     * {@link #completeRun}, the moment a member first reaches a pad -- so by the
     * time either reason reaches {@code exit} there is nothing left to settle
     * beyond ejecting the player. Kept as two constants purely for the exit
     * message's wording (a retreat reads differently from walking out after
     * finishing).
     */
    enum ExitReason { EXIT_PAD, COMMAND }

    static void exit(ServerPlayer player, ExitReason reason) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        InstanceRecord record = byMember.get(player.getUUID());
        if (record == null) {
            player.sendSystemMessage(Component.literal("You are not in a dungeon.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // A keystone run records its completion and hands over its door offer on
        // the *first* pad contact -- see completeRun. By the time exit() is
        // reached the completion, if any, has already happened.
        boolean completed = record.completed.contains(player.getUUID());

        eject(server, record, player);

        // U8 Stage 1: leaving never costs anything. The clock is the only thing
        // that can deplete a keystone, and it does that on its own in onTick --
        // see expireTimedOut -- independently of anyone leaving or staying.
        returnKeystone(server, record, player.getUUID(), player, Keystones.Outcome.NO_CHANGE);

        // The selector room is solo and its own messages already say everything
        // worth saying (the offer, or the choice just made) -- the generic
        // leave/walk-out lines here would just repeat "dungeon" at a player who
        // was never fighting one.
        if (!record.selectorRoom) {
            if (!completed) {
                player.sendSystemMessage(Component.literal("You leave the dungeon behind.")
                        .withStyle(ChatFormatting.GOLD));
            }
            announce(server, record, player.getName().getString()
                            + (completed ? " walks out of the dungeon." : " leaves the dungeon."),
                    player.getUUID());
        }

        purgeIfAbandonedSelectorRoom(server, record);
    }

    /**
     * Hands this member's keystone back, once.
     *
     * <p>With depletion collapsed to a single cause (U8 Stage 1), every call site
     * but the timeout expiry passes {@link Keystones.Outcome#NO_CHANGE}, which
     * costs nothing -- the write still happens, so a stale remote in an offline
     * member's pocket still catches up. The "a completed run can never be
     * charged a failure" escalation U7 needed here is gone along with the
     * outcomes it used to escalate <em>to</em>: {@code COMPLETED_OVER_TIME} could
     * once cost {@code overtimeDepletion}, so a member who had already finished
     * needed protecting from a later death or disconnect being mis-costed as a
     * fresh failure. There is no such outcome left to protect against.
     */
    private static void returnKeystone(MinecraftServer server, InstanceRecord record,
                                       UUID member, ServerPlayer player,
                                       Keystones.Outcome outcome) {
        if (!record.isKeystoneRun() || !record.keystoneReturned.add(member)) {
            return;
        }
        Keystones.returnTo(server, member, player, record.layout.keystoneLevel(), record.affix, outcome);
    }

    /**
     * The first pad contact of a keystone run: record the completion, stamp the
     * reward room on the very first member to reach it, offer a door, and
     * teleport the player in. Deliberately <strong>does not eject</strong> --
     * the reward room's own lodestone is the second contact that leaves (U8
     * Stage 2).
     */
    private static void completeRun(MinecraftServer server, InstanceRecord record,
                                    ServerPlayer player) {
        boolean firstCompletion = record.completed.isEmpty();
        record.completed.add(player.getUUID());

        if (firstCompletion) {
            stampRewardRoom(server, record);
        }

        DungeonLog log = DungeonLog.forServer(server);
        DungeonLog.Entry entry = log.recordCompletion(player.getUUID(),
                record.layout.pathLength(), record.layout.keystoneLevel());

        int chests = record.rewardChests;
        boolean late = chests <= 0;
        if (late) {
            // Completed after the clock: the run still counts and still offers a
            // door, but finishing late costs a couple of levels -- the other way,
            // besides a full timeout, to lose ground. Settled through the same
            // path every other depletion is (Keystones.Outcome.LATE), so the
            // keystoneReturned guard it sets stops a later exit() from
            // overwriting this back to the pre-run level.
            returnKeystone(server, record, player.getUUID(), player, Keystones.Outcome.LATE);
        }
        // The offer is for whatever this player's keystone is now -- the run's
        // starting level normally, or the just-depleted one on a late finish.
        // Reaching a door at all is what mitigates the delevel: a +1 door nets
        // only -1 overall against a 2-level late penalty.
        log.setPendingOffer(player.getUUID(), log.get(player.getUUID()).keystoneLevel());

        Payout.runPayoutCommand(player, record.layout.keystoneLevel(), chests);

        if (!late) {
            player.sendSystemMessage(Component.literal(
                    "You reach the end with " + chests + " chest" + (chests == 1 ? "" : "s")
                            + " waiting, and a door to choose from. Take the compass back to a "
                            + "lodestone when you're ready.")
                    .withStyle(ChatFormatting.AQUA));
        } else {
            player.sendSystemMessage(Component.literal(
                    "The chests stay empty, but there is still a door waiting. Take the "
                            + "compass back to a lodestone when you're ready.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        PocketDungeonsMod.LOG.info("{} completed dungeon slot {} (run #{}, chests {}, tier {})",
                player.getName().getString(), record.slot, entry.runsCompleted(),
                chests, record.layout.lootTier());

        teleportToRewardRoom(server, record, player);
    }

    /**
     * Stamps the reward room, once per instance, at a fixed offset well clear of
     * the planned grid (U8 Stage 2). {@code maxGridSpan} caps a layout at 12
     * cells (192 blocks) inside a 2048 slot pitch, so two cells north of the
     * grid origin is comfortably outside anything the planner could have placed
     * -- if a layout is ever found to reach it, that is {@code maxGridSpan} not
     * holding, not this offset needing to move.
     */
    private static final int REWARD_ROOM_OFFSET_Z = -2 * RoomGeometry.CELL;

    private static void stampRewardRoom(MinecraftServer server, InstanceRecord record) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            record.rewardChests = 0;
            return;
        }
        BlockPos rewardOrigin = record.origin.offset(0, 0, REWARD_ROOM_OFFSET_Z);
        record.rewardRoomOrigin = rewardOrigin;

        int secondsRemaining = record.timer != null ? record.timer.secondsRemaining() : Integer.MAX_VALUE;
        int totalSeconds = record.timer != null ? record.timer.totalSeconds() : 1;
        int chests = PayoutMath.chestCount(secondsRemaining, totalSeconds,
                PocketDungeonsConfig.threeChestPercent(), PocketDungeonsConfig.twoChestPercent());
        record.rewardChests = chests;

        int cellX = rewardOrigin.getX() >> 4;
        int cellZ = rewardOrigin.getZ() >> 4;
        level.setChunkForced(cellX, cellZ, true);
        try {
            TemplateStamper.place(level, level.getStructureManager(), rewardOrigin,
                    TemplateStamper.REWARD_HALL, 0, record.layout.seed());
            TrialContent.applyRewardChests(level, rewardOrigin, chests,
                    DifficultyProfile.of(record.layout.pathLength(), record.layout.keystoneLevel())
                            .lootTier(),
                    record.affix == Keystone.Affix.OMINOUS, record.layout.seed());
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not stamp the reward room for slot {}", record.slot, e);
        }
    }

    /** Standing point just inside the reward room, clear of both the chest row and the pad. */
    private static void teleportToRewardRoom(MinecraftServer server, InstanceRecord record,
                                             ServerPlayer player) {
        if (record.rewardRoomOrigin == null) {
            return;
        }
        BlockPos standing = record.rewardRoomOrigin.offset(8, 1, 2);
        teleport(server, player, PocketDungeonsMod.DUNGEON_LEVEL,
                Vec3.atBottomCenterOf(standing), 0.0f, 0.0f);
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
        returnKeystone(server, record, player.getUUID(), player, Keystones.Outcome.NO_CHANGE);
        announce(server, record, player.getName().getString() + " was thrown out of the dungeon.",
                player.getUUID());

        purgeIfAbandonedSelectorRoom(server, record);
    }

    /** Teleports one member to their own return point and drops them from the party. */
    private static void eject(MinecraftServer server, InstanceRecord record, ServerPlayer player) {
        ReturnPoint point = record.members.remove(player.getUUID());
        byMember.remove(player.getUUID());
        record.onPad.remove(player.getUUID());
        if (record.timer != null) {
            record.timer.removePlayer(player);
        }
        clearTrialOmen(player);
        if (point != null) {
            teleport(server, player, point.dimension(), point.pos(), point.yaw(), point.pitch());
        } else {
            sendToWorldSpawn(server, player);
        }
    }

    /**
     * Drops an offline member; used by the disconnect handler and the tick
     * watcher. {@code member} is threaded through as the stray-sweep
     * exclusion for any resulting purge -- this player is known to be
     * leaving (disconnecting, or already off the player list), so teardown's
     * "someone is still standing here" safety net must not try to teleport
     * them. Doing so mid-disconnect is what corrupts their next login's
     * chunk tracking, leaving them stuck on "Loading terrain..." (see PLAN.md).
     *
     * <p>U8 Stage 1: for an ordinary dungeon this is no longer a lifetime event
     * -- an empty instance is now normal, a dungeon waiting for its owner to
     * come back. It stays one for the selector room, which is not a keystone run
     * and has no clock of its own to close it eventually.
     */
    private static void dropMember(MinecraftServer server, InstanceRecord record,
                                   UUID member, ServerPlayer player, String reason) {
        record.members.remove(member);
        byMember.remove(member);

        // Everything eject detaches, minus the teleport. Dropping a member used to
        // mean only "forget them", which was survivable when an instance died with
        // its last member; U8 made instances outlive everyone, so each of these
        // now persists for the rest of the run:
        //
        //  - onPad is UUID-keyed and read as an edge. A member who leaves standing
        //    on the pad and walks back in (free re-entry, U8 Stage 1) would find
        //    their contact already recorded and the pad inert until they step off.
        //  - ServerBossEvent holds ServerPlayer references and prunes none of them
        //    itself; the timer now ticks with nobody inside, so a disconnected
        //    player would be broadcast to for the rest of the run.
        //  - Trial Omen is the one effect this mod can export into the real world.
        //    eject cleared it; every dropMember path did not, so an admin teleport
        //    out of an ominous run -- or a disconnect -- carried it to the overworld.
        record.onPad.remove(member);
        if (player != null) {
            if (record.timer != null) {
                record.timer.removePlayer(player);
            }
            clearTrialOmen(player);
        }

        if (record.selectorRoom) {
            purge(server, record, reason, member);
        }
    }

    /** The selector room's one member just left or chose; an empty selector room has no reason to linger. */
    private static void purgeIfAbandonedSelectorRoom(MinecraftServer server, InstanceRecord record) {
        if (record.selectorRoom && record.members.isEmpty()) {
            purge(server, record, "selector room abandoned");
        }
    }

    private static void announce(MinecraftServer server, InstanceRecord record,
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
                sendToWorldSpawn(server, player);
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
                    Keystone.Affix.parse(entry.keystoneAffix()));
        }
    }

    private static void onTick(MinecraftServer server) {
        if (++tickCounter % PocketDungeonsConfig.watchIntervalTicks() != 0) {
            return;
        }

        // Ahead of the instance sweep and outside its empty check: a remote goes
        // stale in the overworld, where there is no instance at all.
        reconcileKeystones(server);

        if (bySlot.isEmpty()) {
            return;
        }

        int interval = PocketDungeonsConfig.watchIntervalTicks();
        long now = server.overworld().getGameTime();
        for (InstanceRecord record : new ArrayList<>(bySlot.values())) {
            if (record.timer != null) {
                record.timer.tick(interval);
            }

            // U8 Stage 1: the two end conditions, both independent of membership.
            if (record.isKeystoneRun()) {
                if (record.timer != null && record.timer.overTime() && record.completed.isEmpty()) {
                    expireTimedOut(server, record);
                    continue;
                }
                if (!record.completed.isEmpty() && record.expiresAtTick == 0) {
                    record.expiresAtTick = now + PocketDungeonsConfig.rewardRoomGraceSeconds() * 20L;
                }
                if (record.expiresAtTick != 0 && now >= record.expiresAtTick) {
                    purge(server, record, "reward room grace elapsed");
                    continue;
                }
            }

            for (UUID member : new ArrayList<>(record.members.keySet())) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                if (player == null) {
                    dropMember(server, record, member, null, "member offline");
                    continue;
                }
                // An admin teleport or any other route out of the dimension counts
                // as leaving -- otherwise a member who is not here holds the party
                // open indefinitely.
                if (!player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                    dropMember(server, record, member, player, "member left the dimension");
                    continue;
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
                boolean onPad = isOnExitPad(player, record);
                boolean stepped = onPad && record.onPad.add(member);
                if (!onPad) {
                    record.onPad.remove(member);
                }
                if (stepped) {
                    if (record.isKeystoneRun() && !record.completed.contains(member)) {
                        completeRun(server, record, player);
                    } else {
                        exit(player, ExitReason.EXIT_PAD);
                    }
                }
            }
        }
    }

    /**
     * The clock ran out and nobody reached a pad in time: the one way left to
     * lose a keystone level (U8 Stage 1). Depletes the owner alone -- a party
     * member riding along never had a key at stake -- messages them wherever
     * they are, ejects anyone still inside, and tears the instance down.
     */
    private static void expireTimedOut(MinecraftServer server, InstanceRecord record) {
        ServerPlayer owner = record.owner != null ? server.getPlayerList().getPlayer(record.owner) : null;
        returnKeystone(server, record, record.owner, owner, Keystones.Outcome.TIMED_OUT);
        if (owner == null) {
            PocketDungeonsMod.LOG.info("Dungeon slot {} timed out with its owner offline", record.slot);
        }
        purge(server, record, "timed out");
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
        return record.rewardRoomOrigin != null
                && cellBounds(record.rewardRoomOrigin).contains(Vec3.atCenterOf(below));
    }

    /** The 16x7x16 box of a single fixed-offset room, for the reward and selector rooms. */
    private static AABB cellBounds(BlockPos origin) {
        return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + RoomGeometry.CELL,
                origin.getY() + RoomGeometry.CEILING_Y + 1,
                origin.getZ() + RoomGeometry.CELL);
    }

    private static void purge(MinecraftServer server, InstanceRecord record, String reason) {
        purge(server, record, reason, null);
    }

    private static void purge(MinecraftServer server, InstanceRecord record,
                              String reason, UUID excludeFromStraySweep) {
        for (UUID member : new ArrayList<>(record.members.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                eject(server, record, player);
                player.sendSystemMessage(Component.literal("Your dungeon has closed.")
                        .withStyle(ChatFormatting.GRAY));
            } else {
                record.members.remove(member);
                byMember.remove(member);
            }
            // U8 Stage 1: a purge, a shutdown or a crash is the server's fault and
            // never costs anything. The owner's timeout depletion, if any, already
            // happened in expireTimedOut before this was called.
            returnKeystone(server, record, member, player, Keystones.Outcome.NO_CHANGE);
        }
        if (record.timer != null) {
            record.timer.close();
        }
        if (record.untimed) {
            PocketDungeonsMod.LOG.info("UNTIMED dungeon in slot {} closed ({}), after {}s",
                    record.slot, reason,
                    (server.overworld().getGameTime() - record.createdAtTick) / 20L);
        }
        bySlot.remove(record.slot);
        teardown(server, record.slot, record.origin, record.layout, reason, excludeFromStraySweep,
                record.rewardRoomOrigin);
    }

    private static void teardown(MinecraftServer server, int slot, BlockPos origin,
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
    private static void teardown(MinecraftServer server, int slot, BlockPos origin,
                                 InstanceLayout layout, String reason, UUID excludeFromStraySweep,
                                 BlockPos extraCellOrigin) {
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            usedSlots.remove(slot);
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
                : new ArrayList<>(maximalCellOrigins(origin));
        AABB bounds = layout != null ? layout.bounds() : maximalBounds(origin);

        // The reward room lives outside the planned grid entirely (U8 Stage 2),
        // so it is never part of layout.geometry() -- add its cell explicitly, or
        // a leaked reward room is a permanent scar on that slot.
        if (extraCellOrigin != null) {
            cellOrigins.add(extraCellOrigin);
            bounds = bounds.minmax(cellBounds(extraCellOrigin));
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
            sendToWorldSpawn(server, stray);
        }

        pendingClears.add(new PendingClear(slot, bounds, cellOrigins, reason));
    }

    private static boolean isClearing(int slot) {
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
    private static void processClears(MinecraftServer server) {
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
        usedSlots.remove(clear.slot);
        PocketDungeonsMod.LOG.info("Closed dungeon slot {} ({})", clear.slot, clear.reason);
    }

    /** Runs every queued clear to completion at once, ignoring the tick budget. */
    private static void drainClears(MinecraftServer server) {
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
            final int cellVolume = RoomGeometry.CELL * RoomGeometry.CELL
                    * (RoomGeometry.CEILING_Y + 1);
            int written = 0;
            while (written < budget && cell < cellOrigins.size()) {
                BlockPos origin = cellOrigins.get(cell);
                int x = index / ((RoomGeometry.CEILING_Y + 1) * RoomGeometry.CELL);
                int rest = index % ((RoomGeometry.CEILING_Y + 1) * RoomGeometry.CELL);
                int z = rest / (RoomGeometry.CEILING_Y + 1);
                int y = rest % (RoomGeometry.CEILING_Y + 1);
                RoomBuilder.set(level, origin.offset(x, y, z), AIR);
                written++;
                if (++index >= cellVolume) {
                    index = 0;
                    cell++;
                }
            }
            return written;
        }
    }

    /** Cell origins covering the largest footprint a layout is permitted. */
    private static List<BlockPos> maximalCellOrigins(BlockPos origin) {
        int span = PocketDungeonsConfig.maxGridSpan();
        List<BlockPos> out = new ArrayList<>(span * span);
        for (int cx = 0; cx < span; cx++) {
            for (int cz = 0; cz < span; cz++) {
                out.add(origin.offset(cx * RoomGeometry.CELL, 0, cz * RoomGeometry.CELL));
            }
        }
        return out;
    }

    private static AABB maximalBounds(BlockPos origin) {
        int span = PocketDungeonsConfig.maxGridSpan() * RoomGeometry.CELL;
        return new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + span,
                origin.getY() + RoomGeometry.CEILING_Y + 1,
                origin.getZ() + span);
    }

    // ---- admin (spec section 10) --------------------------------------------

    /**
     * Stamps an unowned instance. This is how the build is verified without a
     * client attached -- the console can build one, inspect it with
     * {@code /execute if block}, and purge it again.
     *
     * <p>A memberless record is registered for the built slot so teardown knows
     * the layout it has to clear. {@code bySlot} already documents that it holds
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
        int slot = allocateSlot();
        BlockPos origin = originForSlot(slot);

        InstanceLayout layout = buildLayout(server, level, slot, origin,
                seed != null ? seed : level.getRandom().nextLong(), keystoneLevel, ominous);
        if (layout == null) {
            // Slot release is deferred to the clear buildLayout already queued
            // for whatever it wrote -- see buildLayout's note.
            return -1;
        }

        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), layout,
                Keystone.Affix.NONE, null);
        bySlot.put(slot, record);
        return slot;
    }

    /** The layout of a built slot, for admin reporting. Null if the slot is unknown. */
    static InstanceLayout adminLayout(int slot) {
        InstanceRecord record = bySlot.get(slot);
        return record != null ? record.layout : null;
    }

    /** Force teardown by slot, whether or not anyone is inside it. */
    static boolean adminPurge(MinecraftServer server, int slot) {
        if (!usedSlots.contains(slot)) {
            return false;
        }
        InstanceRecord record = bySlot.get(slot);
        if (record != null) {
            purge(server, record, "purged by an operator");
        } else {
            teardown(server, slot, originForSlot(slot), null, "purged by an operator");
        }
        return true;
    }

    /** One line per live instance, for {@code /dungeon admin list}. */
    static List<String> adminList(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        for (int slot : usedSlots) {
            BlockPos origin = originForSlot(slot);
            InstanceRecord record = bySlot.get(slot);
            if (record == null) {
                lines.add("slot " + slot + " at " + origin.toShortString() + " -- unowned (admin build)");
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
                    + (record.untimed ? " -- UNTIMED, never expires" : "")
                    + " -- " + who + ", " + ageSeconds + "s old, "
                    + layout.roomCount() + " rooms, path " + layout.pathLength()
                    + ", tier " + layout.lootTier()
                    + (layout.procedural() ? ", seed " + layout.seed() : ", STATIC FALLBACK"));
        }
        return lines;
    }

    /** Origin of a slot, so admin tooling can point at the geometry. */
    static BlockPos slotOrigin(int slot) {
        return originForSlot(slot);
    }

    // ---- helpers ------------------------------------------------------------

    private static int allocateSlot() {
        int slot = 0;
        while (usedSlots.contains(slot)) {
            slot++;
        }
        usedSlots.add(slot);
        return slot;
    }

    private static BlockPos originForSlot(int slot) {
        int slotsPerRow = PocketDungeonsConfig.slotsPerRow();
        int slotPitch = PocketDungeonsConfig.slotPitch();
        return new BlockPos((slot % slotsPerRow) * slotPitch, BASE_Y,
                (slot / slotsPerRow) * slotPitch);
    }

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
     * PLAN.md.
     */
    private static void teleport(MinecraftServer server, ServerPlayer player,
                                 ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch) {
        ServerLevel target = server.getLevel(dimension);
        if (target == null) {
            PocketDungeonsMod.LOG.warn("Return dimension {} is gone; using world spawn",
                    dimension.identifier());
            sendToWorldSpawn(server, player);
            return;
        }
        player.teleport(new TeleportTransition(target, pos, Vec3.ZERO, yaw, pitch,
                TeleportTransition.DO_NOTHING));
    }

    private static void sendToWorldSpawn(MinecraftServer server, ServerPlayer player) {
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
