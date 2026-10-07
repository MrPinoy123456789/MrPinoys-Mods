package pocketdungeons;

import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure-JDK regression for dungeon structure W7a: the Spawner Dungeon's brood wave sizing and
 * phases ({@link BroodWave}), the Ancient City's sculk omen rule and Warden summon rule
 * ({@link SculkOmen}), and the capstone offer rule at the first door ({@link TripDoors}).
 */
public class CapstoneRulesTest {

    private static final String T = "pocketdungeons:";
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static final String SHAPE = """
            {"name": "NAME", "act": ACT, "kind": "KIND", "mainTheme": "frostworks",
             "lootBand": {"min": 1, "max": 2},
             "nodes": [
               {"id": "gate", "name": "Gate", "layer": 1},
               {"id": "mid", "name": "Mid", "layer": 2},
               {"id": "end", "name": "End", "layer": 3, "final": true}
             ],
             "edges": [
               {"from": "gate", "to": "mid"},
               {"from": "mid", "to": "end"}
             ]}
            """;

    private static DungeonDef def(String id, int act, String kind) {
        return DungeonDef.fromJson(T + id, JsonParser.parseString(
                SHAPE.replace("NAME", id).replace("ACT", Integer.toString(act)).replace("KIND", kind)).getAsJsonObject());
    }

    public static void main(String[] args) {
        testBroodSizing();
        testBroodExhaustion();
        testBroodPhases();
        testSculkPulseRule();
        testWardenSummonRule();
        testWardenOnlyInAncientCity();
        testPendingCapstone();
        testCapstoneGuaranteeStaysOffDoorThree();
        testNoGuaranteeWhenNothingPending();
        testRoomNarrowing();
        System.out.println("CapstoneRulesTest passed");
    }

    // ---- brood wave ------------------------------------------------------------------

    private static void testBroodSizing() {
        check(BroodWave.skeletons(1) == 4 && BroodWave.spiders(1) == 6, "a solo wave is 4 skeletons and 6 spiders");
        check(BroodWave.waveSize(1) == 10, "solo wave size");
        int last = 0;
        for (int party = 1; party <= 8; party++) {
            int size = BroodWave.waveSize(party);
            check(size > last || size == BroodWave.MAX_WAVE, "the wave grows with the party: " + party + " -> " + size);
            check(size <= BroodWave.MAX_WAVE, "the wave is capped: " + size);
            check(BroodWave.skeletons(party) > 0 && BroodWave.spiders(party) > 0, "both kinds stay in the wave");
            last = size;
        }
        check(BroodWave.waveSize(4) == (4 + 3 * 2) + (6 + 3 * 3), "party of four: 10 skeletons and 15 spiders");
        check(BroodWave.waveSize(100) <= BroodWave.MAX_WAVE, "a huge party is still capped");
        check(BroodWave.waveSize(0) == BroodWave.waveSize(1), "an empty party counts as one");
        check(BroodWave.waveSize(-3) == BroodWave.waveSize(1), "a negative party counts as one");
    }

    private static void testBroodExhaustion() {
        check(BroodWave.exhaustKills(4, 1) == 24, "four spawners, solo: 24 kills");
        check(BroodWave.exhaustKills(4, 3) == 24 + 12, "two more members add 12 kills");
        check(BroodWave.exhaustKills(0, 1) == BroodWave.KILLS_PER_SPAWNER, "no spawners still needs a floor of kills");
        check(BroodWave.spawnersDone(0, 0, 24), "all broken counts as done");
        check(BroodWave.spawnersDone(3, 24, 24), "exhausted counts as done");
        check(!BroodWave.spawnersDone(3, 23, 24), "one kill short is not done");
    }

