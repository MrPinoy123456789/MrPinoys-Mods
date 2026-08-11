package quizengine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dependency-free test runner. The engine is a fold, so the tests are too: push a
 * round through a list of commands and assert on the final state and payouts.
 * No server, no clock, no mocks.
 *
 * <p>Porting these to JUnit is mechanical — each {@code test(...)} becomes an
 * {@code @Test} method and the helpers below map onto the standard assertions.
 */
public final class EngineTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    // Stable identities make failure output readable.
    static final UUID ALICE = named("alice");
    static final UUID BOB = named("bob");
    static final UUID CARL = named("carl");
    static final UUID DANA = named("dana");
    static final UUID ERIN = named("erin");

    static final ScoringConfig CFG = ScoringConfig.defaults();
    // participation 1, triviaCorrect 5, writerBase 10, perVote 3,
    // voterBase 2, voterPool 12, quorumOptions 2

    public static void main(String[] args) {
        triviaCorrectAndIncorrect();
        triviaDuplicateSubmissionRejected();
        triviaOptionOutOfRangeRejected();
        triviaRejectsVoting();

        quiplashHappyPath();
        quiplashSelfVoteRejected();
        quiplashTwoWayTie();
        quiplashBelowQuorumSkipped();
        quiplashNoVotesSkipped();
        quiplashVoteForUnsubmittedOptionRejected();
        quiplashLoneWinningVoterTakesWholePool();
        quiplashNonSubmitterMayVote();
        quiplashWriterIsTheAuthor();
        quiplashRejectsBlankSubmission();
        quiplashRejectsOverlongSubmission();
        quiplashBlocksDuplicateText();
        submissionTypesAreRoundSpecific();
        quiplashDoubleVoteRejected();

        commandsInWrongOrderLeaveRoundUnchanged();
        inputsAreNotMutated();

        report();
    }

    // ---- trivia -----------------------------------------------------------

    static void triviaCorrectAndIncorrect() {
        Round r = trivia();
        r = play(r,
                new Command.Submit(ALICE, 2),   // correct
                new Command.Submit(BOB, 0),     // wrong
                new Command.Settle());

        RoundResult.Trivia result = (RoundResult.Trivia) lastResult(r);
        check("trivia phase settled", r.phase() == Phase.SETTLED);
        check("trivia correct player recorded", result.correctPlayers().equals(List.of(ALICE)));
        check("trivia correct payout is participation + correct",
                result.payouts().get(ALICE) == 6);
        check("trivia wrong payout is participation only",
                result.payouts().get(BOB) == 1);
    }

    static void triviaDuplicateSubmissionRejected() {
        Engine.Result first = Engine.apply(trivia(), new Command.Submit(ALICE, 1));
        Engine.Result second = Engine.apply(first.round(), new Command.Submit(ALICE, 2));

        check("second submission rejected", second.wasRejected());
        check("rejection reason is ALREADY_SUBMITTED",
                reasonOf(second) == Event.Reason.ALREADY_SUBMITTED);
        check("original answer stands", second.round().submissions().get(ALICE) == 1);
    }

    static void triviaOptionOutOfRangeRejected() {
        Engine.Result out = Engine.apply(trivia(), new Command.Submit(ALICE, 9));
        check("out of range rejected", out.wasRejected());
        check("out of range reason", reasonOf(out) == Event.Reason.OPTION_OUT_OF_RANGE);
        check("no submission recorded", out.round().submissions().isEmpty());
    }

    static void triviaRejectsVoting() {
        Engine.Result r = Engine.apply(trivia(), new Command.Vote(ALICE, 1));
        check("trivia rejects votes", r.wasRejected());
        check("trivia vote reason", reasonOf(r) == Event.Reason.NOT_APPLICABLE_TO_ROUND_TYPE);
    }

    // ---- quiplash ---------------------------------------------------------

    static void quiplashHappyPath() {
        Round r = quiplash();
        r = play(r,
                writes(ALICE, "Wet Regrets"),      // option 0
                writes(BOB, "SS Norovirus"),       // option 1
                writes(CARL, "Buoyant Mistake"),   // option 2
                new Command.CloseSubmissions(),
                new Command.OpenVoting(),
                new Command.Vote(ALICE, 1),
                new Command.Vote(DANA, 1),
                new Command.Vote(ERIN, 0),
                new Command.Settle());

        RoundResult.Quiplash q = (RoundResult.Quiplash) lastResult(r);
        check("quiplash settles", r.phase() == Phase.SETTLED);
        check("winning option is 1", q.winningOptions().equals(List.of(1)));
        check("writer is Bob, who wrote it", q.writers().get(1).equals(BOB));
        check("winning vote count is 2", q.winningVoteCount() == 2);

        // Bob: participation 1 + writerBase 10 + 2 votes * 3 = 17
        check("writer payout", q.payouts().get(BOB) == 17);
        // Alice: participation 1 + voterBase 2 + pool 12/2 = 9
        check("winning voter who also submitted", q.payouts().get(ALICE) == 9);
        // Dana: voterBase 2 + 6, no participation (never submitted)
        check("winning voter who did not submit", q.payouts().get(DANA) == 8);
        // Carl: wrote something that lost, and cast no vote
        check("losing writer gets participation only", q.payouts().get(CARL) == 1);
        // Erin: voted for a losing answer, never wrote one
        check("losing voter gets nothing", q.payouts().get(ERIN) == null);
    }

    static void quiplashSelfVoteRejected() {
        Round r = play(quiplash(),
                writes(ALICE, "Wet Regrets"),
                writes(BOB, "SS Norovirus"),
                new Command.CloseSubmissions(),
                new Command.OpenVoting());

        Engine.Result self = Engine.apply(r, new Command.Vote(ALICE, 0));
        check("self vote rejected", self.wasRejected());
        check("self vote reason", reasonOf(self) == Event.Reason.SELF_VOTE);
        check("no vote recorded", self.round().votes().isEmpty());

        Engine.Result other = Engine.apply(r, new Command.Vote(ALICE, 1));
        check("voting for someone else is fine", !other.wasRejected());
    }

    static void quiplashTwoWayTie() {
        Round r = play(quiplash(),
                writes(ALICE, "Wet Regrets"),
                writes(BOB, "SS Norovirus"),
                new Command.CloseSubmissions(),
                new Command.OpenVoting(),
                new Command.Vote(ALICE, 1),
                new Command.Vote(BOB, 0),
                new Command.Settle());

        RoundResult.Quiplash q = (RoundResult.Quiplash) lastResult(r);
        check("both options win", q.winningOptions().equals(List.of(0, 1)));
        check("both writers recorded", q.writers().size() == 2);

        // Each is writer of a winning option (1 vote) AND a winning voter.
        // participation 1 + writerBase 10 + 1*3 + voterBase 2 + pool 12/2 = 22
        check("tied writer paid in full", q.payouts().get(ALICE) == 22);
        check("tied writer paid in full (b)", q.payouts().get(BOB) == 22);

        int poolPaid = q.winningVoters().size() * (CFG.voterPool() / q.winningVoters().size());
        check("pool split once across the tie, not per option", poolPaid == CFG.voterPool());
    }

    static void quiplashBelowQuorumSkipped() {
        Round r = play(quiplash(),
                new Command.Submit(ALICE, 0),
                new Command.Submit(BOB, 0),      // everyone converged on one option
                new Command.CloseSubmissions(),
                new Command.OpenVoting(),
                new Command.Settle());

        RoundResult result = lastResult(r);
        check("below quorum skips", result instanceof RoundResult.Skipped);
        check("skipped phase", r.phase() == Phase.SKIPPED);
        check("no payouts on skip", result.payouts().isEmpty());
    }

    static void quiplashNoVotesSkipped() {
        Round r = play(quiplash(),
                writes(ALICE, "Wet Regrets"),
                writes(BOB, "SS Norovirus"),
                new Command.CloseSubmissions(),
                new Command.OpenVoting(),
                new Command.Settle());

        RoundResult result = lastResult(r);
        check("no votes skips", result instanceof RoundResult.Skipped);
        check("no votes pays nobody", result.payouts().isEmpty());
    }

    static void quiplashVoteForUnsubmittedOptionRejected() {
        Round r = play(quiplash(),
                writes(ALICE, "Wet Regrets"),
                writes(BOB, "SS Norovirus"),
                new Command.CloseSubmissions(),
                new Command.OpenVoting());

        Engine.Result ghost = Engine.apply(r, new Command.Vote(CARL, 7));
        check("cannot vote for an answer nobody wrote", ghost.wasRejected());
        check("ghost vote reason", reasonOf(ghost) == Event.Reason.OPTION_OUT_OF_RANGE);
    }

    static void quiplashLoneWinningVoterTakesWholePool() {
        Round r = play(quiplash(),
                writes(ALICE, "Wet Regrets"),
                writes(BOB, "SS Norovirus"),
                new Command.CloseSubmissions(),
                new Command.OpenVoting(),
                new Command.Vote(CARL, 1),
                new Command.Settle());

        RoundResult.Quiplash q = (RoundResult.Quiplash) lastResult(r);
        // voterBase 2 + whole pool 12 = 14
        check("lone winning voter takes the pool", q.payouts().get(CARL) == 14);
    }

    static void quiplashNonSubmitterMayVote() {
        Round r = play(quiplash(),
                writes(ALICE, "Wet Regrets"),
                writes(BOB, "SS Norovirus"),
                new Command.CloseSubmissions(),
                new Command.OpenVoting());

        Engine.Result late = Engine.apply(r, new Command.Vote(DANA, 0));
        check("a player who missed submissions can still vote", !late.wasRejected());
    }

    static void quiplashWriterIsTheAuthor() {
        Round r = play(quiplash(),
                writes(BOB, "SS Norovirus"),
                writes(ALICE, "Wet Regrets"),
                new Command.CloseSubmissions(),
                new Command.OpenVoting(),
                new Command.Vote(CARL, 1),
                new Command.Settle());

        RoundResult.Quiplash q = (RoundResult.Quiplash) lastResult(r);
        check("options are numbered in arrival order", q.winningOptions().equals(List.of(1)));
        check("writer is whoever wrote the winning answer", q.writers().get(1).equals(ALICE));
        check("the losing writer gets participation only",
                q.payouts().get(BOB) == CFG.participation());
    }

    static void quiplashRejectsBlankSubmission() {
        Engine.Result blank = Engine.apply(quiplash(), writes(ALICE, "   "));
        check("blank submission rejected", blank.wasRejected());
        check("blank reason", reasonOf(blank) == Event.Reason.BLANK_SUBMISSION);

        Engine.Result empty = Engine.apply(quiplash(), writes(ALICE, ""));
        check("empty submission rejected", empty.wasRejected());
    }

    static void quiplashRejectsOverlongSubmission() {
        String tooLong = "x".repeat(Engine.MAX_SUBMISSION_LENGTH + 1);
        Engine.Result over = Engine.apply(quiplash(), writes(ALICE, tooLong));
        check("overlong submission rejected", over.wasRejected());
        check("overlong reason", reasonOf(over) == Event.Reason.SUBMISSION_TOO_LONG);

        String justFits = "x".repeat(Engine.MAX_SUBMISSION_LENGTH);
        check("a submission at the limit is accepted",
                !Engine.apply(quiplash(), writes(ALICE, justFits)).wasRejected());
    }

    static void quiplashBlocksDuplicateText() {
        Round r = play(quiplash(), writes(ALICE, "Wet Regrets"));

        Engine.Result exact = Engine.apply(r, writes(BOB, "Wet Regrets"));
        check("exact duplicate rejected", exact.wasRejected());
        check("duplicate reason", reasonOf(exact) == Event.Reason.DUPLICATE_SUBMISSION);

        Engine.Result sloppy = Engine.apply(r, writes(BOB, "  wet   REGRETS "));
        check("duplicate rejected regardless of case and spacing", sloppy.wasRejected());

        Engine.Result different = Engine.apply(r, writes(BOB, "Wet Regret"));
        check("a genuinely different answer is accepted", !different.wasRejected());
        check("the option list grew", different.round().options().size() == 2);
    }

    static void submissionTypesAreRoundSpecific() {
        Engine.Result textToTrivia = Engine.apply(trivia(), writes(ALICE, "Timbuktu"));
        check("trivia rejects written submissions", textToTrivia.wasRejected());
        check("trivia text reason",
                reasonOf(textToTrivia) == Event.Reason.NOT_APPLICABLE_TO_ROUND_TYPE);

        Engine.Result indexToQuiplash = Engine.apply(quiplash(), new Command.Submit(ALICE, 0));
        check("quiplash rejects index submissions", indexToQuiplash.wasRejected());
        check("quiplash index reason",
                reasonOf(indexToQuiplash) == Event.Reason.NOT_APPLICABLE_TO_ROUND_TYPE);
    }

    static void quiplashDoubleVoteRejected() {
        Round r = play(quiplash(),
                writes(ALICE, "Wet Regrets"),
                writes(BOB, "SS Norovirus"),
                new Command.CloseSubmissions(),
                new Command.OpenVoting(),
                new Command.Vote(CARL, 0));

        Engine.Result again = Engine.apply(r, new Command.Vote(CARL, 1));
        check("second vote rejected", again.wasRejected());
        check("double vote reason", reasonOf(again) == Event.Reason.ALREADY_VOTED);
        check("first vote stands", again.round().votes().get(CARL) == 0);
    }

    // ---- phase discipline -------------------------------------------------

    static void commandsInWrongOrderLeaveRoundUnchanged() {
        Round base = quiplash();

        Round withAnswers = play(base, writes(ALICE, "Wet Regrets"), writes(BOB, "SS Norovirus"));
        Engine.Result earlyVote = Engine.apply(withAnswers, new Command.Vote(CARL, 0));
        check("cannot vote during submissions", earlyVote.wasRejected());
        check("early vote reason", reasonOf(earlyVote) == Event.Reason.WRONG_PHASE);

        Engine.Result skipClose = Engine.apply(base, new Command.OpenVoting());
        check("cannot open voting without closing submissions", skipClose.wasRejected());

        Round settled = play(trivia(), new Command.Submit(ALICE, 2), new Command.Settle());
        Engine.Result twice = Engine.apply(settled, new Command.Settle());
        check("cannot settle twice", twice.wasRejected());

        Engine.Result lateSubmit = Engine.apply(settled, new Command.Submit(BOB, 2));
        check("cannot submit to a settled round", lateSubmit.wasRejected());
        check("settled round is unchanged by rejected commands",
                lateSubmit.round().submissions().size() == 1);
    }

    static void inputsAreNotMutated() {
        Round before = quiplash();
        Map<UUID, Integer> snapshot = Map.copyOf(before.submissions());
        Engine.apply(before, writes(ALICE, "Wet Regrets"));

        check("apply does not mutate the round it was given",
                before.submissions().equals(snapshot));
        check("original round is still empty", before.submissions().isEmpty());
    }

    // ---- harness ----------------------------------------------------------

    static Round trivia() {
        return Round.trivia("t1", "Which of these is a real place?",
                Option.of("Atlantis", "El Dorado", "Timbuktu", "Shangri-La"), 2, CFG);
    }

    static Round quiplash() {
        return Round.quiplash("q1", "The worst name for a cruise ship", CFG);
    }

    static Command.SubmitText writes(java.util.UUID player, String text) {
        return new Command.SubmitText(player, text);
    }

    /** Fold a list of commands over a round, ignoring events. */
    static Round play(Round round, Command... commands) {
        for (Command c : commands) {
            round = Engine.apply(round, c).round();
        }
        return round;
    }

    /** Re-settle a terminal round to recover its result for assertions. */
    static RoundResult lastResult(Round settled) {
        Round rewound = settled.withPhase(
                settled.type() == RoundType.TRIVIA ? Phase.SUBMITTING : Phase.VOTING);
        for (Event e : Engine.apply(rewound, new Command.Settle()).events()) {
            if (e instanceof Event.Settled s) {
                return s.result();
            }
        }
        throw new AssertionError("round produced no result");
    }

    static Event.Reason reasonOf(Engine.Result result) {
        for (Event e : result.events()) {
            if (e instanceof Event.Rejected r) {
                return r.reason();
            }
        }
        return null;
    }

    static UUID named(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
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
