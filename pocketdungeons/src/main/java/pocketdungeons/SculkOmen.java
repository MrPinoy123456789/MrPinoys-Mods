package pocketdungeons;

/**
 * The pure rules of the Ancient City's stealth (design section 11).
 * No Minecraft imports, so {@code CapstoneRulesTest} runs with plain {@code javac}.
 *
 * <p>Everywhere else a sculk sensor needs five pulses before a wave answers
 * and only the rooms that declare {@code pressure: "omen"} are armed. On an
 * Ancient City floor every sculk sensor and shrieker is armed whatever the
 * room says and every pulse answers. On the final floor the pulses are
 * counted as room answers: the {@code ancientWardenAnswers}-th summons a real vanilla Warden, once.
 * It is the only Warden the mod ever spawns.
 */
final class SculkOmen {

    private SculkOmen() {}

    /** The Ancient City dungeon, also the id of its main theme. */
    static final String ANCIENT_CITY = "ancient_city";

    /** Sensor pulses per wave outside the Ancient City. */
    static final int NORMAL_PULSES_PER_WAVE = 5;
    /** Sensor pulses per wave inside the Ancient City: every activation answers. */
    static final int ANCIENT_PULSES_PER_WAVE = 1;
    /** The Ancient City's final-floor answers that wake the Warden (default; the knob is ancientWardenAnswers). */
    static final int WARDEN_ANSWERS = 2;

    /** The Heard meter after {@code pulses} more sensor pulses (design pass 2026-10-09, Q3). */
    static int heardAfter(int heard, int pulses) {
        return Math.max(0, heard) + Math.max(0, pulses);
    }

    /** Whether a room whose meter reads {@code heard} of {@code max} answers now. */
    static boolean answers(int heard, int max) {
        return max > 0 && heard >= max;
    }

    /** The meter's ceiling for a room: shorter in the Ancient City, where every sound counts. */
    static int heardMax(boolean ancientCity) {
        return ancientCity ? PocketDungeonsConfig.sculkHeardMaxAncient() : PocketDungeonsConfig.sculkHeardMax();
    }

    /**
     * Whether crossing a sculk room unheard pays: it has a spawner, the spawner is cleared, the room never
     * answered, and it has not paid yet.
     */
    static boolean unheardPays(boolean hadSpawner, boolean spawnerCleared, boolean everHeard, boolean alreadyPaid) {
        return hadSpawner && spawnerCleared && !everHeard && !alreadyPaid;
    }

    /** Whether {@code id} (a bare or namespaced dungeon or theme id) is the Ancient City. */
    static boolean isAncientCity(String id) {
        return id != null && ANCIENT_CITY.equals(bare(id));
    }

    /** Whether every sculk sensor and shrieker on a floor of {@code themeId} is armed, room metadata aside. */
    static boolean armsAllSculk(String themeId) {
        return isAncientCity(themeId);
    }

    /**
     * Whether the Warden should be summoned now: an Ancient City dungeon, its
     * final floor, at least {@code ancientWardenAnswers} room answers counted, and
     * none summoned yet this floor.
     */
    static boolean shouldSummonWarden(boolean ancientCity, boolean finalFloor, int answers,
                                      boolean alreadySummoned) {
        return ancientCity && finalFloor && !alreadySummoned
                && answers >= PocketDungeonsConfig.ancientWardenAnswers();
    }

    /** Whether a Warden may be spawned for a dungeon at all: only the Ancient City ever does. */
    static boolean wardenAllowed(String dungeonId) {
        return isAncientCity(dungeonId);
    }

    private static String bare(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
