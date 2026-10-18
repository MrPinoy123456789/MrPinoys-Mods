package pocketdungeons;

import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Campaign and endgame (decision 2026-10-18), T2: the words of the compass in the campaign. The quest line
 * names the dungeons to do next and how many more open the capstone; nothing in it is a number to chase.
 */
public class QuestLineTest {

    public static void main(String[] args) {
        testNamed();
        testFreshAct();
        testProgress();
        testCapstoneOpen();
        testMixedActs();
        testActZeroAndAstrolabe();
        testLockedSigns();
        testRecognisesItsOwnLines();
        testRealData();
        testDashes();
        System.out.println("QuestLineTest passed");
    }

    private static DungeonDef def(String id, String name, int act, String kind, int level) {
        String json = "{\"name\": \"" + name + "\", \"act\": " + act + ", \"baseLevel\": " + level
                + ", \"kind\": \"" + kind + "\", \"mainTheme\": \"rootworks\","
                + " \"lootBand\": {\"min\": 1, \"max\": 2},"
                + " \"nodes\": [{\"id\": \"a\", \"name\": \"A\", \"layer\": 1},"
                + " {\"id\": \"b\", \"name\": \"B\", \"layer\": 2, \"final\": true}],"
                + " \"edges\": [{\"from\": \"a\", \"to\": \"b\"}]}";
        return DungeonDef.fromJson("pocketdungeons:" + id, JsonParser.parseString(json).getAsJsonObject());
    }

    /** Act 1: five dungeons in row order a, b, c, d, e (levels 1 to 5) and the capstone. */
    private static List<DungeonDef> act1() {
        List<DungeonDef> all = new ArrayList<>();
        all.add(def("d1", "Mineshaft", 1, "dungeon", 1));
        all.add(def("d2", "Lush Caves", 1, "dungeon", 2));
        all.add(def("d3", "Infestation", 1, "dungeon", 3));
        all.add(def("d4", "The Kennels", 1, "dungeon", 4));
        all.add(def("d5", "Ossuary", 1, "dungeon", 5));
        all.add(def("cap1", "The Spawner Dungeon", 1, "capstone", 6));
        all.add(def("mine1", "Endless Mine", 1, "endless", 1));
        return all;
    }

    private static void testNamed() {
        check(QuestLine.named(List.of("A")), "A", "one name");
        check(QuestLine.named(List.of("A", "B")), "A or B", "two names");
        check(QuestLine.named(List.of("A", "B", "C")), "A, B or C", "three names");
        check(QuestLine.named(List.of("A", "B", "C", "D")), "A, B, C and 1 more", "four names");
        check(QuestLine.named(List.of("A", "B", "C", "D", "E", "F")), "A, B, C and 3 more", "six names");
        check(QuestLine.the("The Spawner Dungeon"), "the Spawner Dungeon", "a name that starts with The");
        check(QuestLine.the("Ancient City"), "the Ancient City", "a name that does not");
    }

    private static void testFreshAct() {
        List<DungeonDef> all = act1();
        check(QuestLine.lines(Set.of(1), Set.of(), all, 3),
                List.of("Next: Mineshaft, Lush Caves, Infestation and 2 more", "3 more for the Spawner Dungeon"),
                "nothing finished: three named, then the rest, and the three that open the capstone");
    }

    private static void testProgress() {
        List<DungeonDef> all = act1();
        check(QuestLine.lines(Set.of(1), Set.of("pocketdungeons:d1"), all, 3),
                List.of("Next: Lush Caves, Infestation, The Kennels and 1 more", "2 more for the Spawner Dungeon"),
                "one finished: it drops off the line and the count");
        check(QuestLine.lines(Set.of(1), Set.of("d1", "d2"), all, 3),
                List.of("Next: Infestation, The Kennels or Ossuary", "1 more for the Spawner Dungeon"),
                "two finished (bare ids count too): three left, one more opens the capstone");
        // The same list with fewer dungeons left to name.
        List<DungeonDef> small = new ArrayList<>();
        small.add(def("a", "Mineshaft", 1, "dungeon", 1));
        small.add(def("b", "Lush Caves", 1, "dungeon", 2));
        small.add(def("c", "Infestation", 1, "dungeon", 3));
        small.add(def("cap", "The Spawner Dungeon", 1, "capstone", 4));
        check(QuestLine.lines(Set.of(1), Set.of(), small, 3),
                List.of("Next: Mineshaft, Lush Caves or Infestation", "3 more for the Spawner Dungeon"),
                "the owner's example string");
        check(QuestLine.lines(Set.of(1), Set.of("a"), small, 3),
                List.of("Next: Lush Caves or Infestation", "2 more for the Spawner Dungeon"), "two names");
        // An act with only two ordinary dungeons asks for two, not the knob's three.
        List<DungeonDef> two = List.of(def("a", "Mineshaft", 1, "dungeon", 1), def("b", "Lush Caves", 1, "dungeon", 2),
                def("cap", "The Spawner Dungeon", 1, "capstone", 3));
        check(QuestLine.lines(Set.of(1), Set.of("a"), two, 3),
                List.of("Next: Lush Caves", "1 more for the Spawner Dungeon"), "singular and capped by the act");
    }

