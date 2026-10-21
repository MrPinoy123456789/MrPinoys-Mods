package pocketdungeons;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Golden fixtures for the player save record, {@link DungeonLog.Entry}.
 *
 * <p>Player data is sacred: the shape {@code dungeon_log.dat} is written in must not move
 * under a refactor of the record. Each fixture under {@code src/test/resources/dungeonlog}
 * is checked three ways. The input file ({@code name.json}) must decode to exactly the
 * values listed here, accessor by accessor ({@link #snapshot}). Re-encoding it must produce
 * the checked in written form ({@code name.written.json}, made by the code before the
 * record was regrouped), so a rollback to an older mod version still reads what a newer one
 * writes. And the written form must itself decode to the same values and encode to itself.
 *
 * <p>Set valued fields are written in hash order, so both sides are normalized
 * ({@link #normalize}) before comparing; list order that means something (recent themes,
 * recent visitors) is kept.
 *
 * <p>The expected values assume the default scrap price knobs (4 and 3), which the bar
 * settling and the old pool migration read.
 */
public class DungeonLogGoldenTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    /** Array keys whose order is not data (they are sets in the record). */
    private static final Set<String> SET_KEYS = Set.of("extracted_powers", "unlocked_shells",
            "diary_bands_seen", "dungeons_finished", "acts_unlocked", "decide_list");

    public static void main(String[] args) throws IOException {
        fullCurrentShape();
        oldestShape();
        legacyFieldsShape();
        clampsShape();
        bountiesShape();
        mineFloorsShape();
        questLineSeenShape();
        System.out.println("DungeonLogGoldenTest: all assertions passed");
    }

    private static void fullCurrentShape() throws IOException {
        String expected = String.join("\n",
                "runs=12", "bestPath=9", "bestKeystone=14", "keystone=11", "affix=ominous", "pending=6",
                "recentThemes=[deepslate, prismarine, frost]", "completed={deepslate=3, prismarine=1}",
                "currentTheme=frost", "depth=4", "powers=[power_a, power_b]", "publicListed=true",
                "roomName=The Vault", "fuel=17", "shells=[prestige, sandstone]", "roomCompletions=5",
                "visitors=[Bob@1700000000000, Cy@1699999999999]", "diary=[1, 3]", "bag=pilgrim",
                "keyProgress=2", "kitGranted=true", "finished=[pocketdungeons:frostworks]",
                "highestCharts=13", "chartProgress=3", "haul=6", "haulIntroSeen=true",
                "acts=[1, 2, 3, 4]", "actsMigrated=true", "campaignComplete=true", "trips=8",
                "whitelist=true",
                "decideList=[00000000-0000-0000-0000-0000000000b1, 00000000-0000-0000-0000-0000000000b2]",
                "deepestMine=21");
        DungeonLog log = golden("full_current", expected);
        check(log.taskProgress(PLAYER, "station_a"), 2, "task progress sidecar is read");
        // Crew model: no finish_bonus_claimed in the fixture, so the set seeds from dungeons_finished.
        check(log.finishBonusClaimedOf(PLAYER), java.util.Set.of("pocketdungeons:frostworks"),
                "finish_bonus_claimed seeds from dungeons_finished");
        // The one fixture whose input is already in the written form, but for the old scrap
        // pool, which is read and never written (always 0, so omitted).
        JsonElement input = read("full_current.json");
        input.getAsJsonObject().getAsJsonArray("players").get(0).getAsJsonObject()
                .getAsJsonObject("entry").remove("scrap");
        check(pretty(normalize(input)), pretty(normalize(encode(log))),
                "full shape: what the codec writes is the input, unchanged");
    }

    private static void oldestShape() throws IOException {
        // A pre-U8 save: only runs and best_path are required; the streak and the last
        // completed date were dropped fields, and a field no version knows is ignored.
        golden("oldest", String.join("\n",
                "runs=4", "bestPath=7", "bestKeystone=0", "keystone=0", "affix=", "pending=0",
                "recentThemes=[]", "completed={}", "currentTheme=", "depth=0", "powers=[]",
                "publicListed=false", "roomName=", "fuel=0", "shells=[]", "roomCompletions=0",
                "visitors=[]", "diary=[]", "bag=", "keyProgress=0", "kitGranted=false", "finished=[]",
                "highestCharts=0", "chartProgress=0", "haul=0", "haulIntroSeen=false",
                "acts=[1]", "actsMigrated=true", "campaignComplete=false", "trips=0",
                "whitelist=false", "decideList=[]", "deepestMine=0"));
    }

    private static void legacyFieldsShape() throws IOException {
        // Superseded fields (affix, recent themes, current theme, depth, fuel) still load;
        // the old scrap pool migrates the compass and the bar; the pre acts migration runs
        // for a keystone of 15 or more.
        golden("legacy_fields", String.join("\n",
                "runs=30", "bestPath=11", "bestKeystone=22", "keystone=20", "affix=ominous,fragile",
                "pending=20", "recentThemes=[a, b, c]", "completed={a=2}", "currentTheme=b", "depth=2",
                "powers=[]", "publicListed=false", "roomName=", "fuel=12", "shells=[sandstone]",
                "roomCompletions=2", "visitors=[]", "diary=[2]", "bag=scout", "keyProgress=0",
                "kitGranted=false", "finished=[]", "highestCharts=20", "chartProgress=3", "haul=0",
                "haulIntroSeen=false", "acts=[1, 2, 3]", "actsMigrated=true", "campaignComplete=false",
                "trips=0", "whitelist=false", "decideList=[]", "deepestMine=0"));
    }

    private static void clampsShape() throws IOException {
        // Negative numbers clamp to zero, a malformed decide list id is dropped, and a bar
        // holding whole levels (9 at compass 2: 4, then 5) settles into the compass.
        golden("clamps", String.join("\n",
                "runs=1", "bestPath=1", "bestKeystone=0", "keystone=2", "affix=", "pending=0",
                "recentThemes=[]", "completed={}", "currentTheme=", "depth=0", "powers=[]",
                "publicListed=false", "roomName=", "fuel=0", "shells=[]", "roomCompletions=0",
                "visitors=[]", "diary=[]", "bag=", "keyProgress=0", "kitGranted=false", "finished=[]",
                "highestCharts=4", "chartProgress=0", "haul=0", "haulIntroSeen=false",
                "acts=[1]", "actsMigrated=true", "campaignComplete=false", "trips=0",
                "whitelist=false", "decideList=[00000000-0000-0000-0000-0000000000b1]",
                "deepestMine=0"));
    }

    private static void mineFloorsShape() throws IOException {
        // Endless Mine per act (decision 2026-10-11): the Act 1 mine keeps the old deepest_mine_floor field, the
        // later acts' mines live in mine_floors. A malformed act key is dropped and a key for act 1 is ignored
        // (the old field is the act 1 record); the written form is sorted by act and omits the map when empty
        // (every other fixture proves the omission by not changing).
        DungeonLog log = golden("mine_floors", String.join("\n",
                "runs=3", "bestPath=3", "bestKeystone=0", "keystone=4", "affix=", "pending=0",
                "recentThemes=[]", "completed={}", "currentTheme=", "depth=0", "powers=[]",
                "publicListed=false", "roomName=", "fuel=0", "shells=[]", "roomCompletions=0",
                "visitors=[]", "diary=[]", "bag=", "keyProgress=0", "kitGranted=false", "finished=[]",
                "highestCharts=4", "chartProgress=0", "haul=0", "haulIntroSeen=false",
                "acts=[1, 2]", "actsMigrated=true", "campaignComplete=false", "trips=0",
                "whitelist=false", "decideList=[]", "deepestMine=8", "mineFloors={2=5, 3=1}"));
        DungeonLog.Campaign campaign = log.get(PLAYER).campaign();
        check(campaign.deepestMineFloor(1), 8, "act 1 reads the old field");
        check(campaign.deepestMineFloor(2), 5, "act 2 reads mine_floors");
        check(campaign.deepestMineFloor(3), 1, "act 3 reads mine_floors");
        check(campaign.deepestMineFloor(4), 0, "an unseen act has none");
        // An old save (every other fixture) loads with none, and the old field alone still gates act 1 and the
        // old layer bottoms (ActProgress.MineDepth.reaches), which ActUnlockTest pins.
        check(decode(read("full_current.json")).get(PLAYER).campaign().otherMineFloors().isEmpty(), true,
                "a save from before per act mines has no later mine record");
    }

    private static void questLineSeenShape() throws IOException {
        // Campaign and endgame (decision 2026-10-18): the one time "your compass now shows the way" flag.
        // Optional with a default of false, so every older fixture reads as not yet shown (the snapshot
        // only names it when true, so they did not change), and the flag is written only when set.
        DungeonLog log = golden("quest_line_seen", String.join("\n",
                "runs=6", "bestPath=8", "bestKeystone=0", "keystone=3", "affix=", "pending=0",
                "recentThemes=[]", "completed={}", "currentTheme=", "depth=0", "powers=[]",
                "publicListed=false", "roomName=", "fuel=0", "shells=[]", "roomCompletions=0",
                "visitors=[]", "diary=[]", "bag=", "keyProgress=0", "kitGranted=false",
                "finished=[pocketdungeons:mineshaft]", "highestCharts=3", "chartProgress=1", "haul=0",
                "haulIntroSeen=true", "acts=[1]", "actsMigrated=true", "campaignComplete=false", "trips=0",
                "whitelist=false", "decideList=[]", "deepestMine=0", "questLineSeen=true"));
        check(log.get(PLAYER).questLineSeen(), true, "the flag reads back");
        for (String old : List.of("oldest", "legacy_fields", "clamps", "mine_floors", "with_bounties", "full_current")) {
            check(decode(read(old + ".json")).get(PLAYER).questLineSeen(), false,
                    old + ": a save from before the flag reads as not yet shown");
        }
        DungeonLog fresh = decode(read("oldest.json"));
        check(fresh.markQuestLineSeen(PLAYER), true, "the first join after the update handles the message");
        check(fresh.markQuestLineSeen(PLAYER), false, "and only once");
        check(encode(fresh).getAsJsonObject().getAsJsonArray("players").get(0).getAsJsonObject()
                .getAsJsonObject("entry").get("quest_line_seen").getAsBoolean(), true, "and the flag is saved");
        check(fresh.get(PLAYER).withHaul(5).questLineSeen(), true, "other withers keep it");
    }

    private static void bountiesShape() throws IOException {
        DungeonLog log = golden("with_bounties", String.join("\n",
                "runs=2", "bestPath=3", "bestKeystone=0", "keystone=5", "affix=", "pending=0",
                "recentThemes=[]", "completed={}", "currentTheme=", "depth=0", "powers=[]",
                "publicListed=false", "roomName=", "fuel=0", "shells=[]", "roomCompletions=0",
                "visitors=[]", "diary=[]", "bag=", "keyProgress=0", "kitGranted=false", "finished=[]",
                "highestCharts=5", "chartProgress=1", "haul=0", "haulIntroSeen=false",
                "acts=[1]", "actsMigrated=true", "campaignComplete=false", "trips=0",
                "whitelist=false", "decideList=[]", "deepestMine=0"));
        check(log.taskProgress(PLAYER, "station_a"), 2, "task progress survives next to bounties");
        check(log.taskProgress(PLAYER, "station_b"), 1, "second task progress survives");

        // The weekly bounties were retired (2026-10-02) and the in memory sidecar is gone: a
        // save that still carries them loads, and is written back without them (the retired
        // storage_returns list likewise). Nothing else about the save changes.
        JsonObject written = encode(log).getAsJsonObject();
        check(written.has("bounties"), false, "a loaded save is written without the dead bounties list");
        check(written.has("storage_returns"), false, "and without the retired storage returns");
        check(written.has("task_progress"), true, "the live sidecars are still written");

        // Whatever the old field held is ignored, even a shape no version ever wrote.
        JsonObject odd = read("with_bounties.json").getAsJsonObject();
        odd.add("bounties", new com.google.gson.JsonPrimitive("not a list"));
        check(snapshot(decode(odd).get(PLAYER)), snapshot(log.get(PLAYER)),
                "a malformed bounties field does not stop the save loading");
    }

    /**
     * Runs the three checks for one fixture and returns the decoded log.
     * {@code expected} is the {@link #snapshot} of the one player in it.
     */
    private static DungeonLog golden(String name, String expected) throws IOException {
        JsonElement input = read(name + ".json");
        DungeonLog log = decode(input);
        check(snapshot(log.get(PLAYER)), expected, name + ": decoded values");

        JsonElement written = read(name + ".written.json");
        check(pretty(normalize(encode(log))), pretty(normalize(written)),
                name + ": the written form is the golden one");

        DungeonLog reread = decode(written);
        check(snapshot(reread.get(PLAYER)), expected, name + ": the written form decodes to the same values");
        check(pretty(normalize(encode(reread))), pretty(normalize(written)),
                name + ": the written form encodes to itself");
        check(reread.get(PLAYER), log.get(PLAYER), name + ": entries are equal after a round trip");

        // SavedData stores the log as NBT: the same values must survive that encoding too.
        net.minecraft.nbt.Tag nbt = DungeonLog.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, log)
                .result().orElseThrow();
        DungeonLog fromNbt = DungeonLog.CODEC.decode(net.minecraft.nbt.NbtOps.INSTANCE, nbt)
                .result().orElseThrow().getFirst();
        check(snapshot(fromNbt.get(PLAYER)), expected, name + ": NBT round trip keeps the values");
        return log;
    }

    /** Every accessor of an entry as text, sets sorted, so one comparison covers them all. */
    static String snapshot(DungeonLog.Entry e) {
        List<String> visitors = new ArrayList<>();
        for (DungeonLog.VisitorEntry v : e.recentVisitors()) {
            visitors.add(v.name() + "@" + v.timestamp());
        }
        List<String> decide = new ArrayList<>();
        for (UUID id : e.campaign().decideList()) {
            decide.add(id.toString());
        }
        java.util.Collections.sort(decide);
        String base = String.join("\n",
                "runs=" + e.runsCompleted(), "bestPath=" + e.bestPathLength(),
                "bestKeystone=" + e.bestKeystoneLevel(), "keystone=" + e.keystoneLevel(),
                "affix=" + e.keystoneAffix(), "pending=" + e.pendingOfferLevel(),
                "recentThemes=" + e.recentThemes(), "completed=" + new TreeMap<>(e.completedThemes()),
                "currentTheme=" + e.currentTheme(), "depth=" + e.depth(),
                "powers=" + new TreeSet<>(e.extractedPowers()), "publicListed=" + e.publicListed(),
                "roomName=" + e.roomName(), "fuel=" + e.fuel(),
                "shells=" + new TreeSet<>(e.unlockedShells()), "roomCompletions=" + e.roomCompletions(),
                "visitors=" + visitors, "diary=" + new TreeSet<>(e.diaryBandsSeen()), "bag=" + e.bag(),
                "keyProgress=" + e.keyProgress(), "kitGranted=" + e.kitGranted(),
                "finished=" + new TreeSet<>(e.dungeonsFinished()),
                "highestCharts=" + e.highestCharts(), "chartProgress=" + e.chartProgress(),
                "haul=" + e.haul(), "haulIntroSeen=" + e.haulIntroSeen(),
                "acts=" + new TreeSet<>(e.campaign().actsUnlocked()),
                "actsMigrated=" + e.campaign().actsMigrated(),
                "campaignComplete=" + e.campaign().campaignComplete(),
                "trips=" + e.campaign().tripCounter(), "whitelist=" + e.campaign().decideWhitelist(),
                "decideList=" + decide, "deepestMine=" + e.campaign().deepestMineFloor());
        String withMines = e.campaign().otherMineFloors().isEmpty() ? base
                : base + "\nmineFloors=" + new TreeMap<>(e.campaign().otherMineFloors());
        // Named only when set, so the fixtures from before the flag keep their expected text.
        return e.questLineSeen() ? withMines + "\nquestLineSeen=true" : withMines;
    }

    // ---- codec plumbing, shared with the sub record test ----------------------------------

    static DungeonLog decode(JsonElement json) {
        return DungeonLog.CODEC.decode(JsonOps.INSTANCE, json).result().orElseThrow().getFirst();
    }

    static JsonElement encode(DungeonLog log) {
        return DungeonLog.CODEC.encodeStart(JsonOps.INSTANCE, log).result().orElseThrow();
    }

    static JsonElement read(String resource) throws IOException {
        try (InputStream in = DungeonLogGoldenTest.class.getResourceAsStream("/dungeonlog/" + resource)) {
            if (in == null) {
                throw new AssertionError("missing test resource /dungeonlog/" + resource);
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static String pretty(JsonElement json) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(json);
    }

    /**
     * A copy with set valued arrays sorted and arrays of per player objects ordered by
     * player, so hash order never decides a comparison. Object key order does not matter
     * to a {@link JsonObject}, but is sorted here so the pretty text is stable.
     */
    static JsonElement normalize(JsonElement json) {
        return normalize(json, "");
    }

    private static JsonElement normalize(JsonElement json, String key) {
        if (json.isJsonObject()) {
            JsonObject out = new JsonObject();
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
                sorted.put(e.getKey(), normalize(e.getValue(), e.getKey()));
            }
            sorted.forEach(out::add);
            return out;
        }
        if (json.isJsonArray()) {
            List<JsonElement> items = new ArrayList<>();
            for (JsonElement item : json.getAsJsonArray()) {
                items.add(normalize(item, key));
            }
            if (SET_KEYS.contains(key)) {
                items.sort(Comparator.comparing(JsonElement::toString));
            } else if (!items.isEmpty() && items.get(0).isJsonObject()
                    && items.get(0).getAsJsonObject().has("player")) {
                items.sort(Comparator.comparing(j -> j.getAsJsonObject().get("player").getAsString()));
            }
            JsonArray out = new JsonArray();
            items.forEach(out::add);
            return out;
        }
        return json;
    }

    static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ":\nexpected " + expected + "\nbut was  " + actual);
        }
    }
}
