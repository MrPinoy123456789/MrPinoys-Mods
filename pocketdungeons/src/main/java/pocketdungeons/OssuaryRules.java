package pocketdungeons;

import java.util.ArrayList;
import java.util.List;

/**
 * The numbers and decisions of the Ossuary finale (roadmap T21): how many mobs the coffins hold for a party, how
 * they are dealt out, which sealed coffin wakes next and when. No Minecraft imports, so
 * {@code OssuaryRulesTest} runs with plain {@code javac}. {@link OssuaryFinale} does the world work.
 *
 * <p>Every tunable is a named constant here (code constants, not config keys, so the golden config fixtures stay
 * untouched; promote one to a {@code PocketDungeonsConfig} knob when it gets tuned twice).
 */
final class OssuaryRules {

    private OssuaryRules() {}

    /** Knob: the most coffins the finale uses; any further ones found stay sealed dressing. */
    static final int MAX_COFFINS = 8;

    /** Knob: the most mobs the coffins hold, whatever the party; tighter than {@link FinaleRules#MAX_WAVE}. */
    static final int COFFIN_WAVE_CAP = 24;

    /** Knob: the most coffin mobs awake (standing, alive) at once; a new coffin waits while this is reached. */
    static final int AWAKE_CAP = 6;

    /** Knob: a member this close (blocks) to a sealed coffin wakes it. */
    static final double WAKE_RADIUS = 4.0;

    /**
     * Knob: ticks after the last coffin began to crack (or after the wave stood) before the next sealed coffin
     * opens whether or not anyone is near. This is the skip rule: a coffin the party walked past opens behind
     * them, so a skipped coffin always eventually opens.
     */
    static final int SKIP_DELAY_TICKS = 300;

    /** Ticks between one coffin starting to crack and the next, so two never open together. */
    static final int MIN_GAP_TICKS = 60;

    /** The tell: ticks of cracking sound and dust before the lid breaks. Fairness floor is 40. */
    static final int CRACK_TICKS = 50;

    /** The coffin groups of the wave for a party: {@link FinaleRules#counts}, trimmed to {@link #COFFIN_WAVE_CAP}. */
    static int[] waveCounts(int[] bases, int party, int perMemberPercent) {
        int[] out = FinaleRules.counts(bases, party, perMemberPercent);
        int total = 0;
        for (int n : out) {
            total += n;
        }
        int cap = Math.min(COFFIN_WAVE_CAP, FinaleRules.MAX_WAVE);
        while (total > cap) {
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

    /** How many coffins the finale uses of the {@code found} ones in the room. */
    static int coffinsUsed(int found, int total) {
        return Math.max(0, Math.min(Math.min(found, MAX_COFFINS), total));
    }

    /**
     * The group index of each mob of the wave, interleaved so the mix is spread along the dealing order (a
     * wave of 5 skeletons and 2 zombies is never five skeletons and then two zombies).
     */
    static int[] sequence(int[] counts) {
        int total = 0;
        for (int n : counts) {
            total += n;
        }
        int[] out = new int[total];
        int[] placed = new int[counts.length];
        for (int slot = 0; slot < total; slot++) {
            int best = -1;
            double bestLag = Double.NEGATIVE_INFINITY;
            for (int g = 0; g < counts.length; g++) {
                if (placed[g] >= counts[g]) {
                    continue;
                }
                double lag = (slot + 1.0) * counts[g] / total - placed[g];
                if (lag > bestLag) {
                    bestLag = lag;
                    best = g;
                }
            }
            out[slot] = best;
            placed[best]++;
        }
        return out;
    }

    /** How many mobs each of {@code coffins} coffins holds out of {@code total}: as even as it gets, the earliest take the extras. */
    static int[] occupants(int total, int coffins) {
        int[] out = new int[Math.max(0, coffins)];
        for (int i = 0; i < total && out.length > 0; i++) {
            out[i % out.length]++;
        }
        return out;
    }

    /** Whether a member at {@code distSq} (squared blocks) or the passing of time wakes a sealed coffin. */
    static boolean shouldWake(double distSq, long sinceLastCrack) {
        return shouldWake(distSq, sinceLastCrack, true);
    }

    /**
     * {@link #shouldWake(double, long)} with the skip rule switchable (roadmap T03): the boss's second phase wakes a
     * coffin only by a member's approach; its third phase adds the passing of time, the skipped coffin that opens
     * behind a turned back.
     */
    static boolean shouldWake(double distSq, long sinceLastCrack, boolean skipRule) {
        return distSq <= WAKE_RADIUS * WAKE_RADIUS || (skipRule && sinceLastCrack >= SKIP_DELAY_TICKS);
    }

    /**
     * Whether a coffin holding {@code next} mobs may start to crack: never inside {@link #MIN_GAP_TICKS} of the
     * last one, and not while it would take the awake and cracking mobs past {@link #AWAKE_CAP}. With nothing
     * awake or cracking a coffin always may, so a coffin bigger than the cap still opens.
     */
    static boolean mayStart(int awake, int cracking, int next, long sinceLastCrack) {
        if (sinceLastCrack < MIN_GAP_TICKS) {
            return false;
        }
        return awake + cracking == 0 || awake + cracking + next <= AWAKE_CAP;
    }

    /**
     * Which sealed coffin cracks next: the one nearest a member, if it is time ({@link #shouldWake}); -1 for
     * none. {@code distSq[i]} is coffin i's squared distance to its nearest member; {@code sealed[i]} says it
     * is still shut.
     */
    static int choose(double[] distSq, boolean[] sealed, long sinceLastCrack) {
        return choose(distSq, sealed, sinceLastCrack, true);
    }

    /** {@link #choose(double[], boolean[], long)} with the skip rule switchable; see {@link #shouldWake(double, long, boolean)}. */
    static int choose(double[] distSq, boolean[] sealed, long sinceLastCrack, boolean skipRule) {
        int best = -1;
        for (int i = 0; i < distSq.length; i++) {
            if (sealed[i] && (best < 0 || distSq[i] < distSq[best])) {
                best = i;
            }
        }
        return best >= 0 && shouldWake(distSq[best], sinceLastCrack, skipRule) ? best : -1;
    }

    /** The mobs of coffin {@code coffin}: its slice of the dealt sequence, as group indices. */
    static List<Integer> slice(int[] sequence, int[] occupants, int coffin) {
        int from = 0;
        for (int i = 0; i < coffin; i++) {
            from += occupants[i];
        }
        List<Integer> out = new ArrayList<>();
        for (int i = from; i < from + occupants[coffin] && i < sequence.length; i++) {
            out.add(sequence[i]);
        }
        return out;
    }

    /** Whether the cracking tell has run its full length. */
    static boolean crackDone(long sinceCrackStart) {
        return sinceCrackStart >= CRACK_TICKS;
    }
}
