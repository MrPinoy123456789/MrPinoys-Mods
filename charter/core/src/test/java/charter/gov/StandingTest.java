package charter.gov;

import charter.Fixture;
import charter.engine.Outcome;
import charter.gov.actions.Appoint;
import charter.gov.actions.CreateOffice;
import charter.gov.actions.SetSeats;
import charter.gov.actions.Vacate;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.actions.GrantPower;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.org.actions.CreateSubOrg;
import charter.org.actions.Rename;

import java.util.List;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** B1: the standing model, what a player holds and gives away, read from grants. */
public final class StandingTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId GIL = player("Gil");
    static final PlayerId CY = player("Cy");

    private StandingTest() {
    }

    public static void run() {
        aLordshipFounderHoldsEverythingButTheCharter();
        theTreasuryLineCarriesTheSpendLimit();
        aMemberReadsFreedoms();
        aNewMemberCannotVoteYet();
        aSheriffHoldsJustice();
        aMixedGroupSplits();
        aCommuneMemberVotesOnEverything();
        aCouncilOfThree();
        aSubOrganizationReadsItsOwn();
        votesWaitingUntilCast();
        everyPowerIsInOneGroup();
        aNewcomerSeesFreedomsOnly();
    }

    private static Fixture world(Preset preset, int days) {
        Fixture f = new Fixture();
        f.found("Ashfall", preset, KRIS, GIL, CY);
        f.passDays(days, KRIS, GIL, CY);
        return f;
    }

    private static OrgId org(Fixture f) {
        return f.state.orgNamed("Ashfall").orElseThrow().id;
    }

    private static Standing.View view(Fixture f, PlayerId who) {
        return Standing.of(f.state, f.engine, who, f.clock.now()).orElseThrow();
    }

    private static Standing.Line line(Standing.View v, PowerGroup group, Power power) {
        return v.lines().stream().filter(l -> l.group() == group && l.power() == power).findFirst().orElseThrow();
    }

    static void aLordshipFounderHoldsEverythingButTheCharter() {
        Fixture f = world(Preset.LORDSHIP, 4);
        Standing.View v = view(f, KRIS);
        eq("1: a lordship", Standing.Form.LORDSHIP, v.form());
        for (Standing.Line l : v.lines()) {
            if (l.group() == PowerGroup.CHARTER) {
                eq("1: the charter stays with the members", Standing.HolderKind.MEMBERS_BY_VOTE, l.kind());
            } else {
                check("1: " + l.group() + " is a Right", l.right());
                eq("1: held as " + l.group(), "Lord", l.name());
            }
        }
        eq("1: the founder's office", List.of("Lord"), v.yourOffices());
    }

    static void theTreasuryLineCarriesTheSpendLimit() {
        Fixture f = world(Preset.LORDSHIP, 4);
        eq("2: the Lord's spending is limited", 32L,
                line(view(f, KRIS), PowerGroup.TREASURY, null).spendLimitDiamonds());
        eq("2: other powers have no limit", -1L, line(view(f, KRIS), PowerGroup.MEMBERS, null).spendLimitDiamonds());
    }

    static void aMemberReadsFreedoms() {
        Fixture f = world(Preset.LORDSHIP, 4);
        Standing.View v = view(f, GIL);
        for (Standing.Line l : v.lines()) {
            if (l.group() == PowerGroup.CHARTER) {
                eq("3: the charter is a vote", Standing.HolderKind.MEMBERS_BY_VOTE, l.kind());
                check("3: and Gil can vote", l.youCanVote());
            } else {
                eq("3: " + l.group() + " is held by the office", Standing.HolderKind.OFFICE, l.kind());
                eq("3: named Lord", "Lord", l.name());
                eq("3: one holder", 1, l.holders().size());
            }
        }
        check("3: no Rights", v.rights().isEmpty());
        eq("3: no offices", 0, v.yourOffices().size());
    }

    static void aNewMemberCannotVoteYet() {
        Fixture f = world(Preset.LORDSHIP, 2);
        Standing.Line charter = line(view(f, GIL), PowerGroup.CHARTER, null);
        check("4: a member of two days cannot vote on the charter", !charter.youCanVote());
    }

    private static OfficeId sheriff(Fixture f) {
        OrgId org = org(f);
        check("setup: office", f.as(KRIS, new CreateOffice(org, "Sheriff")).done());
        OfficeId id = f.office(org, "Sheriff").id;
        check("setup: appoint", f.as(KRIS, new Appoint(org, id, GIL)).done());
        return id;
    }

    static void aSheriffHoldsJustice() {
        Fixture f = world(Preset.LORDSHIP, 4);
        OfficeId sheriff = sheriff(f);
        check("5: arrest", f.as(KRIS, new GrantPower(org(f), Power.ARREST, new Holder.OfOffice(sheriff), GrantScope.ANY, false, 0)).done());
        check("5: judge", f.as(KRIS, new GrantPower(org(f), Power.JUDGE, new Holder.OfOffice(sheriff), GrantScope.ANY, false, 0)).done());
        Standing.Line justice = line(view(f, GIL), PowerGroup.JUSTICE, null);
        check("5: the Sheriff's justice is a Right", justice.right());
        eq("5: named Sheriff", "Sheriff", justice.name());
        check("5: vacate", f.as(KRIS, new Vacate(org(f), sheriff)).done());
        Standing.Line after = line(view(f, GIL), PowerGroup.JUSTICE, null);
        check("5: after Vacate it is a Freedom again", !after.right());
        eq("5: held by the Lord", "Lord", after.name());
    }

    static void aMixedGroupSplits() {
        Fixture f = world(Preset.LORDSHIP, 4);
        OfficeId sheriff = sheriff(f);
        check("6: arrest only", f.as(KRIS, new GrantPower(org(f), Power.ARREST, new Holder.OfOffice(sheriff), GrantScope.ANY, false, 0)).done());
        Standing.View v = view(f, GIL);
        check("6: the group splits", v.lines().stream().noneMatch(l -> l.group() == PowerGroup.JUSTICE && l.power() == null));
        check("6: arrest is a Right", line(v, PowerGroup.JUSTICE, Power.ARREST).right());
        Standing.Line judge = line(v, PowerGroup.JUSTICE, Power.JUDGE);
        check("6: judge is not", !judge.right());
        eq("6: held by the Lord", "Lord", judge.name());
    }

    static void aCommuneMemberVotesOnEverything() {
        Fixture f = world(Preset.COMMUNE, 4);
        Standing.View v = view(f, KRIS);
        eq("7: a commune", Standing.Form.COMMUNE, v.form());
        for (Standing.Line l : v.lines()) {
            eq("7: " + l.group() + " is by vote", Standing.HolderKind.MEMBERS_BY_VOTE, l.kind());
        }
    }

    private static Fixture council(Office.Deciding deciding) {
        Fixture f = world(Preset.COMMUNE, 4);
        OfficeId lord = f.office(org(f), Preset.LORD).id;
        f.motion(KRIS, new SetSeats(org(f), lord, 3, deciding), GIL, CY);
        for (PlayerId who : new PlayerId[]{KRIS, GIL, CY}) {
            Outcome opened = f.engine.openElection(lord, KRIS);
            var id = ((Outcome.VoteOpened) opened).vote();
            for (PlayerId voter : new PlayerId[]{KRIS, GIL, CY}) f.engine.cast(voter, id, Vote.Ballot.candidate(who));
        }
        return f;
    }

    static void aCouncilOfThree() {
        Fixture together = council(Office.Deciding.TOGETHER);
        Standing.View v = view(together, KRIS);
        eq("8: a council", Standing.Form.COUNCIL, v.form());
        for (Standing.Line l : v.lines()) {
            if (l.group() == PowerGroup.CHARTER) continue;
            eq("8: " + l.group() + " cannot be used alone", Standing.HolderKind.OFFICE, l.kind());
            check("8: it decides together", l.together());
            eq("8: three names", 3, l.holders().size());
        }
        Fixture alone = council(Office.Deciding.ALONE);
        Standing.View a = view(alone, KRIS);
        eq("8: still a council", Standing.Form.COUNCIL, a.form());
        check("8: deciding alone they are Rights", a.lines().stream().filter(l -> l.group() != PowerGroup.CHARTER).allMatch(Standing.Line::right));
    }

    static void aSubOrganizationReadsItsOwn() {
        Fixture f = world(Preset.LORDSHIP, 4);
        check("9: sub organization", f.admin(new CreateSubOrg(org(f), "Guard", Preset.LORDSHIP, GIL)).done());
        Standing.View v = view(f, GIL);
        eq("9: lines are about the sub organization", "Guard", v.orgName());
        eq("9: its form", Standing.Form.LORDSHIP, v.form());
        check("9: its leader holds its powers", v.rights().size() > 0);
    }

    static void votesWaitingUntilCast() {
        Fixture f = world(Preset.LORDSHIP, 4);
        Outcome opened = f.engine.propose(KRIS, new CreateOffice(org(f), "Reeve"));
        var id = ((Outcome.VoteOpened) opened).vote();
        eq("10: an open motion waits for Gil", List.of(id), view(f, GIL).votesWaiting());
        eq("10: not for the proposer who voted", 0, view(f, KRIS).votesWaiting().size());
        f.engine.cast(GIL, id, Vote.Ballot.YES);
        eq("10: after voting it does not", 0, view(f, GIL).votesWaiting().size());
    }

    static void everyPowerIsInOneGroup() {
        for (Power power : Power.values()) {
            int groups = 0;
            for (PowerGroup g : PowerGroup.values()) if (g.powers.contains(power)) groups++;
            eq("11: " + power + " is in exactly one group", 1, groups);
        }
    }

    static void aNewcomerSeesFreedomsOnly() {
        Fixture f = world(Preset.LORDSHIP, 4);
        Standing.View v = Standing.asNewcomer(f.state, f.engine, org(f), f.clock.now());
        check("12: no Rights", v.rights().isEmpty());
        check("12: nobody can vote yet", v.lines().stream().noneMatch(Standing.Line::youCanVote));
        check("12: Lord holds the powers", v.freedoms().stream().anyMatch(l -> l.name().equals("Lord")));
    }
}
