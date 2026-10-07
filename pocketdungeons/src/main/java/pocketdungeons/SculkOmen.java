package pocketdungeons;

/**
 * The pure rules of the Ancient City's stealth (design section 11).
 * No Minecraft imports, so {@code CapstoneRulesTest} runs with plain {@code javac}.
 *
 * <p>Everywhere else a sculk sensor needs five pulses before a wave answers
 * and only the rooms that declare {@code pressure: "omen"} are armed. On an
 * Ancient City floor every sculk sensor and shrieker is armed whatever the
 * room says and every pulse answers. On the final floor the pulses are
 * counted: the {@link #WARDEN_PULSES}-th summons a real vanilla Warden, once.
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
    /** The final-floor pulse count that wakes the Warden. */
    static final int WARDEN_PULSES = 4;

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
     * final floor, at least {@link #WARDEN_PULSES} sensor pulses counted, and
     * none summoned yet this floor.
     */
    static boolean shouldSummonWarden(boolean ancientCity, boolean finalFloor, int pulses,
                                      boolean alreadySummoned) {
        return ancientCity && finalFloor && !alreadySummoned && pulses >= WARDEN_PULSES;
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
