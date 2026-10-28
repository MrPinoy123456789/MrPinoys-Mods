package charter.chronicle;

import charter.time.Durations;

/**
 * Turns a time into words like "the second week of Eclipse", or with the day,
 * "day 3, the second week of Eclipse". A year is four seasons of four weeks of
 * seven days (112 days). The year number is spoken only after the first year
 * ("the second week of Eclipse, year 2"). The seasons have no effect on play.
 *
 * <p>It has no clock of its own: the caller says which millisecond is the start
 * of year one, and how long a day lasts. Wall time uses the real day; a siege
 * time caller can pass a shorter day to match its time scale.
 */
public final class Calendar {

    public static final String[] SEASONS = {"Bargains", "Bloodmoons", "Ragnarok", "Eclipse"};
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

    /** 1 to 7, the day within the week. */
    public int day(long time) {
        return (int) (dayIndex(time) % DAYS_PER_WEEK) + 1;
    }

    /** "day 3, the second week of Eclipse", with ", year 2" after the first year: entries in one week read differently. */
    public String dayPhrase(long time) {
        return "day " + day(time) + ", " + phrase(time);
    }

    /** A plain age for a short notice: "today", "yesterday", "3 days ago". Calendar days of this calendar's length. */
    public String ago(long now, long then) {
        long days = Math.max(0, now - then) / dayMillis;
        if (days == 0) return "today";
        if (days == 1) return "yesterday";
        return days + " days ago";
    }

    /** "the second week of Eclipse", with ", year 2" after the first year. Times before the origin count as the first week. */
    public String phrase(long time) {
        String base = "the " + ORDINALS[week(time) - 1] + " week of " + season(time);
        int year = year(time);
        return year > 1 ? base + ", year " + year : base;
    }

    private long dayIndex(long time) {
        return Math.max(0, time - origin) / dayMillis;
    }
}
