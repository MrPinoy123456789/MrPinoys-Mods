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
        depositNeedsMembershipOrGrant();
        upkeepDrainsAFullGranaryOverFourteenDays();
        upkeepWarnsOncePerThresholdAndRearms();
        aNewKeepHasOnePeriodOfGrace();
        aLapseReleasesTheLandAndKeepsTheNation();
        theRaidersKeepsAreNotKeptUp();
        onlyANationHoldsAKeep();
        oneKeepAndOneOutpostEach();
        anOutpostCostsADepositThatGrowsWithTheNation();
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

    static final class World {
        final Fixture f = new Fixture();
        final SiegeSim sim = new SiegeSim(f.engine, table());
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
        check("I: no alert while raising", !w.f.state.history.has(SiegeEvents.CampActive.class));
        w.advance(Durations.MINUTE);
        eq("I: active at 2 siege hours", CampPhase.ACTIVE, camp.phase());
        eq("I: the alert fires once, on becoming active", 1, w.f.state.history.of(SiegeEvents.CampActive.class).size());
        check("I: an active camp is protected", w.rules().protectedClaim(claim));
        check("I: a keep's claims are always protected", w.rules().protectedClaim(w.f.state.claimAt(ch(0, 0)).orElseThrow()));
    }

    // ---- who may deposit ----

    static void depositNeedsMembershipOrGrant() {
        World w = world();
        w.fill(w.keep.id, BREAD, 300);
        double before = w.granary();
        DepositFood byOutsider = new DepositFood(w.ashfall, w.keep.id, "player:" + VAL, Map.of(BREAD, 40));
        eq("D7: an outsider without a grant cannot deposit", DepositFood.NOT_YOURS.key(), w.f.as(VAL, byOutsider).reasonKey());
        w.f.as(KRIS, new GrantPower(w.ashfall, Power.GRANARY_DEPOSIT, new Holder.OfPlayer(VAL), GrantScope.ANY, false, 0));
        check("D7: with the grant a sellsword can run supplies in", w.f.as(VAL, byOutsider).done());
        check("deposit: a member deposits by right", w.f.as(GIL, new DepositFood(w.ashfall, w.keep.id, "player:" + GIL, Map.of(BREAD, 10))).done());
        eq("deposit: the granary gained what was carried in", 50 * 5.0, w.granary() - before);
    }

    // ---- K2: the granary is the keep's upkeep ----

    static void upkeepDrainsAFullGranaryOverFourteenDays() {
        World w = world();
        w.f.knobs.spoilagePerDayPreserved = 0;
        fillGranary(w);
        double full = w.granary();
        w.advance(Durations.days(1));
        w.advance(Durations.days(6));
        double half = w.granary();
        check("K2: a full granary is half gone after seven of fourteen days (" + half / full + ")", Math.abs(half / full - 0.5) < 0.02);
        w.advance(Durations.days(6));
        check("K2: still fed at thirteen days", w.f.state.core(w.keep.id).isPresent() && w.granary() > 0);
        w.advance(Durations.days(1) + Durations.hours(2));
        check("K2: dry at fourteen days, the keep's claim lapses", w.f.state.core(w.keep.id).isEmpty());
        // Nothing else changes the drain: a rival's camp beside the keep, and more members, change nothing.
        World rival = world();
        rival.f.knobs.spoilagePerDayPreserved = 0;
        fillGranary(rival);
        rival.brineCamp(2, 0, 500);
        rival.raise();
        rival.advance(Durations.days(7) - Durations.hours(2));
        double after = rival.granary() / rival.rules().storageLimit(rival.keep);
        check("K2: a camp beside the keep does not speed the drain (" + after + ")", Math.abs(after - 0.5) < 0.03);
    }

    static void upkeepWarnsOncePerThresholdAndRearms() {
        World w = world();
        w.f.knobs.spoilagePerDayPreserved = 0;
        fillGranary(w);
        w.advance(Durations.days(6) + Durations.hours(12));
        check("K2: no warning before seven days are left", !w.f.state.history.has(SiegeEvents.KeepRunningLow.class));
        w.advance(Durations.days(1));
        eq("K2: a warning at seven days left", 1, w.f.state.history.of(SiegeEvents.KeepRunningLow.class).size());
        eq("K2: it names seven", 7, w.f.state.history.of(SiegeEvents.KeepRunningLow.class).getFirst().daysLeft());
        w.advance(Durations.hours(5));
        eq("K2: not repeated", 1, w.f.state.history.of(SiegeEvents.KeepRunningLow.class).size());
        w.advance(Durations.days(4));
        w.advance(Durations.days(2));
        var warned = w.f.state.history.of(SiegeEvents.KeepRunningLow.class);
        eq("K2: then three days, then one", List.of(7, 3, 1), warned.stream().map(SiegeEvents.KeepRunningLow::daysLeft).toList());
        // Feeding it above the thresholds re-arms them.
        fillGranary(w);
        w.advance(Durations.hours(1));
        w.advance(Durations.days(8));
        eq("K2: fed and drained again, the seven day warning comes again", 4, w.f.state.history.of(SiegeEvents.KeepRunningLow.class).size());
    }

    static void aNewKeepHasOnePeriodOfGrace() {
        World w = world();
        w.advance(Durations.days(13));
        check("K2: an unstocked new keep is still there at thirteen days", w.f.state.core(w.keep.id).isPresent());
        check("K2: and has been warned", w.f.state.history.has(SiegeEvents.KeepRunningLow.class));
        w.advance(Durations.days(1) + Durations.hours(2));
        check("K2: it lapses once the grace is over", w.f.state.core(w.keep.id).isEmpty());
    }

    static void aLapseReleasesTheLandAndKeepsTheNation() {
        World w = world();
        long diamonds = w.f.org(w.ashfall).ledger.balance(Currency.DIAMOND);
        int members = w.f.state.members(w.ashfall).size();
        w.advance(Durations.days(15));
        check("K2: the keep is gone", w.f.state.coresOf(w.ashfall).isEmpty());
        check("K2: its claims are released", w.f.state.claimsOf(w.ashfall).stream().noneMatch(c -> c.fixed()));
        check("K2: it is told plainly", w.f.state.history.has(SiegeEvents.KeepLapsed.class));
        eq("K2: the nation keeps its treasury", diamonds, w.f.org(w.ashfall).ledger.balance(Currency.DIAMOND));
        eq("K2: and its members", members, w.f.state.members(w.ashfall).size());
        check("K2: and is still a nation", w.f.state.org(w.ashfall).map(o -> o.exists()).orElse(false));
        Outcome again = w.f.as(KRIS, new PlaceNexus(w.ashfall, ch(0, 0)));
        check("K2: it may put a new Nexus down: " + again, again.done());
        eq("K2: and pays the founding price again", diamonds - w.f.knobs.keepPriceDiamonds, w.f.org(w.ashfall).ledger.balance(Currency.DIAMOND));
    }

    static void theRaidersKeepsAreNotKeptUp() {
        World w = world();
        w.f.state.org(w.ashfall).orElseThrow().npc = true;
        w.advance(Durations.days(20));
        check("K2: a stronghold of the raiders does not lapse; it falls to its waves", w.f.state.core(w.keep.id).isPresent());
    }

    static void onlyANationHoldsAKeep() {
        World w = world();
        w.f.as(KRIS, new charter.org.actions.CreateSubOrg(w.ashfall, "Guard", Preset.LORDSHIP, GIL));
        OrgId guard = w.f.state.orgNamed("Guard").orElseThrow().id;
        eq("K1: a sub-faction cannot place a Nexus", charter.land.LandReasons.SUB_NO_KEEP.key(),
                w.f.as(GIL, new PlaceNexus(guard, ch(30, 0))).reasonKey());
    }

    // ---- K3: one of each, and the deposit ----

    static void oneKeepAndOneOutpostEach() {
        World w = world();
        eq("K3: a nation has one keep", charter.land.LandReasons.ONE_KEEP.key(), w.f.as(KRIS, new PlaceNexus(w.ashfall, ch(30, 0))).reasonKey());
        check("K3: an organization may plant an outpost", w.f.as(KRIS, new PlantCamp(w.ashfall, ch(40, 0))).done());
        eq("K3: but only one", charter.land.LandReasons.ONE_OUTPOST.key(), w.f.as(KRIS, new PlantCamp(w.ashfall, ch(50, 0))).reasonKey());
        w.f.as(KRIS, new charter.org.actions.CreateSubOrg(w.ashfall, "Guard", Preset.LORDSHIP, GIL));
        OrgId guard = w.f.state.orgNamed("Guard").orElseThrow().id;
        w.f.as(KRIS, new Deposit(guard, Currency.DIAMOND, 100));
        eq("K3: a sub-faction of one is too small for an outpost", charter.land.LandReasons.OUTPOST_TOO_SMALL.key(),
                w.f.as(GIL, new PlantCamp(guard, ch(60, 0))).reasonKey());
        PlayerId newbie = player("Newbie");
        w.f.seen(newbie);
        w.f.admin(new charter.org.actions.InviteMember(guard, newbie));
        w.f.as(newbie, new charter.org.actions.AcceptInvite(newbie, guard));
        check("K3: with two members it may: " + w.f.as(GIL, new PlantCamp(guard, ch(60, 0))), w.f.state.coresOf(guard).size() == 1);
    }

    static void anOutpostCostsADepositThatGrowsWithTheNation() {
        World w = world();
        long start = w.f.org(w.ashfall).ledger.balance(Currency.DIAMOND);
        w.f.as(KRIS, new PlantCamp(w.ashfall, ch(40, 0)));
        Core first = w.f.state.coresOf(w.ashfall).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
        eq("K3: the first outpost of a nation holds the base deposit", (long) w.f.knobs.outpostDeposit, first.deposit);
        eq("K3: the treasury paid it", start - w.f.knobs.outpostDeposit, w.f.org(w.ashfall).ledger.balance(Currency.DIAMOND));
        w.f.as(KRIS, new charter.org.actions.CreateSubOrg(w.ashfall, "Guard", Preset.LORDSHIP, GIL));
        OrgId guard = w.f.state.orgNamed("Guard").orElseThrow().id;
        eq("K3: the next one costs a step more", (long) (w.f.knobs.outpostDeposit + w.f.knobs.outpostDepositStep),
                charter.land.actions.PlantCamp.deposit(w.f.state, w.f.knobs, guard));
        w.f.admin(new LoseCore(first.id));
        eq("K3: an outpost lost without a capture gives its deposit back", start, w.f.org(w.ashfall).ledger.balance(Currency.DIAMOND));
    }
}
