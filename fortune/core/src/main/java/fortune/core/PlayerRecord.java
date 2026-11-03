package fortune.core;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's line in {@code <world>/fortune/players.json}. Plain fields so the fabric module can read and write it
 * with Gson. A spin's result is written here, with {@link #payPending} set, before anything is paid or animated.
 */
public final class PlayerRecord {

    /** The last name seen, for the Annals and stats. */
    public String name = "";
    /** The machine day of the last claim, or -1 for never. */
    public long lastClaimDay = -1;
    /** Claims made in the current week, 0 to 6. The next claim is day {@code weekPosition + 1}. */
    public int weekPosition;
    /** The headline symbol of each claim this week, by symbol id, for the week strip. */
    public List<String> weekSymbols = new ArrayList<>();
    public int streak;
    public int bestStreak;
    public int totalClaims;
    /** The stake tier chosen last time ("none" or a tier id). Remembered, never charged until the lever is pulled. */
    public String stakeTier = "none";
    /** Ticks played before the first spin, counted while {@link #totalClaims} is 0. */
    public long ticksBeforeFirstSpin;

    // ---- the last spin, so reopening the machine shows it and a crash mid payout can finish it ----

    public String lastLine = "";
    public List<String> lastPayline = new ArrayList<>();
    public int lastLevel;
    public boolean lastGolden;
    public boolean lastWelcomeBack;
    /** The spin's reward has been decided and saved but not yet handed over. Finished on the next join or start. */
    public boolean payPending;
    public List<Settings.Extra> pendingExtras = new ArrayList<>();
    public boolean pendingKeepsake;
    public int pendingMilestone;

    // ---- not built yet ----

    /** TODO First Week (spec 5.3). */
    public int firstWeekPosition;
    /** TODO Second Wind (spec 5.4): the day it was last used. */
    public long secondWindDay = -1;

    public boolean claimedOn(long day) {
        return lastClaimDay >= day;
    }
}
