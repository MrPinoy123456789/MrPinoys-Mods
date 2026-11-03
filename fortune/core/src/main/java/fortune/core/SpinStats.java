package fortune.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /fortune admin stats}, from the spin log: daily spinners for the last 14 days, D1 and D7 return rates, the
 * streak spread, Welcome Back spins and the Coin the stake drained. Retune from these numbers, not from guesses.
 */
public final class SpinStats {

    /** One line of {@code spins.jsonl}, as much of it as the stats need. */
    public record Entry(long day, String player, int streak, boolean welcomeBack, long stakeMarks) {
    }

    public record Report(Map<Long, Integer> spinnersByDay, double d1, double d7, Map<String, Integer> streakSpread,
                         int welcomeBacks, long stakeMarks, int stakedSpins) {
    }

    private SpinStats() {
    }

    public static Report of(List<Entry> entries, long today) {
        Map<Long, Set<String>> byDay = new HashMap<>();
        Map<String, Integer> latestStreak = new HashMap<>();
        Map<String, Long> latestDay = new HashMap<>();
        int welcomeBacks = 0;
        long marks = 0;
        int staked = 0;
        for (Entry e : entries) {
            byDay.computeIfAbsent(e.day(), d -> new HashSet<>()).add(e.player());
            if (latestDay.getOrDefault(e.player(), Long.MIN_VALUE) <= e.day()) {
                latestDay.put(e.player(), e.day());
                latestStreak.put(e.player(), e.streak());
            }
            if (e.day() > today - 14) {
                if (e.welcomeBack()) welcomeBacks++;
                if (e.stakeMarks() > 0) {
                    marks += e.stakeMarks();
                    staked++;
                }
            }
        }
        Map<Long, Integer> daily = new LinkedHashMap<>();
        for (long d = today - 13; d <= today; d++) daily.put(d, byDay.getOrDefault(d, Set.of()).size());

        double d1 = rate(byDay.getOrDefault(today - 1, Set.of()), byDay.getOrDefault(today, Set.of()));
        double d7 = rate(union(byDay, today - 13, today - 7), union(byDay, today - 6, today));

        Map<String, Integer> spread = new LinkedHashMap<>();
        spread.put("1", 0);
        spread.put("2 to 6", 0);
        spread.put("7 to 29", 0);
        spread.put("30+", 0);
        for (int s : latestStreak.values()) {
            String bucket = s >= 30 ? "30+" : s >= 7 ? "7 to 29" : s >= 2 ? "2 to 6" : "1";
            spread.merge(bucket, 1, Integer::sum);
        }
        return new Report(daily, d1, d7, spread, welcomeBacks, marks, staked);
    }

    /** The share of {@code before} also in {@code after}, or -1 when {@code before} is empty. */
    private static double rate(Set<String> before, Set<String> after) {
        if (before.isEmpty()) return -1;
        int back = 0;
        for (String p : before) if (after.contains(p)) back++;
        return back / (double) before.size();
    }

    private static Set<String> union(Map<Long, Set<String>> byDay, long from, long to) {
        Set<String> out = new HashSet<>();
        for (long d = from; d <= to; d++) out.addAll(byDay.getOrDefault(d, Set.of()));
        return out;
    }
}
