package charter.gov;

import charter.Fixture;
import charter.api.StateKingdomView;
import charter.engine.Outcome;
import charter.gov.actions.SetSeats;
import charter.gov.actions.Unseat;
import charter.grant.Power;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.id.VoteId;
import charter.org.actions.AppointCoLeader;
import charter.org.actions.AppointLeader;
import charter.org.actions.Expel;
import charter.org.actions.InviteMember;
import charter.org.actions.Leave;
import charter.org.actions.RemoveLord;
import charter.org.actions.Rename;

import java.util.List;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** A kingdom with two Lords, named by operators: equals who each act alone, and neither can undo the other. */
public final class TwoLordsTest {

    private TwoLordsTest() {
    }

    public static void run() {
        operatorsNameASecondLord();
        eitherLordActsAlone();
        neitherLordUndoesTheOther();
        oneLordGoingLeavesTheOtherRulingAlone();
        theSoleLordCommandLeavesOne();
        removingALord();
        membersCannotVoteALordOut();
        councilsStayCouncils();
        theApiNamesBoth();
    }

    /** A lordship ruled by Ann with members Bob and Cy; Bob is made the second Lord. */
    private record Pair(Fixture f, OrgId org, PlayerId ann, PlayerId bob, PlayerId cy, Office lord) {
    }

    private static Pair pair(String name) {
        Fixture f = new Fixture();
        PlayerId ann = player(name + "Ann");
        PlayerId bob = player(name + "Bob");
        PlayerId cy = player(name + "Cy");
        OrgId org = f.found(name, Preset.LORDSHIP, ann, bob, cy);
        Office lord = f.state.office(f.org(org).governance.deciders).orElseThrow();
        check(name + ": setup, a second Lord", f.admin(new AppointCoLeader(org, bob)).done());
        return new Pair(f, org, ann, bob, cy, lord);
    }

    static void operatorsNameASecondLord() {
        Pair p = pair("Azrak");
        check("1: both hold the Lord office", p.lord.holds(p.ann) && p.lord.holds(p.bob));
        check("1: two seats, either may act", p.lord.seats() == 2 && p.lord.deciding() == Office.Deciding.ALONE);
        eq("1: it reads as two Lords, not a council", Standing.Form.CO_LORDSHIP, Standing.form(p.f.state, p.org));
        check("1: the second Lord was recorded", p.f.state.history.entries().stream()
                .anyMatch(e -> e.event() instanceof GovEvents.OfficeFilled filled && filled.holder().equals(p.bob)));
        eq("1: a third Lord is refused", "two_lords_already", p.f.admin(new AppointCoLeader(p.org, p.cy)).reasonKey());
        eq("1: a Lord twice is refused", "already_lord", p.f.admin(new AppointCoLeader(p.org, p.bob)).reasonKey());
        eq("1: a player cannot name a Lord", "not_you", p.f.as(p.ann, new AppointCoLeader(p.org, p.cy)).reasonKey());

        PlayerId stranger = player("Stranger");
        PlayerId other = player("OtherLord");
        OrgId elsewhere = p.f.found("Aurelia", Preset.LORDSHIP, other, stranger);
        Office theirs = p.f.state.office(p.f.org(elsewhere).governance.deciders).orElseThrow();
        theirs.unseatAll();
        eq("1: a member of another kingdom is refused", "leader_elsewhere", p.f.admin(new AppointCoLeader(p.org, stranger)).reasonKey());
        PlayerId fresh = player("Fresh");
        p.f.seen(fresh);
        check("1: an empty seat simply takes the player", p.f.admin(new AppointCoLeader(elsewhere, fresh)).done());
        check("1: as its only Lord, on one seat", theirs.holds(fresh) && theirs.holders().size() == 1 && theirs.seats() == 1);
        check("1: a player in no kingdom joins it", p.f.state.isMember(fresh, elsewhere));

        PlayerId c1 = player("Commoner");
        OrgId commune = p.f.found("Freehold", Preset.COMMUNE, c1);
        eq("1: a commune cannot have two Lords", "not_a_lordship", p.f.admin(new AppointCoLeader(commune, c1)).reasonKey());
    }

