package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-JDK regression for {@link PackHalf}: a stack keeps its share rounded down, the single items keep
 * every other one best tier first, and the choice among the singles is seeded by the trip salt so a retry
 * cannot reroll it (decision 2026-10-18, rule 6).
 */
public class PackHalfTest {

    public static void main(String[] args) {
        testStacksRoundDown();
        testSinglesKeepEveryOther();
        testSeedPinsTheChoice();
        testOrderIsBestTierFirst();
        testOtherPercents();
        testNeverMoreThanTheCount();
        System.out.println("PackHalfTest passed");
    }

    private static PackHalf.Entry stack(int index, String id, int count, int max, int tier) {
        return new PackHalf.Entry(index, id, count, max, tier);
    }

    private static void testStacksRoundDown() {
        List<PackHalf.Entry> pack = List.of(
                stack(0, "cobblestone", 64, 64, 0),
                stack(1, "torch", 7, 64, 0),
                stack(2, "arrow", 2, 64, 0),
                stack(3, "bread", 3, 64, 0));
        int[] kept = PackHalf.keepCounts(pack, 0L, 50);
        check(kept[0], 32, "a full stack keeps half");
        check(kept[1], 3, "seven keeps three");
        check(kept[2], 1, "two keeps one");
        check(kept[3], 1, "three keeps one: rounded down");
    }

    private static void testSinglesKeepEveryOther() {
        List<PackHalf.Entry> singles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            singles.add(stack(i, "tool_" + i, 1, 1, 6 - i));
        }
        int total = 0;
        int[] kept = PackHalf.keepCounts(singles, 1L, 50);
        for (int k : kept) {
            total += k;
        }
        check(total, 3, "six singles keep three");
        check(kept[0], 1, "odd seed keeps the best first");
        check(kept[1], 0, "then skips one");
        check(kept[2], 1, "then keeps the next");
        check(kept[3], 0, "and skips");
        check(kept[4], 1, "and keeps");
        check(kept[5], 0, "and skips the worst");
        int[] other = PackHalf.keepCounts(singles, 0L, 50);
        check(other[0], 0, "even seed skips the best");
        check(other[1], 1, "and keeps the second");
        check(other[5], 1, "and the sixth");
        check(PackHalf.keepCounts(List.of(stack(0, "sword", 1, 1, 3)), 1L, 50)[0], 1,
                "a lone single is kept on an odd seed");
        check(PackHalf.keepCounts(List.of(stack(0, "sword", 1, 1, 3)), 2L, 50)[0], 0,
                "and lost on an even seed: the seed decides");
    }

    private static void testSeedPinsTheChoice() {
        List<PackHalf.Entry> pack = List.of(
                stack(0, "iron_sword", 1, 1, 3), stack(1, "stone_axe", 1, 1, 2), stack(2, "oak_log", 1, 64, 0),
                stack(3, "shield", 1, 1, 1), stack(4, "cobblestone", 33, 64, 0));
        int[] first = PackHalf.keepCounts(pack, 987654321L, 50);
        for (int i = 0; i < 20; i++) {
            int[] again = PackHalf.keepCounts(pack, 987654321L, 50);
            for (int j = 0; j < first.length; j++) {
                check(again[j], first[j], "the same salt always gives the same answer");
            }
        }
        check(PackHalf.keepCounts(pack, -7L, 50).length, 5, "a negative salt is fine");
        check(PackHalf.keepCounts(List.of(), 5L, 50).length, 0, "an empty pack keeps an empty list");
    }

    private static void testOrderIsBestTierFirst() {
        // Same items in a different order of the pack: the rank among the singles, not the slot, decides.
        List<PackHalf.Entry> a = List.of(
                stack(0, "wooden_sword", 1, 1, 1), stack(1, "diamond_sword", 1, 1, 5), stack(2, "iron_sword", 1, 1, 3));
        List<PackHalf.Entry> b = List.of(
                stack(0, "iron_sword", 1, 1, 3), stack(1, "wooden_sword", 1, 1, 1), stack(2, "diamond_sword", 1, 1, 5));
        int[] keptA = PackHalf.keepCounts(a, 1L, 50);
        int[] keptB = PackHalf.keepCounts(b, 1L, 50);
        check(keptA[1], 1, "the diamond sword is the best, kept first");
        check(keptA[2], 0, "the iron sword is skipped");
        check(keptA[0], 1, "the wooden sword is kept third");
        check(keptB[2], 1, "the diamond sword is kept wherever it sits");
        check(keptB[0], 0, "the iron sword is skipped wherever it sits");
        check(keptB[1], 1, "and the wooden sword is kept");
        // Equal tiers fall back to the id, so the order is still total.
        List<PackHalf.Entry> ties = List.of(stack(0, "b_axe", 1, 1, 2), stack(1, "a_axe", 1, 1, 2));
        int[] kept = PackHalf.keepCounts(ties, 1L, 50);
        check(kept[1], 1, "equal tiers: the id order puts a_axe first");
        check(kept[0], 0, "and b_axe second");
    }

    private static void testOtherPercents() {
        List<PackHalf.Entry> pack = List.of(stack(0, "cobblestone", 10, 64, 0), stack(1, "a", 1, 1, 1),
                stack(2, "b", 1, 1, 1), stack(3, "c", 1, 1, 1), stack(4, "d", 1, 1, 1));
        int[] all = PackHalf.keepCounts(pack, 3L, 100);
        check(all[0], 10, "100 percent keeps the whole stack");
        for (int i = 1; i < 5; i++) {
            check(all[i], 1, "100 percent keeps every single");
        }
        int[] none = PackHalf.keepCounts(pack, 3L, 0);
        for (int k : none) {
            check(k, 0, "0 percent keeps nothing");
        }
        check(PackHalf.keepCounts(pack, 3L, 150)[0], 10, "above 100 clamps to everything");
        check(PackHalf.keepCounts(pack, 3L, -20)[0], 0, "below 0 clamps to nothing");
        int[] quarter = PackHalf.keepCounts(pack, 3L, 25);
        check(quarter[0], 2, "25 percent of ten rounds down to two");
        int singlesKept = 0;
        for (int i = 1; i < 5; i++) {
            singlesKept += quarter[i];
        }
        check(singlesKept, 1, "25 percent of four singles keeps one");
    }

    private static void testNeverMoreThanTheCount() {
        List<PackHalf.Entry> pack = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            pack.add(stack(i, "item_" + i, 1 + i % 7, i % 3 == 0 ? 1 : 64, i % 5));
        }
        for (int percent = 0; percent <= 100; percent += 10) {
            for (long salt = -3; salt < 150; salt += 13) {
                int[] kept = PackHalf.keepCounts(pack, salt, percent);
                for (int i = 0; i < kept.length; i++) {
                    if (kept[i] < 0 || kept[i] > pack.get(i).count()) {
                        throw new AssertionError("kept " + kept[i] + " of " + pack.get(i).count()
                                + " at " + percent + " percent");
                    }
                }
            }
        }
    }

    private static void check(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
