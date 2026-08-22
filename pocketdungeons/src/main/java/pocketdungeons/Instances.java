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
            InstanceRecord record = byMember.get(player.getUUID());
            if (record == null) {
                return;
            }
            ReturnPoint point = record.members.get(player.getUUID());
            if (point != null) {
                pendingReturns.put(player.getUUID(), point);
            }
            // One of the four depletion sites. This one cannot hand anything over
            // -- the player is on their way out of the process -- so the level is
            // parked on DungeonLog for their next login (U7 Stage 5).
            returnKeystone(server, record, player.getUUID(), null, Keystones.Outcome.DISCONNECT);
            dropMember(server, record, player.getUUID(), "member disconnected");
        });

        // Rejoining inside a purged slot: send them home rather than leaving them
        // standing in an empty void. Queued, not immediate -- see
        // JOIN_RECOVERY_DELAY_TICKS.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            // Ahead of the dimension check: a keystone parked by a disconnect has
            // to reach its owner whether or not they logged back in inside the void.
            Keystones.drainPending(server, player);
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
        return enter(player, 0, Keystone.Affix.NONE, false);
    }

    /**
     * Spends a keystone from this player's inventory and opens the run it buys.
     *
     * <p>The stack is shrunk only after {@code enter} has actually succeeded --
     * U4's rule, and it matters more here than it did for an echo shard, because a
     * keystone carries a level somebody has spent several runs earning. The stack
     * reference survives the cross-dimension teleport inside {@code enter}:
     * {@code ServerPlayer.teleport} is {@code aload_0 ... areturn} throughout and
     * never recreates the player, which U4's shipped notes confirmed at bytecode
     * level.
     *
     * @param ominousRequested the player asked for the ominous run explicitly
     *                         (an ominous bottle, or {@code /dungeon ominous})
     * @return whether the player went in
     */
    static boolean enterWithKeystone(ServerPlayer player, boolean ominousRequested) {
        ItemStack keystone = Keystone.findHeld(player);
        if (keystone == null) {
            player.sendSystemMessage(Component.literal(
                    "You need a keystone to open a dungeon. Run /dungeon key for your first one.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        int level = Keystone.levelOf(keystone).orElse(1);
        Keystone.Affix affix = Keystone.affixOf(keystone);
        boolean ominous = ominousRequested || affix == Keystone.Affix.OMINOUS;

        if (!enter(player, level, affix, ominous)) {
            return false;
        }
        keystone.shrink(1);
        return true;
    }

    static boolean enter(ServerPlayer player, int keystoneLevel, Keystone.Affix affix,
                         boolean ominousRun) {
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

        // On failure buildLayout has already queued a clear for whatever partial
        // geometry it wrote and the slot's release happens when that clear
        // finishes -- it is deliberately not freed here. See buildLayout's note.
        InstanceLayout layout = buildLayout(server, level, slot, origin, seed, partySize,
                keystoneLevel, ominousRun);
        if (layout == null) {
            player.sendSystemMessage(Component.literal(
                    "The dungeon failed to build. You have not been moved.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), layout, affix);
        bySlot.put(slot, record);

        // The three completion offers go into the terminal cell now rather than at
        // the pad: a vault appearing under a player's nose the instant they arrive
        // reads as a glitch, and stamping is where every other block goes in.
        if (record.isKeystoneRun() && TrialContent.enabled()) {
            try {
                record.choiceVaults.addAll(TrialContent.applyChoiceVaults(
                        level, layout.terminal(), layout.keystoneLevel()));
            } catch (RuntimeException e) {
                // A run that cannot offer an upgrade is still a run worth walking;
                // the keystone comes back either way. Do not fail the entry.
                PocketDungeonsMod.LOG.error("Could not place the choice vaults in slot {}", slot, e);
            }
        }
        if (record.isKeystoneRun() && PocketDungeonsConfig.timerEnabled()) {
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
                            + "key, spend the key on a vault, and reach the lodestone before the "
                            + "clock to be offered a better one. Anything a vault ejects onto the "
                            + "floor is lost when the dungeon closes -- pick it up.")
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
     * @param partySize the opening player's party size, threaded to
     *                  {@link LayoutStamper#stamp} for the difficulty curve
     * @return the layout, or null if stamping itself failed
     */
    private static InstanceLayout buildLayout(MinecraftServer server, ServerLevel level,
                                              int slot, BlockPos origin, long seed, int partySize,
                                              int keystoneLevel, boolean ominousRun) {
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
                return LayoutStamper.stamp(level, origin, plan, partySize,
                        keystoneLevel, ominousRun);
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

        InstanceLayout fallback = StaticLayout.layout(origin, keystoneLevel, ominousRun);
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

    // ---- exit ---------------------------------------------------------------

    /**
     * Why a member is leaving. Only {@link #EXIT_PAD} pays: walking out on the
     * far pad is a completed run, {@code /dungeon exit} from room three is a
     * retreat.
     *
     * <p>Deliberately only the two reasons that actually reach {@link #exit}.
     * A death rescue, a purge and a disconnect each leave through
     * {@code rescue}, {@code purge} and {@code dropMember} instead -- none of
     * which route through here, and none of which pay. Giving them constants
     * would be documenting a dispatch that does not exist.
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

        // A keystone run pays and hands over its token on the *first* pad
        // contact, which is a separate step from leaving -- see completeRun. By
        // the time exit() is reached on the pad the payout has already happened,
        // and record.paid makes a second attempt a no-op regardless.
        boolean completed = record.completed.contains(player.getUUID());

        // Teleport first, pay second. Payout.grant drops what will not fit at the
        // player's feet, and feet still on the exit pad are inside a dungeon this
        // very call is about to tear down.
        eject(server, record, player);

        boolean paid = reason == ExitReason.EXIT_PAD && pay(server, record, player);

        // One of the four depletion sites. The reason is only a starting point:
        // returnKeystone upgrades it if this member has already completed.
        returnKeystone(server, record, player.getUUID(), player, Keystones.Outcome.COMMAND);

        if (!paid && !completed) {
            player.sendSystemMessage(Component.literal("You leave the dungeon behind.")
                    .withStyle(ChatFormatting.GOLD));
        }
        announce(server, record, player.getName().getString()
                        + (paid || completed ? " walks out of the dungeon." : " leaves the dungeon."),
                player.getUUID());

        closeIfEmpty(server, record, "last member left");
    }

    /**
     * Hands this member's keystone back, once.
     *
     * <p>The single call site U7 Stage 4 demands, reached from all four ways out.
     * It cannot hang off {@link ExitReason}, which has two constants and never
     * sees a rescue, a purge or a disconnect.
     */
    private static void returnKeystone(MinecraftServer server, InstanceRecord record,
                                       UUID member, ServerPlayer player,
                                       Keystones.Outcome outcome) {
        if (!record.isKeystoneRun() || !record.keystoneReturned.add(member)) {
            return;
        }
        // A member who has already reached the pad has finished the run, and
        // nothing they do afterwards can un-finish it. Without this, typing
        // /dungeon exit to walk out of the exit room -- or dying to a spawner
        // mob still chasing them around it -- would charge a completed run the
        // retreat or death penalty, which is the opposite of what they earned.
        Keystones.Outcome effective = outcome;
        if (outcome != Keystones.Outcome.SERVER && record.completed.contains(member)) {
            effective = record.completedInTime.contains(member)
                    ? Keystones.Outcome.COMPLETED_IN_TIME
                    : Keystones.Outcome.COMPLETED_OVER_TIME;
        }
        Keystones.returnTo(server, member, player, record.layout.keystoneLevel(), record.affix,
                effective, record.keystoneGranted.contains(member));
    }

    /**
     * The first pad contact of a keystone run: pay, log, and hand over the
     * completion token. Deliberately <strong>does not eject</strong>.
     *
     * <p>This is the one shipped behaviour U7 changes. Before it, stepping on the
     * lodestone paid and threw you out in the same instant, which is fine when the
     * pad is the last thing in the run -- and wrong the moment three vaults are
     * standing in the same room waiting for the token that contact just granted.
     * The second contact is the one that leaves.
     */
    private static void completeRun(MinecraftServer server, InstanceRecord record,
                                    ServerPlayer player) {
        record.completed.add(player.getUUID());
        boolean inTime = record.timer == null || !record.timer.overTime();
        if (inTime) {
            record.completedInTime.add(player.getUUID());
        }

        pay(server, record, player);

        if (inTime && !record.choiceVaults.isEmpty()) {
            Payout.deliver(player, Keystone.mintToken(record.layout.keystoneLevel()));
            player.sendSystemMessage(Component.literal(
                    "You beat the clock. Three vaults, one token -- open the keystone you want, "
                            + "then stand on the lodestone again to leave.")
                    .withStyle(ChatFormatting.AQUA));
        } else if (inTime) {
            player.sendSystemMessage(Component.literal(
                    "You beat the clock. Stand on the lodestone again to leave.")
                    .withStyle(ChatFormatting.AQUA));
        } else {
            player.sendSystemMessage(Component.literal(
                    "Over time. Your keystone comes back as it was -- no upgrade this run. "
                            + "Stand on the lodestone again to leave.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    /**
     * Grants the keystone behind whichever choice vault a member has opened.
     *
     * <p>Vanilla does the sealing: all three vaults ask for the same token, the
     * player holds exactly one, so opening one makes the other two permanently
     * inert. Nothing here has to close them.
     *
     * <p>The claim is read out of the vault's own saved NBT rather than from
     * {@code VaultServerData.getRewardedPlayers()}, which is package-private --
     * see {@code TrialContent.rewardedPlayers}.
     */
    private static void collectVaultClaims(MinecraftServer server, InstanceRecord record) {
        if (record.choiceVaults.isEmpty() || record.keystoneGranted.size() >= record.members.size()) {
            return;
        }
        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            return;
        }
        Keystone.Offer[] offers = Keystone.offers(record.layout.keystoneLevel());
        for (int i = 0; i < record.choiceVaults.size() && i < offers.length; i++) {
            for (UUID claimant : TrialContent.rewardedPlayers(level, record.choiceVaults.get(i))) {
                if (!record.members.containsKey(claimant) || !record.keystoneGranted.add(claimant)) {
                    continue;
                }
                ServerPlayer winner = server.getPlayerList().getPlayer(claimant);
                if (winner == null) {
                    // Claimed it and logged out before the watcher noticed: park it
                    // rather than lose it, same as any other undeliverable keystone.
                    DungeonLog.forServer(server).setPendingKeystone(claimant, offers[i].level());
                    continue;
                }
                Payout.deliver(winner, Keystone.mint(offers[i].level(), offers[i].affix()));
                winner.sendSystemMessage(Component.literal(
                        "Keystone [" + offers[i].level() + "]"
                                + (offers[i].affix() == Keystone.Affix.NONE
                                        ? "" : " (" + offers[i].affix().label.toLowerCase() + ")")
                                + " is yours. The other two are spent.")
                        .withStyle(ChatFormatting.AQUA));
            }
        }
    }

    /**
     * Records the completion and pays for it, once per member per instance.
     *
     * <p>The guard is {@link InstanceRecord#paid}, which dies with the instance:
     * a member who is paid, gets pulled back in by a friend and steps on the pad
     * again is not paid twice, and earning another payout means walking a fresh
     * dungeon. That is a rate limit set by a human rather than by a timer.
     *
     * @return whether this call actually paid
     */
    private static boolean pay(MinecraftServer server, InstanceRecord record, ServerPlayer player) {
        if (!record.paid.add(player.getUUID())) {
            return false;
        }
        DungeonLog.Entry entry = DungeonLog.forServer(server)
                .recordCompletion(player.getUUID(), record.layout.pathLength(),
                        record.layout.keystoneLevel());
        int granted = Payout.grant(player, record.layout, entry.streak());
        Payout.announce(player, entry, granted, record.layout.ominous());
        PocketDungeonsMod.LOG.info("{} completed dungeon slot {} (run #{}, streak {}, tier {}, paid {})",
                player.getName().getString(), record.slot, entry.runsCompleted(),
                entry.streak(), record.layout.lootTier(), granted);
        return true;
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
        // One of the four depletion sites. A death rescue never reaches exit(),
        // so wiring depletion to ExitReason would silently skip it.
        returnKeystone(server, record, player.getUUID(), player, Keystones.Outcome.DEATH);
        announce(server, record, player.getName().getString() + " was thrown out of the dungeon.",
                player.getUUID());

        closeIfEmpty(server, record, "last member thrown out");
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
     */
    private static void dropMember(MinecraftServer server, InstanceRecord record,
                                   UUID member, String reason) {
        record.members.remove(member);
        byMember.remove(member);
        closeIfEmpty(server, record, reason, member);
    }

    private static void closeIfEmpty(MinecraftServer server, InstanceRecord record, String reason) {
        closeIfEmpty(server, record, reason, null);
    }

    private static void closeIfEmpty(MinecraftServer server, InstanceRecord record,
                                     String reason, UUID excludeFromStraySweep) {
        if (record.members.isEmpty()) {
            purge(server, record, reason, excludeFromStraySweep);
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

    private static void onTick(MinecraftServer server) {
        if (bySlot.isEmpty() || ++tickCounter % PocketDungeonsConfig.watchIntervalTicks() != 0) {
            return;
        }

        int interval = PocketDungeonsConfig.watchIntervalTicks();
        for (InstanceRecord record : new ArrayList<>(bySlot.values())) {
            if (record.timer != null && !record.members.isEmpty()) {
                record.timer.tick(interval);
            }
            collectVaultClaims(server, record);
            for (UUID member : new ArrayList<>(record.members.keySet())) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                if (player == null) {
                    dropMember(server, record, member, "member offline");
                    continue;
                }
                // An admin teleport or any other route out of the dimension counts
                // as leaving -- otherwise a member who is not here holds the party
                // open indefinitely.
                if (!player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                    dropMember(server, record, member, "member left the dimension");
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

    // ---- teardown -----------------------------------------------------------

    /**
     * Whether the player is standing on this instance's exit pad.
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
        return record.layout.bounds().contains(Vec3.atCenterOf(below))
                && player.level().getBlockState(below).is(Blocks.LODESTONE);
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
            // The fourth depletion site, and the one that costs nothing: a purge,
            // a shutdown or a crash is the server's fault, so the keystone comes
            // back exactly as it went in.
            returnKeystone(server, record, member, player, Keystones.Outcome.SERVER);
        }
        if (record.timer != null) {
            record.timer.close();
        }
        bySlot.remove(record.slot);
        teardown(server, record.slot, record.origin, record.layout, reason, excludeFromStraySweep);
    }

    private static void teardown(MinecraftServer server, int slot, BlockPos origin,
                                 InstanceLayout layout, String reason) {
        teardown(server, slot, origin, layout, reason, null);
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
                                 InstanceLayout layout, String reason, UUID excludeFromStraySweep) {
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
                ? layout.geometry().cellOrigins()
                : maximalCellOrigins(origin);
        AABB bounds = layout != null ? layout.bounds() : maximalBounds(origin);

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
                seed != null ? seed : level.getRandom().nextLong(), 1, keystoneLevel, ominous);
        if (layout == null) {
            // Slot release is deferred to the clear buildLayout already queued
            // for whatever it wrote -- see buildLayout's note.
            return -1;
        }

        InstanceRecord record = new InstanceRecord(slot, origin, level.getGameTime(), layout,
                Keystone.Affix.NONE);
        if (record.isKeystoneRun() && TrialContent.enabled()) {
            record.choiceVaults.addAll(TrialContent.applyChoiceVaults(
                    level, layout.terminal(), layout.keystoneLevel()));
        }
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
