package charter.gov;

import charter.Fixture;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.actions.Appoint;
import charter.gov.actions.SetSeats;
import charter.gov.actions.Vacate;
import charter.id.OfficeId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import charter.treasury.actions.Withdraw;

import static charter.Check.check;
import static charter.Check.eq;
import static charter.Fixture.player;

/** S1: every faction has a decision makers office with seats (0 a Commune, 1 a Lordship, more a council). */
public final class SeatsTest {

    static final PlayerId KRIS = player("Kris");
    static final PlayerId GIL = player("Gil");
    static final PlayerId CY = player("Cy");

    private SeatsTest() {
    }

    public static void run() {
        aLordshipFoundsWithOneSeat();
        aCommuneFoundsWithNoSeats();
        onlyTheMembersCanChangeTheSeats();
        aCommuneBecomesACouncilThatActsAlone();
        aCouncilDecidingTogetherVotesAmongItsSeats();
        aCouncilVoteCannotDoWhatOnlyTheMembersMay();
        shrinkingACouncilLetsItsHoldersChoose();
        shrinkingToTwoSeatsHoldsTwoElections();
        aLoneHolderShrinkingChoosesNothing();
        anAbsentHolderLosesOnlyTheirSeat();
        appointRespectsTheSeats();
        vacateEmptiesEverySeat();
    }

    private static Fixture world(Preset preset) {
        Fixture f = new Fixture();
        f.found("Ashfall", preset, KRIS, GIL, CY);
        f.passDays(4, KRIS, GIL, CY);
        return f;
    }

    private static OrgId org(Fixture f) {
        return f.state.orgNamed("Ashfall").orElseThrow().id;
    }

    private static OfficeId lord(Fixture f) {
        return f.office(org(f), Preset.LORD).id;
    }

    private static void fund(Fixture f) {
        check("setup: funded", f.as(KRIS, new Deposit(org(f), Currency.DIAMOND, 100)).done());
    }

    private static boolean spends(Fixture f, PlayerId who) {
        return f.as(who, new Withdraw(org(f), Currency.DIAMOND, 10, who)).done();
    }

    private static void setSeats(Fixture f, int seats, Office.Deciding deciding) {
        f.motion(KRIS, new SetSeats(org(f), lord(f), seats, deciding), GIL, CY);
        eq("setup: seats " + seats, seats, f.state.office(lord(f)).orElseThrow().seats());
    }

    private static void elect(Fixture f, PlayerId winner) {
        Outcome opened = f.engine.openElection(lord(f), KRIS);
        check("setup: an election opened for " + winner, opened instanceof Outcome.VoteOpened);
        var id = ((Outcome.VoteOpened) opened).vote();
        for (PlayerId voter : new PlayerId[]{KRIS, GIL, CY}) f.engine.cast(voter, id, Vote.Ballot.candidate(winner));
    }

    static void aLordshipFoundsWithOneSeat() {
        Fixture f = world(Preset.LORDSHIP);
        Office lord = f.office(org(f), Preset.LORD);
        eq("1: one seat", 1, lord.seats());
        eq("1: deciding alone", Office.Deciding.ALONE, lord.deciding());
        eq("1: the founder is seated", KRIS, f.holder(org(f), Preset.LORD));
        eq("1: the organization names its decision makers", lord.id, f.org(org(f)).governance.deciders);
        fund(f);
        check("1: the Lord spends as before", spends(f, KRIS));
        eq("1: a member does not", Reason.NEEDS_VOTE.key(), f.as(GIL, new Withdraw(org(f), Currency.DIAMOND, 10, GIL)).reasonKey());
    }

