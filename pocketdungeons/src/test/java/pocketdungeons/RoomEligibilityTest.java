package pocketdungeons;

import java.util.List;
import java.util.Random;

/**
 * Dungeon structure W5 (design D6a, section 7): room eligibility for a floor, read from the room
 * metadata ({@code dungeons}, legacy {@code theme}, {@code acts}, {@code borrowableBy},
 * {@code graphRole}), and the graph role weight boost. Pure: {@link RoomEligibility} takes plain
 * lists, and the seeded weighted pick is exercised through {@link RoomEligibility#weightFactor}.
 */
public class RoomEligibilityTest {

    private static final String FROST = "pocketdungeons:frostworks";
    private static final String DEEP = "pocketdungeons:deepslate";
    private static final String CAPSTONE = "pocketdungeons:drowned_vault";

    /** An ordinary middle floor of Frostworks (act 2) on its own theme. */
    private static final RoomEligibility.Floor FROST_MID = new RoomEligibility.Floor(
            FROST, FROST, "frostworks", 2, false, FROST, "", false, false, false);

    public static void main(String[] args) {
        testLegacyRoomsUnchanged();
        testDungeonsAndTheme();
        testActsOnly();
        testBorrowedTheme();
        testBorrowableBy();
        testGraphRoleGate();
        testWeightBoost();
        testDungeonBoost();
        testSeededDeterminism();
        System.out.println("RoomEligibilityTest passed");
    }

    private static RoomEligibility.RoomTags room(List<String> dungeons, List<String> theme, List<Integer> acts,
                                                  List<String> roles, List<String> borrowable) {
        return new RoomEligibility.RoomTags(dungeons, theme, acts, roles, borrowable);
    }

    private static final List<String> NONE = List.of();

    private static void testLegacyRoomsUnchanged() {
        RoomEligibility.RoomTags unthemed = room(NONE, NONE, List.of(), NONE, NONE);
        check(RoomEligibility.eligible(unthemed, FROST_MID), "a room with no fields fits any floor");
        check(RoomEligibility.eligible(unthemed, null), "no floor context is always eligible");
        RoomEligibility.RoomTags legacyFrost = room(NONE, List.of("frostworks"), List.of(), NONE, NONE);
        check(RoomEligibility.eligible(legacyFrost, FROST_MID), "a legacy bare theme matches the main theme");
        RoomEligibility.RoomTags legacyDeep = room(NONE, List.of("deepslate"), List.of(), NONE, NONE);
        check(!RoomEligibility.eligible(legacyDeep, FROST_MID), "a legacy theme of another dungeon is out");
        check(RoomEligibility.weightFactor(unthemed, FROST_MID) == 1, "no role, no boost");
    }

    private static void testDungeonsAndTheme() {
        RoomEligibility.RoomTags byDungeon = room(List.of(FROST), NONE, List.of(), NONE, NONE);
        check(RoomEligibility.eligible(byDungeon, FROST_MID), "dungeons names the floor's dungeon");
        RoomEligibility.RoomTags other = room(List.of(DEEP), NONE, List.of(), NONE, NONE);
        check(!RoomEligibility.eligible(other, FROST_MID), "dungeons of another dungeon is out");
        // dungeons replaces the legacy theme when both are written.
        RoomEligibility.RoomTags both = room(List.of(DEEP), List.of("frostworks"), List.of(), NONE, NONE);
        check(!RoomEligibility.eligible(both, FROST_MID), "dungeons wins over a legacy theme");
        // The requested room theme (a theme whose room theme differs from its id) still matches.
        RoomEligibility.Floor odd = new RoomEligibility.Floor(FROST, FROST, "rimed", 2, false, FROST, "",
                false, false, false);
        check(RoomEligibility.eligible(room(NONE, List.of("rimed"), List.of(), NONE, NONE), odd),
                "the planner's room theme still matches a legacy theme list");
    }

    private static void testActsOnly() {
        RoomEligibility.RoomTags act2 = room(NONE, NONE, List.of(2, 3), NONE, NONE);
        check(RoomEligibility.eligible(act2, FROST_MID), "an act-only room fits a dungeon of that act");
        RoomEligibility.RoomTags act1 = room(NONE, NONE, List.of(1), NONE, NONE);
        check(!RoomEligibility.eligible(act1, FROST_MID), "an act-only room is out of another act");
        RoomEligibility.RoomTags named = room(List.of(DEEP), NONE, List.of(2), NONE, NONE);
        check(!RoomEligibility.eligible(named, FROST_MID), "acts only applies to a room that names no dungeon");
    }

