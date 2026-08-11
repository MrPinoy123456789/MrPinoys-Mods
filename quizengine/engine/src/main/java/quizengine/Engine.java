package quizengine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The whole public surface of the engine.
 *
 * <p>{@code apply} never mutates its input and never throws on the hot path. A
 * rejected command returns the round unchanged alongside a {@link Event.Rejected}.
 * Settling is a pure function of submissions, votes and config, so a round can be
 * replayed, tested, or scored on any surface with identical results.
 */
public final class Engine {

    /** Long enough for a punchline, short enough to read on one chat line. */
    public static final int MAX_SUBMISSION_LENGTH = 100;

    private Engine() {}

    public record Result(Round round, List<Event> events) {
        public Result {
            events = List.copyOf(events);
        }

        public boolean wasRejected() {
            return events.stream().anyMatch(e -> e instanceof Event.Rejected);
        }
    }

    public static Result apply(Round round, Command command) {
        return switch (command) {
            case Command.Submit c -> submit(round, c);
            case Command.SubmitText c -> submitText(round, c);
            case Command.Vote c -> vote(round, c);
            case Command.CloseSubmissions ignored -> closeSubmissions(round);
            case Command.OpenVoting ignored -> openVoting(round);
            case Command.Settle ignored -> settle(round);
        };
    }

    // ---- player commands --------------------------------------------------

    private static Result submit(Round round, Command.Submit c) {
        if (round.type() != RoundType.TRIVIA) {
            return reject(round, c.player(), Event.Reason.NOT_APPLICABLE_TO_ROUND_TYPE);
        }
        if (round.phase() != Phase.SUBMITTING) {
            return reject(round, c.player(), Event.Reason.WRONG_PHASE);
        }
        if (!round.isValidOption(c.option())) {
            return reject(round, c.player(), Event.Reason.OPTION_OUT_OF_RANGE);
        }
        if (round.submissions().containsKey(c.player())) {
            return reject(round, c.player(), Event.Reason.ALREADY_SUBMITTED);
        }
        return new Result(
                round.withSubmission(c.player(), c.option()),
                List.of(new Event.SubmissionAccepted(c.player(), c.option())));
    }

    private static Result submitText(Round round, Command.SubmitText c) {
        if (round.type() != RoundType.QUIPLASH) {
            return reject(round, c.player(), Event.Reason.NOT_APPLICABLE_TO_ROUND_TYPE);
        }
        if (round.phase() != Phase.SUBMITTING) {
            return reject(round, c.player(), Event.Reason.WRONG_PHASE);
        }
        if (round.submissions().containsKey(c.player())) {
            return reject(round, c.player(), Event.Reason.ALREADY_SUBMITTED);
        }
        String text = c.text() == null ? "" : c.text().trim();
        if (text.isEmpty()) {
            return reject(round, c.player(), Event.Reason.BLANK_SUBMISSION);
        }
        if (text.length() > MAX_SUBMISSION_LENGTH) {
            return reject(round, c.player(), Event.Reason.SUBMISSION_TOO_LONG);
        }
        if (round.hasSubmissionText(text)) {
            return reject(round, c.player(), Event.Reason.DUPLICATE_SUBMISSION);
        }
        Round next = round.withWrittenSubmission(c.player(), text);
        return new Result(next,
                List.of(new Event.SubmissionAccepted(c.player(), next.submissions().get(c.player()))));
    }

    private static Result vote(Round round, Command.Vote c) {
        if (round.type() != RoundType.QUIPLASH) {
            return reject(round, c.player(), Event.Reason.NOT_APPLICABLE_TO_ROUND_TYPE);
        }
        if (round.phase() != Phase.VOTING) {
            return reject(round, c.player(), Event.Reason.WRONG_PHASE);
        }
        if (!round.isValidOption(c.option())) {
            return reject(round, c.player(), Event.Reason.OPTION_OUT_OF_RANGE);
        }
        if (round.votes().containsKey(c.player())) {
            return reject(round, c.player(), Event.Reason.ALREADY_VOTED);
        }
        if (!round.submittedOptions().contains(c.option())) {
            return reject(round, c.player(), Event.Reason.OPTION_NOT_SUBMITTED);
        }
        Integer own = round.submissions().get(c.player());
        if (own != null && own == c.option()) {
            return reject(round, c.player(), Event.Reason.SELF_VOTE);
        }
        return new Result(
                round.withVote(c.player(), c.option()),
                List.of(new Event.VoteAccepted(c.player(), c.option())));
    }

    // ---- orchestrator commands --------------------------------------------

