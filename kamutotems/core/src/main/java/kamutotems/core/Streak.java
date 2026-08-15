package kamutotems.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public final class Streak {

    public static StreakState onComplete(StreakState prev, String todayKey) {
        if (todayKey == null) {
            return prev == null ? new StreakState(0, null, List.of()) : prev;
        }
        if (prev == null) {
            return new StreakState(1, todayKey, List.of());
        }
        if (todayKey.equals(prev.lastCompletedDateKey())) {
            return prev;
        }
        if (prev.lastCompletedDateKey() == null) {
            return new StreakState(prev.streak() + 1, todayKey, prev.graceUsedDateKeys());
        }

        try {
            LocalDate today = LocalDate.parse(todayKey);
            LocalDate last = LocalDate.parse(prev.lastCompletedDateKey());
            if (!today.isAfter(last)) {
                return new StreakState(1, todayKey, prev.graceUsedDateKeys());
            }
            return new StreakState(prev.streak() + 1, todayKey, prev.graceUsedDateKeys());
        } catch (Exception e) {
            return new StreakState(1, todayKey, prev.graceUsedDateKeys());
        }
    }

    public static StreakState onMissedDay(StreakState prev, String missedKey) {
        if (prev == null) {
            return new StreakState(0, null, List.of(missedKey));
        }
        if (missedKey == null) {
            return prev;
        }

        LocalDate missed;
        try {
            missed = LocalDate.parse(missedKey);
        } catch (Exception e) {
            return new StreakState(0, prev.lastCompletedDateKey(), List.of(missedKey));
        }

        for (String g : prev.graceUsedDateKeys()) {
            try {
                LocalDate gd = LocalDate.parse(g);
                long days = Math.abs(ChronoUnit.DAYS.between(gd, missed));
                if (days >= 0 && days < 7) {
                    return new StreakState(0, prev.lastCompletedDateKey(), prev.graceUsedDateKeys());
                }
            } catch (Exception ignored) {
            }
        }

        List<String> grace = new ArrayList<>(prev.graceUsedDateKeys());
        grace.add(missedKey);
        return new StreakState(prev.streak(), prev.lastCompletedDateKey(), List.copyOf(grace));
    }

    public static int rewardDiamonds(int streak, int base, int perDay, int cap) {
        int v = base + streak * perDay;
        return v > cap ? cap : v;
    }
}
