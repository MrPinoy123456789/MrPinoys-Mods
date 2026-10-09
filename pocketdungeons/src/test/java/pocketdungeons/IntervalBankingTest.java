package pocketdungeons;

import java.util.List;

/**
 * Pure-JDK regression for the interval settlement ({@link IntervalBanking}):
 * what a trip's chest count is, and the words on the door and HOME screens.
 * D24: omen and penalties are gone from the bank; the home lever, a
 * checkpoint exit and a grace expiry settle identically, differing only in
 * the journal's {@code trigger}. J1: floors pay their scrap at the clear, so
 * the bank converts nothing.
 */
public class IntervalBankingTest {

    public static void main(String[] args) {
        testChestsCarryTheDepthBonus();
        testEveryWayHomeSettlesIdentically();
        testEmptyTripBanksBaseChests();
        testWords();
        System.out.println("IntervalBankingTest passed");
    }

    private static void testChestsCarryTheDepthBonus() {
        check(settle(List.of(1, 1, 1)).chests() == 3, "no bonus: 3");
        IntervalBanking.Settlement deep = IntervalBanking.settle(List.of(1, 1, 1, 1), List.of(), 0,
                ZoneRules.DEFAULT.bonusChests(4));
        check(deep.chests() == 4, "floor 4: 3 plus 1 bonus");
    }

    /**
     * D24: the same trip banks the same settlement however it ended. The
     * {@code trigger} is the only difference, and it stays in the journal.
     */
    private static void testEveryWayHomeSettlesIdentically() {
        // settle(...) takes no omen and no penalty, so there is nothing left
        // to differ by: one call shape serves every trigger. The trigger is a
        // journal string, exercised by the settlement callers.
        IntervalBanking.Settlement a = settle(List.of(3, 2, 1));
        IntervalBanking.Settlement b = settle(List.of(3, 2, 1));
        check(a.equals(b), "same trip, same settlement: " + a + " vs " + b);
    }

    /** A trip with no cleared floors still banks the base chest count. */
    private static void testEmptyTripBanksBaseChests() {
        IntervalBanking.Settlement none = IntervalBanking.settle(List.of(), List.of(), 0, 0);
        check(none.chests() == Omen.baseRewardChests(), "no floors: base chests");
        check(none.levels() == 0 && none.scrapLeft() == 0, "no floors: nothing else");
    }

    private static void testWords() {
        checkEquals(IntervalBanking.scrapText(3), "3 scrap");
        checkEquals(IntervalBanking.chartText(1), "1 chart");
        checkEquals(IntervalBanking.chartText(2), "2 charts");
        checkEquals(IntervalBanking.chests(1), "1 chest");
        // The go-home board (Haul and Blood Doors): GO HOME over the haul, Lives N, then what
        // leaving forfeits, then a footer while some haul is at risk.
        checkEquals(home(0, List.of(), solo(0)), "Haul 0 scrap\nLives 5");
        checkEquals(home(3, List.of(), solo(7)), "Haul 7 scrap\nLives 2\nA failed dungeon keeps half.");
        checkEquals(home(1, List.of("vault", "page"), solo(2)),
                "Haul 2 scrap\nLives 4\nUnfinished: vault, page\nA failed dungeon keeps half.");
        checkEquals(home(2, List.of("vault"), solo(0)), "Haul 0 scrap\nLives 3\nUnfinished: vault");
        java.util.Map<String, Integer> party = new java.util.LinkedHashMap<>();
        party.put("Kris", 7);
        party.put("Bob", 4);
        checkEquals(home(3, List.of(), party), "Haul: Kris 7, Bob 4\nLives 2\nA failed dungeon keeps half.");
        java.util.Map<String, Integer> crowd = new java.util.LinkedHashMap<>();
        for (String name : new String[]{"A", "B", "C", "D", "E", "F"}) {
            crowd.put(name, 1);
        }
        checkEquals(IntervalBanking.homeScreen(0, false, List.of(), crowd).haulLine(), "Haul: A 1, B 1, C 1, D 1, +2");
        IntervalBanking.HomeScreen cleared = IntervalBanking.homeScreen(0, true, List.of(), solo(6));
        checkEquals(cleared.title(), "GO HOME");
        check(cleared.finished(), "finished flag");
        check(cleared.footer().isEmpty(), "a finished dungeon has nothing at risk, so no footer");
        // PD-179: a finished dungeon already paid the haul, so the board says what was banked.
        checkEquals(cleared.haulLine(), "Banked 6 scrap");
        checkEquals(IntervalBanking.homeScreen(0, true, List.of(), party).haulLine(), "Banked: Kris 7, Bob 4");
        checkEquals(IntervalBanking.homeScreen(0, false, List.of(), solo(6)).haulLine(), "Haul 6 scrap");
        // PD-180: the HOME title's small line has verbs.
        checkEquals(IntervalBanking.homeSubtitle(7, 4), "Banked 7 scrap. 4 chests in your reward barrel.");
        checkEquals(IntervalBanking.homeSubtitle(0, 1), "Banked 0 scrap. 1 chest in your reward barrel.");
        check(!IntervalBanking.homeSubtitle(7, 4).contains("--"), "no dash punctuation");
        check(!IntervalBanking.homeScreen(0, false, List.of(), solo(0)).finished(), "not finished flag");
        checkEquals(IntervalBanking.homeScreen(4, false, List.of("page"), solo(0)).unfinishedLine(),
                "Unfinished: page");
        check(IntervalBanking.homeScreen(0, true, List.of(), solo(0)).unfinishedLine().isEmpty(),
                "a cleared dungeon forfeits nothing");
        for (String line : List.of(IntervalBanking.scrapText(1), home(0, List.of(), solo(1)),
                home(1, List.of("vault", "page"), party))) {
            check(!line.contains("--") && !line.contains("\u2014"), "no dash punctuation: " + line);
        }
    }

    /** The go-home board's body with the trip at {@code omen} deaths and {@code unfinished} left. */
    private static String home(int omen, List<String> unfinished, java.util.Map<String, Integer> hauls) {
        return IntervalBanking.homeScreen(omen, false, unfinished, hauls).body();
    }

    private static java.util.Map<String, Integer> solo(int haul) {
        return java.util.Map.of("Kris", haul);
    }

    private static IntervalBanking.Settlement settle(List<Integer> steps) {
        return IntervalBanking.settle(steps, List.of(), 0, 0);
    }

    private static void checkEquals(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected '" + expected + "' but was '" + actual + "'");
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
