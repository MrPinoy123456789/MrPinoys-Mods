package charter.mc;

import charter.config.Knobs;
import charter.engine.ActionEngine;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.gov.Office;
import charter.gov.Preset;
import charter.gov.Vote;
import charter.gov.VoteMethod;
import charter.gov.actions.Appoint;
import charter.gov.actions.CreateOffice;
import charter.grant.Grant;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.actions.GrantPower;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.CampPhase;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.land.CoreKind;
import charter.org.Organization;
import charter.org.actions.AcceptInvite;
import charter.org.actions.CreateSubOrg;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.org.actions.Rename;
import charter.siege.Allowance;
import charter.siege.CampSupply;
import charter.siege.KeepSupply;
import charter.siege.SiegeBook;
import charter.siege.Store;
import charter.state.CharterState;
import charter.time.Clock;
import charter.time.Durations;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import charter.treasury.actions.Withdraw;

import java.util.Map;
import java.util.UUID;

/**
 * A state holding every kind of record M0 to M5 have (test H): organizations in a
 * tree, members with roles, offices, root, derived and revoked grants, an open vote
 * with a pending motion and a settled one, an election, treasuries, People, keeps,
 * camps and claims (one contested), granary and camp stores with debts, holds,
 * allowances, truces, a pending fall, an occupation, notoriety and the siege clock.
 * Built through the real engine where an action exists, directly where none does yet.
 */
final class FullState {

    static final String OW = "minecraft:overworld";

    final Clock.Manual clock = new Clock.Manual(1_800_000_000_000L);
    final Knobs knobs = new Knobs();
    final CharterState state;
    final ActionEngine engine;

    final PlayerId alice = player(1), bob = player(2), cara = player(3), dan = player(4), eve = player(5);
    OrgId oakvale, ironhold, guard;
    Core keep, camp;
    Claim contested;

    static PlayerId player(int n) {
        return new PlayerId(new UUID(0xABCDL, n));
    }

    FullState() {
        this(new CharterState());
    }

    FullState(CharterState state) {
        this.state = state;
        this.engine = new ActionEngine(state, knobs, clock);
    }

    static FullState build() {
        FullState f = new FullState();
        f.fill();
        return f;
    }

    private Outcome admin(charter.engine.Action action) {
        Outcome o = engine.run(new Actor.Admin("test"), action);
        if (o.refused()) throw new IllegalStateException(action + " refused: " + o.reasonKey());
        return o;
    }

