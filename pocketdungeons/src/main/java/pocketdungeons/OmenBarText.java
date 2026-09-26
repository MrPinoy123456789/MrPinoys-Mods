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

    /**
     * What the band pays: the floor's completion chests, and whether the
     * cleared floors' door steps bank toward the key when the party goes home
     * ({@code "3 chests, key climbs"}) or bank nothing
     * ({@code "1 chest, key stalls"}). How many levels that is depends on
     * each member's own carried progress, so the bar does not claim a number.
     */
    static String outcome(int band, int chests) {
        return chests + (chests == 1 ? " chest, " : " chests, ")
                + (Omen.levelChange(band) > 0 ? "key climbs" : "key stalls");
    }

    /** {@link #outcome(int, int)} with the band's own chest count and no depth bonus. */
    static String outcome(int band) {
        return outcome(band, Omen.chestCount(band));
    }

    /**
     * The bar during a floor: the floor's omen out of four, the interval's
     * band so far with the chests the floor would pay, and the completion
     * gate. {@code spawnersTotal} below zero means the watcher has not read
     * the floor yet, and zero means it has no spawners; the gate part is left
     * off either way.
     */
    static String activeTitle(int floorOmen, int band, int chests, int spawnersCleared, int spawnersTotal,
                              int spawnersNeeded) {
        StringBuilder title = new StringBuilder()
                .append("Omen ").append(Omen.clamp(floorOmen)).append('/').append(Omen.MAX_OMEN)
                .append(" | ").append(outcome(band, chests));
        if (spawnersTotal > 0) {
            title.append(" | Spawners ").append(Math.max(0, spawnersCleared)).append('/')
                    .append(spawnersTotal).append(", need ").append(spawnersNeeded);
        }
        return title.toString();
    }

    /**
     * The bar between floors: which floor of the interval was just cleared
     * and the band it stands in. The interval length is the usual stopping
     * point, not a wall, so a floor past it reads as deep rather than "4 of
     * 3". A Mine interval has no usual length and counts floors alone.
     */
    static String clearedTitle(int floorsCleared, int floorsPerSafeVisit, boolean mine, int band, int chests) {
        String floor;
        if (mine) {
            floor = "Mine floor " + floorsCleared + " cleared";
        } else if (floorsCleared > Math.max(1, floorsPerSafeVisit)) {
            floor = "Floor " + floorsCleared + " cleared, deep";
        } else {
            floor = "Floor " + floorsCleared + " of " + Math.max(1, floorsPerSafeVisit) + " cleared";
        }
        return floor + " | " + outcome(band, chests);
    }

    /**
     * The floor completion line's verdict, {@code "The omen sits uneasy: 2
     * chests, key climbs so far."} Nothing banks until the party goes home,
     * and any checkpoint can go on, so the verdict is always "so far".
     */
    static String completionVerdict(int band, int chests) {
        return "The omen sits " + bandName(band) + ": " + outcome(band, chests) + " so far.";
    }

    /** The door screen's floor line for the floor a door would open. */
    static String previewFloor(int nextFloor, int floorsPerSafeVisit, boolean mine) {
        if (mine) {
            return "MINE FLOOR " + nextFloor;
        }
        return nextFloor > Math.max(1, floorsPerSafeVisit)
                ? "FLOOR " + nextFloor + ", DEEP"
                : "FLOOR " + nextFloor + " OF " + Math.max(1, floorsPerSafeVisit);
    }

    /** The action bar line when {@code source} raises the omen to {@code omen}. */
    static String riseLine(Omen.Source source, int omen) {
        String line = switch (source) {
            case DWELL -> "The walls notice you lingering.";
            case SENSOR -> "The sculk counts your steps.";
            case SHRIEK -> "Something below heard that.";
            case BARGAIN -> "The bargain is struck. Something leans closer.";
            case DEPTH -> "This deep, the dungeon is already watching.";
        };
        return line + " Omen " + Omen.clamp(omen) + "/" + Omen.MAX_OMEN + ".";
    }

    /**
     * Game ticks between two cues from the same source on one instance. Dwell
     * can tick for every member in turn, and a sensor room pulses in bursts,
     * so both are held back; a shriek is rare and loud enough to earn its
     * own line, and a bargain or a floor's head start happens once.
     */
    static int cueCooldownTicks(Omen.Source source) {
        return switch (source) {
            case DWELL -> 20 * 30;
            case SENSOR -> 20 * 10;
            case SHRIEK -> 20 * 3;
            case BARGAIN, DEPTH -> 0;
        };
    }
}
