package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * M34: weekly bounties for the dungeon host (instance owner). Three bounties per
 * week per owner, seeded from the owner UUID and the ISO week key, reset every
 * Monday UTC. Party members contribute progress toward the owner's bounties; on
 * completion, every online member of the owner's party gets the reward (2 echo
 * shards + 4 emeralds), with the owner getting one bonus shard.
 *
 * <p>Modeled after the archived dailyquests mod's turn-in pattern, adapted to
 * dungeon activities and party play. Unlike a daily quest there is no
 * turn-in step: progress is detected from the mechanic itself, the same shape
 * {@link TaskTracker} (M33) already uses for the guided task line.
 *
 * <p>Bounty progress persists as a sidecar on {@link DungeonLog} (codec key
 * {@code bounties}), the same shape the M33 task-progress sidecar uses, so a
 * {@code dungeon_log.dat} written before M34 loads unchanged: every player
 * simply starts with no bounty progress recorded, and the first read
 * materialises this week's three on the fly.
 */
final class BountyTracker {

    private BountyTracker() {}

    /**
     * One bounty type. The pool the weekly pick draws three from. Order here is
     * the pick's iteration order: a seeded shuffle walks the enum in this
     * order and takes the first three it has not yet picked.
     */
    enum Bounty {
        CLEAR_HALLS("clear_halls", "Clear the Halls", 20),
        ECHO_HARVESTER("echo_harvester", "Echo Harvester", 9),
        SPEEDRUNNER("speedrunner", "Speedrunner", 3),
        HIGH_ROLLER("high_roller", "High Roller", 32),
        SPELUNKER("spelunker", "Spelunker", 2),
        PACK_HUNTER("pack_hunter", "Pack Hunter", 3),
        KEYSTONE_CLIMBER("keystone_climber", "Keystone Climber", 3);

        final String id;
        final String label;
        final int targetCount;

        Bounty(String id, String label, int targetCount) {
            this.id = id;
            this.label = label;
            this.targetCount = targetCount;
        }

        static Bounty byId(String id) {
            for (Bounty b : values()) {
                if (b.id.equals(id)) {
                    return b;
                }
            }
            return null;
        }
    }

    /**
     * One bounty's live state for one owner: which week it belongs to, which
     * bounty it is, how far along, and whether the reward has already paid out.
     * The week key guards against stale progress: a new week's first read
     * discards anything still carrying last week's key.
     */
    record BountyState(String weekKey, String bountyId, int progress, boolean completed) {

        static final com.mojang.serialization.Codec<BountyState> CODEC =
                com.mojang.serialization.codecs.RecordCodecBuilder.create(instance -> instance.group(
                com.mojang.serialization.Codec.STRING.fieldOf("week").forGetter(BountyState::weekKey),
                com.mojang.serialization.Codec.STRING.fieldOf("bounty").forGetter(BountyState::bountyId),
                com.mojang.serialization.Codec.INT.optionalFieldOf("progress", 0)
                        .forGetter(BountyState::progress),
                com.mojang.serialization.Codec.BOOL.optionalFieldOf("completed", false)
                        .forGetter(BountyState::completed)
        ).apply(instance, BountyState::new));
    }

    /** How many bounties an owner gets per week. */
    static final int BOUNTIES_PER_WEEK = 3;

    /** Reward per online member on completion: 2 echo shards + 4 emeralds. */
    static final int REWARD_SHARDS = 2;
    static final int REWARD_EMERALDS = 4;
    /** The owner's bonus on top of the per-member reward. */
    static final int OWNER_BONUS_SHARDS = 1;

    private static final String SIDEBAR_OBJECTIVE = "pd_bounty";