    private static Result closeSubmissions(Round round) {
        if (round.type() != RoundType.QUIPLASH) {
            return reject(round, null, Event.Reason.NOT_APPLICABLE_TO_ROUND_TYPE);
        }
        if (round.phase() != Phase.SUBMITTING) {
            return reject(round, null, Event.Reason.WRONG_PHASE);
        }
        return phaseChange(round, Phase.CLOSED);
    }

    private static Result openVoting(Round round) {
        if (round.type() != RoundType.QUIPLASH) {
            return reject(round, null, Event.Reason.NOT_APPLICABLE_TO_ROUND_TYPE);
        }
        if (round.phase() != Phase.CLOSED) {
            return reject(round, null, Event.Reason.WRONG_PHASE);
        }
        return phaseChange(round, Phase.VOTING);
    }

    private static Result settle(Round round) {
        if (round.phase().isTerminal()) {
            return reject(round, null, Event.Reason.WRONG_PHASE);
        }
        if (round.type() == RoundType.TRIVIA) {
            if (round.phase() != Phase.SUBMITTING) {
                return reject(round, null, Event.Reason.WRONG_PHASE);
            }
            return finish(round, settleTrivia(round));
        }
        if (round.phase() != Phase.VOTING && round.phase() != Phase.CLOSED) {
            return reject(round, null, Event.Reason.WRONG_PHASE);
        }
        return finish(round, settleQuiplash(round));
    }

    // ---- settling ---------------------------------------------------------

    private static RoundResult settleTrivia(Round round) {
        ScoringConfig cfg = round.config();
        Map<UUID, Integer> payouts = new LinkedHashMap<>();
        List<UUID> correct = new ArrayList<>();

        for (Map.Entry<UUID, Integer> e : round.submissions().entrySet()) {
            add(payouts, e.getKey(), cfg.participation());
            if (e.getValue() == round.correctOption()) {
                correct.add(e.getKey());
                add(payouts, e.getKey(), cfg.triviaCorrect());
            }
        }
        return new RoundResult.Trivia(round.correctOption(), correct, payouts);
    }

    private static RoundResult settleQuiplash(Round round) {
        ScoringConfig cfg = round.config();

        if (round.submittedOptions().size() < cfg.quorumOptions()) {
            return new RoundResult.Skipped("below option quorum");
        }
        if (round.votes().isEmpty()) {
            return new RoundResult.Skipped("no votes cast");
        }

        Map<Integer, Integer> tally = round.tally();
        int maxVotes = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Integer> winners = tally.entrySet().stream()
                .filter(e -> e.getValue() == maxVotes)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        Map<UUID, Integer> payouts = new LinkedHashMap<>();
        for (UUID submitter : round.submissions().keySet()) {
            add(payouts, submitter, cfg.participation());
        }

        // Writers of the winning options. On a tie, every tied writer is paid in full.
        Map<Integer, UUID> writers = new LinkedHashMap<>();
        for (int option : winners) {
            round.writerOf(option).ifPresent(writer -> {
                writers.put(option, writer);
                add(payouts, writer, cfg.writerBase() + maxVotes * cfg.perVote());
            });
        }

        // The voter pool splits once across everyone who backed any winning option,
        // so a tie does not double the total payout. Integer division; remainder drops.
        List<UUID> winningVoters = round.votes().entrySet().stream()
                .filter(e -> winners.contains(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        if (!winningVoters.isEmpty()) {
            int share = cfg.voterPool() / winningVoters.size();
            for (UUID voter : winningVoters) {
                add(payouts, voter, cfg.voterBase() + share);
            }
        }

        return new RoundResult.Quiplash(winners, writers, maxVotes, winningVoters, payouts);
    }

    // ---- helpers ----------------------------------------------------------

    private static Result finish(Round round, RoundResult result) {
        Phase next = result instanceof RoundResult.Skipped ? Phase.SKIPPED : Phase.SETTLED;
        return new Result(
                round.withPhase(next),
                List.of(new Event.PhaseChanged(round.phase(), next), new Event.Settled(result)));
    }

    private static Result phaseChange(Round round, Phase next) {
        return new Result(
                round.withPhase(next),
                List.of(new Event.PhaseChanged(round.phase(), next)));
    }

    private static Result reject(Round round, UUID player, Event.Reason reason) {
        return new Result(round, List.of(new Event.Rejected(player, reason)));
    }

    private static void add(Map<UUID, Integer> payouts, UUID player, int amount) {
        payouts.merge(player, amount, Integer::sum);
    }
}