    static void aCommuneFoundsWithNoSeats() {
        Fixture f = world(Preset.COMMUNE);
        Office lord = f.office(org(f), Preset.LORD);
        eq("2: no seats", 0, lord.seats());
        check("2: nobody holds it", lord.vacant());
        fund(f);
        eq("2: a member's spend needs a vote", Reason.NEEDS_VOTE.key(), f.as(KRIS, new Withdraw(org(f), Currency.DIAMOND, 10, KRIS)).reasonKey());
        f.motion(KRIS, new Withdraw(org(f), Currency.DIAMOND, 10, KRIS), GIL, CY);
        eq("2: a vote of the members spends", 90L, f.org(org(f)).ledger.balance(Currency.DIAMOND));
    }

    static void onlyTheMembersCanChangeTheSeats() {
        Fixture f = world(Preset.LORDSHIP);
        fund(f);
        Outcome direct = f.as(KRIS, new SetSeats(org(f), lord(f), 0, Office.Deciding.ALONE));
        eq("3: the Lord cannot set seats directly", Reason.RESERVED.key(), direct.reasonKey());
        setSeats(f, 0, Office.Deciding.ALONE);
        check("3: the former Lord is unseated", f.office(org(f), Preset.LORD).vacant());
        eq("3: the former Lord's spend now needs a vote", Reason.NEEDS_VOTE.key(), f.as(KRIS, new Withdraw(org(f), Currency.DIAMOND, 10, KRIS)).reasonKey());
    }

    static void aCommuneBecomesACouncilThatActsAlone() {
        Fixture f = world(Preset.COMMUNE);
        fund(f);
        setSeats(f, 3, Office.Deciding.ALONE);
        elect(f, KRIS);
        elect(f, GIL);
        elect(f, CY);
        eq("4: three seated", 3, f.office(org(f), Preset.LORD).holders().size());
        check("4: Kris spends alone", spends(f, KRIS));
        check("4: Gil spends alone", spends(f, GIL));
        check("4: Cy spends alone", spends(f, CY));
        eq("4: a full council holds no more elections", Appoint.OFFICE_FULL.key(), f.engine.openElection(lord(f), KRIS).reasonKey());
    }

    static void aCouncilDecidingTogetherVotesAmongItsSeats() {
        Fixture f = world(Preset.COMMUNE);
        fund(f);
        setSeats(f, 3, Office.Deciding.ALONE);
        elect(f, KRIS);
        elect(f, GIL);
        elect(f, CY);
        setSeats(f, 3, Office.Deciding.TOGETHER);
        eq("5: one holder's spend is refused", Reason.NEEDS_SEATS.key(), f.as(KRIS, new Withdraw(org(f), Currency.DIAMOND, 10, KRIS)).reasonKey());

        Outcome opened = f.engine.proposeToSeats(KRIS, lord(f), new Withdraw(org(f), Currency.DIAMOND, 10, KRIS));
        check("5: proposing to the seats opens a vote", opened instanceof Outcome.VoteOpened);
        Vote vote = f.vote(((Outcome.VoteOpened) opened).vote());
        eq("5: the electorate is the three seats", 3, vote.electorate.size());
        eq("5: the vote belongs to the seats", lord(f), vote.seatsOf);
        check("5: one yes of three does not pass", vote.open());
        f.engine.cast(GIL, vote.id, Vote.Ballot.YES);
        eq("5: the second yes passes at once", Vote.State.EXECUTED, vote.state());
        eq("5: and the spend happened", 90L, f.org(org(f)).ledger.balance(Currency.DIAMOND));

        Outcome second = f.engine.proposeToSeats(KRIS, lord(f), new Withdraw(org(f), Currency.DIAMOND, 10, KRIS));
        Vote no = f.vote(((Outcome.VoteOpened) second).vote());
        f.engine.cast(GIL, no.id, Vote.Ballot.NO);
        check("5: one no leaves it open", no.open());
        f.engine.cast(CY, no.id, Vote.Ballot.NO);
        eq("5: two no fail it at once", Vote.State.FAILED, no.state());
        eq("5: and nothing was spent", 90L, f.org(org(f)).ledger.balance(Currency.DIAMOND));

        Outcome outsider = f.engine.proposeToSeats(player("Dee"), lord(f), new Withdraw(org(f), Currency.DIAMOND, 10, KRIS));
        check("5: only a holder may propose", outsider.refused());
    }