    /**
     * The current ISO week key, e.g. {@code "2026-W35"}. ISO weeks start on
     * Monday and the week-based year can differ from the calendar year near
     * the boundary, so both come from {@link WeekFields#ISO}. Real time, not
     * world time: the world clock stops when the server is down and jumps when
     * someone sleeps, neither of which should move a weekly.
     */
    static String weekKey() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int year = now.get(WeekFields.ISO.weekBasedYear());
        int week = now.get(WeekFields.ISO.weekOfWeekBasedYear());
        return String.format("%04d-W%02d", year, week);
    }

    /**
     * The three bounties {@code owner} has this week, in pick order. Seeded
     * from {@code owner.hashCode() ^ weekKey.hashCode()} so the same owner in
     * the same week always gets the same three, no dupes, and a different
     * owner or a different week gets a different pick. Pure logic: no
     * {@link MinecraftServer} or {@link DungeonLog} needed, so
     * {@code BountyTrackerTest} can exercise seeding headlessly.
     */
    static List<Bounty> bountiesFor(UUID owner, String weekKey) {
        long seed = (owner == null ? 0L : owner.hashCode()) ^ (long) weekKey.hashCode();
        seed = seed * 0x9E3779B97F4A7C15L;
        seed = (seed ^ (seed >>> 30)) * 0xBF58476D1CE4E5B9L;
        seed = (seed ^ (seed >>> 27)) * 0x94D049BB133111EBL;
        seed = seed ^ (seed >>> 31);
        List<Bounty> pool = new ArrayList<>(List.of(Bounty.values()));
        Collections.shuffle(pool, new Random(seed));
        return List.copyOf(pool.subList(0, Math.min(BOUNTIES_PER_WEEK, pool.size())));
    }

    /**
     * The owner's live bounty states for this week, materialised from the
     * sidecar if they are missing or stale. A state whose {@code weekKey} does
     * not match the current week is replaced with a fresh zero-progress state
     * for the same bounty id, so a week rollover resets progress without
     * losing the pick.
     */
    static List<BountyState> currentBounties(DungeonLog log, UUID owner) {
        String week = weekKey();
        List<Bounty> picked = bountiesFor(owner, week);
        List<BountyState> stored = log.bountiesOf(owner);
        List<BountyState> result = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Bounty bounty = picked.get(i);
            BountyState state = i < stored.size() ? stored.get(i) : null;
            if (state == null || !state.weekKey().equals(week) || !state.bountyId().equals(bounty.id)) {
                state = new BountyState(week, bounty.id, 0, false);
            }
            result.add(state);
        }
        // Persist if we materialised fresh states, so the sidecar carries this
        // week's key rather than last week's stale one.
        if (!result.equals(stored)) {
            log.setBounties(owner, result);
        }
        return result;
    }

    /**
     * Advances the owner's bounty {@code bountyId} by {@code amount}, if that
     * bounty is one of the owner's three this week and has not already
     * completed. On the call that reaches the target, marks the bounty
     * completed and delivers the reward to every online member of the owner's
     * party (owner included, with the owner bonus). Returns whether the
     * sidecar actually changed, for callers that want to skip the scoreboard
     * sync on a no-op.
     */
    static boolean progress(MinecraftServer server, UUID owner, String bountyId, int amount) {
        if (owner == null || amount <= 0) {
            return false;
        }
        DungeonLog log = DungeonLog.forServer(server);
        String week = weekKey();
        List<BountyState> states = currentBounties(log, owner);
        for (int i = 0; i < states.size(); i++) {
            BountyState state = states.get(i);
            if (!state.bountyId().equals(bountyId) || state.completed()
                    || !state.weekKey().equals(week)) {
                continue;
            }
            Bounty bounty = Bounty.byId(bountyId);
            if (bounty == null) {
                return false;
            }
            int next = Math.min(bounty.targetCount, state.progress() + amount);
            if (next == state.progress()) {
                return false;
            }
            boolean done = next >= bounty.targetCount;
            BountyState updated = new BountyState(week, bountyId, next, done);
            states.set(i, updated);
            log.setBounties(owner, states);
            if (done) {
                deliverRewards(server, owner);
                announceCompletion(server, owner, bounty);
            }
            return true;
        }
        return false;
    }

    /**
     * Delivers the bounty reward to every online member of the owner's party:
     * 2 echo shards + 4 emeralds each, with the owner getting one bonus shard.
     * Offline members miss out, by design: the bounty is a party activity, and
     * the reward is for showing up.
     */
    private static void deliverRewards(MinecraftServer server, UUID owner) {
        InstanceRecord record = InstanceRegistry.byMember.get(owner);
        Set<UUID> members = record != null ? record.members.keySet() : Set.of(owner);
        for (UUID member : members) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player == null) {
                continue;
            }
            int shards = REWARD_SHARDS + (member.equals(owner) ? OWNER_BONUS_SHARDS : 0);
            if (Fuel.item() != null) {
                Payout.deliver(player, new ItemStack(Fuel.item(), shards));
            }
            Payout.deliver(player, new ItemStack(Items.EMERALD, REWARD_EMERALDS));
        }
    }

    private static void announceCompletion(MinecraftServer server, UUID owner, Bounty bounty) {
        InstanceRecord record = InstanceRegistry.byMember.get(owner);
        Set<UUID> members = record != null ? record.members.keySet() : Set.of(owner);
        Component msg = Component.literal("Bounty complete: " + bounty.label + "!")
                .withStyle(ChatFormatting.GREEN);
        for (UUID member : members) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                player.sendSystemMessage(msg);
            }
        }
    }

    /**
     * The bounty lines for the door screen, e.g. "Clear the Halls 12/20", one
     * per line, in pick order. {@code null} if {@code owner} is not online (the
     * door screen only shows for a live owner).
     */
    static List<Component> bountyLines(ServerPlayer owner) {
        MinecraftServer server = owner.level().getServer();
        if (server == null) {
            return null;
        }
        List<BountyState> states = currentBounties(DungeonLog.forServer(server), owner.getUUID());
        List<Component> lines = new ArrayList<>();
        for (BountyState state : states) {
            Bounty bounty = Bounty.byId(state.bountyId());
            if (bounty == null) {
                continue;
            }
            String done = state.completed() ? " (done)" : "";
            lines.add(Component.literal(bounty.label + " " + state.progress() + "/"
                    + bounty.targetCount + done).withStyle(ChatFormatting.AQUA));
        }
        return lines;
    }

    /**
     * Sets the owner's three bounty progress scores on the sidebar objective,
     * creating it on first use. Each bounty is one scoreholder line (the bounty
     * label), with the score being the progress count. Once the owner is
     * offline or has no bounties, their lines are cleared; if that leaves the
     * objective empty, it is removed.
     */
    static void syncScoreboard(MinecraftServer server, ServerPlayer owner) {
        Scoreboard scoreboard = server.getScoreboard();
        List<BountyState> states = currentBounties(DungeonLog.forServer(server), owner.getUUID());
        Objective objective = scoreboard.getObjective(SIDEBAR_OBJECTIVE);
        // Clear any previous lines for this owner before re-setting, so a
        // changed bounty pick does not leave a stale line behind.
        if (objective != null) {
            for (Bounty bounty : Bounty.values()) {
                scoreboard.resetSinglePlayerScore(
                        ScoreHolder.forNameOnly(owner.getName().getString() + ":" + bounty.id), objective);
            }
        }
        if (objective == null) {
            objective = scoreboard.addObjective(SIDEBAR_OBJECTIVE, ObjectiveCriteria.DUMMY,
                    Component.literal("Weekly Bounties"), ObjectiveCriteria.RenderType.INTEGER,
                    false, null);
            scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
        }
        for (BountyState state : states) {
            Bounty bounty = Bounty.byId(state.bountyId());
            if (bounty == null) {
                continue;
            }
            ScoreHolder holder = ScoreHolder.forNameOnly(owner.getName().getString() + ":" + bounty.id);
            scoreboard.getOrCreatePlayerScore(holder, objective).set(state.progress());
        }
    }

    /**
     * Removes the owner's bounty lines from the sidebar, and the objective
     * itself if nobody's lines remain. Called on logout.
     */
    static void clearScoreboard(MinecraftServer server, ServerPlayer owner) {
        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(SIDEBAR_OBJECTIVE);
        if (objective == null) {
            return;
        }
        for (Bounty bounty : Bounty.values()) {
            scoreboard.resetSinglePlayerScore(
                    ScoreHolder.forNameOnly(owner.getName().getString() + ":" + bounty.id), objective);
        }
        if (scoreboard.listPlayerScores(objective).isEmpty()) {
            scoreboard.removeObjective(objective);
        }
    }
}
