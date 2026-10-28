package charter.gov;

import charter.Fixture;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.actions.Appoint;
import charter.gov.actions.CreateOffice;
import charter.gov.actions.Vacate;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.actions.GrantPower;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.org.OrgReasons;
import charter.org.OrgStatus;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Expel;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.org.actions.Rename;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import charter.treasury.actions.Withdraw;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** Acceptance tests A and B (DESIGN.md §22) and the vote rules of §6.3. */
public final class GovernanceTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId GIL = player("Gil");
    static final PlayerId CY = player("Cy");
    static final PlayerId DEE = player("Dee");
    static final PlayerId EVE = player("Eve");

    private GovernanceTest() {
    }

    public static void run() {
        foundingNeedsASecondMember();
        foundingRules();
        testACommune();
        testBLordship();
        absentLordLosesTheOffice();
        newMembersCannotVoteYet();
        electorateIsFixedWhenAVoteOpens();
        tiesFail();
        quorumCountsActiveMembersOnly();
        passedVoteIsRecheckedWhenItRuns();
    }

    static void foundingNeedsASecondMember() {
        Fixture f = new Fixture();
        f.seen(KRIS, GIL);
        f.as(KRIS, new Found(KRIS, "Ashfall", Preset.LORDSHIP));
        OrgId org = f.state.orgNamed("Ashfall").orElseThrow().id;
        eq("found: a new organization is forming", OrgStatus.FORMING, f.org(org).status());
        f.as(KRIS, new InviteMember(org, GIL));
        f.as(GIL, new AcceptInvite(GIL, org));
        eq("found: the second member makes it active", OrgStatus.ACTIVE, f.org(org).status());
        eq("found: the founder of a lordship holds the Lord office", KRIS, f.holder(org, Preset.LORD));
    }

    static void foundingRules() {
        Fixture f = new Fixture();
        OrgId ashfall = f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL);
        eq("found: a taken name is refused", OrgReasons.NAME_TAKEN.key(), f.as(CY, new Found(CY, "ashfall", Preset.COMMUNE)).reasonKey());
        eq("found: a member cannot found another", OrgReasons.ALREADY_MEMBER.key(), f.as(GIL, new Found(GIL, "Vel", Preset.COMMUNE)).reasonKey());
        eq("found: nobody founds for someone else", OrgReasons.NOT_YOU.key(), f.as(CY, new Found(DEE, "Vel", Preset.COMMUNE)).reasonKey());

        OrgId vel = f.found("Vel", Preset.LORDSHIP, CY);
        f.as(KRIS, new InviteMember(ashfall, CY));
        eq("membership: joining another tree means leaving first", OrgReasons.OTHER_TREE.key(), f.as(CY, new AcceptInvite(CY, ashfall)).reasonKey());
        check("membership: refused join changes nothing", f.state.isMember(CY, vel));
    }

    static void testACommune() {
        Fixture f = new Fixture();
        OrgId c = f.found("Riverside", Preset.COMMUNE, KRIS, GIL, CY, DEE);
        f.passDays(3, KRIS, GIL, CY, DEE);
        f.as(KRIS, new Deposit(c, Currency.DIAMOND, 100));

        for (PlayerId member : new PlayerId[]{KRIS, GIL, CY, DEE}) {
            eq("A: an unvoted spend by any member is refused as needing a vote", Reason.NEEDS_VOTE.key(),
                    f.as(member, new Withdraw(c, Currency.DIAMOND, 10, member)).reasonKey());
        }
        eq("A: refusals moved nothing", 100L, f.org(c).ledger.balance(Currency.DIAMOND));

        f.motion(KRIS, new Withdraw(c, Currency.DIAMOND, 30, KRIS), GIL, CY);
        f.closeVotes(KRIS, GIL, CY, DEE);
        eq("A: a spend vote that passes moves the ledger", 70L, f.org(c).ledger.balance(Currency.DIAMOND));

        f.motion(GIL, new CreateOffice(c, "Treasurer"), KRIS, CY);
        f.closeVotes(KRIS, GIL, CY, DEE);
        Office treasurer = f.office(c, "Treasurer");
        f.motion(GIL, new Appoint(c, treasurer.id, CY), KRIS, DEE);
        f.motion(GIL, new GrantPower(c, Power.SPEND, new Holder.OfOffice(treasurer.id), GrantScope.spendUpTo(0, 20), false, 0), KRIS, DEE);
        f.closeVotes(KRIS, GIL, CY, DEE);
        eq("A: an officer vote fills the office", CY, f.holder(c, "Treasurer"));
        check("A: the officer can act within the voted power", f.as(CY, new Withdraw(c, Currency.DIAMOND, 15, CY)).done());
        eq("A: the officer cannot act beyond it", Reason.NEEDS_VOTE.key(), f.as(CY, new Withdraw(c, Currency.DIAMOND, 25, CY)).reasonKey());
        eq("A: another member still cannot spend", Reason.NEEDS_VOTE.key(), f.as(DEE, new Withdraw(c, Currency.DIAMOND, 5, DEE)).reasonKey());
        eq("A: the ledger shows exactly the two spends", 55L, f.org(c).ledger.balance(Currency.DIAMOND));
    }

    static void testBLordship() {
        Fixture f = new Fixture();
        OrgId org = f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL, CY, DEE);
        f.passDays(3, KRIS, GIL, CY, DEE);
        f.as(GIL, new Deposit(org, Currency.DIAMOND, 200));
        int limit = f.knobs.lordSpendLimitDiamonds;

        check("B: the Lord spends under the limit", f.as(KRIS, new Withdraw(org, Currency.DIAMOND, limit, KRIS)).done());
        check("B: the Lord is refused over the limit", f.as(KRIS, new Withdraw(org, Currency.DIAMOND, limit + 1, KRIS)).refused());
        eq("B: the Lord cannot amend the charter", Reason.RESERVED.key(), f.as(KRIS, new Rename(org, "Kris Land")).reasonKey());
        Office lord = f.office(org, Preset.LORD);
        eq("B: the Lord cannot remove himself from the reserved office alone", Reason.RESERVED.key(), f.as(KRIS, new Vacate(org, lord.id)).reasonKey());
        eq("B: no member can expel the Lord without a vote", Reason.RESERVED.key(), f.as(GIL, new Expel(org, KRIS)).reasonKey());

        f.motion(GIL, new Vacate(org, lord.id), CY, DEE);
        f.closeVotes(KRIS, GIL, CY, DEE);
        check("B: the members voted the Lord out", f.office(org, Preset.LORD).vacant());
        eq("B: the Lord's next action is refused", Reason.NEEDS_VOTE.key(), f.as(KRIS, new Withdraw(org, Currency.DIAMOND, 1, KRIS)).reasonKey());
        eq("B: the treasury paid only the one spend", 200L - limit, f.org(org).ledger.balance(Currency.DIAMOND));

        f.motion(GIL, new Rename(org, "Free Ashfall"), CY, DEE);
        f.closeVotes(KRIS, GIL, CY, DEE);
        eq("B: the members amend the charter by vote", "Free Ashfall", f.org(org).name());
    }

    static void absentLordLosesTheOffice() {
        Fixture f = new Fixture();
        OrgId org = f.found("Ashfall", Preset.LORDSHIP, KRIS, GIL, CY);
        f.passDays(6, GIL, CY);
        check("B: a Lord absent 6 days still holds the office", KRIS.equals(f.holder(org, Preset.LORD)));
        f.passDays(1.5, GIL, CY);
        check("B: a Lord absent past 7 days loses the office", f.office(org, Preset.LORD).vacant());
        Vote election = f.state.votes().stream().filter(v -> v.open() && v.kind == Vote.Kind.ELECTION).findFirst().orElse(null);
        check("B: a succession election opens", election != null);
        if (election == null) return;
        f.engine.cast(GIL, election.id, Vote.Ballot.candidate(CY));
        f.engine.cast(CY, election.id, Vote.Ballot.candidate(CY));
        check("B: the election stays open while the absent Lord could still vote", election.open());
        f.closeVotes(GIL, CY);
        eq("B: the election fills the office", CY, f.holder(org, Preset.LORD));
        check("B: the new Lord holds the Lord's powers", f.as(CY, new InviteMember(org, DEE)).done());
    }

    static void newMembersCannotVoteYet() {
        Fixture f = new Fixture();
        OrgId c = f.found("Riverside", Preset.COMMUNE, KRIS, GIL);
        f.passDays(1, KRIS, GIL);
        eq("vote: a member of 1 day cannot propose", Reason.NOT_ELIGIBLE.key(), f.engine.propose(KRIS, new CreateOffice(c, "Reeve")).reasonKey());
        f.passDays(2.1, KRIS, GIL);
        check("vote: after 3 days they can", f.engine.propose(KRIS, new CreateOffice(c, "Reeve")) instanceof Outcome.VoteOpened);
    }

    static void electorateIsFixedWhenAVoteOpens() {
        Fixture f = new Fixture();
        OrgId c = f.found("Riverside", Preset.COMMUNE, KRIS, GIL, CY);
        f.passDays(3, KRIS, GIL, CY);
        VoteId id = f.motion(KRIS, new CreateOffice(c, "Reeve"));
        f.seen(DEE);
        f.admin(new InviteMember(c, DEE));
        f.as(DEE, new AcceptInvite(DEE, c));
        eq("vote: a member admitted after opening cannot vote", Reason.NOT_ELIGIBLE.key(), f.engine.cast(DEE, id, Vote.Ballot.YES).reasonKey());
        f.admin(new Expel(c, GIL));
        check("vote: an expelled voter stays in the electorate", f.engine.cast(GIL, id, Vote.Ballot.YES) == Outcome.OK);
        check("vote: and the vote completes with their ballot", f.vote(id).state() != Vote.State.OPEN || f.vote(id).ballots().size() == 2);
    }

    static void tiesFail() {
        Fixture f = new Fixture();
        OrgId c = f.found("Riverside", Preset.COMMUNE, KRIS, GIL, CY, DEE);
        f.passDays(3, KRIS, GIL, CY, DEE);
        VoteId id = f.motion(KRIS, new CreateOffice(c, "Reeve"), GIL);
        f.engine.cast(CY, id, Vote.Ballot.NO);
        f.engine.cast(DEE, id, Vote.Ballot.NO);
        eq("vote: a tie fails", Vote.State.FAILED, f.vote(id).state());
        eq("vote: a tie says why", "tie", f.vote(id).result());
        check("vote: nothing ran", f.state.officeNamed(c, "Reeve").isEmpty());
    }

    static void quorumCountsActiveMembersOnly() {
        Fixture f = new Fixture();
        OrgId c = f.found("Riverside", Preset.COMMUNE, KRIS, GIL, CY, DEE);
        f.passDays(20, KRIS, GIL);
        VoteId id = f.motion(KRIS, new CreateOffice(c, "Reeve"));
        eq("quorum: only the two active members count, so one ballot is enough", 1, f.vote(id).quorumNeeded());
        f.closeVotes(KRIS, GIL);
        check("quorum: the vote passes on the active quorum", f.state.officeNamed(c, "Reeve").isPresent());

        f.seen(CY, DEE);
        VoteId second = f.motion(KRIS, new CreateOffice(c, "Warden"));
        eq("quorum: with all four active, two ballots are needed", 2, f.vote(second).quorumNeeded());
        f.closeVotes(KRIS, GIL, CY, DEE);
        eq("quorum: one ballot of four fails for quorum", "no quorum", f.vote(second).result());
    }

    static void passedVoteIsRecheckedWhenItRuns() {
        Fixture f = new Fixture();
        OrgId c = f.found("Riverside", Preset.COMMUNE, KRIS, GIL);
        f.passDays(3, KRIS, GIL);
        f.as(KRIS, new Deposit(c, Currency.DIAMOND, 10));
        VoteId id = f.motion(KRIS, new Withdraw(c, Currency.DIAMOND, 10, KRIS));
        f.admin(new Withdraw(c, Currency.DIAMOND, 5, GIL));
        f.closeVotes(KRIS, GIL);
        eq("vote: a passed spend the treasury can no longer cover is refused when it runs",
                Vote.State.REFUSED_AT_EXECUTION, f.vote(id).state());
        eq("vote: nothing more left the treasury", 5L, f.org(c).ledger.balance(Currency.DIAMOND));
    }
}
