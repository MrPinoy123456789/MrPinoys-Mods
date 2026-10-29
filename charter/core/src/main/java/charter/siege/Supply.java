package charter.siege;

import charter.engine.ActionEngine;
import charter.food.FoodEntry;
import charter.food.FoodTable;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.land.LandGraph;
import charter.state.CharterState;
import charter.time.Durations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Getting food in (DESIGN.md §10.4). While a keep is besieged, or a camp is
 * fighting, food goes into its store only if a carrier brought it in through an
 * open side: the fabric side reports each crossing with what the carrier holds,
 * and a deposit is capped by that allowance. In peace anything goes in.
 *
 * <p>The fabric side calls {@link #enter} for players and carts crossing a chunk
 * border on foot, by mount or on rails. A teleport, a pearl, or a crossing of more
 * than {@code teleportDetectBlocks} between checks is never reported as an entry,
 * so it grants nothing.
 */
public final class Supply {

    public enum Entry {
        /** Not an entry into a keep or camp, or a move within one. */
        NOT_A_SITE,
        /** The site is not under siege: no allowance is needed. */
        NOT_NEEDED,
        /** Came in through an open side: the food carried is noted. */
        GRANTED,
        /** Came in across a closed side or from somewhere that is not the perimeter: nothing is noted. */
        CLOSED
    }

    private final ActionEngine engine;
    private final CharterState state;
    private final FoodTable food;

    public Supply(ActionEngine engine, FoodTable food) {
        this.engine = engine;
        this.state = engine.state();
        this.food = food;
    }

    private SiegeRules rules() {
        return new SiegeRules(state, engine.knobs(), food, engine.now());
    }

    /** The keep or camp a chunk belongs to as a site: a keep's nine chunks, or any claim of a camp. */
    public Optional<Core> siteAt(ChunkKey chunk) {
        Optional<Claim> claim = state.claimAt(chunk);
        if (claim.isEmpty() || claim.get().core() == null) return Optional.empty();
        Optional<Core> core = state.core(claim.get().core());
        if (core.isEmpty()) return Optional.empty();
        if (core.get().isKeep() && claim.get().kind() == ClaimKind.OUTLYING) return Optional.empty();
        return core;
    }

    /**
     * True if food going into this site's store needs an allowance right now. Nothing is ever under siege
     * (protected keeps: keeps are never besieged and a camp has no targets), so this is always false; the
     * allowance bookkeeping goes with the outpost rework.
     */
    public boolean underSiege(Core site) {
        return false;
    }

    /**
     * A carrier crossed from {@code from} into {@code to} carrying this food. If that
     * is an entry into a site under siege through one of its open sides, the food is
     * noted for the carry-in window, replacing any earlier allowance at that site.
     */
    public Entry enter(String carrier, ChunkKey from, ChunkKey to, Map<String, Integer> carried) {
        Optional<Core> site = siteAt(to);
        if (site.isEmpty()) return Entry.NOT_A_SITE;
        if (siteAt(from).map(s -> s.id.equals(site.get().id)).orElse(false)) return Entry.NOT_A_SITE;
        return Entry.NOT_NEEDED;
    }

    public Optional<Allowance> allowance(String carrier, CoreId site) {
        long now = state.siegeClock.now();
        return state.siege.allowances.stream()
                .filter(a -> a.carrier.equals(carrier) && a.site.equals(site) && now < a.expiresSiege)
                .findFirst();
    }

    /** The carrier a hopper or dropper deposits as: it has no allowance of its own, only the site's cart pool. */
    public static final String HOPPER = "hopper";
    /** Prefix of cart carriers; their allowances together are the site's cart pool (PLAN.md §5.3). */
    public static final String CART = "cart:";

    /**
     * Puts food into a site's store and returns what was accepted. Under siege an item
     * is accepted only if an allowance of the same item id covers it: the carrier's
     * own first, then, for a player or a hopper, the site's cart pool, soonest to
     * expire first. Otherwise every food item goes in. Non-food items never do. The
     * fabric side moves exactly the accepted items.
     */
    public Map<String, Integer> deposit(String carrier, CoreId siteId, Map<String, Integer> offered, List<HistoryEvent> events) {
        Map<String, Integer> accepted = preview(carrier, siteId, offered);
        if (accepted.isEmpty()) return accepted;
        boolean siege = underSiege(state.core(siteId).orElseThrow());
        int items = 0;
        double nourishment = 0;
        for (Map.Entry<String, Integer> entry : accepted.entrySet()) {
            if (siege) {
                int left = entry.getValue();
                for (Allowance a : sources(carrier, siteId)) {
                    int n = Math.min(left, a.left(entry.getKey()));
                    if (n > 0) {
                        a.use(entry.getKey(), n);
                        left -= n;
                    }
                    if (left == 0) break;
                }
            }
            items += entry.getValue();
            nourishment += entry.getValue() * food.get(entry.getKey()).nourishment();
        }
        if (items > 0) {
            Store store = state.siege.store(siteId);
            accepted.forEach(store.provisions::add);
            events.add(new SiegeEvents.FoodDeposited(engine.now(), state.siegeClock.now(), siteId, carrier, java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(accepted)), nourishment, siege));
        }
        return accepted;
    }

    /** What {@link #deposit} would accept, changing nothing. */
    public Map<String, Integer> preview(String carrier, CoreId siteId, Map<String, Integer> offered) {
        Map<String, Integer> accepted = new LinkedHashMap<>();
        Optional<Core> site = state.core(siteId);
        if (site.isEmpty()) return accepted;
        boolean siege = underSiege(site.get());
        List<Allowance> sources = siege ? sources(carrier, siteId) : List.of();
        if (siege && sources.isEmpty()) return accepted;
        for (Map.Entry<String, Integer> entry : new TreeMap<>(offered).entrySet()) {
            FoodEntry kind = food.get(entry.getKey());
            if (kind == null || entry.getValue() <= 0) continue;
            int covered = 0;
            for (Allowance a : sources) covered += a.left(entry.getKey());
            int n = siege ? Math.min(entry.getValue(), covered) : entry.getValue();
            if (n > 0) accepted.put(entry.getKey(), n);
        }
        return accepted;
    }

    /** The allowances a carrier may draw on at a site, in the order they are used. */
    private List<Allowance> sources(String carrier, CoreId siteId) {
        List<Allowance> out = new java.util.ArrayList<>();
        if (!carrier.equals(HOPPER)) allowance(carrier, siteId).ifPresent(out::add);
        if (carrier.equals(HOPPER) || carrier.startsWith("player:")) out.addAll(cartPool(siteId));
        return out;
    }

    /** The site's cart pool: every live cart allowance there, soonest to expire first. */
    public List<Allowance> cartPool(CoreId siteId) {
        long now = state.siegeClock.now();
        return state.siege.allowances.stream()
                .filter(a -> a.site.equals(siteId) && a.carrier.startsWith(CART) && now < a.expiresSiege)
                .sorted(java.util.Comparator.comparingLong(a -> a.expiresSiege))
                .toList();
    }

    /** How much of an item the site's cart pool still covers. */
    public int poolLeft(CoreId siteId, String itemId) {
        int n = 0;
        for (Allowance a : cartPool(siteId)) n += a.left(itemId);
        return n;
    }

    /**
     * A carrier left a site still holding this food. Its allowance there loses that
     * much, so a cart that rolls in full and out full mints nothing (PLAN.md §5.3).
     */
    public void leave(String carrier, CoreId siteId, Map<String, Integer> aboard) {
        allowance(carrier, siteId).ifPresent(a -> aboard.forEach((id, n) -> {
            int take = Math.min(n, a.left(id));
            if (take > 0) a.use(id, take);
        }));
    }

    /** True if this chunk is one of the keep's twelve perimeter chunks. */
    public static boolean onKeepPerimeter(Core keep, ChunkKey chunk) {
        return LandGraph.keepPerimeterChunks(keep.center).contains(chunk);
    }
}
