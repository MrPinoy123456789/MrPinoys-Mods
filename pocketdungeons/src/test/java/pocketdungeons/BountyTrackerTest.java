package pocketdungeons;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Regression for {@link BountyTracker}'s week key, seeded pick, progress
 * arithmetic, stale-week reset, and sidecar round trip, exercised through the
 * pure {@code bountiesFor}/{@code currentBounties}/{@code weekKey} overloads:
 * no {@code ServerPlayer} or {@code MinecraftServer} is available in this
 * headless test, the same constraint {@code TaskTrackerTest} works under.
 */
public class BountyTrackerTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000004");

    public static void main(String[] args) {
        testWeekKeyFormat();
        testSeededPickStability();
        testSeededPickNoDupes();
        testDifferentOwnerDifferentPick();
        testDifferentWeekDifferentPick();
        testCurrentBountiesMaterialisesFresh();
        testStaleWeekResets();
        testSidecarRoundTrip();
        testLegacySaveDefaults();
        System.out.println("BountyTrackerTest passed");
    }

    /** The week key is non-blank and matches the ISO week pattern "yyyy-Www". */
    private static void testWeekKeyFormat() {
        String key = BountyTracker.weekKey();
        check(key != null && !key.isBlank(), "week key is non-blank");
        check(key.matches("\\d{4}-W\\d{2}"), "week key matches yyyy-Www: " + key);
    }

    /** The same owner and week always picks the same three bounties. */
    private static void testSeededPickStability() {
        String week = "2026-W35";
        List<BountyTracker.Bounty> a = BountyTracker.bountiesFor(OWNER, week);
        List<BountyTracker.Bounty> b = BountyTracker.bountiesFor(OWNER, week);
        check(a.size(), BountyTracker.BOUNTIES_PER_WEEK, "pick returns exactly three");
        check(a.equals(b), "same owner and week picks the same three in the same order");
    }

    /** No bounty appears twice in one pick. */
    private static void testSeededPickNoDupes() {
        String week = "2026-W35";
        List<BountyTracker.Bounty> picked = BountyTracker.bountiesFor(OWNER, week);
        Set<String> ids = new HashSet<>();
        for (BountyTracker.Bounty b : picked) {
            ids.add(b.id);
        }
        check(ids.size(), picked.size(), "no duplicate bounty ids in one pick");
    }

    /** A different owner gets a different pick (not guaranteed, but the seed differs). */
    private static void testDifferentOwnerDifferentPick() {
        String week = "2026-W35";
        List<BountyTracker.Bounty> a = BountyTracker.bountiesFor(OWNER, week);
        List<BountyTracker.Bounty> b = BountyTracker.bountiesFor(OTHER, week);
        // Not a strict inequality check (collisions are possible), but the
        // seed differs so at least exercise that two owners do not always
        // get the same pick. If they happen to match for this pair, try a
        // third owner before declaring failure.
        if (a.equals(b)) {
            UUID third = UUID.fromString("00000000-0000-0000-0000-000000000005");
            List<BountyTracker.Bounty> c = BountyTracker.bountiesFor(third, week);
            check(!a.equals(c), "at least one of three owners gets a different pick");
        } else {
            check(true, "two different owners get different picks");
        }
    }

    /** A different week gets a different pick for the same owner. */
    private static void testDifferentWeekDifferentPick() {
        List<BountyTracker.Bounty> a = BountyTracker.bountiesFor(OWNER, "2026-W35");
        List<BountyTracker.Bounty> b = BountyTracker.bountiesFor(OWNER, "2026-W36");
        check(!a.equals(b), "same owner, different week gets a different pick");
    }

    /**
     * currentBounties materialises fresh zero-progress states when the sidecar
     * is empty, and persists them so the next read is stable.
     */
    private static void testCurrentBountiesMaterialisesFresh() {
        DungeonLog log = new DungeonLog();
        List<BountyTracker.BountyState> states = BountyTracker.currentBounties(log, OWNER);
        check(states.size(), BountyTracker.BOUNTIES_PER_WEEK, "materialised three bounty states");
        for (BountyTracker.BountyState s : states) {
            check(s.progress(), 0, "fresh state has zero progress");
            check(s.completed(), false, "fresh state is not completed");
        }
        // Second read is stable: same bounties, same week key, now persisted.
        List<BountyTracker.BountyState> again = BountyTracker.currentBounties(log, OWNER);
        check(again.equals(states), "second read is stable after materialisation");
    }

    /**
     * A state whose week key does not match the current week is replaced with a
     * fresh zero-progress state for the same bounty id, so a week rollover
     * resets progress without losing the pick.
     */
    private static void testStaleWeekResets() {
        DungeonLog log = new DungeonLog();
        // Seed the sidecar with last week's states, partially completed.
        String staleWeek = "2025-W01";
        List<BountyTracker.Bounty> stalePick = BountyTracker.bountiesFor(OWNER, staleWeek);
        List<BountyTracker.BountyState> staleStates = new java.util.ArrayList<>();
        for (BountyTracker.Bounty b : stalePick) {
            staleStates.add(new BountyTracker.BountyState(staleWeek, b.id, b.targetCount, true));
        }
        log.setBounties(OWNER, staleStates);

        // currentBounties with this week's key should reset all to zero.
        List<BountyTracker.BountyState> current = BountyTracker.currentBounties(log, OWNER);
        for (BountyTracker.BountyState s : current) {
            check(s.progress(), 0, "stale week resets progress to zero");
            check(s.completed(), false, "stale week resets completed to false");
        }
    }

    /** The bounties sidecar round trips through DungeonLog's codec. */
    private static void testSidecarRoundTrip() {
        DungeonLog log = new DungeonLog();
        BountyTracker.currentBounties(log, OWNER);
        // Simulate some progress by writing states directly.
        List<BountyTracker.BountyState> states = log.bountiesOf(OWNER);
        List<BountyTracker.BountyState> modified = new java.util.ArrayList<>();
        for (int i = 0; i < states.size(); i++) {
            BountyTracker.BountyState s = states.get(i);
            modified.add(new BountyTracker.BountyState(s.weekKey(), s.bountyId(), 5, false));
        }
        log.setBounties(OWNER, modified);

        com.google.gson.JsonElement encoded = DungeonLog.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, log).result().orElseThrow();
        DungeonLog decoded = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, encoded).result().orElseThrow().getFirst();

        List<BountyTracker.BountyState> decodedStates = decoded.bountiesOf(OWNER);
        check(decodedStates.size(), BountyTracker.BOUNTIES_PER_WEEK, "decoded sidecar has three states");
        for (BountyTracker.BountyState s : decodedStates) {
            check(s.progress(), 5, "progress round trips through codec");
            check(s.completed(), false, "completed flag round trips through codec");
        }
    }

    /** A dungeon_log.dat written before M34 has no bounties field and loads with an empty sidecar. */
    private static void testLegacySaveDefaults() {
        com.google.gson.JsonObject old = new com.google.gson.JsonObject();
        old.add("players", new com.google.gson.JsonArray());
        DungeonLog legacy = DungeonLog.CODEC.decode(
                com.mojang.serialization.JsonOps.INSTANCE, old).result().orElseThrow().getFirst();
        check(legacy.bountiesOf(OWNER).isEmpty(), "pre-M34 save defaults bounties to empty");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
