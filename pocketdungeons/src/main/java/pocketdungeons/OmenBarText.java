package pocketdungeons;

/**
 * The words on the omen bar and the omen cues, with no Minecraft imports so
 * the formatting is testable with plain {@code javac}. {@link OmenBar} wraps
 * these in components; the band arithmetic itself is {@link Omen}'s.
 *
 * <p>Kept short on purpose: a boss bar title is one line over the top of the
 * screen, and a long one crowds out the view.
 */
final class OmenBarText {

    private OmenBarText() {}

    /** The band as a mood, for the completion line: calm, uneasy, dire. */
    static String bandName(int band) {
        return switch (band) {
            case 0 -> "calm";
            case 1 -> "uneasy";
            default -> "dire";
        };
    }

    /** What the band pays at the safe visit: {@code "3 chests, +1 level"}. */
    static String outcome(int band) {
        int chests = Omen.chestCount(band);
        int levels = Omen.levelChange(band);
        return chests + (chests == 1 ? " chest, +" : " chests, +") + levels
                + (levels == 1 ? " level" : " levels");
    }

    /**
     * The bar during a floor: the floor's omen out of four, the interval's
     * band so far, and the completion gate. {@code spawnersTotal} below zero
     * means the watcher has not read the floor yet, and zero means it has no
     * spawners; the gate part is left off either way.
     */
    static String activeTitle(int floorOmen, int band, int spawnersCleared, int spawnersTotal,
                              int spawnersNeeded) {
        StringBuilder title = new StringBuilder()
                .append("Omen ").append(Omen.clamp(floorOmen)).append('/').append(Omen.MAX_OMEN)
                .append(" | ").append(outcome(band));
        if (spawnersTotal > 0) {
            title.append(" | Spawners ").append(Math.max(0, spawnersCleared)).append('/')
                    .append(spawnersTotal).append(", need ").append(spawnersNeeded);
        }
        return title.toString();
    }

    /**
     * The bar between floors: which floor of the interval was just cleared
     * and the band it stands in. A Mine interval has no fixed length, so it
     * counts floors without a total.
     */
    static String clearedTitle(int floorsCleared, int floorsPerSafeVisit, boolean mine, int band) {
        String floor = mine
                ? "Mine floor " + floorsCleared + " cleared"
                : "Floor " + floorsCleared + " of " + Math.max(1, floorsPerSafeVisit) + " cleared";
        return floor + " | " + outcome(band);
    }

    /**
     * The floor completion line's verdict, {@code "The omen sits uneasy: 2
     * chests, +1 level."} The level is only settled at the safe visit, so a
     * floor that does not end the interval says {@code "so far"}.
     */
    static String completionVerdict(int band, boolean endsInterval) {
        return "The omen sits " + bandName(band) + ": " + outcome(band)
                + (endsInterval ? "." : " so far.");
    }

    /** The door screen's floor line for the floor a door would open. */
    static String previewFloor(int nextFloor, int floorsPerSafeVisit, boolean mine) {
        return mine ? "MINE FLOOR " + nextFloor
                : "FLOOR " + nextFloor + " OF " + Math.max(1, floorsPerSafeVisit);
    }

    /** The action bar line when {@code source} raises the omen to {@code omen}. */
    static String riseLine(Omen.Source source, int omen) {
        String line = switch (source) {
            case DWELL -> "The walls notice you lingering.";
            case SENSOR -> "The sculk counts your steps.";
            case SHRIEK -> "Something below heard that.";
            case BARGAIN -> "The bargain is struck. Something leans closer.";
        };
        return line + " Omen " + Omen.clamp(omen) + "/" + Omen.MAX_OMEN + ".";
    }

    /**
     * Game ticks between two cues from the same source on one instance. Dwell
     * can tick for every member in turn, and a sensor room pulses in bursts,
     * so both are held back; a shriek is rare and loud enough to earn its
     * own line, and a bargain happens once.
     */
    static int cueCooldownTicks(Omen.Source source) {
        return switch (source) {
            case DWELL -> 20 * 30;
            case SENSOR -> 20 * 10;
            case SHRIEK -> 20 * 3;
            case BARGAIN -> 0;
        };
    }
}