    private static void testBroodPhases() {
        int exhaust = 24;
        check(BroodWave.phase(4, 0, exhaust, false, 0) == BroodWave.Phase.SPAWNERS, "fresh: spawners");
        check(BroodWave.phase(2, 10, exhaust, false, 0) == BroodWave.Phase.SPAWNERS, "some broken: still spawners");
        check(BroodWave.phase(0, 3, exhaust, false, 0) == BroodWave.Phase.WAVE, "all broken, wave not out: wave");
        check(BroodWave.phase(2, 24, exhaust, false, 0) == BroodWave.Phase.WAVE, "exhausted, wave not out: wave");
        check(BroodWave.phase(0, 3, exhaust, true, 5) == BroodWave.Phase.WAVE, "wave out and alive: wave");
        check(BroodWave.phase(0, 3, exhaust, true, 0) == BroodWave.Phase.DONE, "wave dead: done");
        check(!BroodWave.padOpen(BroodWave.Phase.SPAWNERS) && !BroodWave.padOpen(BroodWave.Phase.WAVE),
                "the pad is shut until done");
        check(BroodWave.padOpen(BroodWave.Phase.DONE), "the pad opens when done");
        check(BroodWave.padRefusal(BroodWave.Phase.DONE, 0, 0) == null, "no refusal when open");
        check(BroodWave.padRefusal(BroodWave.Phase.SPAWNERS, 3, 0).contains("3"), "the refusal names the spawners left");
        check(BroodWave.padRefusal(BroodWave.Phase.WAVE, 0, 7).contains("7"), "the refusal names the wave left");
        check(BroodWave.padRefusal(BroodWave.Phase.WAVE, 0, 0) != null, "a stirring wave still refuses");
    }

    // ---- sculk omen --------------------------------------------------------------------

    private static void testSculkPulseRule() {
        check(SculkOmen.pulsesPerOmen(false) == 5, "elsewhere a sensor needs five pulses per omen");
        check(SculkOmen.pulsesPerOmen(true) == 1, "in the Ancient City every pulse is an omen");
        check(SculkOmen.omenFromPulses(4, false) == 0 && SculkOmen.omenFromPulses(5, false) == 1, "legacy rule intact");
        check(SculkOmen.omenFromPulses(1, true) == 1 && SculkOmen.omenFromPulses(3, true) == 3, "every pulse counts");
        check(SculkOmen.remainingPulses(7, 1, false) == 2, "legacy remainder");
        check(SculkOmen.remainingPulses(3, 3, true) == 0, "ancient pulses are all paid out");
        check(SculkOmen.omenFromPulses(-2, true) == 0, "negative pulses are nothing");
        check(SculkOmen.armsAllSculk("ancient_city") && SculkOmen.armsAllSculk(T + "ancient_city"),
                "the Ancient City arms every sculk block, bare or namespaced");
        check(!SculkOmen.armsAllSculk("deepslate") && !SculkOmen.armsAllSculk(null) && !SculkOmen.armsAllSculk(""),
                "no other theme does");
    }

    private static void testWardenSummonRule() {
        check(SculkOmen.shouldSummonWarden(true, true, Omen.MAX_OMEN, false), "omen 4 on the final floor summons");
        check(!SculkOmen.shouldSummonWarden(true, true, Omen.MAX_OMEN - 1, false), "omen 3 does not");
        check(!SculkOmen.shouldSummonWarden(true, false, Omen.MAX_OMEN, false), "not on an earlier floor");
        check(!SculkOmen.shouldSummonWarden(false, true, Omen.MAX_OMEN, false), "not in another dungeon");
        check(!SculkOmen.shouldSummonWarden(true, true, Omen.MAX_OMEN, true), "only once");
    }

    private static void testWardenOnlyInAncientCity() {
        check(SculkOmen.wardenAllowed("ancient_city") && SculkOmen.wardenAllowed(T + "ancient_city"),
                "the Ancient City may host the Warden");
        for (String other : List.of("deepslate", "drowned_vault", "spawner_dungeon", "endless_mine", "frostworks",
                "prismarine", "ender_archive", "")) {
            check(!SculkOmen.wardenAllowed(other), "no Warden in " + other);
        }
        check(!SculkOmen.wardenAllowed(null), "no Warden without a dungeon");
    }

