package charter.mapwire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Row run length encoding of a set of chunks: each row of chunks (one z) becomes runs of touching x. A 9 by 9 keep is
 * nine runs; a kingdom of a few hundred chunks is a few dozen.
 */
public final class Runs {

    private Runs() {
    }

    /** Packs a chunk into one long, x in the low half and z in the high half. */
    public static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    public static int x(long packed) {
        return (int) packed;
    }

    public static int z(long packed) {
        return (int) (packed >> 32);
    }

    /** The runs covering exactly these chunks (duplicates ignored), sorted by z then x. */
    public static List<MapModel.ChunkRun> encode(long[] chunks) {
        // Sort by z, then x: compare as (z, x) pairs.
        Long[] boxed = new Long[chunks.length];
        for (int i = 0; i < chunks.length; i++) boxed[i] = chunks[i];
        Arrays.sort(boxed, (a, b) -> {
            int c = Integer.compare(z(a), z(b));
            return c != 0 ? c : Integer.compare(x(a), x(b));
        });
        List<MapModel.ChunkRun> out = new ArrayList<>();
        int i = 0;
        while (i < boxed.length) {
            int z = z(boxed[i]);
            int x0 = x(boxed[i]);
            int x1 = x0;
            i++;
            while (i < boxed.length && z(boxed[i]) == z && x(boxed[i]) <= x1 + 1) {
                x1 = Math.max(x1, x(boxed[i]));
                i++;
            }
            out.add(new MapModel.ChunkRun(z, x0, x1 - x0 + 1));
        }
        return out;
    }

    /** Every chunk the runs cover, packed with {@link #pack}. */
    public static long[] expand(List<MapModel.ChunkRun> runs) {
        long total = 0;
        for (MapModel.ChunkRun r : runs) total += r.length();
        if (total > Integer.MAX_VALUE - 8) throw new MapWire.BadMessage("too many chunks");
        long[] out = new long[(int) total];
        int n = 0;
        for (MapModel.ChunkRun r : runs) {
            for (int k = 0; k < r.length(); k++) out[n++] = pack(r.x0() + k, r.z());
        }
        return out;
    }

    /** How many chunks the runs cover. */
    public static long count(List<MapModel.ChunkRun> runs) {
        long total = 0;
        for (MapModel.ChunkRun r : runs) total += r.length();
        return total;
    }
}
