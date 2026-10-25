package charter.contract;

import charter.Fixture;
import charter.contract.actions.Arrest;
import charter.contract.actions.Breach;
import charter.contract.actions.Confine;
import charter.contract.actions.DepositPersonal;
import charter.contract.actions.Escape;
import charter.contract.actions.Execute;
import charter.contract.actions.FineCustody;
import charter.contract.actions.Pardon;
import charter.contract.actions.Release;
import charter.contract.actions.Sentence;
import charter.engine.Outcome;
import charter.food.FoodTable;
import charter.food.ShelfClass;
import charter.gov.Preset;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.actions.GrantPower;
import charter.grant.actions.RevokeGrant;
import charter.history.HistoryEvent;
import charter.id.ChunkKey;
import charter.id.ClaimId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.land.actions.ClaimLand;
import charter.land.actions.PlaceNexus;
import charter.land.actions.PlantCamp;
import charter.org.actions.CreateSubOrg;
import charter.siege.FallChoice;
import charter.siege.KeepSupply;
import charter.siege.SiegeEvents;
import charter.siege.SiegeInputs;
import charter.siege.SiegeRules;
import charter.siege.SiegeSim;
import charter.siege.actions.ChooseFall;
import charter.siege.actions.DepositFood;
import charter.time.Durations;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/**
 * Acceptance tests S, T (core), U, V (core), W and the D7 half of D, plus the
 * bond and the contract lifecycle (DESIGN.md §22, PLAN.md §10.2). Three
 * factions hold keeps at chunk 0,0 (Ashfall), 20,0 (Brinewatch) and 40,0
 * (Cinder); Tam, Pat, Rob and Sam belong to nobody and get arrested.
 */
