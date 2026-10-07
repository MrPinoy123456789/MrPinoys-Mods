package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure-JDK regression for the dungeon trip door deal ({@link TripDoors}) and the
 * staging map text ({@link DungeonMapText}): seeded stability, steps as a shuffle
 * of 1 to 3, the spare doors repeating a branch, every kind of dungeon dealing
 * the same steps, side edge costs, the first door offering dungeons of unlocked acts,
 * and the shipped dungeons dealing a full staging room at every non-final node.
 */
public class TripDoorsTest {

    private static final String T = "pocketdungeons:";
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("10000000-0000-0000-0000-0000000000a2");

    /** Frostworks shaped: a fork, a one edge node, a two edge node with a side branch, a final. */
    private static final String FROST = """
            {"name": "Frostworks", "act": 2, "baseLevel": 1, "kind": "dungeon", "mainTheme": "frostworks",
             "lootBand": {"min": 2, "max": 3},
             "nodes": [
               {"id": "gate", "name": "Frozen Gate", "layer": 1},
               {"id": "kitchens", "name": "Ice Kitchens", "layer": 2},
               {"id": "furnaces", "name": "Glaze Furnaces", "layer": 2},
               {"id": "barracks", "name": "Stray Barracks", "layer": 3},
               {"id": "fields", "name": "Powder Snow Fields", "layer": 3},
               {"id": "vault", "name": "Glaze Vault", "layer": 3},
               {"id": "big", "name": "The Big Freeze", "layer": 4, "final": true}
             ],
             "edges": [
               {"from": "gate", "to": "kitchens"},
               {"from": "gate", "to": "furnaces"},
               {"from": "kitchens", "to": "barracks"},
               {"from": "kitchens", "to": "fields"},
               {"from": "furnaces", "to": "fields"},
               {"from": "furnaces", "to": "vault", "cost": 2},
               {"from": "barracks", "to": "big"},
               {"from": "fields", "to": "big"},
               {"from": "vault", "to": "big"}
             ]}
            """;

    private static final String MINE = """
            {"name": "Mineshaft", "act": 1, "baseLevel": 1, "kind": "dungeon", "mainTheme": "rootworks",
             "lootBand": {"min": 1, "max": 1},
             "nodes": [
               {"id": "adit", "name": "The Adit", "layer": 1},
               {"id": "seam", "name": "Ore Seam", "layer": 2},
               {"id": "face", "name": "Working Face", "layer": 3, "final": true}
             ],
             "edges": [
               {"from": "adit", "to": "seam"},
               {"from": "seam", "to": "face"}
             ]}
            """;

    private static DungeonDef frost;
    private static DungeonDef mine;

    public static void main(String[] args) throws Exception {
        frost = DungeonDef.fromJson(T + "frostworks", JsonParser.parseString(FROST).getAsJsonObject());
        mine = DungeonDef.fromJson(T + "mineshaft", JsonParser.parseString(MINE).getAsJsonObject());
        testStepsAreAShuffleOfOneToThree();
        testSeededStability();
        testSpareDoorsRepeatABranch();
        testTwoEdgesFillThreeDoors();
        testSideBranchCost();
        testResourceStepsAreDealtLikeAnyOther();
        testVariantsNumberTheCopies();
        testFinalNodeHasNoDoors();
        testFirstDoorsOfferUnlockedDungeons();
        testCompassGatesDungeons();
        testActOneOffersAtCompassOne();
        testFirstDoorsRepeatWhenFewDungeons();
        testReachability();
        testMapText();
        testFirstMapText();
        testShippedDungeonsDealFullRooms();
        System.out.println("TripDoorsTest passed");
    }

