package pocketdungeons;

/**
 * The pure rules of a dungeon\u0027s finale (design pass 2026-10-09, Q4): how big the wave is for a party, how
 * tough the elite is, and what the pad says while the last stand is on. No Minecraft imports, so
 * {@code FinaleRulesTest} runs with plain {@code javac}.
 *
 * <p>The wave grows {@code perMemberPercent} for each member past the first, rounded up, and never past
 * {@link #MAX_WAVE} mobs whatever the party; when the cap bites, the biggest groups give up mobs first so the
 * mix of the wave survives.
 */
final class FinaleRules {

    private FinaleRules() {}

    /** The wave never grows past this many mobs (the elite is not counted). */
    static final int MAX_WAVE = 40;

    /** One group of the wave for {@code party} members: {@code base} grown {@code perMemberPercent} per extra member, rounded up. */
    static int count(int base, int party, int perMemberPercent) {
        int extra = Math.max(0, Math.max(1, party) - 1);
        int scaled = Math.max(0, base) * (100 + Math.max(0, perMemberPercent) * extra);
        return (scaled + 99) / 100;
    }

    /** Every group of the wave for the party, held under {@link #MAX_WAVE} by trimming the biggest group first. */
    static int[] counts(int[] bases, int party, int perMemberPercent) {
        int[] out = new int[bases.length];
        int total = 0;
        for (int i = 0; i < bases.length; i++) {
            out[i] = count(bases[i], party, perMemberPercent);
            total += out[i];
        }
        while (total > MAX_WAVE) {
            int biggest = 0;
            for (int i = 1; i < out.length; i++) {
                if (out[i] > out[biggest]) {
                    biggest = i;
                }
            }
            out[biggest]--;
            total--;
        }
        return out;
    }

    /** The elite\u0027s health for {@code party} members: its base grown {@code percentPerMember} for each member past the first. */
    static int eliteHealth(int base, int party, int percentPerMember) {
        int extra = Math.max(0, Math.max(1, party) - 1);
        return Math.max(1, base) * (100 + Math.max(0, percentPerMember) * extra) / 100;
    }

    /**
     * Whether half of a finale wave is dead (roadmap T01): {@code alive <= total / 2} with integer division,
     * so a wave of 7 is half dead at 3 alive and a wave of 1 only when it is dead. Never before any mob exists.
     * A script's {@code onHalfDead} fires once, the first time this is true.
     */
    static boolean halfDead(int alive, int total) {
        return total > 0 && alive <= total / 2;
    }

    /** The most cave spiders the Mineshaft dark phase adds, whatever the party or the knob. */
    static final int MAX_DARK_SPIDERS = 10;

    /** The shortest warning before the lights go out, in seconds; the tell is never shorter than this. */
    static final int MIN_DARK_TELL_SECONDS = 2;

    /** How far from the exit pad and the terminal the lights are left alone, in blocks (a square). */
    static final int DARK_SPARE_RADIUS = 4;

    /**
     * The cave spiders the Mineshaft dark phase adds (roadmap T20): {@code base} for a party of one, grown
     * {@code perMemberPercent} per extra member like the data wave, never past {@link #MAX_DARK_SPIDERS} and
     * never so many that the finale passes {@link #MAX_WAVE} mobs with the {@code total} it has now.
     */
    static int darkSpiders(int base, int party, int perMemberPercent, int total) {
        int wanted = Math.min(MAX_DARK_SPIDERS, count(base, party, perMemberPercent));
        return Math.max(0, Math.min(wanted, MAX_WAVE - Math.max(0, total)));
    }

    /** The warning before the lights go out, in ticks: the knob in seconds, held to at least the minimum. */
    static long darkTellTicks(int seconds) {
        return Math.max(MIN_DARK_TELL_SECONDS, seconds) * 20L;
    }

    /**
     * Whether a light at {@code (x, z)} is spared because it stands within {@link #DARK_SPARE_RADIUS} of the
     * pad at {@code (padX, padZ)}: the way to the exit is never darkened past what a torch fixes.
     */
    static boolean nearPad(int x, int z, int padX, int padZ) {
        return Math.abs(x - padX) <= DARK_SPARE_RADIUS && Math.abs(z - padZ) <= DARK_SPARE_RADIUS;
    }

    /**
     * What the terminal pad says while the finale stands in the way, or {@code null} when it does not.
     *
     * @param due   the floor\u0027s spawners are cleared, so the last stand is due or on
     * @param done  the wave and its elite are dead
     * @param alive how many of them are still alive
     */
    static String padRefusal(boolean due, boolean done, int alive) {
        if (!due || done) {
            return null;
        }
        return alive > 0
                ? "The last stand is on. " + alive + " left to beat before the pad opens."
                : "The last stand is stirring. Hold the room.";
    }
}
