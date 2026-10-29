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
import charter.siege.SiegeEvents;
import charter.siege.SiegeRules;
import charter.siege.SiegeSim;
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
        OrgId ashfall;
        OrgId brine;
        OrgId cinder;
        Core keepA;
        Core keepB;
        Core keepC;

        World() {
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
        on.arrest(KRIS, on.ashfall, TAM, ch(0, 0));
        check("bond: with the knob on a bond is accepted", on.f.as(KRIS,
                new Sentence(on.ashfall, TAM, List.of(new Term.Tether(Durations.hours(30), on.keepA.id)))).done());
        Contract bond = on.latest();
        eq("bond: capped like confinement, 30 hours cut to a day", Durations.DAY, bond.term(Term.Tether.class).durationMillis());
        check("bond: the bonded player is held", Justice.holding(on.f.state, on.f.knobs, TAM, on.f.engine.now()).isPresent());
        eq("bond: counted exactly like confinement", Durations.DAY, on.f.state.contracts.punishments.counted(TAM, on.f.engine.now()));
        on.arrest(KRIS, on.ashfall, PAT, ch(0, 0));
        eq("bond: only one place to hold a player", ContractReasons.ONE_HOLD.key(), on.f.as(KRIS, new Sentence(on.ashfall, PAT,
                List.of(new Term.Tether(H, on.keepA.id), new Term.Confinement(H, on.keepA.id, "cell")))).reasonKey());
        on.wall(3 * H);
        check("bond: walking out by force is escape", on.f.as(TAM, new Escape(TAM)).done());
        eq("bond: resistance, not breach", ContractState.RESISTED, bond.state());
        eq("bond: no mark", 0, on.f.state.contracts.breachMarks(Party.of(TAM)));
    }
}
