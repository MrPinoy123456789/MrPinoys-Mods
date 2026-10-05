package pocketdungeons;

/**
 * Dungeon structure W7a: the pure rules of the Spawner Dungeon's boss room (design section 11).
 * No Minecraft imports, so {@code CapstoneRulesTest} runs with plain {@code javac}.
 *
 * <p>The boss room holds several classic mob spawners. The terminal pad stays shut until the
 * spawners are done, which happens one of two ways: every spawner is broken, or the party has
 * killed enough of what they spat out that the cages gutter out ("exhausted"). Then the final
 * brood wave (skeletons and cave spiders, sized to the party) is released and must be killed.
 * Only then does the pad open.
 */
final class BroodWave {

    private BroodWave() {}

    /** Where the fight stands. */
    enum Phase {
        /** Spawners still live and not yet exhausted: break them or out-kill them. */
        SPAWNERS,
        /** The wave is due to spawn, or is spawned and still has members alive. */
        WAVE,
        /** The wave is dead: the pad is open. */
        DONE
    }

    /** Skeletons in the final wave for a party of one. */
    static final int BASE_SKELETONS = 4;
    /** Cave spiders in the final wave for a party of one. */
    static final int BASE_SPIDERS = 6;
    /** Extra skeletons per member beyond the first. */
    static final int SKELETONS_PER_EXTRA_MEMBER = 2;
    /** Extra cave spiders per member beyond the first. */
    static final int SPIDERS_PER_EXTRA_MEMBER = 3;
    /** The wave never grows past this many mobs, whatever the party. */
    static final int MAX_WAVE = 40;
    /** Brood kills that burn out one spawner. */
    static final int KILLS_PER_SPAWNER = 6;
    /** Extra kills required per member beyond the first (more hands, more mobs to be spent). */
    static final int KILLS_PER_EXTRA_MEMBER = 6;

    /** Skeletons in the wave for {@code party} members (at least one member). */
    static int skeletons(int party) {
        int extra = Math.max(0, Math.max(1, party) - 1);
        return Math.min(MAX_WAVE / 2, BASE_SKELETONS + extra * SKELETONS_PER_EXTRA_MEMBER);
    }

    /** Cave spiders in the wave for {@code party} members (at least one member). */
    static int spiders(int party) {
        int extra = Math.max(0, Math.max(1, party) - 1);
        return Math.min(MAX_WAVE - skeletons(party), BASE_SPIDERS + extra * SPIDERS_PER_EXTRA_MEMBER);
    }

    /** The whole wave, skeletons plus cave spiders, never more than {@link #MAX_WAVE}. */
    static int waveSize(int party) {
        return skeletons(party) + spiders(party);
    }

    /**
     * Brood kills after which every remaining spawner burns out. Scales with the number of
     * spawners the room started with and with the party, so a bigger party cannot exhaust the
     * cages in a few swings. At least {@link #KILLS_PER_SPAWNER}.
     */
    static int exhaustKills(int initialSpawners, int party) {
        int extra = Math.max(0, Math.max(1, party) - 1);
        return Math.max(1, initialSpawners) * KILLS_PER_SPAWNER + extra * KILLS_PER_EXTRA_MEMBER;
    }

    /** Whether the spawner stage is over: none left standing, or the brood has been out-killed. */
    static boolean spawnersDone(int spawnersLeft, int kills, int exhaustKills) {
        return spawnersLeft <= 0 || kills >= exhaustKills;
    }

    /**
     * The fight's phase.
     *
     * @param spawnersLeft spawner blocks still standing in the boss room
     * @param kills        brood mobs the party has killed in the room
     * @param exhaustKills {@link #exhaustKills} for this room and party
     * @param waveSpawned  whether the final wave has been released
     * @param waveAlive    wave mobs still alive
     */
    static Phase phase(int spawnersLeft, int kills, int exhaustKills, boolean waveSpawned, int waveAlive) {
        if (!spawnersDone(spawnersLeft, kills, exhaustKills)) {
            return Phase.SPAWNERS;
        }
        if (!waveSpawned || waveAlive > 0) {
            return Phase.WAVE;
        }
        return Phase.DONE;
    }

    /** Whether the terminal pad may complete the floor. */
    static boolean padOpen(Phase phase) {
        return phase == Phase.DONE;
    }

    /** What the pad says while it is shut, or {@code null} when it is open. */
    static String padRefusal(Phase phase, int spawnersLeft, int waveAlive) {
        return switch (phase) {
            case SPAWNERS -> "The pad is sealed by the brood. Break the spawners ("
                    + spawnersLeft + " left) or kill what they spit out until the cages burn out.";
            case WAVE -> waveAlive > 0
                    ? "The pad is sealed. The last of the brood is loose: " + waveAlive + " left to kill."
                    : "The pad is sealed. The last of the brood is stirring.";
            case DONE -> null;
        };
    }
}
