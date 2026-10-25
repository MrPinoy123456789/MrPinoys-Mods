package charter.land;

import charter.Fixture;
import charter.engine.Outcome;
import charter.gov.Preset;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.actions.ClaimLand;
import charter.land.actions.LoseCore;
import charter.land.actions.PlaceNexus;
import charter.land.actions.PlantCamp;
import charter.land.actions.Unclaim;
import charter.time.Durations;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/**
 * The land graph (PLAN.md M2): placement refusals, hop assignment, capacity and
 * range, the member cap, perimeters and their open and closed sides, and keep
 * loss (acceptance tests P and the graph half of L). Everything goes through the
 * real engine.
 */
public final class LandTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId BOB = player("Bob");
    static final PlayerId HAL = player("Hal");
    static final PlayerId GIL = player("Gil");
    static final PlayerId ZED = player("Zed");
    static final PlayerId ANN = player("Ann");
    static final PlayerId DEN = player("Den");

    private LandTest() {
    }

    public static void run() {
        keepAndPerimeter();
        placementRefusals();
        rangeCapacityAndMemberCap();
        hopAssignmentOlderCoreWinsTies();
        testPKeepLoss();
        graceLapses();
        unclaimingContestsAndReclaimingRescues();
        testLEncirclementGraph();
        campPerimeterFollowsHostility();
    }

    // ---- helpers ----

    static ChunkKey ch(int x, int z) {
        return new ChunkKey("minecraft:overworld", x, z);
    }

    /** Ashfall, a Lordship with four members and a funded treasury, and Brinewatch, a smaller rival. */
    record World(Fixture f, OrgId ashfall, OrgId brine) {
    }

    static World world() {
        Fixture f = new Fixture();
        OrgId ashfall = f.found("Ashfall", Preset.LORDSHIP, KRIS, BOB, HAL, GIL);
        f.as(GIL, new Deposit(ashfall, Currency.DIAMOND, 500));
        f.as(GIL, new Deposit(ashfall, Currency.COBBLESTONE, 5000));
        OrgId brine = f.found("Brinewatch", Preset.LORDSHIP, ZED, ANN, DEN);
        f.as(DEN, new Deposit(brine, Currency.DIAMOND, 500));
        f.as(DEN, new Deposit(brine, Currency.COBBLESTONE, 5000));
        return new World(f, ashfall, brine);
    }

    static Outcome keep(World w, int x, int z) {
        return w.f.as(KRIS, new PlaceNexus(w.ashfall, ch(x, z)));
    }

    static Outcome claim(World w, int x, int z) {
        return w.f.as(KRIS, new ClaimLand(w.ashfall, ch(x, z)));
    }

    /** Claims a run of chunks along the x axis at z = 0. */
    static void claimRow(World w, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            Outcome out = claim(w, x, 0);
            if (!out.done()) throw new IllegalStateException("claim " + x + " failed: " + out);
        }
    }

    static Claim at(World w, int x, int z) {
        return w.f.state.claimAt(ch(x, z)).orElse(null);
    }

    static Core coreOf(World w, OrgId org, ChunkKey center) {
        return w.f.state.coresOf(org).stream().filter(c -> c.center.equals(center)).findFirst().orElseThrow();
    }

    static boolean has(Outcome out, Class<? extends HistoryEvent> type) {
        return out instanceof Outcome.Done d && d.events().stream().anyMatch(type::isInstance);
    }

    static Set<ChunkKey> chunksOf(List<ChunkKey> list) {
        return new HashSet<>(list);
    }

    // ---- tests ----

    static void keepAndPerimeter() {
        World w = world();
        Outcome placed = keep(w, 0, 0);
        check("keep: a Lord with the power places a nexus", placed.done());
        check("keep: the placement is recorded", has(placed, LandEvents.NexusPlaced.class));
        eq("keep: the price comes out of the treasury", 500L - w.f.knobs.keepPriceDiamonds, w.f.org(w.ashfall).ledger.balance(Currency.DIAMOND));
        eq("keep: nine chunks are claimed", 9, w.f.state.claimsOf(w.ashfall).size());
        eq("keep: the middle chunk holds the nexus", ClaimKind.KEEP_CORE, at(w, 0, 0).kind());
        eq("keep: the other eight are the ring", 8L, w.f.state.claimsOf(w.ashfall).stream().filter(c -> c.kind() == ClaimKind.KEEP_RING).count());

        Core core = coreOf(w, w.ashfall, ch(0, 0));
        Perimeter perimeter = new LandGraph(w.f.state, w.f.knobs).perimeter(core);
        eq("keep: the perimeter is twelve chunks", 12, perimeter.chunks().size());
        check("keep: it has no corner chunks", !chunksOf(perimeter.chunks()).contains(ch(2, 2)) && !chunksOf(perimeter.chunks()).contains(ch(-2, -2)));
        check("keep: it has the chunks beside the ring", chunksOf(perimeter.chunks()).containsAll(List.of(ch(-2, 0), ch(2, 1), ch(0, -2), ch(-1, 2))));
        eq("keep: with no besiegers every side is open", 12, perimeter.open().size());
        check("keep: the keep's own farms there stay open", claim(w, 2, 0).done() && !new LandGraph(w.f.state, w.f.knobs).perimeter(core).isClosed(ch(2, 0)));
    }

    static void placementRefusals() {
        World w = world();
        check("setup: Ashfall's keep is placed", keep(w, 0, 0).done());

        // dimension
        ChunkKey nether = new ChunkKey("minecraft:the_nether", 40, 40);
        eq("wrong dimension: a keep in the nether is refused", "wrong_dimension", w.f.as(KRIS, new PlaceNexus(w.ashfall, nether)).reasonKey());
        eq("wrong dimension: a camp in the nether is refused", "wrong_dimension", w.f.as(KRIS, new PlantCamp(w.ashfall, nether)).reasonKey());
        eq("wrong dimension: a claim in the nether is refused", "wrong_dimension", w.f.as(KRIS, new ClaimLand(w.ashfall, nether)).reasonKey());
        w.f.knobs.claimDimensions.add("minecraft:the_nether");
        check("wrong dimension: the knob lists where claims may be made", w.f.as(KRIS, new PlantCamp(w.ashfall, nether)).done());
        w.f.knobs.claimDimensions.remove("minecraft:the_nether");

        // inside another claim
        eq("inside a claim: a keep overlapping the ring is refused", "keep_needs_room", keep(w, 2, 0).reasonKey());
        eq("inside a claim: claiming a held chunk is refused", "claimed", claim(w, 1, 1).reasonKey());
        eq("inside a claim: a camp cannot stand inside a claim", "claimed", w.f.as(KRIS, new PlantCamp(w.ashfall, ch(0, 0))).reasonKey());
        eq("inside a claim: nor can a rival's camp", "claimed", w.f.as(ZED, new PlantCamp(w.brine, ch(-1, 1))).reasonKey());
        check("inside a claim: beside it is fine for a rival's camp", w.f.as(ZED, new PlantCamp(w.brine, ch(-2, 0))).done());
        eq("inside a claim: the rival camp is raising", CampPhase.RAISING, coreOf(w, w.brine, ch(-2, 0)).phase());
        eq("inside a claim: a camp costs its price", 5000L - w.f.knobs.warBannerPriceCobblestone, w.f.org(w.brine).ledger.balance(Currency.COBBLESTONE));

        // not adjacent
        eq("not adjacent: a claim away from your land is refused", "not_adjacent", claim(w, 10, 10).reasonKey());
        eq("not adjacent: a diagonal does not count", "not_adjacent", claim(w, 2, 2).reasonKey());
        eq("not adjacent: land held by a rival does not link", "not_adjacent", w.f.as(KRIS, new ClaimLand(w.ashfall, ch(-3, 0))).reasonKey());

        // keep gap between factions
        eq("keep gap: a rival keep two chunks from the footprint is refused", "keep_gap", w.f.as(ZED, new PlaceNexus(w.brine, ch(5, 0))).reasonKey());
        eq("keep gap: three chunks is still too close", "keep_gap", w.f.as(ZED, new PlaceNexus(w.brine, ch(6, 0))).reasonKey());
        eq("keep gap: diagonal counts the same", "keep_gap", w.f.as(ZED, new PlaceNexus(w.brine, ch(6, 6))).reasonKey());
        check("keep gap: four free chunks is enough", w.f.as(ZED, new PlaceNexus(w.brine, ch(7, 0))).done());
        check("keep gap: the same faction needs no gap", keep(w, 0, 3).done());

        // paying
        World poor = world();
        poor.f.admin(new charter.treasury.actions.Withdraw(poor.ashfall, Currency.DIAMOND, 450, KRIS));
        eq("price: setup left the treasury short", 50L, poor.f.org(poor.ashfall).ledger.balance(Currency.DIAMOND));
        eq("price: a keep the treasury cannot pay for is refused", "no_funds", keep(poor, 0, 0).reasonKey());

        // authority
        World plain = world();
        eq("power: a plain member cannot place a nexus", "needs_vote", plain.f.as(BOB, new PlaceNexus(plain.ashfall, ch(0, 0))).reasonKey());
        check("power: nothing was claimed by the refusal", plain.f.state.claims().isEmpty());
        eq("power: a plain member cannot claim", "needs_vote", plain.f.as(BOB, new ClaimLand(plain.ashfall, ch(0, 0))).reasonKey());
        eq("power: a plain member cannot plant a camp", "needs_vote", plain.f.as(BOB, new PlantCamp(plain.ashfall, ch(0, 0))).reasonKey());
        eq("power: an outsider is refused outright", "no_authority", plain.f.as(ZED, new PlaceNexus(plain.ashfall, ch(0, 0))).reasonKey());
        eq("power: only the rules or an operator lose a core", "operator_only",
                w.f.as(KRIS, new LoseCore(coreOf(w, w.ashfall, ch(0, 0)).id)).reasonKey());

        // giving up land
        eq("unclaim: a keep chunk cannot be given up alone", "part_of_core", w.f.as(KRIS, new Unclaim(w.ashfall, ch(1, 1))).reasonKey());
        eq("unclaim: a rival's chunk is not yours to give up", "not_your_claim", w.f.as(KRIS, new Unclaim(w.ashfall, ch(-2, 0))).reasonKey());
        eq("unclaim: an unclaimed chunk is not yours", "not_your_claim", w.f.as(KRIS, new Unclaim(w.ashfall, ch(30, 30))).reasonKey());
    }

    static void rangeCapacityAndMemberCap() {
        // range: keep range is 8 hops, so the ninth chunk out is too far
        World w = world();
        keep(w, 0, 0);
        claimRow(w, 2, 8);
        eq("range: a chunk eight hops out is held", 8, at(w, 8, 0).hops());
        eq("range: it is assigned to the keep", ClaimKind.OUTLYING, at(w, 8, 0).kind());
        eq("range: the ninth hop is refused", "out_of_range", claim(w, 9, 0).reasonKey());

        // camp range is 2 hops
        World c = world();
        c.f.as(KRIS, new PlantCamp(c.ashfall, ch(30, 30)));
        check("camp range: one hop out is held", claim(c, 31, 30).done());
        check("camp range: two hops out is held", claim(c, 32, 30).done());
        eq("camp range: three hops is refused", "out_of_range", claim(c, 33, 30).reasonKey());

        // capacity
        World cap = world();
        cap.f.knobs.keepCapacityClaims = 3;
        keep(cap, 0, 0);
        claimRow(cap, 2, 4);
        eq("capacity: a full keep takes no more", "core_full", claim(cap, 5, 0).reasonKey());
        check("capacity: the refusal changed nothing", at(cap, 5, 0) == null && cap.f.state.claimsOf(cap.ashfall).size() == 12);
        check("capacity: a second keep in reach takes the overflow", cap.f.as(KRIS, new PlaceNexus(cap.ashfall, ch(8, 0))).done());
        check("capacity: its side of the gap is claimable", claim(cap, 6, 0).done());
        check("capacity: and so is the chunk the first keep could not take", claim(cap, 5, 0).done());

        // camp capacity: 4 claims beside the banner
        World cc = world();
        cc.f.as(KRIS, new PlantCamp(cc.ashfall, ch(30, 30)));
        for (int[] p : new int[][]{{31, 30}, {29, 30}, {30, 31}, {30, 29}}) claim(cc, p[0], p[1]);
        eq("camp capacity: four claims are held", 4, cc.f.state.claimsOf(cc.ashfall).size() - 1);
        eq("camp capacity: a fifth is refused", "core_full", claim(cc, 31, 31).reasonKey());

        // member cap: six per active member seen in the last 14 days
        World m = world();
        keep(m, 0, 0);
        eq("member cap: four active members hold 24", 24, new LandGraph(m.f.state, m.f.knobs).landCap(m.ashfall, m.f.clock.now()));
        eq("member cap: the keep's own nine do not count", 0, new LandGraph(m.f.state, m.f.knobs).countedClaims(m.ashfall));
        m.f.passDays(15, KRIS);
        eq("member cap: members unseen for 14 days stop counting", 6, new LandGraph(m.f.state, m.f.knobs).landCap(m.ashfall, m.f.clock.now()));
        claimRow(m, 2, 7);
        eq("member cap: six claims reach it", "member_cap", claim(m, 8, 0).reasonKey());
        m.f.seen(BOB);
        check("member cap: a member seen again raises it", claim(m, 8, 0).done());

        // camp claims count toward the cap, the banner chunk does not
        World cm = world();
        cm.f.knobs.claimsPerActiveMember = 1;
        cm.f.as(KRIS, new PlantCamp(cm.ashfall, ch(30, 30)));
        eq("member cap: a banner chunk is free", 0, new LandGraph(cm.f.state, cm.f.knobs).countedClaims(cm.ashfall));
        for (int[] p : new int[][]{{31, 30}, {29, 30}, {30, 31}, {30, 29}}) {
            if (!claim(cm, p[0], p[1]).done()) break;
        }
        eq("member cap: camp claims count, so four members hold four", 4, new LandGraph(cm.f.state, cm.f.knobs).countedClaims(cm.ashfall));
        check("member cap: a camp claim over the cap is refused", !claim(cm, 31, 31).done());
    }

    static void hopAssignmentOlderCoreWinsTies() {
        World w = world();
        w.f.knobs.keepCapacityClaims = 40;
        keep(w, 0, 0);
        keep(w, 10, 0);
        claimRow(w, 2, 8);
        CoreId a = coreOf(w, w.ashfall, ch(0, 0)).id;
        CoreId b = coreOf(w, w.ashfall, ch(10, 0)).id;
        eq("hops: two chunks from the first keep belong to it", a, at(w, 2, 0).core());
        eq("hops: the hop count is kept", 2, at(w, 2, 0).hops());
        eq("hops: five from each is a tie, and the older keep wins", a, at(w, 5, 0).core());
        eq("hops: four from the second keep belongs to it", b, at(w, 6, 0).core());
        eq("hops: its hop count is four", 4, at(w, 6, 0).hops());
        check("hops: the older keep really is older", coreOf(w, w.ashfall, ch(0, 0)).order < coreOf(w, w.ashfall, ch(10, 0)).order);
    }

    /** Two linked keeps; one falls; connected claims move within capacity, farthest drop, the rest orphan; a camp saves them. */
    static World twoLinkedKeeps() {
        World w = world();
        w.f.knobs.keepCapacityClaims = 5;
        keep(w, 0, 0);
        keep(w, 10, 0);
        // land is built from both ends so each chunk is linked to a keep with room when it is claimed
        for (int x : new int[]{2, 3, 4, 5, 8, 7, 6}) {
            Outcome out = claim(w, x, 0);
            if (!out.done()) throw new IllegalStateException("claim " + x + " failed: " + out);
        }
        return w;
    }

    static void testPKeepLoss() {
        World w = twoLinkedKeeps();
        CoreId a = coreOf(w, w.ashfall, ch(0, 0)).id;
        CoreId b = coreOf(w, w.ashfall, ch(10, 0)).id;
        check("P: before the fall x=2..5 belong to the first keep", at(w, 5, 0).core().equals(a) && at(w, 2, 0).core().equals(a));
        check("P: before the fall x=6..8 belong to the second", at(w, 6, 0).core().equals(b) && at(w, 8, 0).core().equals(b));

        long siegeBefore = w.f.state.siegeClock.now();
        Outcome fell = w.f.admin(new LoseCore(b));
        check("P: the fall is recorded", has(fell, LandEvents.CoreLost.class));
        check("P: a claim moved to the surviving keep", has(fell, LandEvents.Reassigned.class));
        check("P: the rest went contested", has(fell, LandEvents.Contested.class));
        check("P: the lost keep is gone", w.f.state.core(b).isEmpty());
        check("P: its nine chunks are no longer claimed", at(w, 10, 0) == null && at(w, 9, 1) == null && at(w, 11, -1) == null);

        eq("P: the connected claim within capacity moved to the survivor", a, at(w, 6, 0).core());
        eq("P: the survivor now holds its full five", 5L, w.f.state.claims().stream().filter(c -> a.equals(c.core()) && !c.fixed()).count());
        check("P: the nearest of the rest stay put", !at(w, 2, 0).contested() && !at(w, 5, 0).contested());
        check("P: the farthest claims drop first", at(w, 7, 0).contested() && at(w, 8, 0).contested());
        check("P: orphans stay claimed and protected while contested", at(w, 7, 0) != null && at(w, 8, 0) != null);
        check("P: orphans belong to no core", at(w, 7, 0).core() == null);
        eq("P: the grace is thirty minutes of siege time", siegeBefore + Durations.minutes(30), at(w, 7, 0).contestedUntil());

        // a camp planted to link them saves them
        Outcome camp = w.f.as(KRIS, new PlantCamp(w.ashfall, ch(7, 1)));
        check("P: the camp is planted", camp.done());
        check("P: the rescue is recorded", has(camp, LandEvents.Rescued.class));
        CoreId campId = coreOf(w, w.ashfall, ch(7, 1)).id;
        check("P: the orphans are no longer contested", !at(w, 7, 0).contested() && !at(w, 8, 0).contested());
        eq("P: they belong to the camp", campId, at(w, 7, 0).core());
        eq("P: and take the camp's kind", ClaimKind.CAMP, at(w, 8, 0).kind());
        w.f.state.siegeClock.advanceSiege(Durations.hours(2));
        eq("P: nothing lapses after the grace would have ended", 0, new LandGraph(w.f.state, w.f.knobs).expireGrace(w.f.clock.now()).size());
        check("P: the saved land is still held", at(w, 8, 0) != null);
    }

    static void graceLapses() {
        World w = twoLinkedKeeps();
        w.f.admin(new LoseCore(coreOf(w, w.ashfall, ch(10, 0)).id));
        w.f.state.siegeClock.advanceSiege(Durations.minutes(29));
        eq("grace: nothing lapses at twenty nine minutes", 0, new LandGraph(w.f.state, w.f.knobs).expireGrace(w.f.clock.now()).size());
        check("grace: the orphans are still claimed", at(w, 7, 0) != null && at(w, 8, 0) != null);
        w.f.state.siegeClock.advanceSiege(Durations.minutes(2));
        List<HistoryEvent> lapsed = new LandGraph(w.f.state, w.f.knobs).expireGrace(w.f.clock.now());
        check("grace: at thirty one minutes they lapse", lapsed.size() == 1 && lapsed.get(0) instanceof LandEvents.Lapsed l && l.count() == 2);
        check("grace: the orphans are no longer claims", at(w, 7, 0) == null && at(w, 8, 0) == null);
        check("grace: the survivor's land is untouched", at(w, 6, 0) != null && !at(w, 6, 0).contested());
        check("grace: the chunks can be claimed by anyone again", w.f.state.claimAt(ch(8, 0)).isEmpty());
    }

    static void unclaimingContestsAndReclaimingRescues() {
        World w = world();
        keep(w, 0, 0);
        claimRow(w, 2, 4);
        Outcome gave = w.f.as(KRIS, new Unclaim(w.ashfall, ch(3, 0)));
        check("unclaim: the claim is given up", gave.done() && at(w, 3, 0) == null);
        check("unclaim: land linked only through it goes contested", at(w, 4, 0).contested());
        check("unclaim: land still linked does not", !at(w, 2, 0).contested());
        Outcome again = claim(w, 3, 0);
        check("unclaim: claiming the gap again rescues the land beyond", has(again, LandEvents.Rescued.class) && !at(w, 4, 0).contested());
    }

    static void testLEncirclementGraph() {
        World w = world();
        keep(w, 0, 0);
        Core keep = coreOf(w, w.ashfall, ch(0, 0));
        LandGraph graph = new LandGraph(w.f.state, w.f.knobs);

        // four corner camps of Brinewatch cover all twelve perimeter chunks
        int[][][] camps = {
                {{2, -2}, {1, -2}, {0, -2}, {2, -1}, {2, 0}},
                {{-2, -2}, {-1, -2}, {-2, -1}, {-2, 0}},
                {{-2, 2}, {-2, 1}, {-1, 2}, {0, 2}},
                {{2, 2}, {1, 2}, {2, 1}},
        };
        for (int[][] camp : camps) {
            check("L: a camp is planted at " + camp[0][0] + "," + camp[0][1], w.f.as(ZED, new PlantCamp(w.brine, ch(camp[0][0], camp[0][1]))).done());
            for (int i = 1; i < camp.length; i++) {
                Outcome out = w.f.as(ZED, new ClaimLand(w.brine, ch(camp[i][0], camp[i][1])));
                if (!out.done()) check("L: claim " + camp[i][0] + "," + camp[i][1] + " failed: " + out, false);
            }
        }
        eq("L: the besiegers hold twelve perimeter chunks", 12L, graph.perimeter(keep).chunks().stream().filter(c -> w.f.state.claimAt(c).isPresent()).count());
        eq("L: camps still raising close nothing", 12, graph.perimeter(keep).open().size());

        for (Core core : w.f.state.coresOf(w.brine)) core.setPhase(CampPhase.ACTIVE, w.f.state.siegeClock.now());
        Perimeter closed = graph.perimeter(keep);
        check("L: all twelve held by active besiegers reads as fully closed", closed.fullyClosed());
        eq("L: no side is open", 0, closed.open().size());

        // breaking the ring: destroy the camp at (2,2); exactly its two sides reopen
        Core southeast = coreOf(w, w.brine, ch(2, 2));
        w.f.admin(new LoseCore(southeast.id));
        Perimeter broken = graph.perimeter(keep);
        check("L: no longer fully closed", !broken.fullyClosed());
        eq("L: exactly the lost camp's sides reopen", Set.of(ch(1, 2), ch(2, 1)), chunksOf(broken.open()));
        eq("L: the other ten stay closed", 10, broken.closed().size());

        // taking back a perimeter chunk also reopens that side: a neutral third party's claim never closes one
        World n = world();
        keep(n, 0, 0);
        n.f.as(ZED, new PlantCamp(n.brine, ch(2, 1)));
        Perimeter withRaising = new LandGraph(n.f.state, n.f.knobs).perimeter(coreOf(n, n.ashfall, ch(0, 0)));
        check("L: a camp still raising leaves its side open", !withRaising.isClosed(ch(2, 1)));
        coreOf(n, n.brine, ch(2, 1)).setPhase(CampPhase.ACTIVE, 0);
        check("L: once active it closes the chunk it holds", new LandGraph(n.f.state, n.f.knobs).perimeter(coreOf(n, n.ashfall, ch(0, 0))).isClosed(ch(2, 1)));
        check("L: and a chunk it does not hold stays open", !new LandGraph(n.f.state, n.f.knobs).perimeter(coreOf(n, n.ashfall, ch(0, 0))).isClosed(ch(2, 0)));
    }

    static void campPerimeterFollowsHostility() {
        World w = world();
        w.f.as(KRIS, new PlantCamp(w.ashfall, ch(50, 50)));
        int[][] around = {{51, 50}, {49, 50}, {50, 51}, {50, 49}};
        for (int[] p : around) w.f.as(ZED, new PlantCamp(w.brine, ch(p[0], p[1])));
        Core mine = coreOf(w, w.ashfall, ch(50, 50));
        LandGraph graph = new LandGraph(w.f.state, w.f.knobs);

        eq("camp: its perimeter is every chunk beside its claims", 4, graph.perimeter(mine).chunks().size());
        eq("camp: with no quarrel a neighbour's claims are open", 4, graph.perimeter(mine).open().size());

        LandGraph.Hostility mineAttacksBrine = camp -> camp.owner.equals(w.ashfall) ? Set.of(w.brine) : Set.of();
        Perimeter byTarget = graph.perimeter(mine, mineAttacksBrine);
        check("camp: chunks held by the faction it besieges are closed", byTarget.fullyClosed());

        LandGraph.Hostility brineAttacksMe = camp -> camp.owner.equals(w.brine) ? Set.of(w.ashfall) : Set.of();
        check("camp: a rival camp still raising against it closes nothing", graph.perimeter(mine, brineAttacksMe).open().size() == 4);
        for (Core core : w.f.state.coresOf(w.brine)) core.setPhase(CampPhase.ACTIVE, 0);
        check("camp: active camps against its owner close their chunks", graph.perimeter(mine, brineAttacksMe).fullyClosed());
        check("camp: and with no quarrel they stay open", graph.perimeter(mine).open().size() == 4);

        // a neutral third party's claim stays open
        World t = world();
        t.f.as(KRIS, new PlantCamp(t.ashfall, ch(50, 50)));
        t.f.as(ZED, new PlantCamp(t.brine, ch(51, 50)));
        Core camp = coreOf(t, t.ashfall, ch(50, 50));
        LandGraph.Hostility aimsElsewhere = c -> c.owner.equals(t.ashfall) ? Set.of(new OrgId(java.util.UUID.nameUUIDFromBytes("other".getBytes()))) : Set.of();
        check("camp: a neutral neighbour's claim is open", !new LandGraph(t.f.state, t.f.knobs).perimeter(camp, aimsElsewhere).isClosed(ch(51, 50)));
    }
}
