package charter.land;

import charter.config.Knobs;
import charter.engine.Reason;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.org.MemberRecord;
import charter.state.CharterState;
import charter.time.Durations;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The rules of land (DESIGN.md §9, §10.4, §10.10, §11), over the cores and claims
 * kept in {@link CharterState}: edge adjacency, fewest-hops assignment to a core
 * (the older core wins a tie), capacity, range, the active member cap, keep
 * footprints and gaps, perimeters with open and closed sides, reassignment after
 * a core is lost, and the orphan grace.
 *
 * <p>Land is held per owning organization. A claim is connected to, and assigned
 * to, cores of the same owner; the member cap and the keep gap are per faction
 * (the root of the owner's tree).
 *
 * <p>Methods that change state return the events to record; the actions and the
 * siege simulation pass them on to the engine. Nothing here reads a clock: wall
 * time is passed in for events, and the grace runs on {@code state.siegeClock}.
 */
public final class LandGraph {

    /** Who a camp is besieging or raiding. The siege simulation supplies this; the land graph does not know it. */
    public interface Hostility {
        /** Roots of the organizations this camp is besieging or raiding. */
        Set<OrgId> targets(Core camp);

        Hostility NONE = camp -> Set.of();
    }

    private final CharterState state;
    private final Knobs knobs;

    public LandGraph(CharterState state, Knobs knobs) {
        this.state = state;
        this.knobs = knobs;
    }

    // ---- geometry ----

    public static List<ChunkKey> neighbours(ChunkKey chunk) {
        return List.of(chunk.offset(1, 0), chunk.offset(-1, 0), chunk.offset(0, 1), chunk.offset(0, -1));
    }

    /** The core chunk and the eight around it. */
    public static List<ChunkKey> footprint(ChunkKey center) {
        List<ChunkKey> out = new ArrayList<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) out.add(center.offset(dx, dz));
        }
        return out;
    }

    /** The twelve chunks sharing an edge with a keep's ring: the 5 by 5 square less its corners and inner 3 by 3. */
    public static List<ChunkKey> keepPerimeterChunks(ChunkKey center) {
        List<ChunkKey> out = new ArrayList<>();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                boolean outer = Math.max(Math.abs(dx), Math.abs(dz)) == 2;
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                if (outer && !corner) out.add(center.offset(dx, dz));
            }
        }
        return out;
    }

    /** Empty chunks between two keep footprints, by the larger axis. Different dimensions are infinitely far apart. */
    public static int gapBetween(ChunkKey a, ChunkKey b) {
        if (!a.dimension().equals(b.dimension())) return Integer.MAX_VALUE;
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z())) - 3;
    }

    public int capacity(Core core) {
        return core.isKeep() ? knobs.keepCapacityClaims : knobs.campCapacityClaims;
    }

    public int range(Core core) {
        return core.isKeep() ? knobs.keepRangeHops : knobs.campRangeHops;
    }

    public boolean dimensionAllowed(ChunkKey chunk) {
        return knobs.claimDimensions.contains(chunk.dimension());
    }

    /** The faction an organization belongs to: the root of its tree. */
    public OrgId faction(OrgId org) {
        return state.root(org);
    }

    // ---- who may hold how much ----

    /** Members of the faction (any level of the tree) seen within the active window. */
    public int activeMembers(OrgId faction, long wallNow) {
        long since = wallNow - Durations.days(knobs.activeWindowDays);
        List<OrgId> tree = new ArrayList<>();
        tree.add(faction);
        tree.addAll(state.descendants(faction));
        int active = 0;
        for (OrgId org : tree) {
            for (MemberRecord member : state.members(org)) {
                boolean seen = state.knownPlayer(member.player()).map(p -> p.lastSeen != 0 && p.lastSeen >= since).orElse(false);
                if (seen) active++;
            }
        }
        return active;
    }

    public int landCap(OrgId faction, long wallNow) {
        return knobs.claimsPerActiveMember * activeMembers(faction, wallNow);
    }

    /** Claims counted against the member cap: everything except the chunks that make up a core. */
    public int countedClaims(OrgId faction) {
        int n = 0;
        for (Claim claim : state.claims()) {
            if (!claim.fixed() && faction(claim.owner).equals(faction)) n++;
        }
        return n;
    }

    // ---- placement checks ----

    public Optional<Reason> keepProblem(OrgId owner, ChunkKey center) {
        if (!dimensionAllowed(center)) return Optional.of(LandReasons.WRONG_DIMENSION);
        for (ChunkKey chunk : footprint(center)) {
            if (state.claimAt(chunk).isPresent()) return Optional.of(LandReasons.KEEP_NEEDS_ROOM);
        }
        OrgId faction = faction(owner);
        for (Core other : state.cores()) {
            if (!other.isKeep() || faction(other.owner).equals(faction)) continue;
            if (gapBetween(center, other.center) < knobs.keepGapChunks) return Optional.of(LandReasons.KEEP_GAP);
        }
        return Optional.empty();
    }

    /** A camp may stand beside foreign land but never inside it. */
    public Optional<Reason> campProblem(ChunkKey center) {
        if (!dimensionAllowed(center)) return Optional.of(LandReasons.WRONG_DIMENSION);
        if (state.claimAt(center).isPresent()) return Optional.of(LandReasons.CLAIMED);
        return Optional.empty();
    }

    public Optional<Reason> claimProblem(OrgId owner, ChunkKey chunk, long wallNow) {
        if (!dimensionAllowed(chunk)) return Optional.of(LandReasons.WRONG_DIMENSION);
        if (state.claimAt(chunk).isPresent()) return Optional.of(LandReasons.CLAIMED);
        boolean touches = neighbours(chunk).stream().anyMatch(n -> state.claimAt(n).map(c -> c.owner.equals(owner)).orElse(false));
        if (!touches) return Optional.of(LandReasons.NOT_ADJACENT);

        Plan plan = plan(owner, chunk);
        if (plan.assigned.get(chunk) == null) {
            return Optional.of(plan.reachable.contains(chunk) ? LandReasons.CORE_FULL : LandReasons.OUT_OF_RANGE);
        }
        for (ChunkKey orphan : plan.orphans) {
            boolean alreadyContested = state.claimAt(orphan).map(Claim::contested).orElse(false);
            if (!alreadyContested) return Optional.of(LandReasons.CORE_FULL);
        }
        OrgId faction = faction(owner);
        if (countedClaims(faction) + 1 > landCap(faction, wallNow)) return Optional.of(LandReasons.MEMBER_CAP);
        return Optional.empty();
    }

    // ---- changes ----

    /** Places a keep and its nine chunks. The caller has checked {@link #keepProblem}. */
    public List<HistoryEvent> placeKeep(OrgId owner, ChunkKey center, long wallNow) {
        Core core = new Core(state.ids.core(), CoreKind.KEEP, owner, center, state.takeCoreOrder(), wallNow,
                CampPhase.ACTIVE, state.siegeClock.now());
        state.addCore(core);
        for (ChunkKey chunk : footprint(center)) {
            ClaimKind kind = chunk.equals(center) ? ClaimKind.KEEP_CORE : ClaimKind.KEEP_RING;
            state.addClaim(new Claim(state.ids.claim(), chunk, owner, kind, core.id, 0, wallNow));
        }
        List<HistoryEvent> events = new ArrayList<>();
        events.add(new LandEvents.NexusPlaced(wallNow, owner, core.id, center));
        events.addAll(reconcile(owner, wallNow));
        return events;
    }

    /** Plants a camp, raising. The caller has checked {@link #campProblem}. */
    public List<HistoryEvent> plantCamp(OrgId owner, ChunkKey center, long wallNow) {
        Core core = new Core(state.ids.core(), CoreKind.CAMP, owner, center, state.takeCoreOrder(), wallNow,
                CampPhase.RAISING, state.siegeClock.now());
        state.addCore(core);
        state.addClaim(new Claim(state.ids.claim(), center, owner, ClaimKind.CAMP, core.id, 0, wallNow));
        List<HistoryEvent> events = new ArrayList<>();
        events.add(new LandEvents.CampPlanted(wallNow, owner, core.id, center));
        events.addAll(reconcile(owner, wallNow));
        return events;
    }

    /** Adds a claim and assigns it. The caller has checked {@link #claimProblem}. */
    public List<HistoryEvent> claim(OrgId owner, ChunkKey chunk, long wallNow) {
        Plan plan = plan(owner, chunk);
        CoreId core = plan.assigned.get(chunk);
        state.addClaim(new Claim(state.ids.claim(), chunk, owner, ClaimKind.OUTLYING, core, plan.hops.get(chunk), wallNow));
        List<HistoryEvent> events = new ArrayList<>();
        events.add(new LandEvents.Claimed(wallNow, owner, chunk, core));
        events.addAll(reconcile(owner, wallNow));
        return events;
    }

    /** Gives up a claim; land that was only linked through it goes contested. */
    public List<HistoryEvent> unclaim(OrgId owner, ChunkKey chunk, long wallNow) {
        state.claimAt(chunk).ifPresent(c -> state.removeClaim(c.id));
        List<HistoryEvent> events = new ArrayList<>();
        events.add(new LandEvents.Unclaimed(wallNow, owner, chunk));
        events.addAll(reconcile(owner, wallNow));
        return events;
    }

    /**
     * A keep fell or a camp was destroyed (DESIGN.md §11.1). The core and the chunks
     * that made it up are gone. Every other claim of the owner is assigned afresh:
     * those still connected to another core within range and capacity move to it
     * (nearest first, so the farthest drop first), and the rest go contested.
     */
    public List<HistoryEvent> loseCore(CoreId id, long wallNow) {
        Core core = state.core(id).orElseThrow();
        List<Claim> mine = state.claimsOf(core.owner).stream().filter(c -> id.equals(c.core())).toList();
        List<HistoryEvent> events = new ArrayList<>();
        events.add(new LandEvents.CoreLost(wallNow, core.owner, id, core.kind, core.center));
        for (Claim claim : mine) {
            if (claim.fixed()) state.removeClaim(claim.id);
        }
        state.removeCore(id);
        events.addAll(reconcile(core.owner, wallNow));
        return events;
    }

    /**
     * Ends the grace of contested claims whose time has run out on the siege clock:
     * they are no longer claims (DESIGN.md §11.2).
     */
    public List<HistoryEvent> expireGrace(long wallNow) {
        long siegeNow = state.siegeClock.now();
        Map<OrgId, Integer> lapsed = new LinkedHashMap<>();
        for (Claim claim : List.copyOf(state.claims())) {
            if (claim.contested() && claim.contestedUntil() <= siegeNow) {
                state.removeClaim(claim.id);
                lapsed.merge(claim.owner, 1, Integer::sum);
            }
        }
        List<HistoryEvent> events = new ArrayList<>();
        lapsed.forEach((org, n) -> events.add(new LandEvents.Lapsed(wallNow, org, n)));
        return events;
    }

    // ---- assignment ----

    /** Assigns an owner's land afresh and reports what moved. */
    List<HistoryEvent> reconcile(OrgId owner, long wallNow) {
        Plan plan = plan(owner, null);
        long graceEnds = state.siegeClock.now() + Durations.minutes(knobs.orphanGraceMinutes);
        int contested = 0;
        int rescued = 0;
        Map<List<CoreId>, Integer> byPair = new LinkedHashMap<>();

        for (Claim claim : state.claimsOf(owner)) {
            if (claim.fixed()) continue;
            CoreId target = plan.assigned.get(claim.chunk);
            if (target == null) {
                if (!claim.contested()) {
                    claim.contest(graceEnds);
                    contested++;
                }
                continue;
            }
            Core core = state.core(target).orElseThrow();
            ClaimKind kind = core.isKeep() ? ClaimKind.OUTLYING : ClaimKind.CAMP;
            if (claim.contested()) rescued++;
            else if (claim.core() != null && !claim.core().equals(target)) {
                List<CoreId> pair = new ArrayList<>();
                pair.add(claim.core());
                pair.add(target);
                byPair.merge(pair, 1, Integer::sum);
            }
            claim.assign(target, kind, plan.hops.get(claim.chunk));
        }

        List<HistoryEvent> events = new ArrayList<>();
        byPair.forEach((pair, n) -> events.add(new LandEvents.Reassigned(wallNow, owner, pair.get(0), pair.get(1), n)));
        if (rescued > 0) events.add(new LandEvents.Rescued(wallNow, owner, rescued));
        if (contested > 0) events.add(new LandEvents.Contested(wallNow, owner, contested, graceEnds));
        return events;
    }

    /** A computed assignment of every chunk an owner holds, plus an optional chunk about to be added. */
    private record Plan(Map<ChunkKey, CoreId> assigned, Map<ChunkKey, Integer> hops, Set<ChunkKey> reachable,
                        List<ChunkKey> orphans) {
    }

    private Plan plan(OrgId owner, ChunkKey extra) {
        Set<ChunkKey> chunks = new LinkedHashSet<>();
        Map<ChunkKey, CoreId> assigned = new LinkedHashMap<>();
        Map<ChunkKey, Integer> hops = new HashMap<>();
        List<ChunkKey> loose = new ArrayList<>();
        for (Claim claim : state.claimsOf(owner)) {
            chunks.add(claim.chunk);
            if (claim.fixed() && claim.core() != null) {
                assigned.put(claim.chunk, claim.core());
                hops.put(claim.chunk, claim.hops());
            } else {
                loose.add(claim.chunk);
            }
        }
        if (extra != null) {
            chunks.add(extra);
            loose.add(extra);
        }

        List<Core> cores = state.coresOf(owner).stream().filter(c -> c.isKeep() || c.phase() != CampPhase.PACKED).sorted(Comparator.comparingLong(c -> c.order)).toList();
        Map<CoreId, Map<ChunkKey, Integer>> distances = new HashMap<>();
        for (Core core : cores) distances.put(core.id, distancesFrom(core.center, chunks));

        // Candidates for each loose chunk: cores within range, nearest first, older first on a tie.
        Map<ChunkKey, List<Core>> candidates = new HashMap<>();
        Map<ChunkKey, Integer> nearest = new HashMap<>();
        Set<ChunkKey> reachable = new HashSet<>();
        for (ChunkKey chunk : loose) {
            List<Core> within = new ArrayList<>();
            for (Core core : cores) {
                Integer d = distances.get(core.id).get(chunk);
                if (d != null && d >= 1 && d <= range(core)) within.add(core);
            }
            within.sort(Comparator.<Core>comparingInt(c -> distances.get(c.id).get(chunk)).thenComparingLong(c -> c.order));
            candidates.put(chunk, within);
            if (!within.isEmpty()) {
                reachable.add(chunk);
                nearest.put(chunk, distances.get(within.get(0).id).get(chunk));
            }
        }

        // Nearest claims fill a core first, so the farthest are the ones that drop.
        List<ChunkKey> order = new ArrayList<>(reachable);
        order.sort(Comparator.<ChunkKey>comparingInt(nearest::get).thenComparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z));
        Map<CoreId, Integer> used = new HashMap<>();
        for (ChunkKey chunk : order) {
            for (Core core : candidates.get(chunk)) {
                if (used.getOrDefault(core.id, 0) < capacity(core)) {
                    used.merge(core.id, 1, Integer::sum);
                    assigned.put(chunk, core.id);
                    hops.put(chunk, distances.get(core.id).get(chunk));
                    break;
                }
            }
        }
        List<ChunkKey> orphans = new ArrayList<>();
        for (ChunkKey chunk : loose) {
            if (!assigned.containsKey(chunk)) orphans.add(chunk);
        }
        // The extra chunk is reported through assigned/reachable, not as an orphan to explain away.
        if (extra != null) orphans.remove(extra);
        return new Plan(assigned, hops, reachable, orphans);
    }

    /** Edge-adjacent hop counts from a start chunk through the given chunks. */
    private static Map<ChunkKey, Integer> distancesFrom(ChunkKey start, Set<ChunkKey> chunks) {
        Map<ChunkKey, Integer> dist = new HashMap<>();
        Deque<ChunkKey> queue = new ArrayDeque<>();
        dist.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            ChunkKey at = queue.poll();
            for (ChunkKey next : neighbours(at)) {
                if (chunks.contains(next) && !dist.containsKey(next)) {
                    dist.put(next, dist.get(at) + 1);
                    queue.add(next);
                }
            }
        }
        return dist;
    }

    // ---- perimeters ----

    /**
     * The perimeter of a core with each side open or closed (DESIGN.md §10.4, §10.10,
     * decision D17). A keep's perimeter chunk is closed if held by a faction with an
     * active camp touching it. A camp's is closed if held by a faction the camp is
     * besieging or raiding, or one with an active camp against the camp's owner;
     * the {@code hostility} says who attacks whom. Neutral claims stay open, and so
     * do contested claims: land with no core behind it holds no side shut.
     */
    public Perimeter perimeter(Core core, Hostility hostility) {
        return core.isKeep() ? keepPerimeter(core) : campPerimeter(core, hostility);
    }

    public Perimeter perimeter(Core core) {
        return perimeter(core, Hostility.NONE);
    }

    private Perimeter keepPerimeter(Core keep) {
        List<ChunkKey> chunks = keepPerimeterChunks(keep.center);
        Set<ChunkKey> inPerimeter = new HashSet<>(chunks);
        OrgId defender = faction(keep.owner);
        Set<OrgId> besiegers = new HashSet<>();
        for (Core camp : state.cores()) {
            if (!camp.activeCamp() || faction(camp.owner).equals(defender)) continue;
            boolean touches = state.claims().stream()
                    .anyMatch(c -> camp.id.equals(c.core()) && inPerimeter.contains(c.chunk));
            if (touches) besiegers.add(faction(camp.owner));
        }
        Set<ChunkKey> closed = new LinkedHashSet<>();
        for (ChunkKey chunk : chunks) {
            state.claimAt(chunk).ifPresent(c -> {
                if (!c.contested() && besiegers.contains(faction(c.owner))) closed.add(chunk);
            });
        }
        return new Perimeter(keep.id, chunks, closed);
    }

    private Perimeter campPerimeter(Core camp, Hostility hostility) {
        Set<ChunkKey> own = new HashSet<>();
        for (Claim claim : state.claims()) {
            if (camp.id.equals(claim.core())) own.add(claim.chunk);
        }
        Set<ChunkKey> edge = new LinkedHashSet<>();
        for (ChunkKey chunk : own) {
            for (ChunkKey next : neighbours(chunk)) {
                if (!own.contains(next)) edge.add(next);
            }
        }
        OrgId home = faction(camp.owner);
        Set<OrgId> hostile = new HashSet<>(hostility.targets(camp));
        for (Core other : state.cores()) {
            if (other.activeCamp() && !faction(other.owner).equals(home) && hostility.targets(other).contains(home)) {
                hostile.add(faction(other.owner));
            }
        }
        Set<ChunkKey> closed = new LinkedHashSet<>();
        for (ChunkKey chunk : edge) {
            state.claimAt(chunk).ifPresent(c -> {
                OrgId holder = faction(c.owner);
                if (!c.contested() && !holder.equals(home) && hostile.contains(holder)) closed.add(chunk);
            });
        }
        return new Perimeter(camp.id, new ArrayList<>(edge), closed);
    }
}
