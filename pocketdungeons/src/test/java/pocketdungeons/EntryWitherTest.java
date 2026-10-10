package pocketdungeons;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pins what each {@code withX} method on {@link DungeonLog.Entry} changes: exactly the
 * accessors it is named for and nothing else, however the record is laid out inside.
 * Starts from the fully populated golden fixture, so every field holds a distinct,
 * non default value and a wither that dropped or crossed a field shows up as a diff.
 */
public class EntryWitherTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    public static void main(String[] args) throws IOException {
        DungeonLog log = DungeonLogGoldenTest.decode(DungeonLogGoldenTest.read("full_current.json"));
        DungeonLog.Entry base = log.get(PLAYER);

        diff("withRunStats", base, base.withRunStats(1, 2, 3),
                "runs=1", "bestPath=2", "bestKeystone=3");
        diff("withKeystone", base, base.withKeystone(5, "fragile"), "keystone=5", "affix=fragile");
        diff("withPendingOfferLevel", base, base.withPendingOfferLevel(9), "pending=9");
        diff("withPublicListed", base, base.withPublicListed(false), "publicListed=false");
        diff("withFuel", base, base.withFuel(3), "fuel=3");
        diff("withRoomName", base, base.withRoomName("Annex"), "roomName=Annex");
        diff("withThemeProgress", base, base.withThemeProgress(Map.of("zz", 1), "yy", 7),
                "completed={zz=1}", "currentTheme=yy", "depth=7");
        diff("withExtractedPowers", base, base.withExtractedPowers(Set.of("p")), "powers=[p]");
        diff("withUnlockedShells", base, base.withUnlockedShells(Set.of("s")), "shells=[s]");
        diff("withRoomCompletions", base, base.withRoomCompletions(9), "roomCompletions=9");
        diff("withRecentVisitors", base,
                base.withRecentVisitors(List.of(new DungeonLog.VisitorEntry("Zed", 5L))),
                "visitors=[Zed@5]");
        diff("withDiaryBandsSeen", base, base.withDiaryBandsSeen(Set.of(7)), "diary=[7]");
        diff("withKeyProgress", base, base.withKeyProgress(4), "keyProgress=4");
        diff("withKitGranted", base, base.withKitGranted(false), "kitGranted=false");
        diff("withBag", base, base.withBag("scout"), "bag=scout");
        diff("withCampaign", base, base.withCampaign(
                        new DungeonLog.Campaign(Set.of(1), true, false, 1, false, Set.of(), 2)),
                "acts=[1]", "campaignComplete=false", "trips=1", "whitelist=false", "decideList=[]",
                "deepestMine=2");
        diff("withDungeonsFinished", base, base.withDungeonsFinished(Set.of("d")), "finished=[d]");
        diff("withCompass", base, base.withCompass(20, 2), "highestCharts=20", "chartProgress=2");
        diff("withHaul", base, base.withHaul(1), "haul=1");
        diff("withHaulIntroSeen", base, base.withHaulIntroSeen(false), "haulIntroSeen=false");

        // The record's own clamps hold through a wither.
        diff("withFuel clamps", base, base.withFuel(-4), "fuel=0");
        diff("withHaul clamps", base, base.withHaul(-4), "haul=0");
        diff("withRoomCompletions clamps", base, base.withRoomCompletions(-4), "roomCompletions=0");
        diff("withKeyProgress clamps", base, base.withKeyProgress(-4), "keyProgress=0");
        // A bar holding whole levels settles into the compass: 9 at compass 2 pays 4 then 5.
        diff("withCompass settles", base, base.withCompass(2, 9), "highestCharts=4", "chartProgress=0");

        // A wither does not touch the receiver.
        check(DungeonLogGoldenTest.snapshot(base), DungeonLogGoldenTest.snapshot(log.get(PLAYER)),
                "the base entry is unchanged by the withers above");

        // resetCampaign keeps the room settings, unlocks and campaign, and zeroes the rest.
        DungeonLog resetLog = DungeonLogGoldenTest.decode(DungeonLogGoldenTest.read("full_current.json"));
        resetLog.resetCampaign(PLAYER);
        diff("resetCampaign", base, resetLog.get(PLAYER),
                "runs=0", "bestPath=0", "bestKeystone=0", "keystone=0", "affix=", "pending=0",
                "recentThemes=[]", "completed={}", "currentTheme=", "depth=0", "powers=[]", "fuel=0",
                "roomCompletions=0", "bag=", "keyProgress=0", "kitGranted=false", "highestCharts=0",
                "chartProgress=0", "haul=0", "haulIntroSeen=false");

        // The empty entry every unknown player reads as.
        check(DungeonLogGoldenTest.snapshot(new DungeonLog().get(PLAYER)),
                DungeonLogGoldenTest.snapshot(DungeonLog.NONE), "unknown player reads as NONE");
        check(DungeonLog.NONE.campaign(), DungeonLog.Campaign.FRESH, "NONE carries the fresh campaign");

        System.out.println("EntryWitherTest passed");
    }

    /** {@code next} differs from {@code base} in exactly the snapshot lines named in {@code changes}. */
    private static void diff(String what, DungeonLog.Entry base, DungeonLog.Entry next, String... changes) {
        Map<String, String> before = lines(DungeonLogGoldenTest.snapshot(base));
        Map<String, String> after = lines(DungeonLogGoldenTest.snapshot(next));
        Map<String, String> expected = new LinkedHashMap<>(before);
        for (String change : changes) {
            int eq = change.indexOf('=');
            String key = change.substring(0, eq);
            if (!expected.containsKey(key)) {
                throw new AssertionError(what + ": unknown snapshot key " + key);
            }
            expected.put(key, change);
        }
        check(after, expected, what + ": changed exactly " + List.of(changes));
    }

    private static Map<String, String> lines(String snapshot) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : snapshot.split("\n")) {
            out.put(line.substring(0, line.indexOf('=')), line);
        }
        return out;
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ":\nexpected " + expected + "\nbut was  " + actual);
        }
    }
}
