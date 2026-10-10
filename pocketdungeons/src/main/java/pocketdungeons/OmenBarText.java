package pocketdungeons;

/**
 * The words on the lives bar and the wave cues, with no Minecraft imports so
 * the formatting is testable with plain {@code javac}. {@link OmenBar} wraps
 * these in components; the arithmetic itself is {@link Omen}'s.
 *
 * <p>J3: the player tracks lives, {@code 5 - omen}. The omen never touches
 * the payout; it is danger only.
 *
 * <p>Kept short on purpose: a boss bar title is one line over the top of the
 * screen, and a long one crowds out the view.
 */
final class OmenBarText {

    private OmenBarText() {}

    /**
     * What going home would pay right now: the chest rolls into the reward
     * barrel; chart scrap is what a floor banks.
     */
    static String outcome(int chests) {
        return (chests == 1 ? "1 loot roll, " : chests + " loot rolls, ") + "chart scrap";
    }

    /** {@link #outcome(int)} with the base chest count. */
    static String outcome() {
        return outcome(Omen.baseRewardChests());
    }

    /** The omen at which one more death ends the run. */
    static final int WARN_OMEN = Omen.MAX_OMEN;

    /**
     * The bar's colour by the trip's omen: 0 and 1 green (0), 2 and 3 yellow
     * (1), 4 red (2). Four lives or more read green; one life reads red.
     */
    static int omenColourIndex(int omen) {
        int clamped = Omen.clamp(omen);
        return clamped <= 1 ? 0 : clamped < Omen.MAX_OMEN ? 1 : 2;
    }

    /** {@code "Lives 3"}: the risk meter's half of any bar title. */
    static String livesText(int omen) {
        return "Lives " + Omen.lives(omen);
    }

    /**
     * The bar during a floor: the spawner gate first, then the lives left.
     * {@code "Spawners 2/3 | Lives 3"}. A trip down to one life warns that
     * the next death ends the run.
     */
    static String activeTitle(int omen, int chests, int spawnersCleared, int spawnersTotal,
                              int spawnersNeeded) {
        // Playtest 2026-09-26 (A1): the title was too long to read, and the one
        // thing the player tracks is the spawner gate. It leads; lives follow
        // (J3: the risk meter is lives, 5 - omen).
        StringBuilder title = new StringBuilder();
        if (spawnersTotal > 0) {
            int needed = Math.max(1, spawnersNeeded);
            int cleared = Math.max(0, spawnersCleared);
            int remaining = Math.max(0, needed - cleared);
            title.append(remaining == 0 ? "Spawners done"
                    : "Spawners " + cleared + "/" + needed);
        } else {
            title.append("Spawners done");
        }
        title.append(" | ").append(livesText(omen));
        if (Omen.nextDeathFails(omen)) {
            title.append(" | one more fall ends the run");
        }
        return title.toString();
    }

    /**
     * The bar between floors: which floor of the dungeon was just cleared, what
     * going home pays, and the lives left. {@code dungeon} is the dungeon's
     * display name, empty outside a dungeon graph. A Mine trip has no usual
     * length and counts floors alone.
     */
    static String clearedTitle(int floorsCleared, String dungeon, boolean mine, boolean finished,
                               boolean finalAhead, int omen, int chests) {
        // PD-174: the loot outcome used to ride on this line and the whole thing ran off the
        // player's screen. The outcome is in the completion chat line; the bar keeps the floor
        // and the lives.
        return clearedHeadline(floorsCleared, dungeon, mine, finished, finalAhead)
                + " | " + livesText(omen);
    }

    /**
     * PD-174: the big on-screen title of a floor clear. The headline alone, without the
     * {@code ", final floor ahead"} tail, which is too wide at title size; the tail moves to
     * {@link #clearedSubtitle}.
     */
    static String clearedScreenTitle(int floorsCleared, String dungeon, boolean mine, boolean finished) {
        return clearedHeadline(floorsCleared, dungeon, mine, finished, false);
    }

