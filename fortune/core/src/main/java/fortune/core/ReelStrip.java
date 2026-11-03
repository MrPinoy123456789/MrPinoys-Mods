package fortune.core;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The reel animation as data. Each reel scrolls down one row per frame and stops on its rolled symbol; the frames
 * slow over the last eight gaps. A reel's strip holds every symbol that passes through its three rows: frame
 * {@code f} shows {@code strip[f + 2]} on top, {@code strip[f + 1]} on the payline and {@code strip[f]} below, so the
 * last frame puts {@code strip[frames]} (the rolled symbol) on the payline. Everything else on the strip is filler
 * drawn from the symbol weights.
 */
public final class ReelStrip {

    /** The gaps, in ticks, between a reel's last frames: it slows before it stops. */
    public static final int[] SLOWDOWN = {2, 2, 2, 3, 3, 4, 5, 7};
    /** When each reel stops, in ticks after the pull: about 1.5 s, 2.2 s and 3 s. */
    public static final int[] STOP_TICKS = {30, 44, 60};

    private ReelStrip() {
    }

    /** The ticks after the pull at which a reel draws a frame, ascending; the last one is its stop. */
    public static int[] frameTicks(int stopTick, int baseGap) {
        int gap = Math.max(1, baseGap);
        List<Integer> backwards = new ArrayList<>();
        int t = stopTick;
        backwards.add(t);
        for (int i = SLOWDOWN.length - 1; i >= 0; i--) {
            t -= Math.max(SLOWDOWN[i], gap);
            if (t <= 0) break;
            backwards.add(t);
        }
        while (t - gap > 0) {
            t -= gap;
            backwards.add(t);
        }
        int[] out = new int[backwards.size()];
        for (int i = 0; i < out.length; i++) out[i] = backwards.get(out.length - 1 - i);
        return out;
    }

    /** A strip for {@code frames} frames that ends with {@code payline} on the middle row. */
    public static int[] strip(int frames, int payline, Roller roller, RandomGenerator random) {
        int[] strip = new int[frames + 2];
        for (int i = 0; i < strip.length; i++) strip[i] = roller.filler(random);
        strip[frames] = payline;
        return strip;
    }

    /** The three rows of a reel at a frame, top to bottom. Frame -1 is the resting view before the first frame. */
    public static int[] window(int[] strip, int frame) {
        int f = Math.max(0, Math.min(frame, strip.length - 3));
        return new int[]{strip[f + 2], strip[f + 1], strip[f]};
    }
}
