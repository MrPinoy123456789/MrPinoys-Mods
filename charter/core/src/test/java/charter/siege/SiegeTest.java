package charter.siege;

import charter.Fixture;
import charter.engine.Outcome;
import charter.food.FoodTable;
import charter.food.ShelfClass;
import charter.gov.Preset;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.actions.GrantPower;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.CoreId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.CampPhase;
import charter.land.Core;
import charter.land.actions.ClaimLand;
import charter.land.actions.LoseCore;
import charter.land.actions.PlaceNexus;
import charter.land.actions.PlantCamp;
import charter.siege.actions.ChooseFall;
import charter.siege.actions.DepositFood;
import charter.time.Durations;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/**
 * Acceptance tests I (core), J, K (core), L, M, N, O, Q, R and X, the fall, and
 * the time scale (DESIGN.md §22, PLAN.md §10). Ashfall's keep sits at chunk 0,0;
 * its twelve perimeter chunks are x = -2 or 2 with z in -1..1, and z = -2 or 2
 * with x in -1..1. Brinewatch besieges from there.
 */
public final class SiegeTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId GIL = player("Gil");
    static final PlayerId CY = player("Cy");
    static final PlayerId ZED = player("Zed");
    static final PlayerId ANN = player("Ann");
    static final PlayerId DEN = player("Den");
    static final PlayerId VAL = player("Val");
    static final PlayerId VIN = player("Vin");

    static final String BREAD = "minecraft:bread";
    static final String BEEF = "minecraft:cooked_beef";

    private SiegeTest() {
    }

    public static void run() {
        testIRaising();
        testJEndurance(3);
        testJEndurance(30);
        testJPresenceFloor();
        timeScaleAgrees();
        testKCarryIn();
        cartPool();
        depositNeedsMembershipOrGrant();
        testLEncirclement();
        testMStarvationGate();
        testNCampSupply();
        testORaid();
        testQNewFaction();
        testRTruce();
        theFall();
        testXSally();
        notorietyRisesAndDecays();
    }

    // ---- the world ----

    static ChunkKey ch(int x, int z) {
        return new ChunkKey("minecraft:overworld", x, z);
    }

    static FoodTable table() {
        return new FoodTable()
                .put(BREAD, 5, ShelfClass.PRESERVED)
                .put("minecraft:dried_kelp", 1, ShelfClass.PRESERVED)
                .put(BEEF, 8, ShelfClass.COOKED)
                .put("minecraft:carrot", 3, ShelfClass.FRESH);
    }

    /** Counts the tests set by hand. */
    static final class Inputs implements SiegeInputs {
        final Map<CoreId, Integer> inCamp = new HashMap<>();
        final Map<CoreId, Integer> inKeep = new HashMap<>();
        final Map<CoreId, Integer> inside = new HashMap<>();
        final Map<CoreId, Integer> beside = new HashMap<>();
        public int attackersInCamp(CoreId camp) { return inCamp.getOrDefault(camp, 0); }
        public int defendersInKeep(CoreId keep) { return inKeep.getOrDefault(keep, 0); }
        public int attackersInsideKeep(CoreId keep) { return inside.getOrDefault(keep, 0); }
        public int defendersBesideCamp(CoreId camp) { return beside.getOrDefault(camp, 0); }
        public int prisoners(CoreId keep) { return 0; }
    }

    static final class World {
        final Fixture f = new Fixture();
        final Inputs inputs = new Inputs();
        final SiegeSim sim = new SiegeSim(f.engine, table()).inputs(inputs);
        OrgId ashfall;
        OrgId brine;
        Core keep;
        final List<PlayerId> ashfallMembers = new ArrayList<>();

        Supply supply() {
            return sim.supply();
        }

        SiegeRules rules() {
            return sim.rules();
        }

        /** Advances siege time and keeps everyone seen, so active member counts hold. */
        void advance(long siegeMillis) {
            List<PlayerId> all = new ArrayList<>(ashfallMembers);
            all.addAll(List.of(ZED, ANN, DEN, VAL, VIN));
            f.seen(all.toArray(PlayerId[]::new));
            sim.advance(siegeMillis);
        }

        KeepSupply keepSupply() {
            return f.state.siege.keep(keep.id).supply;
        }

        double granary() {
            return sim.provisions(keep.id).nourishment(sim.food());
        }

        void fill(CoreId site, String item, int n) {
            List<HistoryEvent> events = new ArrayList<>();
            Map<String, Integer> accepted = supply().deposit("setup", site, Map.of(item, n), events);
            if (accepted.getOrDefault(item, 0) != n) throw new IllegalStateException("fill refused: " + accepted);
        }

        /** Plants a Brinewatch camp, stocks it, and claims the extra chunks for it. */
        Core camp(OrgId org, PlayerId lord, int x, int z, int bread, int[]... extra) {
            Outcome planted = f.as(lord, new PlantCamp(org, ch(x, z)));
            if (!planted.done()) throw new IllegalStateException("plant failed: " + planted);
            Core camp = f.state.cores().stream().filter(c -> c.center.equals(ch(x, z))).findFirst().orElseThrow();
            if (bread > 0) fill(camp.id, BREAD, bread);
            for (int[] at : extra) {
                Outcome claimed = f.as(lord, new ClaimLand(org, ch(at[0], at[1])));
                if (!claimed.done()) throw new IllegalStateException("camp claim failed at " + at[0] + "," + at[1] + ": " + claimed);
            }
            return camp;
        }

        Core brineCamp(int x, int z, int bread, int[]... extra) {
            return camp(brine, ZED, x, z, bread, extra);
        }

        void raise() {
            advance(Durations.hours(f.knobs.campRaisingHours));
        }

        /** Advances in steps until the keep falls; returns the siege hours it took, or -1. */
        double hoursToFall(long stepMillis, double maxHours) {
            long start = f.state.siegeClock.now();
            long limit = start + Durations.hours(maxHours);
            while (f.state.siegeClock.now() < limit) {
                advance(stepMillis);
                if (f.state.siege.falls.containsKey(keep.id) || f.state.core(keep.id).isEmpty()) {
                    return (f.state.siegeClock.now() - start) / (double) Durations.HOUR;
                }
            }
            return -1;
        }
    }

    /** Ashfall (with {@code members} members in all) holds a keep at 0,0; Brinewatch is its rival. Both are past new-faction protection. */
    static World world(int members) {
        World w = new World();
        PlayerId[] others = new PlayerId[members - 1];
        others[0] = GIL;
        if (members > 2) others[1] = CY;
        for (int i = 2; i < others.length; i++) others[i] = player("Ashfolk" + i);
        w.ashfallMembers.add(KRIS);
        w.ashfallMembers.addAll(List.of(others));
        w.ashfall = w.f.found("Ashfall", Preset.LORDSHIP, KRIS, others);
        w.brine = w.f.found("Brinewatch", Preset.LORDSHIP, ZED, ANN, DEN);
        w.f.as(GIL, new Deposit(w.ashfall, Currency.DIAMOND, 400));
        w.f.as(GIL, new Deposit(w.ashfall, Currency.COBBLESTONE, 5000));
        w.f.as(DEN, new Deposit(w.brine, Currency.DIAMOND, 400));
        w.f.as(DEN, new Deposit(w.brine, Currency.COBBLESTONE, 5000));
        w.f.passDays(3.1);
        w.advance(0);
        Outcome placed = w.f.as(KRIS, new PlaceNexus(w.ashfall, ch(0, 0)));
        if (!placed.done()) throw new IllegalStateException("keep failed: " + placed);
        w.keep = w.f.state.coresOf(w.ashfall).getFirst();
        return w;
    }

    static World world() {
        return world(3);
    }

    /** Fills the granary to its storage limit with bread. */
    static int fillGranary(World w) {
        int bread = (int) Math.floor(w.rules().storageLimit(w.keep) / 5);
        w.fill(w.keep.id, BREAD, bread);
        return bread;
    }

    // ---- I: raising ----

    static void testIRaising() {
        World w = world();
        fillGranary(w);
        Core camp = w.brineCamp(2, 0, 500);
        var claim = w.f.state.claimAt(ch(2, 0)).orElseThrow();
        check("I: a raising camp is not protected", !w.rules().protectedClaim(claim));
        w.advance(Durations.hours(2) - Durations.MINUTE);
        eq("I: still raising a minute short of 2 hours", CampPhase.RAISING, camp.phase());
        eq("I: a raising camp does not besiege", KeepSupply.SUPPLIED, w.keepSupply());
        check("I: no alert while raising", !w.f.state.history.has(SiegeEvents.CampActive.class));
        w.advance(Durations.MINUTE);
        eq("I: active at 2 siege hours", CampPhase.ACTIVE, camp.phase());
        eq("I: the alert fires once, on becoming active", 1, w.f.state.history.of(SiegeEvents.CampActive.class).size());
        check("I: the alert names the keep's faction", w.f.state.history.of(SiegeEvents.CampActive.class).getFirst().targets().contains(w.ashfall));
        check("I: an active camp is protected", w.rules().protectedClaim(claim));
        eq("I: now the keep is besieged", KeepSupply.BESIEGED, w.keepSupply());
        check("I: the siege is recorded", w.f.state.history.has(SiegeEvents.SiegeBegan.class));
    }

    // ---- J: endurance ----

    static void testJEndurance(int members) {
        World w = world(members);
        int bread = fillGranary(w);
        w.brineCamp(2, 0, 20_000);
        w.raise();
        double hours = w.hoursToFall(Durations.MINUTE * 10, 100);
        check("J: " + members + " mouths: " + bread + " bread at the storage limit", bread > 0);
        check("J: " + members + " mouths: the keep falls (" + hours + "h)", hours > 0);
        check("J: " + members + " mouths: lasts 84 hours within 7% (" + hours + "h)", hours >= 84 * 0.93 && hours <= 84.2);
    }

    static void testJPresenceFloor() {
        World w = world();
        fillGranary(w);
        Core camp = w.brineCamp(2, 0, 20_000);
        w.raise();
        w.inputs.inCamp.put(camp.id, 20);
        double hours = w.hoursToFall(Durations.MINUTE * 10, 100);
        check("J: with every attacker present the keep still lasts at least 42 hours within 7% (" + hours + "h)",
                hours >= 42 * 0.93 && hours <= 42.2);
        World g = world();
        fillGranary(g);
        Core camp2 = g.brineCamp(2, 0, 20_000);
        g.raise();
        g.inputs.inKeep.put(g.keep.id, 20);
        g.inputs.inCamp.put(camp2.id, 0);
        double longest = g.hoursToFall(Durations.HOUR, 200);
        // Half drain is 168 hours; a week of spoilage at 2% a day takes about 7% of the bread.
        check("J: a full garrison halves the drain, never more (" + longest + "h)", longest >= 168 * 0.9 && longest <= 168.5);
    }

    static void timeScaleAgrees() {
        double byAdvance = endurance(w -> w.hoursToFall(Durations.MINUTE, 100));
        double scaled = endurance(w -> {
            w.f.knobs.timeScale = 24;
            long start = w.f.state.siegeClock.now();
            for (int i = 0; i < 200_000; i++) {
                w.sim.step(5_000);
                if (w.f.state.siege.falls.containsKey(w.keep.id)) return (w.f.state.siegeClock.now() - start) / (double) Durations.HOUR;
            }
            return -1;
        });
        double realTime = endurance(w -> {
            long start = w.f.state.siegeClock.now();
            for (int i = 0; i < 100_000; i++) {
                w.sim.step(5_000);
                if (w.f.state.siege.falls.containsKey(w.keep.id)) return (w.f.state.siegeClock.now() - start) / (double) Durations.HOUR;
            }
            return -1;
        });
        check("time scale: advance and scale 24 agree within half an hour (" + byAdvance + "h, " + scaled + "h)",
                scaled > 0 && Math.abs(byAdvance - scaled) <= 0.5);
        check("time scale: a scale of 1 in real 5 second steps runs the same drain (" + realTime + "h)",
                realTime > 0 && Math.abs(byAdvance - realTime) <= 0.5);
        System.out.println("siege: full granary, 3 mouths: advance " + byAdvance + "h, scale 24 " + scaled + "h, real time " + realTime + "h");
    }

    interface Run {
        double go(World w);
    }

    static double endurance(Run run) {
        World w = world();
        fillGranary(w);
        w.brineCamp(2, 0, 20_000);
        w.raise();
        return run.go(w);
    }

    // ---- K: carry-in ----

    static void testKCarryIn() {
        World w = world();
        w.fill(w.keep.id, BREAD, 200);
        w.brineCamp(2, 0, 2000);
        w.raise();
        Supply s = w.supply();
        List<HistoryEvent> events = new ArrayList<>();

        eq("K: food from a chest already inside is refused", Map.of(), s.deposit("chest", w.keep.id, Map.of(BREAD, 10), events));
        eq("K: walking in from an open side notes the food", Supply.Entry.GRANTED,
                s.enter("player:gil", ch(0, 2), ch(0, 1), Map.of(BREAD, 100, "minecraft:stone", 64)));
        eq("K: the noted food can be deposited", Map.of(BREAD, 100), s.deposit("player:gil", w.keep.id, Map.of(BREAD, 150), events));
        eq("K: but no more than was carried in", Map.of(), s.deposit("player:gil", w.keep.id, Map.of(BREAD, 1), events));
        eq("K: walking in across the camp's chunk notes nothing", Supply.Entry.CLOSED,
                s.enter("player:cy", ch(2, 0), ch(1, 0), Map.of(BREAD, 50)));
        eq("K: so nothing goes in", Map.of(), s.deposit("player:cy", w.keep.id, Map.of(BREAD, 50), events));
        eq("K: arriving from somewhere that is not the perimeter (a pearl, a teleport) notes nothing", Supply.Entry.CLOSED,
                s.enter("player:cy", ch(9, 9), ch(1, 1), Map.of(BREAD, 50)));

        s.enter("player:cy", ch(-2, 0), ch(-1, 0), Map.of(BREAD, 10));
        eq("K: bread carried in does not let beef in", Map.of(), s.deposit("player:cy", w.keep.id, Map.of(BEEF, 5), events));
        s.enter("player:cy", ch(-2, 0), ch(-1, 0), Map.of(BREAD, 3));
        eq("K: entering again replaces the allowance", Map.of(BREAD, 3), s.deposit("player:cy", w.keep.id, Map.of(BREAD, 10), events));

        s.enter("cart:1", ch(0, -2), ch(0, -1), Map.of(BREAD, 20));
        w.advance(Durations.minutes(w.f.knobs.carryInWindowMinutes) + Durations.MINUTE);
        eq("K: an allowance expires after the carry-in window", Map.of(), s.deposit("cart:1", w.keep.id, Map.of(BREAD, 20), events));
        eq("K: moving within the keep is not an entry", Supply.Entry.NOT_A_SITE,
                s.enter("player:gil", ch(0, 1), ch(0, 0), Map.of(BREAD, 5)));
        check("K: deposits under siege are recorded for the debrief",
                events.stream().anyMatch(e -> e instanceof SiegeEvents.FoodDeposited d && d.underSiege()));
    }

    /** K2, core half (PLAN.md §5.3, §5.4): carts feed a pool that hoppers and hand deposits draw on. */
    static void cartPool() {
        World w = world();
        w.fill(w.keep.id, BREAD, 200);
        w.brineCamp(2, 0, 2000);
        w.raise();
        Supply s = w.supply();
        List<HistoryEvent> events = new ArrayList<>();

        eq("K2: a hopper with no cart pool puts nothing in", Map.of(), s.deposit(Supply.HOPPER, w.keep.id, Map.of(BREAD, 1), events));
        eq("K2: a cart rolling in through an open side is noted", Supply.Entry.GRANTED,
                s.enter("cart:a", ch(0, 2), ch(0, 1), Map.of(BREAD, 20, BEEF, 4)));
        eq("K2: the pool covers what the cart carried", 20, s.poolLeft(w.keep.id, BREAD));
        eq("K2: a hopper unloading it draws on the pool", Map.of(BREAD, 1), s.deposit(Supply.HOPPER, w.keep.id, Map.of(BREAD, 1), events));
        eq("K2: and debits it", 19, s.poolLeft(w.keep.id, BREAD));
        eq("K2: a cart cannot carry its own way in as a hopper would", Map.of(), s.deposit("cart:b", w.keep.id, Map.of(BREAD, 1), events));

        s.enter("player:runner", ch(-2, 0), ch(-1, 0), Map.of(BREAD, 2));
        eq("K2: a player uses their own allowance first, then the cart pool", Map.of(BREAD, 7),
                s.deposit("player:runner", w.keep.id, Map.of(BREAD, 7), events));
        eq("K2: two came from the runner, five from the pool", 14, s.poolLeft(w.keep.id, BREAD));
        eq("K2: matching is by item id: beef in the pool does not let carrots in", Map.of(),
                s.deposit(Supply.HOPPER, w.keep.id, Map.of("minecraft:carrot", 1), events));

        s.enter("cart:c", ch(0, -2), ch(0, -1), Map.of(BREAD, 30));
        s.leave("cart:c", w.keep.id, Map.of(BREAD, 30));
        eq("K2: a cart that rolls in full and out full mints nothing", 14, s.poolLeft(w.keep.id, BREAD));
        s.enter("cart:c", ch(0, -2), ch(0, -1), Map.of(BREAD, 30));
        s.leave("cart:c", w.keep.id, Map.of(BREAD, 10));
        eq("K2: one that unloads twenty and leaves with ten adds twenty", 34, s.poolLeft(w.keep.id, BREAD));
        eq("K2: a cart crossing in from the camp's chunk adds nothing", Supply.Entry.CLOSED,
                s.enter("cart:d", ch(2, 0), ch(1, 0), Map.of(BREAD, 50)));
        eq("K2: so the pool is unchanged", 34, s.poolLeft(w.keep.id, BREAD));
    }

    static void depositNeedsMembershipOrGrant() {
        World w = world();
        w.fill(w.keep.id, BREAD, 300);
        w.brineCamp(2, 0, 2000);
        w.raise();
        double before = w.granary();
        w.supply().enter("player:" + VAL, ch(0, 2), ch(0, 1), Map.of(BREAD, 40));
        DepositFood byOutsider = new DepositFood(w.ashfall, w.keep.id, "player:" + VAL, Map.of(BREAD, 40));
        eq("D7: an outsider without a grant cannot deposit", DepositFood.NOT_YOURS.key(), w.f.as(VAL, byOutsider).reasonKey());
        w.f.as(KRIS, new GrantPower(w.ashfall, Power.GRANARY_DEPOSIT, new Holder.OfPlayer(VAL), GrantScope.ANY, false, 0));
        check("D7: with the grant a sellsword can run supplies in", w.f.as(VAL, byOutsider).done());
        w.supply().enter("player:" + GIL, ch(0, 2), ch(0, 1), Map.of(BREAD, 10));
        check("deposit: a member deposits by right", w.f.as(GIL, new DepositFood(w.ashfall, w.keep.id, "player:" + GIL, Map.of(BREAD, 10))).done());
        eq("deposit: without carry-in the action is refused", DepositFood.NOTHING_IN.key(),
                w.f.as(GIL, new DepositFood(w.ashfall, w.keep.id, "player:" + GIL, Map.of(BREAD, 10))).reasonKey());
        eq("deposit: the granary gained exactly what was carried in", 50 * 5.0, w.granary() - before);
    }

    // ---- L: encirclement ----

    static Core[] encircle(World w) {
        return new Core[]{
                w.brineCamp(2, 0, 3000, new int[]{2, 1}, new int[]{2, -1}),
                w.brineCamp(-2, 0, 3000, new int[]{-2, 1}, new int[]{-2, -1}),
                w.brineCamp(0, 2, 3000, new int[]{1, 2}, new int[]{-1, 2}),
                w.brineCamp(0, -2, 3000, new int[]{1, -2}, new int[]{-1, -2})};
    }

    static void testLEncirclement() {
        World w = world();
        w.fill(w.keep.id, BREAD, 300);
        Core[] camps = encircle(w);
        w.raise();
        eq("L: twelve sides held by besiegers encircle the keep", KeepSupply.ENCIRCLED, w.keepSupply());
        check("L: encirclement is recorded", w.f.state.history.has(SiegeEvents.Encircled.class));
        int granted = 0;
        for (ChunkKey side : charter.land.LandGraph.keepPerimeterChunks(w.keep.center)) {
            for (ChunkKey into : List.of(side.offset(1, 0), side.offset(-1, 0), side.offset(0, 1), side.offset(0, -1))) {
                if (w.supply().enter("player:gil", side, into, Map.of(BREAD, 10)) == Supply.Entry.GRANTED) granted++;
            }
        }
        eq("L: no side lets food in", 0, granted);

        w.f.admin(new LoseCore(camps[1].id));
        w.advance(Durations.MINUTE);
        eq("L: breaking one camp reopens the ring", KeepSupply.BESIEGED, w.keepSupply());
        check("L: the broken ring is recorded", w.f.state.history.has(SiegeEvents.RingBroken.class));
        var counting = w.rules().countingCamps(w.keep);
        Set<ChunkKey> open = Set.copyOf(w.rules().keepPerimeter(w.keep, counting).open());
        eq("L: exactly the west camp's three sides are open", Set.of(ch(-2, -1), ch(-2, 0), ch(-2, 1)), open);
        eq("L: and food comes in through them", Supply.Entry.GRANTED, w.supply().enter("player:gil", ch(-2, 0), ch(-1, 0), Map.of(BREAD, 10)));
    }

    // ---- M: starvation and assault ----

    static void testMStarvationGate() {
        World w = world();
        fillGranary(w);
        w.brineCamp(2, 0, 5000);
        w.raise();
        w.inputs.inside.put(w.keep.id, 5);
        double before = w.granary();
        w.advance(Durations.hours(2));
        double drained = before - w.granary();
        check("M: above 25% attackers inside change nothing (" + drained + " in 2h, base 60)", drained >= 55 && drained <= 66);

        World s = world();
        s.fill(s.keep.id, BREAD, 100);
        s.brineCamp(2, 0, 5000);
        s.raise();
        double quiet = s.granary();
        s.advance(Durations.hours(1));
        double withoutAssault = quiet - s.granary();
        s.inputs.inside.put(s.keep.id, 5);
        double loud = s.granary();
        s.advance(Durations.hours(1));
        double withAssault = loud - s.granary();
        check("M: the keep is starving below 25%", s.f.state.siege.keep(s.keep.id).starving);
        check("M: below 25% attackers inside drain four times faster (" + withoutAssault + " then " + withAssault + ")",
                withoutAssault >= 25 && withoutAssault <= 35 && withAssault >= 115 && withAssault <= 125);
    }

    // ---- N: camp supply ----

    static void testNCampSupply() {
        World w = world();
        w.fill(w.keep.id, BREAD, 300);
        Core empty = w.brineCamp(2, 0, 0);
        w.raise();
        eq("N: a camp with an empty store does not besiege", KeepSupply.SUPPLIED, w.keepSupply());
        eq("N: it reads as empty", CampSupply.EMPTY, w.f.state.siege.camp(empty.id).supply);

        World c = world();
        c.fill(c.keep.id, BREAD, 300);
        for (int[] at : new int[][]{{2, 1}, {2, -1}, {3, 1}, {3, 0}}) {
            Outcome claimed = c.f.as(KRIS, new ClaimLand(c.ashfall, ch(at[0], at[1])));
            if (!claimed.done()) throw new IllegalStateException("ashfall claim failed: " + claimed);
        }
        Core camp = c.brineCamp(2, 0, 400);
        c.raise();
        eq("N: a camp boxed in by the defenders' claims is cut off", CampSupply.CUT_OFF, c.f.state.siege.camp(camp.id).supply);
        check("N: being cut off is recorded", c.f.state.history.has(SiegeEvents.CampCutOff.class));
        double before = c.sim.provisions(camp.id).nourishment(c.sim.food());
        c.advance(Durations.hours(1));
        double burned = before - c.sim.provisions(camp.id).nourishment(c.sim.food());
        check("N: a cut off camp burns 60 an hour (" + burned + ")", burned >= 55 && burned <= 65);
        eq("N: nothing can be carried into a cut off camp", Supply.Entry.CLOSED,
                c.supply().enter("player:zed", ch(3, 0), ch(2, 0), Map.of(BREAD, 10)));
    }

    // ---- O: raids ----

    static void testORaid() {
        World w = world();
        for (int[] at : new int[][]{{0, 2}, {0, 3}}) w.f.as(KRIS, new ClaimLand(w.ashfall, ch(at[0], at[1])));
        Core camp = w.brineCamp(1, 3, 500);
        w.raise();
        w.advance(Durations.hours(6) - Durations.MINUTE * 2);
        check("O: a farm holds just under 6 hours of raiding", w.f.state.claimAt(ch(0, 3)).isPresent());
        check("O: while it holds it stays protected", w.rules().protectedClaim(w.f.state.claimAt(ch(0, 3)).orElseThrow()));
        w.advance(Durations.MINUTE * 3);
        check("O: after 6 hours of raiding the farm is unclaimed", w.f.state.claimAt(ch(0, 3)).isEmpty());
        check("O: the emptied hold is recorded with the raider",
                w.f.state.history.of(SiegeEvents.HoldEmptied.class).stream().anyMatch(e -> e.raider().equals(w.brine)));

        World r = world();
        for (int[] at : new int[][]{{0, 2}, {0, 3}}) r.f.as(KRIS, new ClaimLand(r.ashfall, ch(at[0], at[1])));
        Core raider = r.brineCamp(1, 3, 500);
        r.raise();
        r.advance(Durations.hours(3));
        var farm = r.f.state.claimAt(ch(0, 3)).orElseThrow();
        long left = r.f.state.siege.holds.get(farm.id);
        check("O: three hours of raiding leaves three hours of hold", Math.abs(left - Durations.hours(3)) <= Durations.MINUTE * 2);
        r.f.admin(new LoseCore(raider.id));
        r.advance(Durations.hours(6));
        long refilled = r.f.state.siege.holds.getOrDefault(farm.id, Durations.hours(6));
        check("O: an unraided, connected hold refills at a quarter hour per hour", Math.abs(refilled - Durations.hours(4.5)) <= Durations.MINUTE * 5);
        r.advance(Durations.hours(7));
        check("O: and is full again", !r.f.state.siege.holds.containsKey(farm.id));
        check("O: a raid alone does not besiege the keep", w.f.state.siege.keep(w.keep.id).supply == KeepSupply.SUPPLIED);
        eq("O: (camp kept for clarity)", camp.owner, w.brine);
    }

    // ---- Q: new factions ----

    static void testQNewFaction() {
        World w = new World();
        w.ashfallMembers.addAll(List.of(KRIS, GIL, CY));
        w.brine = w.f.found("Brinewatch", Preset.LORDSHIP, ZED, ANN, DEN);
        w.f.as(DEN, new Deposit(w.brine, Currency.DIAMOND, 400));
        w.f.as(DEN, new Deposit(w.brine, Currency.COBBLESTONE, 5000));
        w.f.passDays(4, ZED, ANN, DEN);
        w.ashfall = w.f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL, CY);
        w.f.as(GIL, new Deposit(w.ashfall, Currency.DIAMOND, 400));
        w.f.as(GIL, new Deposit(w.ashfall, Currency.COBBLESTONE, 5000));
        w.f.as(KRIS, new PlaceNexus(w.ashfall, ch(0, 0)));
        w.keep = w.f.state.coresOf(w.ashfall).getFirst();
        w.fill(w.keep.id, BREAD, 300);
        w.f.as(ZED, new PlaceNexus(w.brine, ch(30, 0)));
        w.f.as(ZED, new ClaimLand(w.brine, ch(32, 0)));

        w.brineCamp(2, 0, 2000);
        w.raise();
        eq("Q: a faction under 3 days old cannot be besieged", KeepSupply.SUPPLIED, w.keepSupply());
        double before = w.granary();
        w.advance(Durations.hours(1));
        eq("Q: its granary is not drawn on", before, w.granary());

        w.camp(w.ashfall, KRIS, 33, 0, 200);
        w.raise();
        check("Q: raising a camp against someone ends the protection", w.f.state.siege.protectionWaived.contains(w.ashfall));
        check("Q: the waiver is recorded", w.f.state.history.has(SiegeEvents.ProtectionWaived.class));
        eq("Q: and now the keep is besieged", KeepSupply.BESIEGED, w.keepSupply());
    }

    // ---- R: truce ----

    static void testRTruce() {
        World w = world();
        w.fill(w.keep.id, BREAD, 300);
        Core camp = w.brineCamp(2, 0, 2000);
        w.raise();
        eq("R: besieged", KeepSupply.BESIEGED, w.keepSupply());
        w.f.admin(new LoseCore(camp.id));
        w.advance(Durations.MINUTE);
        eq("R: the siege is lifted", KeepSupply.SUPPLIED, w.keepSupply());
        check("R: a truce begins", w.f.state.history.has(SiegeEvents.TruceBegan.class));
        eq("R: the same attacker cannot plant against that keep", PlantCamp.TRUCE.key(),
                w.f.as(ZED, new PlantCamp(w.brine, ch(-2, 0))).reasonKey());

        OrgId vel = w.f.found("Vel", Preset.LORDSHIP, VAL, VIN);
        w.f.as(VIN, new Deposit(vel, Currency.COBBLESTONE, 2000));
        w.f.passDays(3.1);
        w.advance(0);
        Core velCamp = w.camp(vel, VAL, -2, 0, 2000);
        w.raise();
        eq("R: another faction is not barred", KeepSupply.BESIEGED, w.keepSupply());
        w.f.admin(new LoseCore(velCamp.id));
        w.advance(Durations.days(3));
        check("R: after 3 days the first attacker may plant again", w.f.as(ZED, new PlantCamp(w.brine, ch(0, 2))).done());
    }

    // ---- the fall ----

    static void theFall() {
        World w = world();
        w.fill(w.keep.id, BREAD, 12);
        w.brineCamp(2, 0, 2000);
        w.raise();
        w.hoursToFall(Durations.MINUTE, 10);
        check("fall: an empty besieged granary makes the keep fall", w.f.state.history.has(SiegeEvents.KeepFell.class));
        var fallen = w.f.state.siege.falls.get(w.keep.id);
        check("fall: the besieger is asked to choose", fallen != null && fallen.besieger().equals(w.brine));
        check("fall: the keep is unprotected while the besieger chooses",
                !w.rules().protectedClaim(w.f.state.claimAt(ch(0, 0)).orElseThrow()));
        long diamonds = w.f.org(w.ashfall).ledger.balance(Currency.DIAMOND);
        check("fall: the besieger razes it", w.f.as(ZED, new ChooseFall(w.brine, w.keep.id, FallChoice.RAZE)).done());
        check("fall: the keep is gone", w.f.state.core(w.keep.id).isEmpty() && w.f.state.claimAt(ch(0, 0)).isEmpty());
        eq("fall: a quarter of the treasury is plundered", (long) Math.floor(diamonds * 0.25),
                diamonds - w.f.org(w.ashfall).ledger.balance(Currency.DIAMOND));
        check("fall: the plunder is recorded for spilling", w.f.state.history.has(SiegeEvents.Plundered.class));

        World t = world();
        t.fill(t.keep.id, BREAD, 12);
        t.brineCamp(2, 0, 2000);
        t.raise();
        t.hoursToFall(Durations.MINUTE, 10);
        eq("fall: nobody else can choose", ChooseFall.NOT_FALLEN.key(),
                t.f.as(KRIS, new ChooseFall(t.ashfall, t.keep.id, FallChoice.OCCUPY)).reasonKey());
        t.advance(Durations.minutes(t.f.knobs.fallChoiceMinutes + 1));
        check("fall: with no choice in time the keep is razed", t.f.state.core(t.keep.id).isEmpty());

        World o = world();
        o.fill(o.keep.id, BREAD, 12);
        o.brineCamp(2, 0, 2000);
        o.raise();
        o.hoursToFall(Durations.MINUTE, 10);
        o.f.as(ZED, new ChooseFall(o.brine, o.keep.id, FallChoice.OCCUPY));
        check("fall: an occupied keep stays recorded to its owner",
                o.f.state.core(o.keep.id).map(c -> c.owner.equals(o.ashfall)).orElse(false));
        eq("fall: the occupier holds it", o.brine, o.rules().holder(o.keep));
        o.advance(Durations.days(o.f.knobs.occupationCapDays) + Durations.MINUTE);
        check("fall: control returns when the occupation cap runs out", !o.f.state.siege.occupations.containsKey(o.keep.id));
        check("fall: the occupier leaves under a truce", o.f.state.siege.truce(o.keep.id, o.brine, o.f.state.siegeClock.now()));
        o.advance(Durations.hours(1));
        check("fall: so the camp still beside it does not take it again at once",
                o.f.state.core(o.keep.id).isPresent() && !o.f.state.siege.falls.containsKey(o.keep.id));
    }

    // ---- X: sallies ----

    static void testXSally() {
        World w = world();
        w.fill(w.keep.id, BREAD, 300);
        Core camp = w.brineCamp(2, 0, 2000);
        w.raise();
        w.inputs.beside.put(camp.id, 1);
        w.inputs.inCamp.put(camp.id, 1);
        w.advance(Durations.hours(2));
        check("X: an attacker in the camp cancels a defender beside it", !w.f.state.siege.camp(camp.id).exposed);
        w.inputs.inCamp.put(camp.id, 0);
        w.advance(Durations.hours(1) - Durations.MINUTE * 2);
        check("X: just under an hour of sally does not break it", !w.f.state.siege.camp(camp.id).exposed);
        w.advance(Durations.MINUTE * 3);
        check("X: an hour of defenders unopposed exposes the camp", w.f.state.siege.camp(camp.id).exposed);
        check("X: an exposed camp's claims are not protected", !w.rules().protectedClaim(w.f.state.claimAt(ch(2, 0)).orElseThrow()));
        w.inputs.beside.put(camp.id, 0);
        w.advance(Durations.hours(1) + Durations.MINUTE);
        check("X: the sally hold refills when uncontested", !w.f.state.siege.camp(camp.id).exposed);
    }

    static void notorietyRisesAndDecays() {
        World w = world();
        w.fill(w.keep.id, BREAD, 300);
        w.brineCamp(2, 0, 2000);
        w.raise();
        check("notoriety: raising a camp against someone adds notoriety", w.f.state.siege.notorietyOf(w.brine) > 0.99);
        double before = w.f.state.siege.notorietyOf(w.brine);
        w.advance(Durations.days(0.5));
        check("notoriety: it decays", w.f.state.siege.notorietyOf(w.brine) < before);
        check("notoriety: the defender has none", w.f.state.siege.notorietyOf(w.ashfall) == 0);
    }
}
