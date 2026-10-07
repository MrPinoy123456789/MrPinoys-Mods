package pocketdungeons;

/**
 * Dungeon structure W7a: the pure rules of the Ancient City's stealth (design section 11).
 * No Minecraft imports, so {@code CapstoneRulesTest} runs with plain {@code javac}.
 *
 * <p>Everywhere else a sculk sensor is worth one omen per five pulses and only the rooms that
 * declare {@code pressure: "omen"} are armed. On an Ancient City floor every sculk sensor and
 * shrieker is armed whatever the room says, a sensor pulse is worth one omen, and a shriek one
 * omen as before. On the final floor, the moment the floor's omen reaches
 * {@link Omen#MAX_OMEN} a real vanilla Warden is summoned, once. It is the only Warden the mod
 * ever spawns.
 */
final class SculkOmen {

    private SculkOmen() {}

    /** The Ancient City dungeon, also the id of its main theme. */
    static final String ANCIENT_CITY = "ancient_city";

    /** Sensor pulses per omen outside the Ancient City (spec 5.2). */
    static final int NORMAL_PULSES_PER_OMEN = 5;
    /** Sensor pulses per omen inside the Ancient City: every activation counts. */
    static final int ANCIENT_PULSES_PER_OMEN = 1;

    /** Whether {@code id} (a bare or namespaced dungeon or theme id) is the Ancient City. */
    static boolean isAncientCity(String id) {
        return id != null && ANCIENT_CITY.equals(bare(id));
    }

    /** Whether every sculk sensor and shrieker on a floor of {@code themeId} is armed, room metadata aside. */
    static boolean armsAllSculk(String themeId) {
        return isAncientCity(themeId);
    }

    /** Sensor pulses that make one omen. */
    static int pulsesPerOmen(boolean ancientCity) {
        return ancientCity ? ANCIENT_PULSES_PER_OMEN : NORMAL_PULSES_PER_OMEN;
    }

    /** Omen gained from {@code pendingPulses} banked pulses (whole omen only). */
    static int omenFromPulses(int pendingPulses, boolean ancientCity) {
        return Math.max(0, pendingPulses) / pulsesPerOmen(ancientCity);
    }

    /** Pulses left over after {@code gained} omen was paid out of {@code pendingPulses}. */
    static int remainingPulses(int pendingPulses, int gained, boolean ancientCity) {
        return Math.max(0, pendingPulses - gained * pulsesPerOmen(ancientCity));
    }

    /**
     * Whether the Warden should be summoned now: an Ancient City dungeon, its final floor, the floor's
     * omen at the maximum, and none summoned yet this floor.
     */
    static boolean shouldSummonWarden(boolean ancientCity, boolean finalFloor, int omen, boolean alreadySummoned) {
        return ancientCity && finalFloor && !alreadySummoned && omen >= Omen.MAX_OMEN;
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