public final class ContractTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId GIL = player("Gil");
    static final PlayerId CY = player("Cy");
    static final PlayerId ZED = player("Zed");
    static final PlayerId ANN = player("Ann");
    static final PlayerId DEN = player("Den");
    static final PlayerId VAL = player("Val");
    static final PlayerId VIN = player("Vin");
    static final PlayerId WES = player("Wes");
    static final PlayerId TAM = player("Tam");
    static final PlayerId PAT = player("Pat");
    static final PlayerId ROB = player("Rob");
    static final PlayerId SAM = player("Sam");
    static final PlayerId[] EVERYONE = {KRIS, GIL, CY, ZED, ANN, DEN, VAL, VIN, WES, TAM, PAT, ROB, SAM};

    static final String BREAD = "minecraft:bread";
    static final long H = Durations.HOUR;

    private ContractTest() {
    }

    public static void run() {
        testSImposedCaps();
        testBasisCaps();
        testArrestAndCustody();
        testFineAndExecution();
        testTResistance();
        testSignedBreach();
        testUWeeklyCap();
        testPardonAndRelease();
        testNotoriety();
        testVPrisonersEat();
        testWOccupation();
        testD7Deposit();
        testBond();
    }

    // ---- the world ----

    static ChunkKey ch(int x, int z) {
        return new ChunkKey("minecraft:overworld", x, z);
    }

    static FoodTable table() {
        return new FoodTable().put(BREAD, 5, ShelfClass.PRESERVED);
    }

    static final class World {
        final Fixture f = new Fixture();
        final SiegeSim sim = new SiegeSim(f.engine, table());
        final SiegeInputs inputs = JusticeInputs.wrap(SiegeInputs.NONE, f.state, f.knobs, f.engine::now);
        OrgId ashfall;
        OrgId brine;
        OrgId cinder;
        Core keepA;
        Core keepB;
        Core keepC;

        World() {
            sim.inputs(inputs);
        }

        /** Advances wall time and runs a siege pass so the contracts see it. */
        void wall(long millis) {
            f.pass(millis, EVERYONE);
            sim.advance(Durations.MINUTE);
        }

        /** Advances siege time with everyone seen. */
        void adv(long siegeMillis) {
            f.seen(EVERYONE);
            sim.advance(siegeMillis);
        }

        Outcome arrest(PlayerId officer, OrgId faction, PlayerId prisoner, ChunkKey where) {
            return f.as(officer, new Arrest(faction, prisoner, where));
        }

        Outcome confine(PlayerId judge, OrgId faction, PlayerId prisoner, long millis, Core keep) {
            return f.as(judge, new Confine(faction, prisoner, millis, keep.id, "cell"));
        }

        /** Arrests at the faction's own keep and confines, expecting both to work. */
        void jail(PlayerId officer, OrgId faction, Core keep, PlayerId prisoner, long millis) {
            Outcome arrested = arrest(officer, faction, prisoner, keep.center);
            Outcome confined = confine(officer, faction, prisoner, millis, keep);
            if (!arrested.done() || !confined.done()) throw new IllegalStateException("jail failed: " + arrested + " " + confined);
        }

        Contract latest() {
            List<Contract> all = new ArrayList<>(f.state.contracts.all());
            return all.getLast();
        }

        List<Contract> contracts(Basis basis) {
            return f.state.contracts.all().stream().filter(c -> c.basis == basis).toList();
        }

        void fill(Core site, int bread) {
            List<HistoryEvent> events = new ArrayList<>();
            Map<String, Integer> accepted = sim.supply().deposit("setup", site.id, Map.of(BREAD, bread), events);
            if (accepted.getOrDefault(BREAD, 0) != bread) throw new IllegalStateException("fill refused");
        }

        double granary(Core keep) {
            return sim.provisions(keep.id).nourishment(sim.food());
        }

        Core camp(OrgId org, PlayerId lord, int x, int z, int bread) {
            Outcome planted = f.as(lord, new PlantCamp(org, ch(x, z)));
            if (!planted.done()) throw new IllegalStateException("plant failed: " + planted);
            Core camp = f.state.cores().stream().filter(c -> c.center.equals(ch(x, z))).findFirst().orElseThrow();
            fill(camp, bread);
            return camp;
        }

        void raise() {
            adv(Durations.hours(f.knobs.campRaisingHours));
        }

        void untilFall(Core keep) {
            for (int i = 0; i < 20_000 && !f.state.siege.falls.containsKey(keep.id); i++) adv(Durations.MINUTE);
        }
    }

    static World world() {
        World w = new World();
        w.ashfall = w.f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL, CY);
        w.brine = w.f.found("Brinewatch", Preset.LORDSHIP, ZED, ANN, DEN);
        w.cinder = w.f.found("Cinder", Preset.LORDSHIP, VAL, VIN, WES);
        w.f.as(GIL, new Deposit(w.ashfall, Currency.DIAMOND, 400));
        w.f.as(DEN, new Deposit(w.brine, Currency.DIAMOND, 400));
        w.f.as(VIN, new Deposit(w.cinder, Currency.DIAMOND, 400));
        w.f.as(GIL, new Deposit(w.ashfall, Currency.COBBLESTONE, 5000));
        w.f.as(DEN, new Deposit(w.brine, Currency.COBBLESTONE, 5000));
        w.f.passDays(3.1);
        w.adv(0);
        w.f.seen(EVERYONE);
        require(w.f.as(KRIS, new PlaceNexus(w.ashfall, ch(0, 0))), "Ashfall keep");
        require(w.f.as(ZED, new PlaceNexus(w.brine, ch(20, 0))), "Brinewatch keep");
        require(w.f.as(VAL, new PlaceNexus(w.cinder, ch(40, 0))), "Cinder keep");
        w.keepA = w.f.state.coresOf(w.ashfall).getFirst();
        w.keepB = w.f.state.coresOf(w.brine).getFirst();
        w.keepC = w.f.state.coresOf(w.cinder).getFirst();
        return w;
    }

    static void require(Outcome outcome, String what) {
        if (!outcome.done()) throw new IllegalStateException(what + " failed: " + outcome);
    }

    static Term.Confinement confinement(Contract c) {
        return c.term(Term.Confinement.class);
    }

    // ---- S: imposed caps ----

    static void testSImposedCaps() {
        World w = world();
        long now = w.f.engine.now();

        w.arrest(KRIS, w.ashfall, TAM, ch(0, 0));
        Outcome three = w.confine(KRIS, w.ashfall, TAM, Durations.days(3), w.keepA);
        check("S: a 3 day confinement is accepted, cut down", three.done());
        Contract jail = w.latest();
        eq("S: it is an imposed contract on the custody basis", Consent.IMPOSED, jail.consent);
        eq("S: custody basis", Basis.CUSTODY, jail.basis);
        eq("S: the 3 days were cut to 1", Durations.DAY, confinement(jail).durationMillis());
        var imposed = w.f.state.history.of(ContractEvents.Imposed.class).getLast();
        check("S: the record says the term was cut", imposed.cut());
        check("S: the prisoner is held now", Justice.holding(w.f.state, w.f.knobs, TAM, now).isPresent());

        w.arrest(KRIS, w.ashfall, PAT, ch(0, 0));
        eq("S: a confinement under the minimum is refused", ContractReasons.TOO_SHORT.key(),
                w.confine(KRIS, w.ashfall, PAT, Durations.minutes(10), w.keepA).reasonKey());
        w.f.as(PAT, new DepositPersonal(PAT, Currency.DIAMOND, 40));
        Outcome bigFine = w.f.as(KRIS, new FineCustody(w.ashfall, PAT, 40));
        check("S: a fine over the cap is accepted, cut", bigFine.done());
        eq("S: the fine term was cut to 16 diamonds", 16L, w.latest().term(Term.Fine.class).diamonds());
        eq("S: only 16 diamonds left the deposit", 24L, w.f.state.contracts.vault(PAT).balance(Currency.DIAMOND));

        w.arrest(KRIS, w.ashfall, ROB, ch(0, 0));
        ClaimId some = w.f.state.claims().iterator().next().id;
        eq("S: ceding land is refused under custody", ContractReasons.NOT_ALLOWED.key(),
                w.f.as(KRIS, new Sentence(w.ashfall, ROB, List.of(new Term.Cede(Set.of(some))))).reasonKey());
        eq("S: so is a payment, which only a war goal allows", ContractReasons.NOT_ALLOWED.key(),
                w.f.as(KRIS, new Sentence(w.ashfall, ROB, List.of(new Term.Payment(Currency.DIAMOND, 5)))).reasonKey());
        eq("S: an empty sentence is refused", ContractReasons.NO_TERMS.key(),
                w.f.as(KRIS, new Sentence(w.ashfall, ROB, List.of())).reasonKey());
        eq("S: a refusal leaves the prisoner in custody", true, w.f.state.contracts.custodyOf(ROB).isPresent());
        eq("S: a cell must be one of the faction's own keeps", ContractReasons.NO_CELL.key(),
                w.confine(KRIS, w.ashfall, ROB, H, w.keepB).reasonKey());
    }

    static void testBasisCaps() {
        var k = new charter.config.Knobs();
        check("caps: a war goal allows ceding", BasisCaps.check(Basis.WAR_GOAL, new Term.Cede(Set.of(new ClaimId(new java.util.UUID(1, 1)))), k).ok());
        check("caps: a war goal allows a relation lock and a claim limit",
                BasisCaps.check(Basis.WAR_GOAL, new Term.ClaimLimit(10, Durations.days(3)), k).ok());
        eq("caps: a war goal does not allow confinement", ContractReasons.NOT_ALLOWED.key(),
                BasisCaps.check(Basis.WAR_GOAL, new Term.Confinement(H, null, ""), k).refusal().key());
        var vassal = BasisCaps.check(Basis.WAR_GOAL, new Term.GrantTerm(Power.SPEND, null, Durations.days(30)), k);
        eq("caps: imposed vassalage is cut to 14 days", Durations.days(14), vassal.term().durationMillis());
        eq("caps: occupation does not allow ceding", ContractReasons.NOT_ALLOWED.key(),
                BasisCaps.check(Basis.OCCUPATION, new Term.Cede(Set.of(new ClaimId(new java.util.UUID(1, 2)))), k).refusal().key());
        eq("caps: an occupation's access is cut to 7 days", Durations.days(7),
                BasisCaps.check(Basis.OCCUPATION, new Term.Access(null, Durations.days(30)), k).term().durationMillis());
        eq("caps: custody does not allow an access term", ContractReasons.NOT_ALLOWED.key(),
                BasisCaps.check(Basis.CUSTODY, new Term.Access(null, H), k).refusal().key());
        eq("caps: banishment is cut to a day", Durations.DAY,
                BasisCaps.check(Basis.CUSTODY, new Term.Banishment(null, Durations.days(9)), k).term().durationMillis());
        eq("caps: a respawn delay is cut to an hour", H,
                BasisCaps.check(Basis.CUSTODY, new Term.RespawnDelay(Durations.hours(5)), k).term().durationMillis());
        eq("caps: a bond is refused while the knob is off", ContractReasons.BOND_OFF.key(),
                BasisCaps.check(Basis.CUSTODY, new Term.Tether(H, null), k).refusal().key());
        eq("caps: a zero fine means nothing", ContractReasons.BAD_TERM.key(),
                BasisCaps.check(Basis.CUSTODY, new Term.Fine(0), k).refusal().key());
        check("caps: only confinement, bonds and respawn delays count toward the week",
                new Term.Confinement(H, null, "").countsTowardWeeklyCap() && new Term.Tether(H, null).countsTowardWeeklyCap()
                        && new Term.RespawnDelay(H).countsTowardWeeklyCap()
                        && !new Term.Banishment(null, H).countsTowardWeeklyCap() && !new Term.Fine(1).countsTowardWeeklyCap());
    }

    // ---- arrest and custody ----

    static void testArrestAndCustody() {
        World w = world();
        eq("arrest: only on the faction's own land", ContractReasons.NOT_YOUR_LAND.key(), w.arrest(KRIS, w.ashfall, TAM, ch(20, 0)).reasonKey());
        eq("arrest: unclaimed land is nobody's", ContractReasons.NOT_YOUR_LAND.key(), w.arrest(KRIS, w.ashfall, TAM, ch(9, 9)).reasonKey());
        eq("arrest: a member with no arrest power needs a vote", charter.engine.Reason.NEEDS_VOTE.key(), w.arrest(GIL, w.ashfall, TAM, ch(0, 0)).reasonKey());
        eq("arrest: not yourself", ContractReasons.SELF.key(), w.arrest(KRIS, w.ashfall, KRIS, ch(0, 0)).reasonKey());
        check("arrest: the Lord arrests a player on the keep's land", w.arrest(KRIS, w.ashfall, TAM, ch(0, 1)).done() || w.arrest(KRIS, w.ashfall, TAM, ch(0, 0)).done());
        check("arrest: it is in the record", w.f.state.history.has(ContractEvents.Arrested.class));
        eq("arrest: the same player cannot be arrested twice", ContractReasons.ALREADY_HELD.key(), w.arrest(KRIS, w.ashfall, TAM, ch(0, 0)).reasonKey());
        eq("arrest: another faction cannot sentence a player in Ashfall's custody", ContractReasons.NO_CUSTODY.key(),
                w.confine(ZED, w.brine, TAM, H, w.keepB).reasonKey());

        w.wall(Durations.minutes(6));
        check("custody: it lapses after 5 minutes with no sentence", w.f.state.history.has(ContractEvents.CustodyLapsed.class));
        eq("custody: a lapsed custody cannot be sentenced", ContractReasons.NO_CUSTODY.key(), w.confine(KRIS, w.ashfall, TAM, H, w.keepA).reasonKey());
        check("custody: the player can be arrested again", w.arrest(KRIS, w.ashfall, TAM, ch(0, 0)).done());
        w.wall(Durations.minutes(4));
        check("custody: a sentence inside the window works", w.confine(KRIS, w.ashfall, TAM, H, w.keepA).done());
        eq("custody: sentencing ends the custody", false, w.f.state.contracts.custodyOf(TAM).isPresent());
    }

    // ---- fines and execution ----

    static void testFineAndExecution() {
        World w = world();
        long brineBefore = w.f.org(w.brine).ledger.balance(Currency.DIAMOND);
        long ashBefore = w.f.org(w.ashfall).ledger.balance(Currency.DIAMOND);
        w.f.as(ANN, new DepositPersonal(ANN, Currency.DIAMOND, 5));
        w.arrest(KRIS, w.ashfall, ANN, ch(0, 0));
        check("fine: a custody fine goes through", w.f.as(KRIS, new FineCustody(w.ashfall, ANN, 16)).done());
        var fined = w.f.state.history.of(ContractEvents.Fined.class).getLast();
        eq("fine: asked 16", 16L, fined.asked());
        eq("fine: paid only what the prisoner had in their own deposit", 5L, fined.paid());
        eq("fine: the prisoner's deposit is empty", 0L, w.f.state.contracts.vault(ANN).balance(Currency.DIAMOND));
        eq("fine: the jailer's treasury gained the 5", ashBefore + 5, w.f.org(w.ashfall).ledger.balance(Currency.DIAMOND));
        eq("fine: the prisoner's own faction treasury is untouched", brineBefore, w.f.org(w.brine).ledger.balance(Currency.DIAMOND));
        check("fine: an outsider's sentence is marked as one", w.f.state.history.of(ContractEvents.Imposed.class).getLast().outsider());

        w.arrest(KRIS, w.ashfall, SAM, ch(0, 0));
        check("fine: no deposit, nothing paid", w.f.as(KRIS, new FineCustody(w.ashfall, SAM, 10)).done());
        eq("fine: paid 0", 0L, w.f.state.history.of(ContractEvents.Fined.class).getLast().paid());
        check("fine: a fine alone leaves the prisoner free", Justice.holding(w.f.state, w.f.knobs, SAM, w.f.engine.now()).isEmpty());

        // execution
        w.arrest(KRIS, w.ashfall, ROB, ch(0, 0));
        Outcome hanged = w.f.as(KRIS, new Execute(w.ashfall, ROB, Durations.hours(3), Durations.days(5)));
        check("execute: accepted, with the terms cut", hanged.done());
        Contract c = w.latest();
        eq("execute: respawn delay cut to an hour", H, c.term(Term.RespawnDelay.class).durationMillis());
        eq("execute: banishment cut to a day", Durations.DAY, c.term(Term.Banishment.class).durationMillis());
        check("execute: recorded", w.f.state.history.has(ContractEvents.Executed.class));
        long now = w.f.engine.now();
        eq("execute: the prisoner waits an hour to respawn", H, Justice.respawnDelayLeft(w.f.state, w.f.knobs, ROB, now));
        check("execute: banished from Ashfall's land", Justice.banished(w.f.state, w.f.knobs, ROB, w.ashfall, now));
        check("execute: not from Brinewatch's", !Justice.banished(w.f.state, w.f.knobs, ROB, w.brine, now));
        check("execute: not held in a cell", Justice.holding(w.f.state, w.f.knobs, ROB, now).isEmpty());
        w.wall(H + Durations.MINUTE * 2);
        eq("execute: the wait is over after an hour", 0L, Justice.respawnDelayLeft(w.f.state, w.f.knobs, ROB, w.f.engine.now()));
        check("execute: still banished", Justice.banished(w.f.state, w.f.knobs, ROB, w.ashfall, w.f.engine.now()));
        w.wall(Durations.DAY);
        check("execute: banishment ends after a day", !Justice.banished(w.f.state, w.f.knobs, ROB, w.ashfall, w.f.engine.now()));
        eq("execute: the contract is fulfilled", ContractState.FULFILLED, c.state());
    }

    // ---- T: resistance and breach ----

    static void testTResistance() {
        World w = world();
        w.jail(KRIS, w.ashfall, w.keepA, TAM, Durations.hours(12));
        Contract cell = w.latest();
        long now = w.f.engine.now();
        eq("T: only the prisoner can escape", ContractReasons.NOT_YOU.key(), w.f.as(KRIS, new Escape(TAM)).reasonKey());
        eq("T: someone not held cannot escape", ContractReasons.NOT_HELD.key(), w.f.as(PAT, new Escape(PAT)).reasonKey());
        w.wall(2 * H);
        check("T: the prisoner escapes", w.f.as(TAM, new Escape(TAM)).done());
        eq("T: the sentence is resisted", ContractState.RESISTED, cell.state());
        eq("T: no breach mark on the prisoner", 0, w.f.state.contracts.breachMarks(Party.of(TAM)));
        eq("T: no breach mark on the jailer either", 0, w.f.state.contracts.breachMarks(Party.of(w.ashfall)));
        eq("T: no one is recorded as the breaker", null, cell.breachedBy());
        check("T: the escape is in the Chronicle", w.f.state.history.has(ContractEvents.Escaped.class));
        check("T: free at once", Justice.holding(w.f.state, w.f.knobs, TAM, w.f.engine.now()).isEmpty());
        check("T: the week counts only the time served (about 2 hours, not 12)",
                w.f.state.contracts.punishments.counted(TAM, w.f.engine.now()) <= 2 * H + Durations.MINUTE * 2);
        eq("T: an imposed contract cannot be breached", false, cell.breach(Party.of(TAM), now));

        // starving out an occupation by the siege rules
        World o = occupiedWorld();
        Contract occupation = o.contracts(Basis.OCCUPATION).getFirst();
        Core camp = o.camp(o.ashfall, KRIS, -2, 0, 2000);
        o.raise();
        o.adv(Durations.hours(1));
        eq("T: starving the occupiers out resists the occupation", ContractState.RESISTED, occupation.state());
        eq("T: no mark on the occupier", 0, o.f.state.contracts.breachMarks(Party.of(o.brine)));
        eq("T: no mark on the owner", 0, o.f.state.contracts.breachMarks(Party.of(o.ashfall)));
        check("T: the Chronicle records it was resisted", o.f.state.history.has(ContractEvents.Resisted.class));
        check("T: the camp that did it is still ours", camp != null);
    }

    static void testSignedBreach() {
        World w = world();
        Contract treaty = new Contract(w.f.state.ids.contract(), Consent.SIGNED, null, Party.of(w.ashfall), Party.of(w.brine),
                List.of(new Term.Payment(Currency.DIAMOND, 10)), w.f.engine.now(), null, false, Long.MAX_VALUE);
        w.f.state.contracts.add(treaty);
        eq("signed: waits for the other party", ContractState.PROPOSED, treaty.state());
        check("signed: a stranger cannot accept", !treaty.accept(Party.of(w.cinder)));
        check("signed: the other party accepts", treaty.accept(Party.of(w.brine)));
        eq("signed: now it runs", ContractState.ACTIVE, treaty.state());
        eq("signed: a player cannot record a breach", Breach.RULES_ONLY.key(), w.f.as(ZED, new Breach(treaty.id, Party.of(w.brine))).reasonKey());
        eq("signed: only a party can break it", ContractReasons.NOT_A_PARTY.key(), w.f.admin(new Breach(treaty.id, Party.of(w.cinder))).reasonKey());
        check("signed: the rules record Brinewatch breaking it", w.f.admin(new Breach(treaty.id, Party.of(w.brine))).done());
        eq("signed: it is BREACHED", ContractState.BREACHED, treaty.state());
        eq("signed: by Brinewatch", Party.of(w.brine), treaty.breachedBy());
        eq("signed: a mark on the breaker", 1, w.f.state.contracts.breachMarks(Party.of(w.brine)));
        eq("signed: none on the other party", 0, w.f.state.contracts.breachMarks(Party.of(w.ashfall)));
        check("signed: the Chronicle records it", w.f.state.history.has(ContractEvents.Breached.class));
        eq("signed: it cannot be broken twice", ContractReasons.NOT_LIVE.key(), w.f.admin(new Breach(treaty.id, Party.of(w.brine))).reasonKey());
        w.jail(KRIS, w.ashfall, w.keepA, TAM, H);
        eq("signed: an imposed contract has no breach", ContractReasons.IMPOSED_NO_BREACH.key(),
                w.f.admin(new Breach(w.latest().id, Party.of(TAM))).reasonKey());
    }

    // ---- U: the weekly cap ----

    static void testUWeeklyCap() {
        World w = world();
        w.jail(KRIS, w.ashfall, w.keepA, TAM, Durations.hours(16));
        w.wall(Durations.hours(16) + Durations.MINUTE * 2);
        eq("U: the first sentence has run its 16 hours", ContractState.FULFILLED, w.contracts(Basis.CUSTODY).getFirst().state());
        eq("U: 16 hours are counted", Durations.hours(16), w.f.state.contracts.punishments.counted(TAM, w.f.engine.now()));

        w.arrest(ZED, w.brine, TAM, ch(20, 0));
        check("U: another faction sentences 16 hours", w.confine(ZED, w.brine, TAM, Durations.hours(16), w.keepB).done());
        eq("U: but only 8 are left of the week, so they serve 8", Durations.hours(8), confinement(w.latest()).durationMillis());
        check("U: the cut is recorded", w.f.state.history.of(ContractEvents.Imposed.class).getLast().cut());
        eq("U: the week is full", 0L, w.f.state.contracts.punishments.remaining(TAM, w.f.engine.now(), w.f.knobs));
        w.wall(Durations.hours(8) + Durations.MINUTE * 2);
        check("U: after 8 hours the prisoner is free", Justice.holding(w.f.state, w.f.knobs, TAM, w.f.engine.now()).isEmpty());

        w.arrest(VAL, w.cinder, TAM, ch(40, 0));
        eq("U: a third sentence that week is refused", ContractReasons.WEEKLY_CAP.key(), w.confine(VAL, w.cinder, TAM, H, w.keepC).reasonKey());
        check("U: an execution goes ahead, but its respawn delay counts toward the week and is cut away",
                w.f.as(VAL, new Execute(w.cinder, TAM, H, Durations.hours(2))).done());
        Contract executed = w.latest();
        check("U: no respawn delay is left in it", executed.term(Term.RespawnDelay.class) == null);
        eq("U: banishment does not count toward the week, so it stands", Durations.hours(2), executed.term(Term.Banishment.class).durationMillis());
        check("U: the cut is recorded", w.f.state.history.of(ContractEvents.Imposed.class).getLast().cut());

        w.arrest(KRIS, w.ashfall, TAM, ch(0, 0));
        eq("U: the same faction must wait 3 days before sentencing the same player", ContractReasons.COOLDOWN.key(),
                w.confine(KRIS, w.ashfall, TAM, H, w.keepA).reasonKey());
        w.wall(Durations.days(7));
        w.arrest(KRIS, w.ashfall, TAM, ch(0, 0));
        check("U: a week later the slate is clean", w.confine(KRIS, w.ashfall, TAM, H, w.keepA).done());
        eq("U: a full hour", H, confinement(w.latest()).durationMillis());
    }

    static void testPardonAndRelease() {
        World w = world();
        w.jail(KRIS, w.ashfall, w.keepA, PAT, Durations.hours(16));
        w.wall(2 * H);
        eq("release: a member with no judge power needs a vote", charter.engine.Reason.NEEDS_VOTE.key(), w.f.as(GIL, new Release(w.ashfall, PAT)).reasonKey());
        check("release: the jailer lets the prisoner go", w.f.as(KRIS, new Release(w.ashfall, PAT)).done());
        check("release: free", Justice.holding(w.f.state, w.f.knobs, PAT, w.f.engine.now()).isEmpty());
        check("release: recorded", w.f.state.history.has(ContractEvents.Released.class));
        long counted = w.f.state.contracts.punishments.counted(PAT, w.f.engine.now());
        check("release: the week counts only the time served (" + counted + ")", counted >= 2 * H && counted <= 2 * H + Durations.MINUTE * 2);
        w.arrest(VAL, w.cinder, PAT, ch(40, 0));
        check("release: another faction can then give nearly the whole week", w.confine(VAL, w.cinder, PAT, Durations.hours(24), w.keepC).done());
        long got = confinement(w.latest()).durationMillis();
        check("release: about 22 hours (" + got + ")", got <= Durations.hours(22) && got >= Durations.hours(22) - Durations.MINUTE * 2);
        eq("release: nothing to release for a faction that did not jail", ContractReasons.NOT_HELD.key(), w.f.as(KRIS, new Release(w.ashfall, PAT)).reasonKey());

        // a higher organization pardons
        require(w.f.as(KRIS, new CreateSubOrg(w.ashfall, "Ashfall Guard", Preset.LORDSHIP, GIL)), "sub-org");
        OrgId guard = w.f.state.orgNamed("Ashfall Guard").orElseThrow().id;
        w.arrest(GIL, guard, SAM, ch(0, 0));
        check("pardon: the guard sentences from Ashfall's land", w.confine(GIL, guard, SAM, Durations.hours(10), w.keepA).done());
        eq("pardon: Brinewatch has no power to pardon Ashfall's prisoner", charter.engine.Reason.NO_AUTHORITY.key(),
                w.f.as(PAT, new Pardon(w.ashfall, SAM)).reasonKey());
        check("pardon: Ashfall pardons the guard's prisoner", w.f.as(KRIS, new Pardon(w.ashfall, SAM)).done());
        check("pardon: free", Justice.holding(w.f.state, w.f.knobs, SAM, w.f.engine.now()).isEmpty());
        check("pardon: recorded", w.f.state.history.has(ContractEvents.Pardoned.class));
        eq("pardon: nothing left to pardon", ContractReasons.NOT_HELD.key(), w.f.as(KRIS, new Pardon(w.ashfall, SAM)).reasonKey());
    }

    static void testNotoriety() {
        World w = world();
        double start = w.f.state.siege.notorietyOf(w.ashfall);
        w.jail(KRIS, w.ashfall, w.keepA, TAM, H);
        double light = w.f.state.siege.notorietyOf(w.ashfall) - start;
        w.jail(KRIS, w.ashfall, w.keepA, PAT, Durations.hours(24));
        double heavy = w.f.state.siege.notorietyOf(w.ashfall) - start - light;
        check("notoriety: an imposed sentence costs the imposer notoriety (" + light + ")", light > 0);
        check("notoriety: a harsher sentence costs more (" + light + " then " + heavy + ")", heavy > light * 5);
        eq("notoriety: charged to the faction, not to anyone else", 0.0, w.f.state.siege.notorietyOf(w.brine));
        var event = w.f.state.history.of(ContractEvents.Imposed.class).getLast();
        check("notoriety: the record carries it", Math.abs(event.notoriety() - heavy) < 1e-9);
        double before = w.f.state.siege.notorietyOf(w.cinder);
        w.f.knobs.imposedNotorietyPerSeverity = 3.0;
        w.jail(VAL, w.cinder, w.keepC, ROB, H);
        check("notoriety: the knob scales it", w.f.state.siege.notorietyOf(w.cinder) - before > light * 2.9);
    }

    // ---- V: prisoners eat ----

    static void testVPrisonersEat() {
        World w = world();
        w.f.knobs.imposedNotorietyPerSeverity = 0;
        int base = w.sim.rules().mouths(w.keepA, w.inputs.prisoners(w.keepA.id));
        w.jail(KRIS, w.ashfall, w.keepA, TAM, Durations.hours(20));
        eq("V: one prisoner is one more mouth", base + 1, w.sim.rules().mouths(w.keepA, w.inputs.prisoners(w.keepA.id)));
        w.jail(KRIS, w.ashfall, w.keepA, PAT, Durations.hours(20));
        eq("V: two prisoners, two more mouths", base + 2, w.sim.rules().mouths(w.keepA, w.inputs.prisoners(w.keepA.id)));
        eq("V: the count is per keep", 0, w.inputs.prisoners(w.keepB.id));
        double limitWith = w.sim.rules().storageLimit(w.keepA);
        World none = world();
        eq("V: prisoners do not raise the storage limit", none.sim.rules().storageLimit(none.keepA), limitWith);

        // the real drain, through the sim
        none.f.knobs.imposedNotorietyPerSeverity = 0;
        for (World world : List.of(w, none)) {
            world.fill(world.keepA, 400);
            world.camp(world.brine, ZED, 2, 0, 3000);
            world.raise();
        }
        double wStart = w.granary(w.keepA);
        double nStart = none.granary(none.keepA);
        w.adv(H);
        none.adv(H);
        double withPrisoners = wStart - w.granary(w.keepA);
        double without = nStart - none.granary(none.keepA);
        check("V: the keep is besieged", w.f.state.siege.keep(w.keepA.id).supply == KeepSupply.BESIEGED);
        check("V: an hour with 2 prisoners costs about 5/3 of an hour without (" + withPrisoners + " vs " + without + ")",
                without > 0 && Math.abs(withPrisoners / without - 5.0 / 3.0) < 0.05);

        // when the keep falls the prisoners walk free
        w.untilFall(w.keepA);
        check("V: the keep falls", w.f.state.siege.falls.containsKey(w.keepA.id));
        eq("V: no prisoners are left in it", 0, w.inputs.prisoners(w.keepA.id));
        check("V: the prisoners are released and recorded", w.f.state.history.has(ContractEvents.PrisonersFreed.class));
        check("V: Tam walks free", Justice.holding(w.f.state, w.f.knobs, TAM, w.f.engine.now()).isEmpty());
        eq("V: the sentences ended, not escaped", ContractState.ENDED, w.contracts(Basis.CUSTODY).getFirst().state());
        eq("V: no breach mark for being freed", 0, w.f.state.contracts.breachMarks(Party.of(TAM)));
        check("V: the week counts only the time served", w.f.state.contracts.punishments.counted(TAM, w.f.engine.now()) < Durations.hours(20));
    }

    // ---- W: occupation ----

    static World occupiedWorld() {
        World w = world();
        w.fill(w.keepA, 12);
        w.camp(w.brine, ZED, 2, 0, 2000);
        w.raise();
        w.untilFall(w.keepA);
        require(w.f.as(ZED, new ChooseFall(w.brine, w.keepA.id, FallChoice.OCCUPY)), "occupy");
        return w;
    }

    static void testWOccupation() {
        World w = occupiedWorld();
        Contract occupation = w.contracts(Basis.OCCUPATION).getFirst();
        check("W: the keep stays recorded to its owner", w.f.state.core(w.keepA.id).map(c -> c.owner.equals(w.ashfall)).orElse(false));
        eq("W: the occupier holds it", w.brine, w.sim.rules().holder(w.keepA));
        eq("W: the occupation is an imposed contract", Consent.IMPOSED, occupation.consent);
        eq("W: on the occupation basis", Basis.OCCUPATION, occupation.basis);
        eq("W: imposed by the occupier", Party.of(w.brine), occupation.imposer);
        eq("W: on the owner", Party.of(w.ashfall), occupation.subject);
        eq("W: it is running", ContractState.ACTIVE, occupation.state());
        check("W: it carries an access term for the keep", occupation.term(Term.Access.class) != null
                && occupation.term(Term.Access.class).keep().equals(w.keepA.id));
        check("W: the occupier holds access", Occupations.holdsAccess(w.f.state, w.keepA.id, w.brine));
        check("W: the owner does not", !Occupations.holdsAccess(w.f.state, w.keepA.id, w.ashfall));
        check("W: the occupation was recorded in the Chronicle", w.f.state.history.has(ContractEvents.Imposed.class));

        // the granary is the occupier's
        long mine = w.f.engine.now();
        Outcome owner = w.f.as(KRIS, new DepositFood(w.ashfall, w.keepA.id, "player:" + KRIS, Map.of(BREAD, 50)));
        eq("W: the owner can no longer feed it", DepositFood.NOT_YOURS.key(), owner.reasonKey());
        double before = w.granary(w.keepA);
        Outcome occupier = w.f.as(ZED, new DepositFood(w.ashfall, w.keepA.id, "player:" + ZED, Map.of(BREAD, 50)));
        check("W: the occupier can put food in the granary (" + mine + ")", occupier.done());
        eq("W: and it gained what they put in", 250.0, w.granary(w.keepA) - before);
        SiegeRules rules = w.sim.rules();
        eq("W: its mouths are the occupier's members", 3, rules.mouths(w.keepA, 0));

        // the cap runs out
        World c = occupiedWorld();
        Contract capped = c.contracts(Basis.OCCUPATION).getFirst();
        c.adv(Durations.days(c.f.knobs.occupationCapDays) - Durations.MINUTE);
        eq("W: still running just before the cap", ContractState.ACTIVE, capped.state());
        c.adv(Durations.MINUTE * 2);
        eq("W: the cap runs out, the contract is fulfilled", ContractState.FULFILLED, capped.state());
        eq("W: control returns to the owner", c.ashfall, c.sim.rules().holder(c.keepA));
        check("W: the owner holds access again", !Occupations.holdsAccess(c.f.state, c.keepA.id, c.brine));
        check("W: still recorded to the owner", c.f.state.core(c.keepA.id).map(k -> k.owner.equals(c.ashfall)).orElse(false));
        eq("W: no breach marks", 0, c.f.state.contracts.breachMarks(Party.of(c.brine)));

        // the defenders starve the occupiers out
        World s = occupiedWorld();
        Contract starved = s.contracts(Basis.OCCUPATION).getFirst();
        s.f.as(ZED, new DepositFood(s.ashfall, s.keepA.id, "player:" + ZED, Map.of(BREAD, 20)));
        s.camp(s.ashfall, KRIS, -2, 0, 2000);
        s.raise();
        check("W: the owner's camp besieges the occupier", s.f.state.siege.keep(s.keepA.id).supply != KeepSupply.SUPPLIED
                || starved.state() != ContractState.ACTIVE);
        for (int i = 0; i < 600 && starved.state() == ContractState.ACTIVE; i++) s.adv(Durations.MINUTE);
        eq("W: starved out, control returns", s.ashfall, s.sim.rules().holder(s.keepA));
        eq("W: the contract is resisted", ContractState.RESISTED, starved.state());
        check("W: the keep is still recorded to its owner", s.f.state.core(s.keepA.id).map(k -> k.owner.equals(s.ashfall)).orElse(false));
        check("W: the starving-out is recorded by the siege too", s.f.state.history.of(SiegeEvents.OccupationEnded.class).stream()
                .anyMatch(e -> e.why().equals("starved")));
    }

    // ---- D7: the deposit grant ----

    static void testD7Deposit() {
        World w = world();
        w.fill(w.keepA, 300);
        w.camp(w.brine, ZED, 2, 0, 2000);
        w.raise();
        check("D7: the keep is besieged", w.sim.rules().supplyOf(w.keepA) != KeepSupply.SUPPLIED);
        String carrier = "player:" + VAL;
        DepositFood deposit = new DepositFood(w.ashfall, w.keepA.id, carrier, Map.of(BREAD, 60));
        w.sim.supply().enter(carrier, ch(0, 2), ch(0, 1), Map.of(BREAD, 40));
        eq("D7: an outsider with no grant is refused", DepositFood.NOT_YOURS.key(), w.f.as(VAL, deposit).reasonKey());

        require(w.f.as(KRIS, new GrantPower(w.ashfall, Power.GRANARY_DEPOSIT, new Holder.OfPlayer(VAL), GrantScope.ANY, false, 0)), "grant");
        var grant = w.f.state.grants().stream().filter(g -> g.power == Power.GRANARY_DEPOSIT && g.recipient.equals(new Holder.OfPlayer(VAL))).findFirst().orElseThrow();
        double before = w.granary(w.keepA);
        check("D7: with a deposit grant the outsider deposits into the besieged granary", w.f.as(VAL, deposit).done());
        eq("D7: but only the 40 they carried in, not the 60 they offered", 40 * 5.0, w.granary(w.keepA) - before);
        eq("D7: nothing more on the same allowance", DepositFood.NOTHING_IN.key(), w.f.as(VAL, deposit).reasonKey());

        w.sim.supply().enter(carrier, ch(0, 2), ch(0, 1), Map.of(BREAD, 30));
        require(w.f.as(KRIS, new RevokeGrant(w.ashfall, Power.GRANARY_DEPOSIT, grant.id)), "revoke");
        eq("D7: the moment the grant is revoked the allowance is no good", DepositFood.NOT_YOURS.key(),
                w.f.as(VAL, new DepositFood(w.ashfall, w.keepA.id, carrier, Map.of(BREAD, 30))).reasonKey());
    }

    // ---- the bond (D12) ----

    static void testBond() {
        World off = world();
        off.arrest(KRIS, off.ashfall, TAM, ch(0, 0));
        eq("bond: off by default", false, off.f.knobs.labourTerms);
        eq("bond: a bond is refused", ContractReasons.BOND_OFF.key(),
                off.f.as(KRIS, new Sentence(off.ashfall, TAM, List.of(new Term.Tether(Durations.hours(4), off.keepA.id)))).reasonKey());
        check("bond: refusing it leaves custody in place", off.f.state.contracts.custodyOf(TAM).isPresent());

        World on = world();
        on.f.knobs.labourTerms = true;
        on.f.knobs.imposedNotorietyPerSeverity = 0;
        on.arrest(KRIS, on.ashfall, TAM, ch(0, 0));
        check("bond: with the knob on a bond is accepted", on.f.as(KRIS,
                new Sentence(on.ashfall, TAM, List.of(new Term.Tether(Durations.hours(30), on.keepA.id)))).done());
        Contract bond = on.latest();
        eq("bond: capped like confinement, 30 hours cut to a day", Durations.DAY, bond.term(Term.Tether.class).durationMillis());
        check("bond: the bonded player is held", Justice.holding(on.f.state, on.f.knobs, TAM, on.f.engine.now()).isPresent());
        eq("bond: and is a mouth on the granary", 1, on.inputs.prisoners(on.keepA.id));
        eq("bond: counted exactly like confinement", Durations.DAY, on.f.state.contracts.punishments.counted(TAM, on.f.engine.now()));
        on.arrest(KRIS, on.ashfall, PAT, ch(0, 0));
        eq("bond: only one place to hold a player", ContractReasons.ONE_HOLD.key(), on.f.as(KRIS, new Sentence(on.ashfall, PAT,
                List.of(new Term.Tether(H, on.keepA.id), new Term.Confinement(H, on.keepA.id, "cell")))).reasonKey());
        on.wall(3 * H);
        check("bond: walking out by force is escape", on.f.as(TAM, new Escape(TAM)).done());
        eq("bond: resistance, not breach", ContractState.RESISTED, bond.state());
        eq("bond: no mark", 0, on.f.state.contracts.breachMarks(Party.of(TAM)));
        eq("bond: no longer a mouth", 0, on.inputs.prisoners(on.keepA.id));
    }
}