    static void aCouncilVoteCannotDoWhatOnlyTheMembersMay() {
        Fixture f = world(Preset.COMMUNE);
        setSeats(f, 3, Office.Deciding.TOGETHER);
        elect(f, KRIS);
        elect(f, GIL);
        elect(f, CY);
        Outcome seats = f.engine.proposeToSeats(KRIS, lord(f), new SetSeats(org(f), lord(f), 1, Office.Deciding.ALONE));
        eq("6: the seats cannot set the seats", Reason.RESERVED.key(), seats.reasonKey());
        eq("6: nothing changed", 3, f.office(org(f), Preset.LORD).seats());
    }

    private static Vote openElection(Fixture f) {
        return f.state.votes().stream().filter(v -> v.open() && v.kind == Vote.Kind.ELECTION && lord(f).equals(v.office))
                .findFirst().orElse(null);
    }

    static void shrinkingACouncilLetsItsHoldersChoose() {
        Fixture f = world(Preset.COMMUNE);
        setSeats(f, 3, Office.Deciding.ALONE);
        elect(f, KRIS);
        elect(f, GIL);
        elect(f, CY);
        PlayerId dee = player("Dee");
        f.seen(dee);
        f.admin(new charter.org.actions.InviteMember(org(f), dee));
        f.as(dee, new charter.org.actions.AcceptInvite(dee, org(f)));
        setSeats(f, 1, Office.Deciding.ALONE);
        Office lord = f.office(org(f), Preset.LORD);
        check("7: every holder stepped down", lord.vacant());
        Vote vote = openElection(f);
        check("7: an election opened at once", vote != null);
        check("7: it is among the former council", vote.restricted() && vote.electorate.equals(java.util.Set.of(KRIS, GIL, CY)));
        eq("7: a member who was not on the council cannot vote", charter.engine.Reason.NOT_ELIGIBLE.key(),
                f.engine.cast(dee, vote.id, Vote.Ballot.candidate(dee)).reasonKey());
        for (PlayerId voter : new PlayerId[]{KRIS, GIL, CY}) f.engine.cast(voter, vote.id, Vote.Ballot.candidate(GIL));
        eq("7: the winner takes the seat", GIL, f.holder(org(f), Preset.LORD));
        eq("7: and only they", 1, lord.holders().size());
        check("7: no further election", openElection(f) == null);
    }

    static void shrinkingToTwoSeatsHoldsTwoElections() {
        Fixture f = world(Preset.COMMUNE);
        setSeats(f, 3, Office.Deciding.ALONE);
        elect(f, KRIS);
        elect(f, GIL);
        elect(f, CY);
        setSeats(f, 2, Office.Deciding.ALONE);
        Vote first = openElection(f);
        check("7b: the first election opened", first != null);
        for (PlayerId voter : new PlayerId[]{KRIS, GIL, CY}) f.engine.cast(voter, first.id, Vote.Ballot.candidate(CY));
        Vote second = openElection(f);
        check("7b: a second election follows among the same holders", second != null && second.restricted()
                && second.electorate.equals(first.electorate));
        eq("7b: the seated winner cannot be named again", Appoint.ALREADY_SEATED.key(),
                f.engine.cast(KRIS, second.id, Vote.Ballot.candidate(CY)).reasonKey());
        for (PlayerId voter : new PlayerId[]{KRIS, GIL, CY}) f.engine.cast(voter, second.id, Vote.Ballot.candidate(KRIS));
        Office lord = f.office(org(f), Preset.LORD);
        check("7b: both seats are filled", lord.holds(CY) && lord.holds(KRIS) && lord.holders().size() == 2);
    }

