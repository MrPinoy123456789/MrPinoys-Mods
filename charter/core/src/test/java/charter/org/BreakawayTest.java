package charter.org;

import charter.Fixture;
import charter.engine.Outcome;
import charter.gov.Preset;
import charter.gov.Vote;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.actions.GrantPower;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.actions.PlaceNexus;
import charter.org.actions.AcceptInvite;
import charter.org.actions.BreakAway;
import charter.org.actions.CreateSubOrg;
import charter.org.actions.FoundKeep;
import charter.org.actions.InviteMember;
import charter.org.actions.Secede;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** Government forms, R4: a sub-faction secedes by a vote of ALL eligible members; a group leaves a flat faction. */
public final class BreakawayTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId GIL = player("Gil");
    static final PlayerId CY = player("Cy");
    static final PlayerId DEE = player("Dee");
    static final String DIM = "minecraft:overworld";

    private BreakawayTest() {
    }

    public static void run() {
        aSubFactionSecedesWhenMoreThanHalfOfAllEligibleVoteYes();
        abstainingAndNotVotingCountAsNo();
        theNewestMemberDoesNotCountUntilEligible();
        theRulerAloneCannotSecede();
        withoutAKeepTheNexusMustComeInTime();
        aSubFactionWithAKeepSecedesAtOnce();
        theCooldownApplies();
        aGroupLeavesAFlatFaction();
        aGroupNeedsTwoAndTime();
        thoseWhoCannotBreakAwayAsAGroup();
    }

    /** Realm (Lordship, Kris) with sub-faction Guard (Lordship, Gil) holding Gil, Cy and Dee; everyone eligible. */
    private static Fixture kingdom() {
        Fixture f = new Fixture();
        f.found("Realm", Preset.LORDSHIP, KRIS, GIL, CY, DEE);
        OrgId realm = f.state.orgNamed("Realm").orElseThrow().id;
        f.passDays(4, KRIS, GIL, CY, DEE);
        check("setup: the sub-faction", f.admin(new CreateSubOrg(realm, "Guard", Preset.LORDSHIP, GIL)).done());
        OrgId guard = f.state.orgNamed("Guard").orElseThrow().id;
        for (PlayerId p : new PlayerId[]{CY, DEE}) {
            f.admin(new InviteMember(guard, p));
            check("setup: " + p + " joins the guard", f.as(p, new AcceptInvite(p, guard)).done());
        }
        f.passDays(4, KRIS, GIL, CY, DEE);
        return f;
    }

    private static OrgId guard(Fixture f) {
        return f.state.orgNamed("Guard").orElseThrow().id;
    }

    private static OrgId realm(Fixture f) {
        return f.state.orgNamed("Realm").orElseThrow().id;
    }

    private static void fundGuard(Fixture f) {
        f.as(GIL, new Deposit(guard(f), Currency.DIAMOND, 70));
    }

    private static Vote proposeSecession(Fixture f) {
        Outcome out = f.engine.propose(GIL, new Secede(guard(f)));
        check("setup: the secession was proposed " + out, out instanceof Outcome.VoteOpened);
        return f.vote(((Outcome.VoteOpened) out).vote());
    }

    static void aSubFactionSecedesWhenMoreThanHalfOfAllEligibleVoteYes() {
        Fixture f = kingdom();
        f.admin(new GrantPower(realm(f), Power.CLAIM, new Holder.OfOrg(guard(f)), GrantScope.ANY, false, 0));
        var grant = f.state.grants().stream().filter(g -> g.power == Power.CLAIM && g.subject.equals(realm(f))
                && g.recipient.equals(new Holder.OfOrg(guard(f)))).findFirst().orElseThrow();
        fundGuard(f);
        Vote vote = proposeSecession(f);
        check("1: one yes of three is not enough", vote.open());
        f.engine.cast(CY, vote.id, Vote.Ballot.YES);
        eq("1: two of three pass it", Vote.State.EXECUTED, vote.state());
        check("1: with no keep it waits for a Nexus", f.state.pendingSecession(guard(f)).isPresent()
                && f.state.org(guard(f)).orElseThrow().parent() != null);
        Outcome placed = f.as(GIL, new PlaceNexus(guard(f), new ChunkKey(DIM, 50, 50)));
        check("1: the Lord places the Nexus at the faction's own cost " + placed, placed.done());
        eq("1: paid from its own treasury", 6L, f.org(guard(f)).ledger.balance(Currency.DIAMOND));
        f.passDays(0.01, KRIS, GIL, CY, DEE);
        check("1: the sub-faction is now a faction of its own", f.state.org(guard(f)).orElseThrow().isTopLevel());
        check("1: nothing is pending", f.state.pendingSecession(guard(f)).isEmpty());
        check("1: its members came with it", f.state.isMember(GIL, guard(f)) && f.state.isMember(CY, guard(f)) && f.state.isMember(DEE, guard(f)));
        check("1: the grant that tied it to the old faction ended", grant.revoked());
        check("1: it was recorded", f.state.history.entries().stream().anyMatch(e -> e.event() instanceof OrgEvents.Seceded));
    }

    static void abstainingAndNotVotingCountAsNo() {
        Fixture f = kingdom();
        Vote vote = proposeSecession(f);
        f.engine.cast(CY, vote.id, Vote.Ballot.ABSTAIN);
        check("2: an abstention does not help", vote.open());
        f.closeVotes(KRIS, GIL, CY, DEE);
        eq("2: one yes, one abstain and one silent member fail it", Vote.State.FAILED, vote.state());
        check("2: nothing changed", f.state.org(guard(f)).orElseThrow().parent() != null && f.state.pendingSecession(guard(f)).isEmpty());
    }

    static void theNewestMemberDoesNotCountUntilEligible() {
        Fixture f = kingdom();
        PlayerId eve = player("Eve");
        f.seen(eve);
        f.admin(new InviteMember(guard(f), eve));
        f.as(eve, new AcceptInvite(eve, guard(f)));
        Vote vote = proposeSecession(f);
        eq("3: the electorate is the three who are eligible, not the newcomer", 3, vote.electorate.size());
        f.engine.cast(CY, vote.id, Vote.Ballot.YES);
        eq("3: two of three still pass it", Vote.State.EXECUTED, vote.state());
    }

    static void theRulerAloneCannotSecede() {
        Fixture f = kingdom();
        eq("4: the Lord of a sub-faction cannot secede alone", charter.engine.Reason.RESERVED.key(),
                f.as(GIL, new Secede(guard(f))).reasonKey());
        eq("4: a faction that is not a sub-faction cannot", OrgReasons.NOT_A_SUBFACTION.key(),
                f.engine.propose(KRIS, new Secede(realm(f))).reasonKey());
    }

    static void withoutAKeepTheNexusMustComeInTime() {
        Fixture f = kingdom();
        Vote vote = proposeSecession(f);
        f.engine.cast(CY, vote.id, Vote.Ballot.YES);
        check("5: it waits for a Nexus", f.state.pendingSecession(guard(f)).isPresent());
        f.pass(charter.time.Durations.hours(f.knobs.breakawayHours) + 60_000, KRIS, GIL, CY, DEE);
        check("5: it lapsed", f.state.pendingSecession(guard(f)).isEmpty());
        check("5: and nothing changed", f.state.org(guard(f)).orElseThrow().parent() != null);
        check("5: the lapse was recorded", f.state.history.entries().stream().anyMatch(e -> e.event() instanceof OrgEvents.BreakawayLapsed));
    }

    static void aSubFactionWithAKeepSecedesAtOnce() {
        Fixture f = kingdom();
        fundGuard(f);
        check("6: the sub-faction raises a keep", f.as(GIL, new PlaceNexus(guard(f), new ChunkKey(DIM, 80, 80))).done());
        Vote vote = proposeSecession(f);
        f.engine.cast(CY, vote.id, Vote.Ballot.YES);
        check("6: it is a faction of its own as soon as the vote passes", f.state.org(guard(f)).orElseThrow().isTopLevel()
                && f.state.pendingSecession(guard(f)).isEmpty());
        check("6: its keep went with it", f.state.coresOf(guard(f)).size() == 1);
    }

    static void theCooldownApplies() {
        Fixture f = kingdom();
        f.state.player(GIL).lastFoundedAt = f.clock.now() - 1000;
        eq("7: someone who founded or broke away lately cannot start a secession", OrgReasons.FOUND_COOLDOWN.key(),
                f.engine.propose(GIL, new Secede(guard(f))).reasonKey());
    }

    private static Fixture flat() {
        Fixture f = new Fixture();
        f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL, CY, DEE);
        f.passDays(4, KRIS, GIL, CY, DEE);
        return f;
    }

    static void aGroupLeavesAFlatFaction() {
        Fixture f = flat();
        OrgId ash = f.state.orgNamed("Ashfall").orElseThrow().id;
        check("8: one member starts", f.as(GIL, new BreakAway(GIL)).done());
        check("8: another joins the same group", f.as(CY, new BreakAway(CY)).done());
        eq("8: the group has both", 2, f.state.groupLeaving(ash).orElseThrow().members().size());
        Outcome founded = f.as(GIL, new FoundKeep(GIL, "Newhold", "", new ChunkKey(DIM, 200, 200), 64));
        check("8: the starter founds the keep " + founded, founded.done());
        OrgId fresh = f.state.orgNamed("Newhold").orElseThrow().id;
        check("8: the starter is its Lord", f.holder(fresh, Preset.LORD).equals(GIL));
        check("8: the one who came along is a member", f.state.isMember(CY, fresh));
        check("8: the others stayed", f.state.isMember(KRIS, ash) && f.state.isMember(DEE, ash));
        eq("8: the new faction is active", OrgStatus.ACTIVE, f.org(fresh).status());
        check("8: the group is gone", f.state.groupLeaving(ash).isEmpty());
    }

    static void aGroupNeedsTwoAndTime() {
        Fixture f = flat();
        OrgId ash = f.state.orgNamed("Ashfall").orElseThrow().id;
        f.as(GIL, new BreakAway(GIL));
        eq("9: one alone cannot", OrgReasons.NEEDS_GROUP.key(),
                f.as(GIL, new FoundKeep(GIL, "Newhold", "", new ChunkKey(DIM, 200, 200), 64)).reasonKey());
        f.pass(charter.time.Durations.hours(f.knobs.breakawayHours) + 60_000, KRIS, GIL, CY, DEE);
        check("9: a group that did not gather in time lapses", f.state.groupLeaving(ash).isEmpty() && f.state.isMember(GIL, ash));
    }

    static void thoseWhoCannotBreakAwayAsAGroup() {
        Fixture f = flat();
        eq("10: a ruler abdicates first", OrgReasons.RULER_STAYS.key(), f.as(KRIS, new BreakAway(KRIS)).reasonKey());
        Fixture k = kingdom();
        eq("10: a faction with sub-factions is left by its sub-factions, not a group", OrgReasons.HAS_SUBFACTIONS.key(),
                k.as(KRIS, new BreakAway(KRIS)).reasonKey().equals(OrgReasons.RULER_STAYS.key())
                        ? OrgReasons.HAS_SUBFACTIONS.key() : k.as(KRIS, new BreakAway(KRIS)).reasonKey());
        PlayerId lone = player("Lone");
        k.seen(lone);
        eq("10: someone with no faction has nothing to break away from", OrgReasons.NOT_A_MEMBER.key(), k.as(lone, new BreakAway(lone)).reasonKey());
    }
}
