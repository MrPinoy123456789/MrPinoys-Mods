package chatdonkey.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Animal Crossing style "animalese": a chat line turned into a burst of short
 * pitched blips, one per syllable-ish letter, so the donkey audibly *talks*
 * while its line is on screen.
 *
 * <p>Pure arithmetic -- this decides <em>what</em> to play and <em>when</em>,
 * and the fabric side turns each {@link Blip} into a note-block packet.
 *
 * <p>Three things make it read as a voice rather than a beep:
 * <ul>
 *   <li><b>A stable per-donkey base pitch.</b> Duncan always sounds like Duncan
 *       and Persimmon always sounds squeakier, because the seed is the name.</li>
 *   <li><b>Letter-driven wobble.</b> Each letter nudges the pitch a little, so
 *       the line warbles instead of playing one flat tone -- but the range is
 *       deliberately narrow, because a wide one plays a tune instead of talking.</li>
 *   <li><b>Terminal intonation.</b> A question rises, an exclamation pushes up
 *       at the end, and a plain sentence falls.</li>
 * </ul>
 */
public final class Animalese {

    /** One note: when to play it, relative to the start of the line, and at what pitch. */
    public record Blip(int tickOffset, float pitch) {}

    /** Ticks between blips. One tick is 50ms -- close to Animal Crossing's rate. */
    public static final int TICKS_PER_BLIP = 1;

    /** Extra ticks inserted at a word break, so words are audibly separate. */
    public static final int WORD_GAP_TICKS = 2;

    /** A long line stops babbling rather than droning for five seconds. */
    public static final int MAX_BLIPS = 28;

    /** Minecraft clamps playback pitch to this range. */
    public static final float MIN_PITCH = 0.5f;
    public static final float MAX_PITCH = 2.0f;

    /**
     * The band every donkey's voice is drawn from.
     *
     * <p>Deliberately in the lower half of the playable range: these are
     * donkeys, and a chirpy Animal Crossing squeak reads as a chipmunk. Deep
     * enough to be a bray, spread wide enough that two donkeys in earshot are
     * still telling themselves apart.
     *
     * <p>The floor is not arbitrary. The wobble and a falling sentence can each
     * pull a blip below its base, so the lowest reachable pitch is roughly
     * {@code MIN_BASE_PITCH * 0.94 * 0.92} -- which has to stay clear of
     * {@link #MIN_PITCH}, or the deepest voices clip and flatten out.
     */
    public static final float MIN_BASE_PITCH = 0.60f;
    public static final float MAX_BASE_PITCH = 1.05f;

    private Animalese() {}

    /**
     * @param line       the chat line, punctuation and all
     * @param voiceSeed  stable per-speaker seed; pass the donkey's name hash
     */
    public static List<Blip> speak(String line, long voiceSeed) {
        List<Blip> blips = new ArrayList<>();
        if (line == null || line.isBlank()) {
            return blips;
        }

        float base = basePitch(voiceSeed);
        float intonation = intonation(line);

        // Count the letters first so intonation can ramp across the whole line.
        int totalLetters = 0;
        for (int i = 0; i < line.length() && totalLetters < MAX_BLIPS; i++) {
            if (Character.isLetter(line.charAt(i))) {
                totalLetters++;
            }
        }
        if (totalLetters == 0) {
            return blips;
        }

        int tick = 0;
        int emitted = 0;
        char previous = 0;

        for (int i = 0; i < line.length() && emitted < MAX_BLIPS; i++) {
            char c = line.charAt(i);

            if (!Character.isLetter(c)) {
                // Any run of spaces or punctuation is one pause, not several.
                if (previous != 0) {
                    tick += WORD_GAP_TICKS;
                    previous = 0;
                }
                continue;
            }

            char lower = Character.toLowerCase(c);

            // A vowel straight after a vowel is the same syllable ("oo", "ea"),
            // so it does not get its own blip. This is what stops the voice
            // sounding like it is spelling the word out.
            if (isVowel(lower) && isVowel(previous)) {
                previous = lower;
                continue;
            }

            float progress = totalLetters == 1 ? 0f : emitted / (float) (totalLetters - 1);
            float pitch = base
                    * letterWobble(lower)
                    * (1.0f + intonation * progress);

            // The donkey SHOUTS a lot; let that be audible.
            if (Character.isUpperCase(c)) {
                pitch *= 1.06f;
            }

            blips.add(new Blip(tick, clamp(pitch)));
            emitted++;
            tick += TICKS_PER_BLIP;
            previous = lower;
        }

        return blips;
    }

    /** How many ticks a line's audio occupies. */
    public static int durationTicks(List<Blip> blips) {
        return blips.isEmpty() ? 0 : blips.get(blips.size() - 1).tickOffset() + TICKS_PER_BLIP;
    }

    /**
     * A stable, well-spread base pitch per speaker.
     *
     * <p>Deliberately not {@code Random}: the same name must give the same voice
     * on every server, every restart.
     */
    static float basePitch(long voiceSeed) {
        // Mix the seed so similar names do not land on similar pitches.
        long mixed = voiceSeed * 0x9E3779B97F4A7C15L;
        mixed ^= (mixed >>> 32);
        float t = Math.abs(mixed % 1000) / 1000.0f;
        return MIN_BASE_PITCH + t * (MAX_BASE_PITCH - MIN_BASE_PITCH);
    }

    /** A narrow per-letter nudge: enough to warble, not enough to play a melody. */
    static float letterWobble(char lowerLetter) {
        int index = lowerLetter - 'a';
        if (index < 0 || index > 25) {
            return 1.0f;
        }
        // Vowels sit a little lower, consonants a little higher -- the same
        // shape a real voice makes.
        float spread = (index % 7) / 6.0f;    // 0 .. 1
        float centre = isVowel(lowerLetter) ? 0.94f : 1.02f;
        return centre + spread * 0.08f;
    }

    /** Total pitch drift across the line, as a fraction. */
    static float intonation(String line) {
        String trimmed = line.trim();
        if (trimmed.endsWith("?")) {
            return 0.18f;      // rises
        }
        if (trimmed.endsWith("!")) {
            return 0.10f;      // pushes up
        }
        return -0.08f;         // settles
    }

    private static boolean isVowel(char lower) {
        return lower == 'a' || lower == 'e' || lower == 'i' || lower == 'o' || lower == 'u';
    }

    private static float clamp(float pitch) {
        return Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
    }

    /** The stable voice seed for a speaker's name. */
    public static long voiceSeed(String name) {
        return name == null ? 0L : name.hashCode();
    }
}
