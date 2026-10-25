package charter.chronicle;

import charter.time.Durations;

/**
 * Turns a time into words like "the second week of Frostfall". A year is four
 * seasons of four weeks of seven days (112 days). The year number is spoken only
 * after the first year ("the second week of Frostfall, year 2").
 *
 * <p>It has no clock of its own: the caller says which millisecond is the start
 * of year one, and how long a day lasts. Wall time uses the real day; a siege
 * time caller can pass a shorter day to match its time scale.
 */
public final class Calendar {

    public static final String[] SEASONS = {"Seedtime", "Highsun", "Harvest", "Frostfall"};
    private static final String[] ORDINALS = {"first", "second", "third", "fourth"};
    private static final int DAYS_PER_WEEK = 7;
    private static final int WEEKS_PER_SEASON = 4;
    private static final int DAYS_PER_SEASON = DAYS_PER_WEEK * WEEKS_PER_SEASON;
    private static final int DAYS_PER_YEAR = DAYS_PER_SEASON * SEASONS.length;

    private final long origin;
    private final long dayMillis;

    public Calendar(long origin, long dayMillis) {
        if (dayMillis <= 0) throw new IllegalArgumentException("a day must last longer than 0");
        this.origin = origin;
        this.dayMillis = dayMillis;
    }

    /** Real days, with year one starting at the given time. */
    public static Calendar startingAt(long origin) {
        return new Calendar(origin, Durations.DAY);
    }

    public int year(long time) {
        return (int) (dayIndex(time) / DAYS_PER_YEAR) + 1;
    }

    public String season(long time) {
        return SEASONS[(int) (dayIndex(time) % DAYS_PER_YEAR / DAYS_PER_SEASON)];
    }

    /** 1 to 4. */
    public int week(long time) {
        return (int) (dayIndex(time) % DAYS_PER_SEASON / DAYS_PER_WEEK) + 1;
    }

    /** "the second week of Frostfall", with ", year 2" after the first year. Times before the origin count as the first week. */
    public String phrase(long time) {
        String base = "the " + ORDINALS[week(time) - 1] + " week of " + season(time);
        int year = year(time);
        return year > 1 ? base + ", year " + year : base;
    }

    private long dayIndex(long time) {
        return Math.max(0, time - origin) / dayMillis;
    }
}
