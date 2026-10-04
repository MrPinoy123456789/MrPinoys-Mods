package pocketdungeons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The floor history (2026-10-02): newest first, capped, round trips through
 * {@link DungeonLog}'s codec, and reads right on the board. Also pins the two
 * {@code DungeonLog} sidecars this change left behind: the task progress
 * {@link StationTutorial} still uses, and the superseded weekly bounties,
 * which must load and save unchanged.
 */
public class FloorHistoryTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000007");

    public static void main(String[] args) {
        testNewestFirstAndCapped();
        testRoundTrip();
        testWording();
        testLegacySidecarsSurvive();
        System.out.println("FloorHistoryTest passed");
    }

    private static FloorHistory.Entry entry(long t, String outcome) {
        return new FloorHistory.Entry(t, 9, "pocketdungeons:basalt_foundry", 2,
                List.of("pocketdungeons:feral"), 459, outcome, "pocketdungeons:sump", "lava");
    }

    private static void testNewestFirstAndCapped() {
        DungeonLog log = new DungeonLog();
        for (int i = 0; i < FloorHistory.KEPT + 5; i++) {
            log.addFloorHistory(PLAYER, entry(i, FloorHistory.CLEARED));
        }
        List<FloorHistory.Entry> history = log.floorHistoryOf(PLAYER);
        check(history.size(), FloorHistory.KEPT, "the history is capped");
        check(history.get(0).timestamp(), (long) FloorHistory.KEPT + 4, "the newest entry is first");
        check(history.get(history.size() - 1).timestamp(), 5L, "the oldest kept entry is last");
        check(log.floorHistoryOf(UUID.randomUUID()).isEmpty(), true, "a stranger has no history");
    }

    private static void testRoundTrip() {
        DungeonLog log = new DungeonLog();
        log.addFloorHistory(PLAYER, entry(1, FloorHistory.FAILED));
        log.addFloorHistory(PLAYER, entry(2, FloorHistory.QUIT));
        DungeonLog decoded = roundTrip(log);
        check(decoded.floorHistoryOf(PLAYER), log.floorHistoryOf(PLAYER), "the floor history round trips");
    }

    private static void testWording() {
        check(FloorHistory.duration(459), "7:39", "seconds read as minutes and seconds");
        check(FloorHistory.duration(5), "0:05", "a short floor keeps two second digits");
        check(FloorHistory.words("pocketdungeons:kennel_crossing"), "Kennel Crossing", "room ids read as words");
        check(FloorHistory.ending(entry(1, FloorHistory.FAILED)), "FAILED in Sump (Lava)",
                "a failure names the room and the cause");
        check(FloorHistory.ending(entry(1, FloorHistory.QUIT)), "QUIT in Sump", "a quit names the room");
        check(FloorHistory.ending(entry(1, FloorHistory.CLEARED)), "CLEARED", "a clear needs no room");
    }

    /** Task progress still round trips, and a save's bounty states load and save unchanged. */
    private static void testLegacySidecarsSurvive() {
        DungeonLog log = new DungeonLog();
        log.setTaskProgress(PLAYER, "station_salvage", 1);
        check(roundTrip(log).taskProgress(PLAYER, "station_salvage"), 1, "task progress round trips");

        JsonObject bounty = new JsonObject();
        bounty.addProperty("week", "2026-W39");
        bounty.addProperty("bounty", "tidy");
        bounty.addProperty("progress", 1);
        JsonArray states = new JsonArray();
        states.add(bounty);
        JsonObject owner = new JsonObject();
        owner.addProperty("player", PLAYER.toString());
        owner.add("bounties", states);
        JsonArray bounties = new JsonArray();
        bounties.add(owner);
        JsonObject save = new JsonObject();
        save.add("players", new JsonArray());
        save.add("bounties", bounties);

        DungeonLog loaded = DungeonLog.CODEC.decode(JsonOps.INSTANCE, save).result().orElseThrow().getFirst();
        JsonElement saved = DungeonLog.CODEC.encodeStart(JsonOps.INSTANCE, loaded).result().orElseThrow();
        check(saved.getAsJsonObject().get("bounties"), bounties, "old bounty states are kept as they were");
    }

    private static DungeonLog roundTrip(DungeonLog log) {
        JsonElement encoded = DungeonLog.CODEC.encodeStart(JsonOps.INSTANCE, log).result().orElseThrow();
        return DungeonLog.CODEC.decode(JsonOps.INSTANCE, encoded).result().orElseThrow().getFirst();
    }

    private static void check(Object actual, Object expected, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