    static void eitherLordActsAlone() {
        Pair p = pair("Valen");
        for (Power power : Preset.LORD_POWERS) {
            check("2: the second Lord uses " + power + " alone", p.f.as(p.bob, new Fixture.Use(p.org, power)).done());
            check("2: the first Lord uses " + power + " alone", p.f.as(p.ann, new Fixture.Use(p.org, power)).done());
        }
        PlayerId newcomer = player("ValenNew");
        p.f.seen(newcomer);
        check("2: the second Lord invites alone", p.f.as(p.bob, new InviteMember(p.org, newcomer)).done());
    }

    static void neitherLordUndoesTheOther() {
        Pair p = pair("Oskar");
        eq("3: one Lord cannot unseat the other", "needs_seats", p.f.as(p.ann, new Unseat(p.org, p.lord.id, p.bob)).reasonKey());
        eq("3: nor expel them", "needs_seats", p.f.as(p.ann, new Expel(p.org, p.bob)).reasonKey());
        eq("3: nor rename the kingdom alone", "needs_seats", p.f.as(p.bob, new Rename(p.org, "Oskarheim")).reasonKey());
        check("3: both still sit", p.lord.holds(p.ann) && p.lord.holds(p.bob));
        p.f.knobs.leadersChangeGovernance = true;
        eq("3: nor change the seats alone", "needs_seats",
                p.f.as(p.ann, new SetSeats(p.org, p.lord.id, 1, Office.Deciding.ALONE)).reasonKey());
        p.f.knobs.leadersChangeGovernance = false;

        // Put to the seats, it passes only when the other Lord agrees.
        Outcome put = p.f.engine.proposeToSeats(p.ann, p.lord.id, new Unseat(p.org, p.lord.id, p.bob));
        check("3: it can be put to the seats", put instanceof Outcome.VoteOpened);
        VoteId vote = ((Outcome.VoteOpened) put).vote();
        p.f.engine.cast(p.ann, vote, Vote.Ballot.YES);
        check("3: one Lord's yes is not enough", p.lord.holds(p.bob));
        p.f.engine.cast(p.bob, vote, Vote.Ballot.YES);
        p.f.closeVotes(p.ann, p.bob, p.cy);
        check("3: with both, it is done", !p.lord.holds(p.bob));
        check("3: and the other rules alone, on one seat", p.lord.holds(p.ann) && p.lord.seats() == 1);
    }

    static void oneLordGoingLeavesTheOtherRulingAlone() {
        Pair p = pair("Gallo");
        check("4: the second Lord leaves", p.f.as(p.bob, new Leave(p.bob)).done());
        check("4: the first still rules, alone", p.lord.holds(p.ann) && p.lord.holders().size() == 1 && p.lord.seats() == 1);
        eq("4: it reads as a lordship again", Standing.Form.LORDSHIP, Standing.form(p.f.state, p.org));

        Pair q = pair("Mire");
        q.f.passDays(q.f.knobs.absentLordDays + 1, q.ann, q.cy);
        check("4: the absent Lord loses their seat", !q.lord.holds(q.bob));
        check("4: the present Lord keeps theirs, on one seat", q.lord.holds(q.ann) && q.lord.seats() == 1);
        check("4: no election opens while a Lord still sits",
                q.f.state.votes().stream().noneMatch(v -> v.open() && v.kind == Vote.Kind.ELECTION && q.lord.id.equals(v.office)));
        check("4: the absence was recorded", q.f.state.history.entries().stream()
                .anyMatch(e -> e.event() instanceof GovEvents.OfficeVacated v && v.formerHolder().equals(q.bob) && v.why().equals("absent")));
    }