    private void fill() {
        long now = clock.now();
        state.touch(alice, "Alice", now);
        state.touch(bob, "Bob", now);
        state.touch(cara, "Cara", now);
        state.touch(dan, "Dan", now);
        state.touch(eve, "Eve", now);

        engine.run(new Actor.Player(alice), new Found(alice, "Oakvale", Preset.COMMUNE));
        engine.run(new Actor.Player(cara), new Found(cara, "Ironhold", Preset.LORDSHIP));
        oakvale = state.orgNamed("Oakvale").orElseThrow().id;
        ironhold = state.orgNamed("Ironhold").orElseThrow().id;
        admin(new InviteMember(oakvale, bob));
        engine.run(new Actor.Player(bob), new AcceptInvite(bob, oakvale));
        admin(new InviteMember(oakvale, dan));
        admin(new InviteMember(ironhold, eve));
        engine.run(new Actor.Player(eve), new AcceptInvite(eve, ironhold));
        admin(new CreateSubOrg(ironhold, "Ironhold Guard", Preset.COMMUNE, eve));
        guard = state.orgNamed("Ironhold Guard").orElseThrow().id;

        admin(new CreateOffice(oakvale, "Treasurer"));
        Office treasurer = state.officeNamed(oakvale, "Treasurer").orElseThrow();
        admin(new Appoint(oakvale, treasurer.id, alice));
        admin(new GrantPower(oakvale, Power.SPEND, new Holder.OfOffice(treasurer.id), GrantScope.spendUpTo(100, 5), true, 0));
        engine.run(new Actor.Player(alice), new GrantPower(oakvale, Power.SPEND, new Holder.OfPlayer(bob),
                GrantScope.spendUpTo(10, 1), false, now + Durations.days(30)));
        admin(new GrantPower(oakvale, Power.CLAIM, new Holder.OfOrg(guard), GrantScope.ANY, false, 0));
        state.grants().stream().filter(g -> g.power == Power.CLAIM).findFirst().orElseThrow().revoke(now + 5);

        admin(new Deposit(oakvale, Currency.DIAMOND, 50));
        admin(new Deposit(oakvale, Currency.COBBLESTONE, 500));

        Organization oak = state.org(oakvale).orElseThrow();
        oak.describe("A village by the river.");
        oak.governance.setMethod(Power.SPEND, VoteMethod.SUPERMAJORITY);
        oak.governance.quorumShare = 0.6;
        oak.people.name = "Oak folk";
        oak.people.originStory = "They came down from the hills.";
        oak.people.banner = "{pattern:stripes}";
        oak.people.revision = 2;
        state.restoreMembership(new charter.org.MemberRecord(bob, oakvale, state.membership(bob).orElseThrow().joinedAt(),
                java.util.Set.of("smith", "scout")));

        // Votes: members must have stood for the eligibility period.
        clock.advance(Durations.days(4));
        state.touch(alice, "Alice", clock.now());
        state.touch(bob, "Bob", clock.now());
        engine.propose(alice, new Withdraw(oakvale, Currency.DIAMOND, 5, bob));
        Outcome renamed = engine.propose(alice, new Rename(oakvale, "Oakvale Commons"));
        engine.cast(bob, ((Outcome.VoteOpened) renamed).vote(), Vote.Ballot.NO);
        Office lord = state.officeNamed(ironhold, "Lord").orElseThrow();
        state.addBreakaway(new charter.org.Breakaway(charter.org.Breakaway.Kind.SUBFACTION, guard, null, java.util.List.of(), now, now + 1000));
        state.addBreakaway(new charter.org.Breakaway(charter.org.Breakaway.Kind.GROUP, oakvale, alice, java.util.List.of(alice, bob), now, now + 2000));
        state.addJoinRequest(new charter.org.JoinRequest(oakvale, eve, clock.now()));
        // A council of two deciding together, with a vote among its seats still open (S1).
        admin(new charter.gov.actions.SetSeats(ironhold, lord.id, 3, Office.Deciding.TOGETHER));
        admin(new InviteMember(ironhold, dan));
        engine.run(new Actor.Player(dan), new AcceptInvite(dan, ironhold));
        admin(new Appoint(ironhold, lord.id, dan));
        engine.proposeToSeats(cara, lord.id, new CreateOffice(ironhold, "Scribe"));
        engine.openElection(lord.id, null);

        // Land, placed directly: placement and its prices are M8's.
        keep = new Core(state.ids.core(), CoreKind.KEEP, oakvale, new ChunkKey(OW, 0, 0), state.takeCoreOrder(),
                clock.now(), CampPhase.ACTIVE, 0);
        camp = new Core(state.ids.core(), CoreKind.CAMP, ironhold, new ChunkKey(OW, 6, 0), state.takeCoreOrder(),
                clock.now(), CampPhase.RAISING, 1234);
        state.addCore(keep);
        state.addCore(camp);
        state.addClaim(new Claim(state.ids.claim(), keep.center, oakvale, ClaimKind.KEEP_CORE, keep.id, 0, clock.now()));
        Claim farm = new Claim(state.ids.claim(), new ChunkKey(OW, 2, 0), oakvale, ClaimKind.OUTLYING, keep.id, 2, clock.now());
        state.addClaim(farm);
        contested = new Claim(state.ids.claim(), new ChunkKey(OW, 3, 3), oakvale, ClaimKind.OUTLYING, keep.id, 3, clock.now());
        contested.restoreContested(9_999_999L);
        state.addClaim(contested);
        state.addClaim(new Claim(state.ids.claim(), camp.center, ironhold, ClaimKind.CAMP, camp.id, 0, clock.now()));

        // The siege book.
        SiegeBook book = state.siege;
        Store granary = book.store(keep.id);
        granary.provisions.add("minecraft:bread", 30).add("minecraft:baked_potato", 12);
        granary.eatDebt = 0.4;
        granary.spoilDebts.put("minecraft:bread", 0.25);
        book.store(camp.id).provisions.add("minecraft:cooked_beef", 64);
        book.store(camp.id).eatDebt = 1.75;
        SiegeBook.KeepSite site = book.keep(keep.id);
        site.supply = KeepSupply.BESIEGED;
        site.starving = true;
        site.besiegers.add(ironhold);
        SiegeBook.CampSite campSite = book.camp(camp.id);
        campSite.supply = CampSupply.CUT_OFF;
        campSite.sallyLeft = 5_000;
        campSite.exposed = true;
        campSite.targets.add(oakvale);
        book.holds.put(farm.id, 7_200_000L);
        book.allowances.add(new Allowance("player:" + bob.value(), keep.id, Map.of("minecraft:bread", 5), 99_999));
        book.truces.add(new SiegeBook.Truce(keep.id, guard, 555));
        book.falls.put(keep.id, new SiegeBook.PendingFall(keep.id, ironhold, 777));
        book.occupations.put(keep.id, new SiegeBook.Occupation(keep.id, ironhold, 888));
        book.notoriety.put(ironhold, 2.5);
        book.protectionWaived.add(ironhold);

        state.siegeClock.advanceSiege(123_456);

        // The contracts block (M6): a jailing through the real actions, a personal
        // deposit, a breach mark, and a signed contract carrying every kind of term.
        admin(new charter.contract.actions.Arrest(oakvale, eve, keep.center));
        admin(new charter.contract.actions.Confine(oakvale, eve, Durations.hours(2), keep.id, "cell"));
        admin(new charter.contract.actions.DepositPersonal(dan, Currency.DIAMOND, 7));
        charter.contract.Party oakParty = new charter.contract.Party(oakvale, null);
        charter.contract.Party ironParty = new charter.contract.Party(ironhold, null);
        state.contracts.markBreach(ironParty);
        java.util.List<charter.contract.Term> everyTerm = java.util.List.of(
                new charter.contract.Term.Payment(Currency.COBBLESTONE, 64),
                new charter.contract.Term.Fine(3),
                new charter.contract.Term.Delivery("minecraft:bread", 100, 86_400_000L),
                new charter.contract.Term.GrantTerm(Power.GRANARY_DEPOSIT, oakvale, 3_600_000L),
                new charter.contract.Term.Access(keep.id, 7_200_000L),
                new charter.contract.Term.Confinement(3_600_000L, keep.id, "north cell"),
                new charter.contract.Term.Tether(3_600_000L, keep.id),
                new charter.contract.Term.Banishment(oakvale, 3_600_000L),
                new charter.contract.Term.RespawnDelay(600_000L),
                new charter.contract.Term.RelationLock(java.util.Set.of(guard), 86_400_000L),
                new charter.contract.Term.ClaimLimit(12, 86_400_000L),
                new charter.contract.Term.Cede(java.util.Set.of(farm.id)),
                new charter.contract.Term.Release("vassals"));
        charter.contract.Contract treaty = new charter.contract.Contract(state.ids.contract(), charter.contract.Consent.SIGNED,
                null, oakParty, ironParty, everyTerm, clock.now(), keep.id, false, clock.now() + Durations.days(5));
        treaty.accept(oakParty);
        state.contracts.add(treaty);
    }

    /** The grants derived from another grant. */
    static long derivedCount(CharterState state) {
        return state.grants().stream().filter(g -> !g.isRoot()).count();
    }

    static Grant first(CharterState state, Power power) {
        return state.grants().stream().filter(g -> g.power == power).findFirst().orElseThrow();
    }
}