    /** The line under the floor clear title: what the party can do next. */
    static String clearedSubtitle(boolean finished, boolean finalAhead) {
        if (finished) {
            return "LEAVE";
        }
        return finalAhead ? "Final floor ahead: GO HOME or DESCEND" : "GO HOME or DESCEND";
    }

    /**
     * The floor half of {@link #clearedTitle}, also the on-screen title a floor clear
     * shows: {@code "Floor 2 of Frostworks cleared"}, with {@code ", final floor
     * ahead"} one floor from the end and {@code "Frostworks cleared"} on the final
     * floor.
     */
    static String clearedHeadline(int floorsCleared, String dungeon, boolean mine, boolean finished,
                                  boolean finalAhead) {
        if (mine) {
            return "Mine floor " + floorsCleared + " cleared";
        }
        boolean named = dungeon != null && !dungeon.isBlank();
        if (finished) {
            return (named ? dungeon : "Dungeon") + " cleared";
        }
        String head = named ? "Floor " + floorsCleared + " of " + dungeon + " cleared"
                : "Floor " + floorsCleared + " cleared";
        return finalAhead ? head + ", final floor ahead" : head;
    }

    /**
     * The floor completion line's verdict, {@code "2 lives left: 3 loot
     * rolls, chart scrap so far."} Nothing banks until the party goes home,
     * and any checkpoint can go on, so the verdict is always "so far".
     */
    static String completionVerdict(int omen, int chests) {
        return Omen.lives(omen) + (Omen.lives(omen) == 1 ? " life" : " lives") + " left: "
                + outcome(chests) + " so far.";
    }

    /**
     * The door screen's floor line for the floor a door would open:
     * {@code "FROSTWORKS: FLOOR 3"} (PD-152, playtest 2026-10-05-1: the player reads it
     * dungeon first, and the keystone level does not belong on it), or
     * {@code "FROSTWORKS: FINAL FLOOR"} when the door leads to the dungeon's last floor.
     */
    static String previewFloor(int nextFloor, String dungeon, boolean mine, boolean finalFloor) {
        if (mine) {
            return "MINE FLOOR " + nextFloor;
        }
        if (dungeon == null || dungeon.isBlank()) {
            return finalFloor ? "FINAL FLOOR" : "FLOOR " + nextFloor;
        }
        String name = dungeon.toUpperCase();
        return name + (finalFloor ? ": FINAL FLOOR" : ": FLOOR " + nextFloor);
    }

    /** The action bar line when a death takes a life: {@code "A bad omen. 2 lives left."} */
    static String deathCue(int omen) {
        int lives = Omen.lives(omen);
        return "A bad omen. " + lives + (lives == 1 ? " life" : " lives") + " left.";
    }

    /** The action bar line when {@code source} sends a wave or takes its price. */
    static String cueLine(Omen.Source source) {
        return switch (source) {
            case DWELL -> "The walls notice you lingering; more enemies are coming.";
            case SENSOR -> "The sculk counts your steps; it is sending company.";
            case SHRIEK -> "Something below heard that; it is sending company.";
            case BARGAIN -> "The bargain is struck; the floor fights two levels harder.";
            case SILENCE -> "The silence hears you use that; it is sending company.";
            case VAULT -> "The vault's hoard buys off the dungeon's attention. A life back.";
            case DEATH -> "A bad omen.";
            case DOOR -> "The door takes a life.";
        };
    }

    /**
     * Game ticks between two cues from the same source on one instance. Dwell
     * can tick for every member in turn, and a sensor room pulses in bursts,
     * so both are held back; a shriek is rare and loud enough to earn its
     * own line, and a bargain or a death happens once.
     */
    static int cueCooldownTicks(Omen.Source source) {
        return switch (source) {
            case DWELL -> 20 * 30;
            case SENSOR -> 20 * 10;
            case SHRIEK -> 20 * 3;
            case SILENCE -> 20 * 2;
            case BARGAIN, VAULT, DEATH, DOOR -> 0;
        };
    }

    /**
     * Game ticks a cue's action bar line is kept on screen after the trigger. A
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