    // ---- the capstone offer rule -------------------------------------------------------

    private static void testPendingCapstone() {
        DungeonDef spawner = def("spawner_dungeon", 1, "capstone");
        DungeonDef city = def("ancient_city", 2, "capstone");
        DungeonDef vault = def("drowned_vault", 3, "capstone");
        DungeonDef story = def("frostworks", 2, "story");
        List<DungeonDef> all = List.of(vault, story, city, spawner);

        check(TripDoors.pendingCapstone(all, Set.of(1), Set.of()) == spawner, "act 1 open: the spawner dungeon");
        check(TripDoors.pendingCapstone(all, Set.of(1, 2), Set.of()) == spawner,
                "the lowest uncleared capstone wins while act 1's is pending");
        check(TripDoors.pendingCapstone(all, Set.of(1, 2), Set.of(T + "spawner_dungeon")) == city,
                "act 1 cleared: the Ancient City is next");
        check(TripDoors.pendingCapstone(all, Set.of(1, 2), Set.of("spawner_dungeon")) == city,
                "a bare id in the finished set counts as the namespaced dungeon");
        check(TripDoors.pendingCapstone(all, Set.of(1, 2), Set.of("spawner_dungeon", "ancient_city")) == null,
                "act 3 is not open, so its capstone is never pending");
        check(TripDoors.pendingCapstone(all, Set.of(1, 2, 3), Set.of("spawner_dungeon", "ancient_city")) == vault,
                "act 3 open: the Drowned Vault");
        check(TripDoors.pendingCapstone(all, Set.of(1, 2, 3),
                Set.of("spawner_dungeon", "ancient_city", T + "drowned_vault")) == null, "all cleared: nothing pending");
        check(TripDoors.pendingCapstone(List.of(story), Set.of(2), Set.of()) == null, "a story dungeon is never a capstone");
        check(TripDoors.pendingCapstone(List.of(), Set.of(1), Set.of()) == null, "no dungeons, nothing pending");
        check(TripDoors.pendingCapstone(all, Set.of(), Set.of()) == null, "no open act, nothing pending");
    }

    private static void testCapstoneGuaranteeStaysOffDoorThree() {
        DungeonDef spawner = def("spawner_dungeon", 1, "capstone");
        List<DungeonDef> pool = new ArrayList<>();
        pool.add(spawner);
        for (int i = 0; i < 6; i++) {
            pool.add(def("story" + i, 1, "story"));
        }
        Set<Integer> slotsUsed = new HashSet<>();
        for (int salt = 0; salt < 300; salt++) {
            TripDoors.Door[] doors = TripDoors.dealFirst(OWNER, pool, salt, spawner);
            check(doors.length == 3, "three doors");
            boolean onOneOrTwo = doors[0].dungeonId().equals(spawner.id()) || doors[1].dungeonId().equals(spawner.id());
            check(onOneOrTwo, "the capstone is on door 1 or 2 for salt " + salt);
            for (int slot = 0; slot < 2; slot++) {
                if (doors[slot].dungeonId().equals(spawner.id())) {
                    slotsUsed.add(slot);
                }
            }
            Set<String> ids = new HashSet<>();
            for (TripDoors.Door door : doors) {
                ids.add(door.dungeonId());
                check(door.cost() == 0, "an entry is free");
            }
            check(ids.size() == 3, "three distinct dungeons when seven are open: " + ids);
            check(sameDoors(doors, TripDoors.dealFirst(OWNER, pool, salt, spawner)), "stable for the same salt");
            Set<Integer> steps = new HashSet<>();
            for (TripDoors.Door door : doors) {
                steps.add(door.step());
            }
            check(steps.equals(Set.of(1, 2, 3)), "steps are still one of each");
        }
        check(slotsUsed.equals(Set.of(0, 1)), "both door 1 and door 2 are used over many trips: " + slotsUsed);

        // One dungeon only: the guarantee changes nothing.
        TripDoors.Door[] only = TripDoors.dealFirst(OWNER, List.of(spawner), 4, spawner);
        for (TripDoors.Door door : only) {
            check(door.dungeonId().equals(spawner.id()), "a lone capstone fills every door");
        }
    }