    static void theSoleLordCommandLeavesOne() {
        Pair p = pair("Tamsin");
        check("5: an operator names a sole Lord", p.f.admin(new AppointLeader(p.org, p.cy)).done());
        check("5: exactly one Lord", p.lord.holders().size() == 1 && p.lord.holds(p.cy) && p.lord.seats() == 1);
        check("5: the old Lords are members still", p.f.state.isMember(p.ann, p.org) && p.f.state.isMember(p.bob, p.org));
        Pair q = pair("Brann");
        check("5: naming a sitting Lord sole Lord", q.f.admin(new AppointLeader(q.org, q.bob)).done());
        check("5: keeps them and drops the other", q.lord.holders().size() == 1 && q.lord.holds(q.bob) && q.lord.seats() == 1);
    }

    static void removingALord() {
        Pair p = pair("Isen");
        eq("6: a member who is no Lord cannot be removed as one", "not_lord", p.f.admin(new RemoveLord(p.org, p.cy)).reasonKey());
        eq("6: a player cannot remove a Lord", "not_you", p.f.as(p.ann, new RemoveLord(p.org, p.bob)).reasonKey());
        check("6: an operator removes one", p.f.admin(new RemoveLord(p.org, p.bob)).done());
        check("6: the other rules alone", p.lord.holds(p.ann) && p.lord.holders().size() == 1 && p.lord.seats() == 1);
        check("6: the removed Lord stays a member", p.f.state.isMember(p.bob, p.org));
        check("6: the last Lord removed", p.f.admin(new RemoveLord(p.org, p.ann)).done());
        check("6: leaves the seat empty", p.lord.vacant() && p.lord.seats() == 1);
    }

    static void membersCannotVoteALordOut() {
        Pair p = pair("Dorn");
        p.f.passDays(p.f.knobs.voteEligibilityDays + 1, p.ann, p.bob, p.cy);
        check("7: a member cannot unseat a Lord", !p.f.as(p.cy, new Unseat(p.org, p.lord.id, p.ann)).done());
        Outcome proposed = p.f.engine.propose(p.cy, new Unseat(p.org, p.lord.id, p.ann));
        if (proposed instanceof Outcome.VoteOpened v) {
            p.f.engine.cast(p.cy, v.vote(), Vote.Ballot.YES);
            p.f.closeVotes(p.ann, p.bob, p.cy);
        }
        check("7: nor vote one out", p.lord.holds(p.ann) && p.lord.holds(p.bob));
    }

    static void councilsStayCouncils() {
        Fixture f = new Fixture();
        PlayerId a = player("CouncilA");
        OrgId commune = f.found("Moot", Preset.COMMUNE, a);
        Office office = f.state.office(f.org(commune).governance.deciders).orElseThrow();
        office.setSeats(2);
        eq("8: a commune's two seats are a council", Standing.Form.COUNCIL, Standing.form(f.state, commune));

        Pair p = pair("Keld");
        p.lord.setDeciding(Office.Deciding.TOGETHER);
        eq("8: two seats deciding together are a council", Standing.Form.COUNCIL, Standing.form(p.f.state, p.org));
        p.lord.setDeciding(Office.Deciding.ALONE);
        p.lord.setSeats(3);
        eq("8: three seats are a council", Standing.Form.COUNCIL, Standing.form(p.f.state, p.org));
    }

    static void theApiNamesBoth() {
        Pair p = pair("Ruin");
        var view = new StateKingdomView(p.f.state);
        eq("9: lordOf is the first Lord", java.util.Optional.of(p.ann.value()), view.lordOf(p.org.toString()));
        eq("9: lordsOf is both", List.of(p.ann.value(), p.bob.value()), view.lordsOf(p.org.toString()));

        Standing.View cy = Standing.of(p.f.state, p.f.engine, p.cy, p.f.clock.now()).orElseThrow();
        check("9: a member reads the Lord powers as held by the two Lords",
                cy.lines().stream().anyMatch(l -> l.kind() == Standing.HolderKind.LORDS && l.holders().size() == 2));
    }
}