    private static void testBorrowedTheme() {
        // A Frostworks floor on the Deepslate theme (D6a): the dungeon that owns that theme is deepslate.
        RoomEligibility.Floor borrowed = new RoomEligibility.Floor(FROST, FROST, "deepslate", 2, false, DEEP, DEEP,
                false, false, false);
        RoomEligibility.RoomTags deepRoom = room(List.of(DEEP), NONE, List.of(), NONE, NONE);
        check(RoomEligibility.eligible(deepRoom, borrowed), "a borrowed floor draws the owner dungeon's rooms");
        check(!RoomEligibility.eligible(deepRoom, FROST_MID), "the same room is out of a floor that did not borrow");
        RoomEligibility.RoomTags legacyDeep = room(NONE, List.of("deepslate"), List.of(), NONE, NONE);
        check(RoomEligibility.eligible(legacyDeep, borrowed), "a legacy theme of the borrowed theme matches");
        RoomEligibility.RoomTags frostRoom = room(List.of(FROST), NONE, List.of(), NONE, NONE);
        check(RoomEligibility.eligible(frostRoom, borrowed), "the dungeon's own rooms still fit its borrowed floor");
        RoomEligibility.RoomTags elsewhere = room(List.of("pocketdungeons:copper_works"), NONE, List.of(), NONE, NONE);
        check(!RoomEligibility.eligible(elsewhere, borrowed), "a third dungeon's room is still out");
    }

    private static void testBorrowableBy() {
        RoomEligibility.RoomTags lent = room(List.of(DEEP), NONE, List.of(), NONE, List.of(FROST));
        check(RoomEligibility.eligible(lent, FROST_MID), "borrowableBy lists the floor dungeon");
        RoomEligibility.RoomTags lentElsewhere = room(List.of(DEEP), NONE, List.of(), NONE,
                List.of("pocketdungeons:copper_works"));
        check(!RoomEligibility.eligible(lentElsewhere, FROST_MID), "borrowableBy of another dungeon is out");
        RoomEligibility.RoomTags bare = room(List.of(DEEP), NONE, List.of(), NONE, List.of("frostworks"));
        check(RoomEligibility.eligible(bare, FROST_MID), "a bare id in borrowableBy is qualified");
    }

    private static void testGraphRoleGate() {
        RoomEligibility.Floor finalFloor = new RoomEligibility.Floor(FROST, FROST, "frostworks", 2, false, FROST, "",
                false, true, false);
        RoomEligibility.Floor capFinal = new RoomEligibility.Floor(CAPSTONE, CAPSTONE, "drowned", 3, true, CAPSTONE,
                "", false, true, false);
        RoomEligibility.Floor capMid = new RoomEligibility.Floor(CAPSTONE, CAPSTONE, "drowned", 3, true, CAPSTONE,
                "", false, false, false);
        RoomEligibility.RoomTags fin = room(NONE, NONE, List.of(), List.of("final"), NONE);
        check(RoomEligibility.eligible(fin, finalFloor), "a final room appears on a final node");
        check(!RoomEligibility.eligible(fin, FROST_MID), "a final room never appears on an ordinary node");
        RoomEligibility.RoomTags cap = room(NONE, NONE, List.of(), List.of("capstone"), NONE);
        check(RoomEligibility.eligible(cap, capFinal), "a capstone room appears on a capstone dungeon's final node");
        check(!RoomEligibility.eligible(cap, finalFloor), "a capstone room never appears in a non-capstone dungeon");
        check(!RoomEligibility.eligible(cap, capMid), "a capstone room never appears on a non-final node");
        RoomEligibility.RoomTags both = room(NONE, NONE, List.of(), List.of("final", "capstone"), NONE);
        check(RoomEligibility.eligible(both, finalFloor), "a final-and-capstone room fits an ordinary final node");
        check(!RoomEligibility.eligible(both, capMid), "but not a mid node");
        RoomEligibility.RoomTags any = room(NONE, NONE, List.of(), List.of("any"), NONE);
        check(RoomEligibility.eligible(any, FROST_MID), "an any room fits everywhere");
        RoomEligibility.RoomTags entry = room(NONE, NONE, List.of(), List.of("entry"), NONE);
        check(RoomEligibility.eligible(entry, FROST_MID), "an entry room is preferred on entry nodes, not exclusive");
        RoomEligibility.RoomTags mixed = room(NONE, NONE, List.of(), List.of("final", "side_reward"), NONE);
        check(RoomEligibility.eligible(mixed, FROST_MID), "a room with a non-final role besides final is allowed");
    }