    private static void testCapstoneOpen() {
        List<DungeonDef> all = act1();
        check(QuestLine.lines(Set.of(1), Set.of("d1", "d2", "d3"), all, 3),
                List.of("Next: the Spawner Dungeon"), "three finished: the capstone is the next step");
        check(QuestLine.lines(Set.of(1), Set.of("d3", "d4", "d5"), all, 3),
                List.of("Next: the Spawner Dungeon"), "any three open it");
        check(QuestLine.lines(Set.of(1), Set.of("d1", "d2", "d3"), all, 2),
                List.of("Next: the Spawner Dungeon"), "and a lower knob opens it sooner");
        check(QuestLine.lines(Set.of(1), Set.of("d1", "d2", "d3", "mine1"), all, 3),
                List.of("Next: the Spawner Dungeon"), "the mine is not a dungeon of the count and never gates");
    }

    private static void testMixedActs() {
        List<DungeonDef> all = new ArrayList<>(act1());
        all.add(def("e1", "Deepslate", 2, "dungeon", 6));
        all.add(def("e2", "Frostworks", 2, "dungeon", 7));
        all.add(def("e3", "Copper Works", 2, "dungeon", 8));
        all.add(def("cap2", "Ancient City", 2, "capstone", 9));
        Set<String> act1Done = Set.of("d1", "d2", "d3", "cap1");
        // Act 1 is finished, act 2 is open: the line moves up to act 2.
        check(QuestLine.lines(Set.of(1, 2), act1Done, all, 3),
                List.of("Next: Deepslate, Frostworks or Copper Works", "3 more for the Ancient City"),
                "the lowest open act that is not complete drives the line");
        // Act 2 not open yet: act 1's capstone is done and nothing else is open.
        check(QuestLine.lines(Set.of(1), act1Done, all, 3), List.of("Next: the Astrolabe"),
                "every open act is done: back to the Astrolabe");
        // A stored act above the open set never speaks.
        check(QuestLine.lines(Set.of(1), Set.of("d1"), all, 3).get(0).startsWith("Next: Lush Caves"),
                true, "a closed act is not named");
        // Act 2 capstone finished too: the Astrolabe.
        Set<String> both = new java.util.HashSet<>(act1Done);
        both.add("cap2");
        check(QuestLine.lines(Set.of(1, 2), both, all, 3), List.of("Next: the Astrolabe"), "all done");
        // An act with a capstone done but ordinary dungeons left is complete (the capstone is the finish).
        check(QuestLine.lines(Set.of(1, 2), Set.of("cap1", "e1"), all, 3).get(0).startsWith("Next: Frostworks"),
                true, "a finished capstone completes its act whatever else is left");
    }

    private static void testActZeroAndAstrolabe() {
        List<DungeonDef> all = new ArrayList<>(act1());
        all.add(def("first_night", "The First Night", 0, "dungeon", 1));
        check(QuestLine.lines(Set.of(0), Set.of(), all, 3), List.of("Next: The First Night"),
                "Act 0 has one dungeon and no capstone: a single line");
        check(QuestLine.lines(Set.of(1), Set.of("first_night"), all, 3).get(0),
                "Next: Mineshaft, Lush Caves, Infestation and 2 more",
                "after Act 0 the first quest line is the Act 1 dungeons");
        check(QuestLine.lines(Set.of(), Set.of(), all, 3), List.of(), "no open act, no line");
        check(QuestLine.lines(Set.of(1), Set.of(), List.of(), 3), List.of(), "no dungeons loaded, no line");
    }