    private static void testStepsAreAShuffleOfOneToThree() {
        Set<List<Integer>> orders = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            int[] steps = TripDoors.dealSteps(TripDoors.seed(OWNER, "d", "n", i, 9));
            List<Integer> sorted = new ArrayList<>(List.of(steps[0], steps[1], steps[2]));
            orders.add(List.of(steps[0], steps[1], steps[2]));
            sorted.sort(null);
            check(sorted.equals(List.of(1, 2, 3)), "every deal is +1, +2 and +3 once each: " + sorted);
        }
        check(orders.size() == 6, "all six orders turn up over many seeds: " + orders.size());
    }

    private static void testSeededStability() {
        TripDoors.Door[] a = TripDoors.dealNext(OWNER, frost, "gate", 1);
        for (int i = 0; i < 50; i++) {
            check(same(a, TripDoors.dealNext(OWNER, frost, "gate", 1)), "same state, same deal (preview is stable)");
        }
        // A different owner, node or path length re-deals; at least one of many differs.
        boolean ownerMatters = false;
        boolean pathMatters = false;
        for (int path = 1; path < 30; path++) {
            ownerMatters |= !same(TripDoors.dealNext(OWNER, frost, "kitchens", path),
                    TripDoors.dealNext(OTHER, frost, "kitchens", path));
            pathMatters |= !same(TripDoors.dealNext(OWNER, frost, "kitchens", path),
                    TripDoors.dealNext(OWNER, frost, "kitchens", path + 1));
        }
        check(ownerMatters, "the owner is part of the seed");
        check(pathMatters, "the path length is part of the seed");
        check(TripDoors.seed(OWNER, "a", "b", 1, 1) == TripDoors.seed(OWNER, "a", "b", 1, 1), "seed is a pure function");
        check(TripDoors.seed(OWNER, "a", "b", 1, 1) != TripDoors.seed(OWNER, "a", "c", 1, 1), "node changes the seed");
    }

    /** One out edge: all three doors lead to it, at the three steps. */
    private static void testSpareDoorsRepeatABranch() {
        DungeonDef oneEdge = DungeonDef.fromJson(T + "line", JsonParser.parseString(MINE)
                .getAsJsonObject());
        TripDoors.Door[] doors = TripDoors.dealNext(OWNER, oneEdge, "adit", 1);
        check(doors.length == 3, "three doors");
        Set<Integer> steps = new HashSet<>();
        for (TripDoors.Door door : doors) {
            check(door.nodeId().equals("seam"), "a lone branch repeats on every door");
            steps.add(door.step());
        }
        check(steps.equals(Set.of(1, 2, 3)), "the repeats still carry +1, +2 and +3: " + steps);
    }

    private static void testTwoEdgesFillThreeDoors() {
        for (int path = 1; path <= 30; path++) {
            TripDoors.Door[] doors = TripDoors.dealNext(OWNER, frost, "kitchens", path);
            Set<String> nodes = new HashSet<>();
            for (TripDoors.Door door : doors) {
                nodes.add(door.nodeId());
            }
            check(nodes.equals(Set.of("barracks", "fields")), "two branches both appear: " + nodes);
        }
        // Three edges never repeat.
        DungeonDef three = DungeonDef.fromJson(T + "three", JsonParser.parseString("""
                {"name": "Three", "act": 1, "baseLevel": 1, "kind": "dungeon", "mainTheme": "rootworks",
                 "lootBand": {"min": 1, "max": 1},
                 "nodes": [{"id": "a", "name": "A", "layer": 1}, {"id": "b", "name": "B", "layer": 2},
                           {"id": "c", "name": "C", "layer": 2}, {"id": "d", "name": "D", "layer": 2},
                           {"id": "e", "name": "E", "layer": 3, "final": true}],
                 "edges": [{"from": "a", "to": "b"}, {"from": "a", "to": "c"}, {"from": "a", "to": "d"},
                           {"from": "b", "to": "e"}, {"from": "c", "to": "e"}, {"from": "d", "to": "e"}]}
                """).getAsJsonObject());
        Set<String> nodes = new HashSet<>();
        for (TripDoors.Door door : TripDoors.dealNext(OWNER, three, "a", 1)) {
            nodes.add(door.nodeId());
        }
        check(nodes.equals(Set.of("b", "c", "d")), "three edges, three different doors: " + nodes);
    }

    private static void testSideBranchCost() {
        boolean sawSide = false;
        for (int path = 1; path <= 30; path++) {
            for (TripDoors.Door door : TripDoors.dealNext(OWNER, frost, "furnaces", path)) {
                if (door.nodeId().equals("vault")) {
                    check(door.cost() == 2 && door.sideBranch(), "the side edge costs its authored scrap");
                    sawSide = true;
                } else {
                    check(door.cost() == 0 && !door.sideBranch(), "a main edge is free");
                }
            }
        }
        check(sawSide, "the side branch is dealt");
        for (TripDoors.Door door : TripDoors.dealNext(OWNER, frost, "gate", 1)) {
            check(door.cost() == 0, "no cost on the main fork");
        }
    }

    private static void testResourceStepsAreDealtLikeAnyOther() {
        for (int path = 1; path <= 30; path++) {
            TripDoors.Door[] doors = TripDoors.dealNext(OWNER, mine, "adit", path);
            check(shuffleOfOneTwoThree(doors), "a node dungeon deals a shuffle of 1, 2, 3: "
                    + java.util.Arrays.toString(doors));
        }
        for (int salt = 0; salt < 20; salt++) {
            TripDoors.Door[] first = TripDoors.dealFirst(OWNER, List.of(mine), salt);
            check(shuffleOfOneTwoThree(first), "entering a node dungeon deals a shuffle of 1, 2, 3 too");
            for (TripDoors.Door door : first) {
                check(door.dungeonId().equals(T + "mineshaft") && door.nodeId().equals("adit"),
                        "the entry door is the dungeon's entry: " + door);
            }
        }
        List<DungeonDef> pool = List.of(mine, frost, DungeonDef.fromJson(T + "other",
                JsonParser.parseString(FROST.replace("Frostworks", "Other")).getAsJsonObject()));
        for (int salt = 0; salt < 20; salt++) {
            check(shuffleOfOneTwoThree(TripDoors.dealFirst(OWNER, pool, salt)),
                    "mixed first doors deal a shuffle of 1, 2, 3 whatever stands behind them");
        }
    }

    private static boolean shuffleOfOneTwoThree(TripDoors.Door[] doors) {
        int[] steps = new int[doors.length];
        for (int i = 0; i < doors.length; i++) {
            steps[i] = doors[i].step();
        }
        java.util.Arrays.sort(steps);
        return java.util.Arrays.equals(steps, new int[]{1, 2, 3});
    }

    /** A node with one edge deals three doors to the same floor: variants 0, 1 and 2, in slot order. */
    private static void testVariantsNumberTheCopies() {
        for (int path = 1; path <= 20; path++) {
            TripDoors.Door[] doors = TripDoors.dealNext(OWNER, mine, "adit", path);
            for (int slot = 0; slot < doors.length; slot++) {
                check(doors[slot].variant() == slot, "one edge, copies numbered in order: " + doors[slot]);
                check(doors[slot].pathLength() == path, "the door carries its path length");
            }
        }
        // Two edges: the repeat of the first branch is variant 1, the other branch starts at 0.
        for (int path = 1; path <= 20; path++) {
            TripDoors.Door[] doors = TripDoors.dealNext(OWNER, frost, "gate", path);
            java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            for (TripDoors.Door door : doors) {
                int before = seen.merge(door.nodeId(), 1, Integer::sum) - 1;
                check(door.variant() == before, "variant counts earlier doors to the same floor: " + door);
            }
        }
    }

    private static void testFinalNodeHasNoDoors() {
        check(TripDoors.dealNext(OWNER, frost, "big", 5).length == 0, "a final floor has no doors");
        check(TripDoors.dealNext(OWNER, frost, "nope", 5).length == 0, "an unknown node has no doors");
        check(TripDoors.isFinal(frost, "big") && !TripDoors.isFinal(frost, "gate"), "final detection");
        check(!TripDoors.isFinal(null, "big"), "no dungeon, no final");
        check(TripDoors.finalAhead(frost, "barracks") && TripDoors.finalAhead(frost, "vault"), "one floor from the end");
        check(!TripDoors.finalAhead(frost, "kitchens") && !TripDoors.finalAhead(frost, "big"), "not ahead elsewhere");
    }

    private static void testFirstDoorsOfferUnlockedDungeons() {
        DungeonDef endless = DungeonDef.fromJson(T + "endless_mine", JsonParser.parseString("""
                {"name": "Endless Mine", "act": 1, "baseLevel": 1, "kind": "endless", "mainTheme": "rootworks",
                 "lootBand": {"min": 1, "max": 1},
                 "nodes": [{"id": "face", "name": "Face", "layer": 1}], "edges": []}
                """).getAsJsonObject());
        List<DungeonDef> all = List.of(frost, mine, endless);
        List<DungeonDef> act1 = TripDoors.eligibleFirst(all, Set.of(1), 25);
        check(act1.size() == 1 && act1.get(0).id().equals(T + "mineshaft"),
                "act 1 offers the mineshaft, never endless: " + act1);
        List<DungeonDef> both = TripDoors.eligibleFirst(all, Set.of(1, 2), 25);
        check(both.size() == 2 && both.get(0).id().compareTo(both.get(1).id()) < 0, "sorted by id, endless excluded");
        check(TripDoors.eligibleFirst(all, Set.of(), 25).isEmpty(), "no unlocked act, no dungeon");
        check(TripDoors.dealFirst(OWNER, List.of(), 0).length == 0, "nothing eligible deals nothing");
    }

    private static void testCompassGatesDungeons() {
        DungeonDef late = DungeonDef.fromJson(T + "late",
                JsonParser.parseString(FROST.replace("Frostworks", "Late")
                        .replace("\"baseLevel\": 1", "\"baseLevel\": 8")).getAsJsonObject());
        check(late.unlockLevel() == 8, "unlockLevel reads baseLevel");
        List<DungeonDef> all = List.of(frost, mine, late);
        // frost is act 2 baseLevel 1, mine act 1 baseLevel 1, late act 2 baseLevel 8.
        check(TripDoors.eligibleFirst(all, Set.of(1, 2), 7).contains(mine)
                && !TripDoors.eligibleFirst(all, Set.of(1, 2), 7).contains(late),
                "compass 7 does not see the level 8 dungeon");
        check(TripDoors.eligibleFirst(all, Set.of(1, 2), 8).contains(late),
                "compass 8 unlocks it");
        check(TripDoors.eligibleFirst(List.of(late), Set.of(2), 1).isEmpty(),
                "a locked dungeon is never eligible");
        check(TripDoors.lockedFirst(all, Set.of(1, 2), 7).equals(List.of(late)),
                "the locked list holds what the compass has not reached");
        check(TripDoors.lockedFirst(all, Set.of(1, 2), 25).isEmpty(), "nothing locked at compass 25");
        check(TripDoors.lockedFirst(all, Set.of(1), 0).equals(List.of(mine)),
                "locked hides closed acts but lists open ones the compass has not reached");
    }

    private static void testFirstDoorsRepeatWhenFewDungeons() {
        TripDoors.Door[] one = TripDoors.dealFirst(OWNER, List.of(frost), 3);
        check(one.length == 3, "three doors from one dungeon");
        Set<Integer> steps = new HashSet<>();
        for (TripDoors.Door door : one) {
            check(door.dungeonId().equals(T + "frostworks") && door.nodeId().equals("gate"), "leads to the entry");
            steps.add(door.step());
        }
        check(steps.equals(Set.of(1, 2, 3)), "steps still dealt across the repeats");

        List<DungeonDef> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(DungeonDef.fromJson(T + "d" + i,
                    JsonParser.parseString(FROST.replace("Frostworks", "D" + i)).getAsJsonObject()));
        }
        for (int salt = 0; salt < 20; salt++) {
            TripDoors.Door[] doors = TripDoors.dealFirst(OWNER, five, salt);
            Set<String> ids = new HashSet<>();
            for (TripDoors.Door door : doors) {
                ids.add(door.dungeonId());
                check(door.cost() == 0, "a dungeon entry is free");
            }
            check(ids.size() == 3, "three distinct dungeons when five are open: " + ids);
            check(same(doors, TripDoors.dealFirst(OWNER, five, salt)), "stable for the same salt");
        }
        boolean saltMatters = false;
        for (int salt = 1; salt < 20; salt++) {
            saltMatters |= !same(TripDoors.dealFirst(OWNER, five, 0), TripDoors.dealFirst(OWNER, five, salt));
        }
        check(saltMatters, "the salt re-deals between trips");
    }

    private static void testReachability() {
        check(TripDoors.reachableFrom(frost, "furnaces").equals(Set.of("furnaces", "fields", "vault", "big")),
                "furnaces reaches fields, the vault and the end");
        check(TripDoors.reachableFrom(frost, "big").equals(Set.of("big")), "the final reaches only itself");
        check(TripDoors.reachableFrom(frost, "nope").isEmpty(), "unknown node reaches nothing");
    }

    private static void testMapText() {
        TripDoors.Door[] doors = TripDoors.dealNext(OWNER, frost, "furnaces", 2);
        List<DungeonMapText.Line> lines = DungeonMapText.lines(frost, "furnaces", List.of("gate", "furnaces"), doors, false);
        String text = join(lines);
        check(text.contains("FROSTWORKS"), "title");
        check(text.contains(">> Glaze Furnaces <<"), "the current node is marked");
        check(text.contains("* Frozen Gate"), "a visited node is marked");
        check(text.contains("The Big Freeze (FINAL FLOOR)"), "the final node is marked");
        check(text.contains("to Glaze Vault (side branch: 2 scrap)"), "a side edge shows its cost");
        check(text.contains("Ice Kitchens (out of reach now)") || text.contains("Stray Barracks (out of reach now)"),
                "nodes no door can reach are flagged");
        check(text.contains("Doors from here"), "doors are listed");
        check(text.contains("Door 1: ") && text.contains("Door 3: "), "all three doors listed");
        check(text.contains("can reach: "), "each door lists what it can reach");
        // Steps appear only on the doors, never on later floors: exactly three '+N' step words.
        int stepWords = 0;
        for (DungeonMapText.Line line : lines) {
            if (line.text().startsWith("Door ")) {
                check(line.text().matches("Door [123]: .*, \\+[123].*"), "a door line carries its step: " + line.text());
                stepWords++;
            } else {
                check(!line.text().matches(".*\\+[123]\\b.*"), "no step shown off the doors: " + line.text());
            }
        }
        check(stepWords == 3, "three door lines");
        for (DungeonMapText.Line line : lines) {
            check(!line.text().contains("--") && line.text().indexOf('—') < 0, "no dash punctuation: " + line.text());
        }
        // The side door says so.
        boolean sideDoor = false;
        for (DungeonMapText.Line line : lines) {
            sideDoor |= line.text().startsWith("Door ") && line.text().contains("side branch: 2 scrap")
                    && line.text().contains("Glaze Vault");
        }
        check(sideDoor || !has(doors, "vault"), "the side door line shows its cost");

        String done = join(DungeonMapText.lines(frost, "big", List.of("gate", "kitchens", "fields", "big"),
                new TripDoors.Door[0], true));
        check(done.contains("The dungeon is cleared. Pull the HOME lever."), "the finished map says go home");
        check(!done.contains("Doors from here"), "no doors once finished");
    }

    private static void testFirstMapText() {
        TripDoors.Door[] doors = TripDoors.dealFirst(OWNER, List.of(frost, mine), 0);
        String text = join(DungeonMapText.firstLines(doors, id -> id.equals(frost.id()) ? frost : mine));
        check(text.contains("CHOOSE A DUNGEON"), "heading");
        check(text.contains("Frostworks") && text.contains("Mineshaft"), "both dungeons named");
        check(text.contains("ends at The Big Freeze"), "the final floor is named");
        check(!text.contains("+0 scrap"), "no door is dealt zero scrap");
    }

    /** Every non-final node of every shipped dungeon deals three doors with steps 1 to 3. */
    private static void testShippedDungeonsDealFullRooms() throws Exception {
        String[] names = {"rootworks", "infestation", "ossuary", "deepslate", "copper_works", "frostworks",
                "prismarine", "drowned_vault", "basalt_foundry", "blackstone", "ender_archive"};
        for (String name : names) {
            DungeonDef def;
            try (InputStream in = TripDoorsTest.class.getClassLoader()
                    .getResourceAsStream("data/pocketdungeons/dungeon/" + name + ".json")) {
                check(in != null, "shipped dungeon on the classpath: " + name);
                JsonObject obj = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                def = DungeonDef.fromJson(T + name, obj);
            }
            for (DungeonDef.Node node : def.nodes()) {
                TripDoors.Door[] doors = TripDoors.dealNext(OWNER, def, node.id(), 1);
                if (node.isFinal()) {
                    check(doors.length == 0, name + " " + node.id() + ": a final floor deals no doors");
                    continue;
                }
                check(doors.length == 3, name + " " + node.id() + ": three doors");
                Set<Integer> steps = new HashSet<>();
                boolean free = false;
                for (TripDoors.Door door : doors) {
                    steps.add(door.step());
                    free |= door.cost() == 0;
                }
                check(steps.equals(Set.of(1, 2, 3)), name + " " + node.id() + ": steps " + steps);
                check(free, name + " " + node.id() + ": at least one free door (a main edge always exists)");
            }
            // The entry is reachable from the first deal of a one dungeon pool.
            TripDoors.Door[] first = TripDoors.dealFirst(OWNER, List.of(def), 0);
            check(first[0].nodeId().equals(def.entry().id()), name + ": first door leads to the entry node");
        }
    }

    /** D23: a fresh compass still has somewhere to go. */
    private static void testActOneOffersAtCompassOne() throws Exception {
        List<DungeonDef> shipped = new ArrayList<>();
        String[] names = {"mineshaft", "rootworks", "infestation", "ossuary", "spawner_dungeon"};
        for (String name : names) {
            try (InputStream in = TripDoorsTest.class.getClassLoader()
                    .getResourceAsStream("data/pocketdungeons/dungeon/" + name + ".json")) {
                check(in != null, "shipped dungeon on the classpath: " + name);
                shipped.add(DungeonDef.fromJson(T + name,
                        JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                                .getAsJsonObject()));
            }
        }
        check(!TripDoors.eligibleFirst(shipped, Set.of(1), 1).isEmpty(),
                "act 1 at compass 1 offers at least one dungeon");
        check(TripDoors.eligibleFirst(shipped, Set.of(1), 1).stream()
                        .allMatch(d -> d.unlockLevel() <= 1),
                "everything offered at compass 1 is unlocked");
    }

    // ---- helpers ------------------------------------------------------------------

    private static boolean has(TripDoors.Door[] doors, String nodeId) {
        for (TripDoors.Door door : doors) {
            if (door.nodeId().equals(nodeId)) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<DungeonMapText.Line> lines) {
        StringBuilder out = new StringBuilder();
        for (DungeonMapText.Line line : lines) {
            out.append(line.text()).append('\n');
        }
        return out.toString();
    }

    private static boolean same(TripDoors.Door[] a, TripDoors.Door[] b) {
        return java.util.Arrays.equals(a, b);
    }

    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }
}