    private static void testWeightBoost() {
        RoomEligibility.Floor entryFloor = new RoomEligibility.Floor(FROST, FROST, "frostworks", 2, false, FROST, "",
                true, false, false);
        RoomEligibility.Floor sideFloor = new RoomEligibility.Floor(FROST, FROST, "frostworks", 2, false, FROST, "",
                false, false, true);
        RoomEligibility.RoomTags entry = room(NONE, NONE, List.of(), List.of("entry"), NONE);
        RoomEligibility.RoomTags side = room(NONE, NONE, List.of(), List.of("side_reward"), NONE);
        check(RoomEligibility.weightFactor(entry, entryFloor) == RoomEligibility.ROLE_BOOST, "entry room boosted on the entry node");
        check(RoomEligibility.weightFactor(entry, FROST_MID) == 1, "entry room not boosted elsewhere");
        check(RoomEligibility.weightFactor(side, sideFloor) == RoomEligibility.ROLE_BOOST, "side_reward boosted on a side edge floor");
        check(RoomEligibility.weightFactor(side, FROST_MID) == 1, "side_reward not boosted on a main edge floor");
        check(RoomEligibility.weightFactor(side, entryFloor) == 1, "side_reward not boosted on the entry node");
        check(RoomEligibility.weightFactor(entry, null) == 1, "no floor, no boost");
    }

    /**
     * PD-149: a room bound to the floor's dungeon or main theme draws DUNGEON_BOOST,
     * stacking multiplicatively with the role boost; a room reached only through a
     * borrowed theme or borrowableBy does not get it.
     */
    private static void testDungeonBoost() {
        RoomEligibility.RoomTags own = room(List.of(FROST), NONE, List.of(), NONE, NONE);
        check(RoomEligibility.weightFactor(own, FROST_MID) == RoomEligibility.DUNGEON_BOOST,
                "the dungeon's own room is boosted on its floor");
        RoomEligibility.RoomTags legacyOwn = room(NONE, List.of("frostworks"), List.of(), NONE, NONE);
        check(RoomEligibility.weightFactor(legacyOwn, FROST_MID) == RoomEligibility.DUNGEON_BOOST,
                "a legacy theme match counts as bound");
        RoomEligibility.RoomTags generic = room(NONE, NONE, List.of(), NONE, NONE);
        check(RoomEligibility.weightFactor(generic, FROST_MID) == 1, "a generic hall is not boosted");
        RoomEligibility.RoomTags lent = room(List.of(DEEP), NONE, List.of(), NONE, List.of(FROST));
        check(RoomEligibility.weightFactor(lent, FROST_MID) == 1,
                "a room only borrowed into the dungeon is not boosted");
        RoomEligibility.Floor entryFloor = new RoomEligibility.Floor(FROST, FROST, "frostworks", 2, false, FROST, "",
                true, false, false);
        RoomEligibility.RoomTags ownEntry = room(List.of(FROST), NONE, List.of(), List.of("entry"), NONE);
        check(RoomEligibility.weightFactor(ownEntry, entryFloor)
                        == RoomEligibility.DUNGEON_BOOST * RoomEligibility.ROLE_BOOST,
                "the boosts stack on a bound entry room");
        check(RoomEligibility.weightFactor(own, null) == 1, "no floor, no boost");
    }

    /** The boost only changes weights, so a seeded weighted pick stays reproducible and shifts toward boosted rooms. */
    private static void testSeededDeterminism() {
        RoomEligibility.Floor entryFloor = new RoomEligibility.Floor(FROST, FROST, "frostworks", 2, false, FROST, "",
                true, false, false);
        RoomEligibility.RoomTags entry = room(NONE, NONE, List.of(), List.of("entry"), NONE);
        RoomEligibility.RoomTags plain = room(NONE, NONE, List.of(), NONE, NONE);
        int first = 0;
        int second = 0;
        int boostedWins = 0;
        for (long seed = 0; seed < 600; seed++) {
            int a = pick(seed, entry, plain, entryFloor);
            int b = pick(seed, entry, plain, entryFloor);
            check(a == b, "the same seed picks the same room");
            if (a == 0) {
                boostedWins++;
            }
            first += a;
            second += b;
        }
        check(first == second, "reproducible across runs");
        // Weights 3 and 1: the boosted room wins about 75 percent of 600 seeds.
        check(boostedWins > 380 && boostedWins < 520, "the boosted room wins about three in four: " + boostedWins);
    }

    private static int pick(long seed, RoomEligibility.RoomTags a, RoomEligibility.RoomTags b,
                            RoomEligibility.Floor floor) {
        int wa = RoomEligibility.weightFactor(a, floor);
        int wb = RoomEligibility.weightFactor(b, floor);
        int roll = new Random(seed).nextInt(wa + wb);
        return roll < wa ? 0 : 1;
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
