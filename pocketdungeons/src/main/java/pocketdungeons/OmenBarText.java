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
     * What going home would pay right now. Omen no longer reduces chests or
     * keystone progress; it only makes the dungeon deadlier. The band still
     * colours the bar and the kit refill at home.
     */
    static String outcome(int band, int chests) {
        // Playtest 2026-09-27: "key climbs" was jargon; say what it means.
        return chests + (chests == 1 ? " reward chest, " : " reward chests, ") + "key progress";
    }

    /** {@link #outcome(int, int)} with the base chest count and no depth bonus. */
    static String outcome(int band) {
        return outcome(band, Omen.baseRewardChests());
    }

    /**
     * The bar during a floor: the spawner gate first, then the floor's omen
     * (hidden at 0), then the chests the floor would pay. The bar's colour
     * and fill carry the band. The top band warns that one more death ends
     * the run.
     */
    static String activeTitle(int floorOmen, int band, int chests, int spawnersCleared, int spawnersTotal,
                              int spawnersNeeded) {
        // Playtest 2026-09-26 (A1): the title was too long to read, and the one
        // thing the player tracks is the spawner gate. It leads now; the omen
        // follows with the chests it is worth, and the bar's colour and fill
        // carry the band.
        StringBuilder title = new StringBuilder();
        if (spawnersTotal > 0) {
            int needed = Math.max(1, spawnersNeeded);
            int cleared = Math.max(0, spawnersCleared);
            int remaining = Math.max(0, needed - cleared);
            title.append(remaining == 0 ? "Spawners done"
                    : remaining + " Spawner" + (remaining == 1 ? "" : "s") + " remaining");
        } else {
            title.append("Spawners done");
        }
        int omen = Omen.clamp(floorOmen);
        if (omen > 0) {
            title.append(" | Omen ").append(omen).append('/').append(Omen.MAX_OMEN);
        }
        title.append(" | ").append(chests).append(chests == 1 ? " chest" : " chests");
        if (band >= 2) {
            title.append(" | one more fall ends the run");
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
        return clearedHeadline(floorsCleared, floorsPerSafeVisit, mine) + " | " + outcome(band, chests);
    }

    /** The floor half of {@link #clearedTitle}, also the on-screen title a floor clear shows. */
    static String clearedHeadline(int floorsCleared, int floorsPerSafeVisit, boolean mine) {
        if (mine) {
            return "Mine floor " + floorsCleared + " cleared";
        }
        if (floorsCleared > Math.max(1, floorsPerSafeVisit)) {
            return "Floor " + floorsCleared + " cleared, deep";
        }
        return "Floor " + floorsCleared + " of " + Math.max(1, floorsPerSafeVisit) + " cleared";
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
            case DWELL -> "The walls notice you lingering; more enemies are coming.";
            case SENSOR -> "The sculk counts your steps; the next fight will be harder.";
            case SHRIEK -> "Something below heard that; it is sending company.";
            case BARGAIN -> "The bargain is struck; the dungeon leans closer.";
            case DEPTH -> "This deep, the dungeon is already watching; expect heavier guards.";
            case SILENCE -> "The silence hears you use that; the next fight will be harder.";
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
            case SILENCE -> 20 * 2;
            case BARGAIN, DEPTH -> 0;
        };
    }

    /**
     * Game ticks a cue's action bar line is kept on screen after the rise. A
     * lone overlay message fades in about three seconds, and the sensor line
     * is the one that explains a mechanic, so it is repainted until the player
     * has had time to read it (playtest 2026-10-01, PD-100).
     */
    static int holdTicks(Omen.Source source) {
        return switch (source) {
            case SENSOR -> 20 * 8;
            default -> 0;
        };
    }
}
