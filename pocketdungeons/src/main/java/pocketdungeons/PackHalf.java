package pocketdungeons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The half rule of the pack and the pot (decision 2026-10-18, rule 6), with no Minecraft in it so a plain
 * JVM can pin it. A pot whose floor ends unemptied keeps a share of its items, and a failed dungeon keeps
 * the same share of every pot and every member's pack; the share is a percent (50 by default).
 *
 * <p>Each stack of two or more keeps {@code count * percent / 100}, rounded down. The single items left
 * over (a stack of one, or an item that does not stack) cannot be divided, so they are sorted best tier
 * first and the share is spread along that order: at 50 percent every other one is kept. Which end the
 * spread starts from is seeded by the trip's salt, so a retry of the same settlement keeps the same
 * items and cannot be rerolled.
 */
final class PackHalf {

    private PackHalf() {}

    /**
     * One stack as the rule sees it: where it sits in the list handed in, what it is, how many, how many
     * fit in a stack, and its tier (higher is better).
     */
    record Entry(int index, String id, int count, int maxStack, int tier) {}

    /**
     * How many of each entry's items are kept, aligned with {@code entries} (the same position, not the
     * {@link Entry#index}). A value is never below 0 and never above the entry's count. {@code salt} seeds
     * the choice among the singles; the same inputs always give the same answer.
     */
    static int[] keepCounts(List<Entry> entries, long salt, int percent) {
        int share = Math.max(0, Math.min(100, percent));
        int[] kept = new int[entries.size()];
        List<Integer> singles = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.count() <= 0) {
                continue;
            }
            if (isSingle(entry)) {
                singles.add(i);
            } else {
                kept[i] = (int) ((long) entry.count() * share / 100);
            }
        }
        // Best tier first; ties fall back to the id and then the original position, so the order never
        // depends on how the inventory happened to be arranged beyond the position itself.
        singles.sort(Comparator
                .comparingInt((Integer i) -> -entries.get(i).tier())
                .thenComparing(i -> entries.get(i).id())
                .thenComparingInt(i -> entries.get(i).index())
                .thenComparingInt(i -> i));
        int offset = (int) Math.floorMod(salt, 100L);
        for (int rank = 0; rank < singles.size(); rank++) {
            if ((rank + offset + 1) * share / 100 - (rank + offset) * share / 100 == 1) {
                kept[singles.get(rank)] = 1;
            }
        }
        return kept;
    }

    /**
     * Whether an entry is one of the single items: a stack of one, which is also every item that does not
     * stack (its {@link Entry#maxStack()} is 1, so it never holds more).
     */
    static boolean isSingle(Entry entry) {
        return entry.count() == 1;
    }
}