    static void aLoneHolderShrinkingChoosesNothing() {
        Fixture lordship = world(Preset.LORDSHIP);
        f(lordship, 0, Office.Deciding.ALONE);
        check("7c: a lone Lord shrinking to 0 just steps down, no election",
                lordship.office(org(lordship), Preset.LORD).vacant() && openElection(lordship) == null);
        Fixture same = world(Preset.LORDSHIP);
        f(same, 1, Office.Deciding.TOGETHER);
        check("7c: a lone Lord keeps the seat when the seats do not drop", same.holder(org(same), Preset.LORD).equals(KRIS)
                && openElection(same) == null);
        Fixture council = world(Preset.COMMUNE);
        setSeats(council, 3, Office.Deciding.ALONE);
        elect(council, KRIS);
        elect(council, GIL);
        elect(council, CY);
        f(council, 0, Office.Deciding.ALONE);
        check("7c: a council shrinking to 0 hands the decision to the members, no election",
                council.office(org(council), Preset.LORD).vacant() && openElection(council) == null);
    }

    private static void f(Fixture f, int seats, Office.Deciding deciding) {
        f.motion(KRIS, new SetSeats(org(f), lord(f), seats, deciding), GIL, CY);
    }

    static void anAbsentHolderLosesOnlyTheirSeat() {
        Fixture f = world(Preset.COMMUNE);
        setSeats(f, 3, Office.Deciding.ALONE);
        elect(f, KRIS);
        elect(f, GIL);
        elect(f, CY);
        f.passDays(f.knobs.absentLordDays + 1, KRIS, GIL);
        Office lord = f.office(org(f), Preset.LORD);
        check("8: the absent holder is unseated", !lord.holds(CY));
        check("8: the others keep their seats", lord.holds(KRIS) && lord.holds(GIL));
        check("8: one election opened for the empty seat",
                f.state.votes().stream().anyMatch(v -> v.open() && v.kind == Vote.Kind.ELECTION && lord.id.equals(v.office)));
    }

    static void appointRespectsTheSeats() {
        Fixture council = world(Preset.COMMUNE);
        setSeats(council, 3, Office.Deciding.ALONE);
        elect(council, KRIS);
        elect(council, GIL);
        elect(council, CY);
        PlayerId dee = player("Dee");
        council.seen(dee);
        council.admin(new charter.org.actions.InviteMember(org(council), dee));
        council.as(dee, new charter.org.actions.AcceptInvite(dee, org(council)));
        eq("9: a full council refuses another appointment", Appoint.OFFICE_FULL.key(),
                council.admin(new Appoint(org(council), lord(council), dee)).reasonKey());
        eq("9: a holder cannot be seated twice", Appoint.ALREADY_SEATED.key(),
                council.admin(new Appoint(org(council), lord(council), KRIS)).reasonKey());

        Fixture lordship = world(Preset.LORDSHIP);
        check("9: a full one seat office is replaced", lordship.admin(new Appoint(org(lordship), lord(lordship), GIL)).done());
        eq("9: the new holder", GIL, lordship.holder(org(lordship), Preset.LORD));

        Fixture commune = world(Preset.COMMUNE);
        eq("9: no seats, no appointments", Appoint.NO_SEATS.key(),
                commune.admin(new Appoint(org(commune), lord(commune), GIL)).reasonKey());
    }

    static void vacateEmptiesEverySeat() {
        Fixture f = world(Preset.COMMUNE);
        setSeats(f, 3, Office.Deciding.ALONE);
        elect(f, KRIS);
        elect(f, GIL);
        elect(f, CY);
        check("10: vacate runs", f.admin(new Vacate(org(f), lord(f))).done());
        check("10: every seat is empty", f.office(org(f), Preset.LORD).vacant());
        eq("10: the seats remain", 3, f.office(org(f), Preset.LORD).seats());
    }
}