    private static void testLockedSigns() {
        List<DungeonDef> all = act1();
        DungeonDef cap = all.get(5);
        check(QuestLine.lockedSign(cap, all, 3), "Finish 3 dungeons", "a locked capstone");
        check(QuestLine.lockedSign(cap, all, 1), "Finish 1 dungeon", "singular");
        check(QuestLine.lockedSign(cap, all, 99), "Finish 5 dungeons", "capped at the act's dungeons");
        DungeonDef act2 = def("e1", "Deepslate", 2, "dungeon", 6);
        check(QuestLine.lockedSign(act2, all, 3), "Finish Act 1", "a door of a closed act");
        check(QuestLine.lockedSign(all.get(0), all, 3), "Finish Act 1", "never below act 1");
    }

    private static void testRecognisesItsOwnLines() {
        check(QuestLine.isQuestLine("Next: Mineshaft"), true, "a next line");
        check(QuestLine.isQuestLine("2 more for the Spawner Dungeon"), true, "a count line");
        check(QuestLine.isQuestLine("12 more for the Ancient City"), true, "a two digit count");
        check(QuestLine.isQuestLine("Compass 4: 1/5 scrap to 5"), false, "the endgame line is not a quest line");
        check(QuestLine.isQuestLine("Right-click a lodestone to use it."), false, "the use hint stays");
        check(QuestLine.isQuestLine("more for you"), false, "no digits");
    }

    /** The shipped data: the first quest line after Act 0 names Act 1 dungeons and the Spawner Dungeon. */
    private static void testRealData() {
        check(QuestLine.ITEM_NAME, "Compass", "the campaign item name");
        check(QuestLine.NEXT_PREFIX, "Next: ", "prefix");
        check(QuestLine.MORE_INFIX, " more for ", "infix");
        List<DungeonDef> real = new ArrayList<>();
        java.io.File[] files = new java.io.File("src/main/resources/data/pocketdungeons/dungeon").listFiles();
        for (java.io.File file : files) {
            if (!file.getName().endsWith(".json")) {
                continue;
            }
            try {
                String id = file.getName().replace(".json", "");
                real.add(DungeonDef.fromJson("pocketdungeons:" + id, JsonParser.parseString(
                        java.nio.file.Files.readString(file.toPath())).getAsJsonObject()));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }
        List<String> first = QuestLine.lines(Set.of(0), Set.of(), real, 3);
        check(first, List.of("Next: The First Night"), "a new player's first line");
        List<String> after = QuestLine.lines(Set.of(1), Set.of("pocketdungeons:first_night"), real, 3);
        System.out.println("real Act 1 line: " + after);
        check(after.size(), 2, "two lines");
        check(after.get(1), "3 more for the Spawner Dungeon", "the real capstone");
        check(after.get(0).startsWith("Next: "), true, "the real first line");
        List<String> capstone = QuestLine.lines(Set.of(1), Set.of("pocketdungeons:mineshaft",
                "pocketdungeons:infestation", "pocketdungeons:rootworks"), real, 3);
        check(capstone, List.of("Next: the Spawner Dungeon"), "three real dungeons open the real capstone");
    }

    private static void testDashes() {
        List<DungeonDef> all = act1();
        List<String> everything = new ArrayList<>();
        everything.addAll(QuestLine.lines(Set.of(1), Set.of(), all, 3));
        everything.addAll(QuestLine.lines(Set.of(1), Set.of("d1", "d2", "d3"), all, 3));
        everything.addAll(QuestLine.lines(Set.of(1), Set.of("d1", "d2", "d3", "cap1"), all, 3));
        everything.add(QuestLine.lockedSign(all.get(5), all, 3));
        everything.add(DungeonProgress.WAKE_TITLE);
        everything.add(DungeonProgress.WAKE_SUBTITLE);
        for (String text : everything) {
            if (text.contains("--") || text.contains("—") || text.length() > 60) {
                throw new AssertionError("bad player text: " + text);
            }
        }
        check(DungeonProgress.WAKE_TITLE, "Your compass wakes up.", "the wake title");
        check(DungeonProgress.WAKE_SUBTITLE, "The Chart Room is open.", "the wake subtitle");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
