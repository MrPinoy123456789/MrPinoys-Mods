package ballot;

import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free test runner, same pattern as the quiz engine: no JUnit on the
 * classpath, no server, no clock. Every case is a sequence of calls and an assertion
 * on what came back.
 */
public final class PollTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    static final String ALICE = "11111111-1111-1111-1111-111111111111";
    static final String BOB = "22222222-2222-2222-2222-222222222222";
    static final String CARL = "33333333-3333-3333-3333-333333333333";

    static long clock = 1_754_400_000L;

    public static void main(String[] args) {
        namesAreSeparateFromIdentity();
        entriesAppearWhenBoxesArePlaced();
        organisersCanLabelUnclaimedEntries();
        newPollsRefuseToOpenUntilNamed();
        readinessDependsOnKind();
        stateTransitionsAreOrdered();
        votingNeedsTwoEntries();

        claimingIsFirstComeOneEach();
        releaseFreesThePlot();
        releaseBlockedOnceVotingStarts();
        namingIsOwnerOnlyAndBounded();
        claimingRejectedOnOwnerlessPolls();

        votesAreRecordedAndChangeable();
        selfVoteBlocked();
        voteRejectedOutsideVotingPhase();
        voteChangeCanBeForbidden();
        withdrawRemovesTheVote();

        entriesLockOnceVotingStarts();
        removingAnEntryRemovesItsVotesAndStations();

        stationsBindAndRebind();
        checklistFindsEntriesWithoutStations();

        tallyAndWinners();
        tiesAreDeclaredNotBroken();
        closingWithNoVotesIsFine();

        historyRecordsEverything();

        lecternsBindToThePollNotAnEntry();
        signsBindSeparatelyFromBoxes();
        signsFindTheirEntryByProximity();

        deadlinesAreWallClock();
        warningsFireOnlyOnce();

        report();
    }

    // ---- identity and lifecycle -------------------------------------------

    static void namesAreSeparateFromIdentity() {
        Poll p = Poll.create("p_abc123", "", Rules.forClaimable(), now());

        check("key is the identity", p.key().equals("p_abc123"));
        check("starts unnamed", p.isUnnamed());
        check("shows a placeholder rather than a blank", p.title().equals("Unnamed vote"));

        check("naming works", p.setName("Art show", now()).ok());
        check("free text, spaces and all", p.name().equals("Art show"));
        check("identity did not move", p.key().equals("p_abc123"));
        check("renaming again is fine", p.setName("Town monument", now()).ok());
        check("blank refused", !p.setName("  ", now()).ok());
        check("overlong refused", !p.setName("x".repeat(Poll.MAX_NAME + 1), now()).ok());
        check("logged", hasEvent(p, "poll_renamed"));
    }

    /**
     * v2 creates entries by placing a ballot box, so the model must accept an entry
     * with no label at all and let it be numbered automatically. This is the shape the
     * placement path relies on.
     */
    static void entriesAppearWhenBoxesArePlaced() {
        Poll p = claimable("p_place1");
        p.moveTo(PollState.OPEN, now());

        Outcome first = p.addEntry(null, now());
        check("unlabelled entry accepted", first.ok());
        check("its id comes back for binding", first.value() == 1);

        Outcome second = p.addEntry(null, now());
        check("ids increment", second.value() == 2);
        check("both exist", p.entries().size() == 2);
        check("they read as Untitled until named",
                p.entry(1).orElseThrow().label().equals("Untitled"));

        p.bindStation(first.value(), Station.BOX, "overworld", 1, 64, 1, now());
        check("box binds to the new entry", p.boxFor(1).isPresent());
        check("the other still needs one", p.entriesWithoutStations().size() == 1);
    }

    static void organisersCanLabelUnclaimedEntries() {
        Poll p = Poll.create("p_opts01", "Roof colour", Rules.forOwnerless(), now());
        p.moveTo(PollState.OPEN, now());
        p.addEntry(null, now());

        check("organiser names an ownerless entry", p.labelEntry(1, "Red", now()).ok());
        check("it took", p.entry(1).orElseThrow().label().equals("Red"));
        check("blank refused", !p.labelEntry(1, " ", now()).ok());
        check("unknown entry refused", !p.labelEntry(9, "Blue", now()).ok());

        p.addEntry("Green", now());
        p.moveTo(PollState.VOTING, now());
        check("locked once voting starts", !p.labelEntry(1, "Crimson", now()).ok());
    }

    static void newPollsRefuseToOpenUntilNamed() {
        Poll p = Poll.create("p_new001", "", Rules.forClaimable(), now());

        check("a fresh vote is unnamed", p.isUnnamed());
        check("and will not open", !p.moveTo(PollState.OPEN, now()).ok());

        p.setName("Art show", now());
        check("named now", !p.isUnnamed());
        check("and opens", p.moveTo(PollState.OPEN, now()).ok());
    }

    /**
     * The two kinds are ready under different conditions, and the readiness check is
     * what the settings screen turns into a list of what is left to do.
     */
    static void readinessDependsOnKind() {
        Poll poll = Poll.create("p_ready1", "", Rules.forOwnerless(), now());
        check("a new vote needs a name", poll.whatsMissing().contains("a name"));
        poll.setName("Roof colour", now());
        check("then options", poll.whatsMissing().contains("at least two options"));

        poll.addEntry("Red", now());
        poll.addEntry("Green", now());
        check("then somewhere to vote",
                poll.whatsMissing().contains("a ballot box to vote at"));
        poll.bindBallotBox("overworld", 1, 64, 1, now());
        check("poll ready", poll.whatsMissing().isEmpty());

        // Rebinding moves the single box rather than adding another.
        poll.bindBallotBox("overworld", 2, 64, 2, now());
        check("still one ballot box",
                poll.stationsOfKind(Station.BALLOT).size() == 1);
        check("it moved", poll.hasBallotBoxAt("overworld", 2, 64, 2));

        Poll comp = Poll.create("p_ready2", "Art show", Rules.forClaimable(), now());
        comp.addEntry(null, now());
        comp.addEntry(null, now());
        check("a competition wants boxes per plot",
                comp.whatsMissing().stream().anyMatch(m -> m.contains("ballot box")));
        comp.bindStation(1, Station.BOX, "overworld", 1, 64, 1, now());
        comp.bindStation(2, Station.BOX, "overworld", 5, 64, 1, now());
        check("and signs per plot",
                comp.whatsMissing().stream().anyMatch(m -> m.contains("sign")));
        comp.bindStation(1, Station.SIGN, "overworld", 1, 65, 2, now());
        comp.bindStation(2, Station.SIGN, "overworld", 5, 65, 2, now());
        check("competition ready", comp.whatsMissing().isEmpty());
        check("a competition never wants a poll ballot box", !comp.hasBallotBox());
    }

    static void stateTransitionsAreOrdered() {
        Poll p = claimable("p_art001");
        check("starts in draft", p.state() == PollState.DRAFT);
        check("cannot jump straight to voting", !p.moveTo(PollState.VOTING, now()).ok());
        check("draft to open", p.moveTo(PollState.OPEN, now()).ok());
        check("cannot go back to draft", !p.moveTo(PollState.DRAFT, now()).ok());
        check("cannot re-open", !p.moveTo(PollState.OPEN, now()).ok());
    }

    static void votingNeedsTwoEntries() {
        Poll p = claimable("p_art001");
        p.moveTo(PollState.OPEN, now());
        p.addEntry("Plot 1", now());
        check("one entry is not a vote", !p.moveTo(PollState.VOTING, now()).ok());
        p.addEntry("Plot 2", now());
        check("two entries is", p.moveTo(PollState.VOTING, now()).ok());
    }

    // ---- claiming ---------------------------------------------------------

    static void claimingIsFirstComeOneEach() {
        Poll p = openWithPlots(3);

        check("first claim succeeds", p.claim(1, ALICE, "Alice", now()).ok());
        check("second player cannot take it", !p.claim(1, BOB, "Bob", now()).ok());
        check("owner cannot re-claim their own", !p.claim(1, ALICE, "Alice", now()).ok());
        check("one plot per player", !p.claim(2, ALICE, "Alice", now()).ok());
        check("a different player can take a different plot",
                p.claim(2, BOB, "Bob", now()).ok());
        check("ownership is recorded", p.entry(1).orElseThrow().isOwnedBy(ALICE));
    }

    static void releaseFreesThePlot() {
        Poll p = openWithPlots(2);
        p.claim(1, ALICE, "Alice", now());
        p.rename(ALICE, "The Drowning Man", now());

        check("release works", p.release(ALICE, now()).ok());
        check("plot is free again", !p.entry(1).orElseThrow().isClaimed());
        check("the title goes with it", !p.entry(1).orElseThrow().hasLabel());
        check("releasing twice does nothing", !p.release(ALICE, now()).ok());
        check("someone else can now claim it", p.claim(1, BOB, "Bob", now()).ok());
    }

    static void releaseBlockedOnceVotingStarts() {
        Poll p = openWithPlots(2);
        p.claim(1, ALICE, "Alice", now());
        p.claim(2, BOB, "Bob", now());
        p.moveTo(PollState.VOTING, now());

        // Otherwise a losing entrant withdraws rather than lose in public.
        check("cannot release during voting", !p.release(ALICE, now()).ok());
        check("still owned", p.entry(1).orElseThrow().isOwnedBy(ALICE));
    }

    static void namingIsOwnerOnlyAndBounded() {
        Poll p = openWithPlots(2);
        p.claim(1, ALICE, "Alice", now());

        check("owner can name", p.rename(ALICE, "The Drowning Man", now()).ok());
        check("name is stored", p.entry(1).orElseThrow().label().equals("The Drowning Man"));
        check("non-owner cannot name", !p.rename(BOB, "Mine now", now()).ok());
        check("blank rejected", !p.rename(ALICE, "   ", now()).ok());
        check("overlong rejected", !p.rename(ALICE, "x".repeat(Poll.MAX_LABEL + 1), now()).ok());
        check("at the limit is fine", p.rename(ALICE, "x".repeat(Poll.MAX_LABEL), now()).ok());
        check("unnamed entries read as Untitled",
                p.entry(2).orElseThrow().label().equals("Untitled"));
    }

    static void claimingRejectedOnOwnerlessPolls() {
        Poll p = Poll.create("p_roof01", "Roof colour", Rules.forOwnerless(), now());
        p.moveTo(PollState.OPEN, now());
        p.addEntry("Red", now());
        p.addEntry("Green", now());

        check("no claiming without plots", !p.claim(1, ALICE, "Alice", now()).ok());
        check("but voting still works normally", p.moveTo(PollState.VOTING, now()).ok());
        check("and anyone may vote", p.vote(ALICE, 1, now()).ok());
    }

    // ---- voting -----------------------------------------------------------

    static void votesAreRecordedAndChangeable() {
        Poll p = votingWithPlots(3);

        check("vote accepted", p.vote(CARL, 1, now()).ok());
        check("vote is stored", p.voteOf(CARL).orElseThrow() == 1);
        check("voting for the same one again is refused", !p.vote(CARL, 1, now()).ok());
        check("changing is allowed", p.vote(CARL, 2, now()).ok());
        check("the change stuck", p.voteOf(CARL).orElseThrow() == 2);
        check("still only one vote held", p.votes().size() == 1);
    }

    static void selfVoteBlocked() {
        Poll p = votingWithPlots(2);   // plot 1 is Alice's, plot 2 is Bob's
        check("cannot vote for your own", !p.vote(ALICE, 1, now()).ok());
        check("can vote for someone else's", p.vote(ALICE, 2, now()).ok());
    }

    static void voteRejectedOutsideVotingPhase() {
        Poll p = openWithPlots(2);
        check("no voting while open", !p.vote(CARL, 1, now()).ok());

        p.claim(1, ALICE, "Alice", now());
        p.claim(2, BOB, "Bob", now());
        p.moveTo(PollState.VOTING, now());
        p.vote(CARL, 1, now());
        p.close(now());

        check("no voting once closed", !p.vote(BOB, 2, now()).ok());
    }

    static void voteChangeCanBeForbidden() {
        Poll p = Poll.create("p_final1", "Final say",
                new Rules(true, false, false, true, true, 1), now());
        p.moveTo(PollState.OPEN, now());
        p.addEntry("Red", now());
        p.addEntry("Green", now());
        p.moveTo(PollState.VOTING, now());

        check("first vote fine", p.vote(ALICE, 1, now()).ok());
        check("second refused when changes are off", !p.vote(ALICE, 2, now()).ok());
        check("original stands", p.voteOf(ALICE).orElseThrow() == 1);
    }

    static void withdrawRemovesTheVote() {
        Poll p = votingWithPlots(2);
        p.vote(CARL, 1, now());
        check("withdraw works", p.withdraw(CARL, now()).ok());
        check("vote is gone", p.voteOf(CARL).isEmpty());
        check("withdrawing twice does nothing", !p.withdraw(CARL, now()).ok());
    }

    // ---- entries ----------------------------------------------------------

    static void entriesLockOnceVotingStarts() {
        Poll p = votingWithPlots(2);
        check("cannot add during voting", !p.addEntry("Latecomer", now()).ok());
        check("cannot remove during voting", !p.removeEntry(1, now()).ok());
    }

    static void removingAnEntryRemovesItsVotesAndStations() {
        Poll p = openWithPlots(3);
        p.bindStation(2, Station.BOX, "overworld", 10, 64, 10, now());

        check("station bound", p.stations().size() == 1);
        check("removal works", p.removeEntry(2, now()).ok());
        check("its station went with it", p.stations().isEmpty());
        check("the other entries are untouched", p.entries().size() == 2);
        check("removing a ghost entry is refused", !p.removeEntry(99, now()).ok());
    }

    // ---- stations ---------------------------------------------------------

    static void stationsBindAndRebind() {
        Poll p = openWithPlots(2);
        check("bind", p.bindStation(1, Station.BOX, "overworld", 5, 64, 5, now()).ok());
        check("bind to unknown entry refused",
                !p.bindStation(99, Station.BOX, "overworld", 6, 64, 6, now()).ok());

        // Re-binding the same block replaces rather than duplicating.
        p.bindStation(2, Station.BOX, "overworld", 5, 64, 5, now());
        check("one station per block", p.stations().size() == 1);
        check("it now points at the new entry",
                p.stationAt("overworld", 5, 64, 5).orElseThrow().entryId() == 2);

        check("unbind", p.unbindStation("overworld", 5, 64, 5, now()).ok());
        check("unbinding nothing is refused", !p.unbindStation("overworld", 9, 9, 9, now()).ok());
    }

    static void checklistFindsEntriesWithoutStations() {
        Poll p = openWithPlots(3);
        p.bindStation(1, Station.BOX, "overworld", 1, 64, 1, now());
        check("two plots still need boxes", p.entriesWithoutStations().size() == 2);
        p.bindStation(2, Station.BOX, "overworld", 2, 64, 2, now());
        p.bindStation(3, Station.BOX, "overworld", 3, 64, 3, now());
        check("checklist clean", p.entriesWithoutStations().isEmpty());
    }

    // ---- results ----------------------------------------------------------

    static void tallyAndWinners() {
        Poll p = votingWithPlots(3);
        p.vote(CARL, 2, now());
        p.vote(ALICE, 2, now());
        p.vote(BOB, 3, now());

        check("tally counts", p.tally().get(2) == 2);
        check("unvoted entries appear as zero", p.tally().get(1) == 0);
        check("single winner", p.winners().equals(List.of(2)));
        check("ranked puts the winner first", p.ranked().get(0).getKey() == 2);
        check("close succeeds", p.close(now()).ok());
        check("state is closed", p.state() == PollState.CLOSED);
    }

    static void tiesAreDeclaredNotBroken() {
        Poll p = votingWithPlots(3);
        p.vote(CARL, 1, now());
        p.vote(ALICE, 2, now());

        check("both are winners", p.winners().equals(List.of(1, 2)));
    }

    static void closingWithNoVotesIsFine() {
        Poll p = votingWithPlots(2);
        check("closes cleanly", p.close(now()).ok());
        check("nobody won", p.winners().isEmpty());
    }

    // ---- history ----------------------------------------------------------

    static void historyRecordsEverything() {
        Poll p = openWithPlots(2);
        p.claim(1, ALICE, "Alice", now());
        p.rename(ALICE, "The Drowning Man", now());
        p.claim(2, BOB, "Bob", now());
        p.moveTo(PollState.VOTING, now());
        p.vote(CARL, 1, now());
        p.vote(CARL, 2, now());
        p.close(now());

        check("creation logged", hasEvent(p, "poll_created"));
        check("claims logged", hasEvent(p, "entry_claimed"));
        check("names logged", hasEvent(p, "entry_named"));
        check("votes logged", hasEvent(p, "vote_cast"));
        check("changes logged separately from casts", hasEvent(p, "vote_changed"));
        check("settlement logged", hasEvent(p, "poll_settled"));

        // The whole reason for keeping history: who flipped, and to what.
        PollEvent change = p.events().stream()
                .filter(e -> e.type().equals("vote_changed"))
                .findFirst().orElseThrow();
        check("the change records where the vote came from", change.get("from").equals("1"));
        check("and where it went", change.get("to").equals("2"));

        check("rejected actions leave no trace",
                p.events().stream().noneMatch(e -> e.type().equals("entry_released")));
    }

    // ---- lecterns ---------------------------------------------------------

    static void lecternsBindToThePollNotAnEntry() {
        Poll p = openWithPlots(2);

        // No entry needs to exist for a lectern, unlike a box or a sign.
        check("binds", p.bindLectern("overworld", 7, 64, 7, now()).ok());
        check("found", p.hasLecternAt("overworld", 7, 64, 7));
        check("not counted as a box", p.entriesWithoutStations().size() == 2);

        // Rebinding the same block replaces rather than stacking.
        p.bindLectern("overworld", 7, 64, 7, now());
        check("one lectern", p.stationsOfKind(Station.LECTERN).size() == 1);

        p.bindLectern("overworld", 8, 64, 8, now());
        check("a poll may have several", p.stationsOfKind(Station.LECTERN).size() == 2);

        check("unbinds", p.unbindStation("overworld", 7, 64, 7, now()).ok());
        check("gone", !p.hasLecternAt("overworld", 7, 64, 7));
        check("the other survives", p.hasLecternAt("overworld", 8, 64, 8));
    }

    // ---- signs ------------------------------------------------------------

    static void signsBindSeparatelyFromBoxes() {
        Poll p = openWithPlots(2);
        p.bindStation(1, Station.BOX, "overworld", 10, 64, 10, now());
        p.bindStation(1, Station.SIGN, "overworld", 10, 65, 11, now());

        check("both stations exist", p.stations().size() == 2);
        check("box found", p.boxFor(1).isPresent());
        check("sign found", p.signFor(1).isPresent());
        check("a sign does not satisfy the box checklist",
                p.entriesWithoutStations().size() == 1);
        check("and the sign checklist tracks separately",
                p.entriesWithoutSigns().size() == 1);

        // One sign per entry: binding a second replaces the first.
        p.bindStation(1, Station.SIGN, "overworld", 20, 65, 20, now());
        check("still one sign", p.stationsOfKind(Station.SIGN).size() == 1);
        check("it moved", p.signFor(1).orElseThrow().x() == 20);

        check("removing the entry clears both", p.removeEntry(1, now()).ok());
        check("nothing left bound", p.stations().isEmpty());
    }

    static void signsFindTheirEntryByProximity() {
        Poll p = openWithPlots(3);
        p.bindStation(1, Station.BOX, "overworld", 0, 64, 0, now());
        p.bindStation(2, Station.BOX, "overworld", 40, 64, 0, now());

        check("nearest box wins",
                p.nearestBoxEntry("overworld", 3, 64, 0, 16).orElseThrow() == 1);
        check("and from the other end",
                p.nearestBoxEntry("overworld", 38, 64, 2, 16).orElseThrow() == 2);
        check("out of range finds nothing",
                p.nearestBoxEntry("overworld", 500, 64, 500, 16).isEmpty());
        check("wrong dimension finds nothing",
                p.nearestBoxEntry("the_nether", 0, 64, 0, 16).isEmpty());
    }

    // ---- deadlines --------------------------------------------------------

    static void deadlinesAreWallClock() {
        Poll p = claimable("p_art001");
        long base = 2_000_000_000L;

        check("a deadline in the past is refused", !p.setDeadline(base - 10, base).ok());
        check("a deadline in the future is fine", p.setDeadline(base + 100, base).ok());

        p.moveTo(PollState.OPEN, base);
        p.addEntry("Plot 1", base);
        p.addEntry("Plot 2", base);
        check("not overdue while open", !p.isOverdue(base + 200));

        p.moveTo(PollState.VOTING, base);
        check("not overdue before the time", !p.isOverdue(base + 50));
        check("overdue after it", p.isOverdue(base + 101));

        // The whole point: a deadline missed while the server was down still fires.
        check("overdue long after it", p.isOverdue(base + 999_999));

        p.close(base + 200);
        check("a closed poll is never overdue", !p.isOverdue(base + 999_999));
        check("remaining is reported", p.secondsRemaining(base) == 100);
    }

    static void warningsFireOnlyOnce() {
        Poll p = claimable("p_art001");
        check("nothing warned yet", !p.hasWarned("1d"));
        p.markWarned("1d", now());
        check("marked", p.hasWarned("1d"));
        check("other marks unaffected", !p.hasWarned("1h"));

        // Recorded in history, so a restart cannot cause a second announcement.
        check("the mark is in the history", hasEvent(p, "poll_warning"));
    }

    // ---- harness ----------------------------------------------------------

    static long now() {
        return clock += 60;
    }

    static Poll claimable(String key) {
        return Poll.create(key, "Art show", Rules.forClaimable(), now());
    }

    static Poll openWithPlots(int count) {
        Poll p = claimable("p_art001");
        p.moveTo(PollState.OPEN, now());
        for (int i = 1; i <= count; i++) {
            p.addEntry(null, now());
        }
        return p;
    }

    /** Plots 1 and 2 belong to Alice and Bob; voting is open. */
    static Poll votingWithPlots(int count) {
        Poll p = openWithPlots(count);
        p.claim(1, ALICE, "Alice", now());
        p.claim(2, BOB, "Bob", now());
        p.moveTo(PollState.VOTING, now());
        return p;
    }

    static boolean hasEvent(Poll p, String type) {
        return p.events().stream().anyMatch(e -> e.type().equals(type));
    }

    static void check(String description, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failures.add(description);
        }
    }

    static void report() {
        System.out.println(passed + " passed, " + failures.size() + " failed");
        for (String f : failures) {
            System.out.println("  FAILED: " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }
}
