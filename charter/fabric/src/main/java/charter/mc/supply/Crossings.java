package charter.mc.supply;

import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.land.Core;
import charter.siege.Supply;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Turns movement into supply-line entries (PLAN.md §5.2, §5.3). Players and carts
 * are both carriers, named "player:uuid" or "cart:uuid"; each is observed with its
 * position and, only when needed, the food it holds.
 *
 * <p>A move counts as an entry only when it crosses into an edge-adjacent chunk,
 * covers no more than {@code teleportDetectBlocks} since the last observation, and
 * the carrier is not in transit by ender pearl or chorus fruit. Then core's
 * {@link Supply#enter} decides whether that side is open. A carrier leaving a site
 * reports what it still holds, so a cart rolling in and out full mints nothing.
 * The rules here only filter movement; every supply rule is core's.
 */
public final class Crossings {

    /** Where a carrier was last seen. */
    record Seen(String dimension, double x, double z) {
        ChunkKey chunk() {
            return new ChunkKey(dimension, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        }
    }

    /** What happened to one observation, for tests and the siege log. */
    public enum Result { FIRST, SAME_CHUNK, JUMP, IN_TRANSIT, ENTERED, CLOSED, NOT_NEEDED, LEFT, OTHER }

    private final Map<String, Seen> last = new HashMap<>();
    /** Carrier to the game tick its transit flag lifts. */
    private final Map<String, Long> transit = new HashMap<>();
    private final Supplier<Supply> supply;
    private final int jumpBlocks;

    public Crossings(Supplier<Supply> supply, int jumpBlocks) {
        this.supply = supply;
        this.jumpBlocks = jumpBlocks;
    }

    /** Marks a carrier as moving by teleport until {@code untilTick}: no crossing counts meanwhile. */
    public void inTransit(String carrier, long untilTick) {
        transit.merge(carrier, untilTick, Math::max);
    }

    /** Forgets a carrier (logged out, removed): the next observation is a first sighting, never an entry. */
    public void forget(String carrier) {
        last.remove(carrier);
        transit.remove(carrier);
    }

    public void forgetAllBut(java.util.Set<String> keep, String prefix) {
        last.keySet().removeIf(k -> k.startsWith(prefix) && !keep.contains(k));
        transit.keySet().removeIf(k -> k.startsWith(prefix) && !keep.contains(k));
    }

    /**
     * Observes a carrier at a position. {@code food} is only asked for when the move
     * might count, so the common case (no chunk change) costs one comparison.
     */
    public Result observe(String carrier, String dimension, double x, double z, long tick, Supplier<Map<String, Integer>> food) {
        Seen now = new Seen(dimension, x, z);
        Seen before = last.put(carrier, now);
        Long transitUntil = transit.get(carrier);
        if (transitUntil != null && tick >= transitUntil) transit.remove(carrier);
        if (before == null) return Result.FIRST;
        ChunkKey from = before.chunk();
        ChunkKey to = now.chunk();
        if (from.equals(to)) return Result.SAME_CHUNK;
        if (!from.dimension().equals(to.dimension())) return Result.JUMP;
        double moved = Math.hypot(x - before.x(), z - before.z());
        int dx = Math.abs(from.x() - to.x());
        int dz = Math.abs(from.z() - to.z());
        if (dx + dz != 1 || moved > jumpBlocks) return Result.JUMP;
        if (transitUntil != null && tick < transitUntil) return Result.IN_TRANSIT;

        Supply s = supply.get();
        Optional<Core> leaving = s.siteAt(from);
        Optional<Core> entering = s.siteAt(to);
        Result result = Result.OTHER;
        Map<String, Integer> held = null;
        if (leaving.isPresent() && !entering.map(c -> c.id.equals(leaving.get().id)).orElse(false)) {
            held = food.get();
            s.leave(carrier, leaving.get().id, held);
            result = Result.LEFT;
        }
        if (entering.isPresent()) {
            if (held == null) held = food.get();
            Supply.Entry entry = s.enter(carrier, from, to, held);
            result = switch (entry) {
                case GRANTED -> Result.ENTERED;
                case CLOSED -> Result.CLOSED;
                case NOT_NEEDED -> Result.NOT_NEEDED;
                case NOT_A_SITE -> result;
            };
        }
        return result;
    }

    /** The site a carrier was last seen in, if any. */
    public Optional<CoreId> lastSite(String carrier) {
        Seen seen = last.get(carrier);
        return seen == null ? Optional.empty() : supply.get().siteAt(seen.chunk()).map(c -> c.id);
    }
}
