package fortune.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The machine's day. A day runs from {@code rolloverHourUtc} to the same hour the next day, and is named by the UTC
 * date it starts on: with an 08:00 rollover, 07:59 UTC on the 4th is still the 3rd. Days are epoch day numbers so
 * gaps are plain subtraction.
 */
public final class Calendar {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    private Calendar() {
    }

    /** The machine day an instant falls on. */
    public static long dayOf(long epochMillis, int rolloverHourUtc) {
        return Math.floorDiv(epochMillis - rolloverHourUtc * HOUR, DAY);
    }

    /** The instant the given machine day starts. */
    public static long startOf(long day, int rolloverHourUtc) {
        return day * DAY + rolloverHourUtc * HOUR;
    }

    /** Milliseconds until the next day starts. */
    public static long millisToNextDay(long epochMillis, int rolloverHourUtc) {
        return startOf(dayOf(epochMillis, rolloverHourUtc) + 1, rolloverHourUtc) - epochMillis;
    }

    /** yyyy-MM-dd for the day, as used in the payout run id. */
    public static String iso(long day) {
        return LocalDate.ofEpochDay(day).toString();
    }

    /** A short countdown such as {@code 6 h 12 min} or {@code 4 min}. */
    public static String countdown(long millis) {
        long minutes = Math.max(1, (millis + 59_999) / 60_000);
        long hours = minutes / 60;
        long rest = minutes % 60;
        return hours > 0 ? hours + " h " + rest + " min" : rest + " min";
    }

    /** A plain date such as {@code 12 Nov}, for "Your streak is safe until". */
    public static String shortDate(long day) {
        LocalDate date = LocalDate.ofEpochDay(day);
        String month = date.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH);
        return date.getDayOfMonth() + " " + month;
    }

    /** UTC timestamp text for the spin log. */
    public static String utc(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).toString();
    }
}