    private static void testNoGuaranteeWhenNothingPending() {
        List<DungeonDef> pool = List.of(def("a", 1, "story"), def("b", 1, "story"), def("c", 1, "story"),
                def("d", 1, "story"));
        for (int salt = 0; salt < 20; salt++) {
            check(sameDoors(TripDoors.dealFirst(OWNER, pool, salt), TripDoors.dealFirst(OWNER, pool, salt, null)),
                    "no capstone pending: the deal is the plain one");
        }
        // A guaranteed dungeon that is not in the eligible pool is ignored.
        DungeonDef outsider = def("outsider", 1, "capstone");
        check(sameDoors(TripDoors.dealFirst(OWNER, pool, 3), TripDoors.dealFirst(OWNER, pool, 3, outsider)),
                "an ineligible capstone is not forced in");
    }

    // ---- the boss room choice ----------------------------------------------------------

    private static void testRoomNarrowing() {
        RoomEligibility.RoomTags plain = new RoomEligibility.RoomTags(List.of(), List.of(), List.of(), List.of(), List.of());
        RoomEligibility.RoomTags boss = new RoomEligibility.RoomTags(List.of("spawner_dungeon"), List.of(), List.of(1),
                List.of("capstone"), List.of());
        List<RoomEligibility.RoomTags> candidates = List.of(plain, boss);
        RoomEligibility.Floor capstoneFinal = new RoomEligibility.Floor("spawner_dungeon", "spawner_dungeon",
                "spawner_dungeon", 1, true, "spawner_dungeon", "", false, true, false);
        RoomEligibility.Floor capstoneMid = new RoomEligibility.Floor("spawner_dungeon", "spawner_dungeon",
                "spawner_dungeon", 1, true, "spawner_dungeon", "", false, false, false);
        RoomEligibility.Floor storyFinal = new RoomEligibility.Floor("frostworks", "frostworks", "frostworks", 2,
                false, "frostworks", "", false, true, false);

        check(RoomEligibility.narrowToCapstone(candidates, t -> t, capstoneFinal, true).equals(List.of(boss)),
                "the terminal cell of a capstone final floor takes only the boss room");
        check(RoomEligibility.narrowToCapstone(candidates, t -> t, capstoneFinal, false).size() == 2,
                "other roles on that floor are untouched");
        check(RoomEligibility.narrowToCapstone(candidates, t -> t, capstoneMid, true).size() == 2,
                "a capstone dungeon's earlier floors are untouched");
        check(RoomEligibility.narrowToCapstone(candidates, t -> t, storyFinal, true).size() == 2,
                "a story dungeon's final floor is untouched");
        check(RoomEligibility.narrowToCapstone(List.of(plain), t -> t, capstoneFinal, true).equals(List.of(plain)),
                "a capstone with no boss room (the Drowned Vault) keeps the plain exit hall");
        check(RoomEligibility.narrowToCapstone(candidates, t -> t, null, true).size() == 2, "no floor, no narrowing");

        // The boss rooms themselves only match their own dungeon.
        check(RoomEligibility.eligible(boss, capstoneFinal), "the boss room fits its own final floor");
        check(!RoomEligibility.eligible(boss, capstoneMid), "the capstone graph role keeps it off earlier floors");
        RoomEligibility.Floor otherCapstone = new RoomEligibility.Floor("ancient_city", "ancient_city", "ancient_city",
                2, true, "ancient_city", "", false, true, false);
        check(!RoomEligibility.eligible(boss, otherCapstone), "another dungeon's capstone never draws it");
    }

    private static boolean sameDoors(TripDoors.Door[] a, TripDoors.Door[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(b[i])) {
                return false;
            }
        }
        return true;
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
