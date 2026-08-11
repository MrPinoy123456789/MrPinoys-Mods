package quizengine;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a settled round produced. Payouts are the authoritative output — the
 * orchestrator adds them to the leaderboard and hands out any item rewards.
 */
public sealed interface RoundResult {

    /** Points awarded, by player. Never null; empty for a skipped round. */
    Map<UUID, Integer> payouts();

    record Trivia(
            int correctOption,
            List<UUID> correctPlayers,
            Map<UUID, Integer> payouts
    ) implements RoundResult {
        public Trivia {
            correctPlayers = List.copyOf(correctPlayers);
            payouts = Map.copyOf(payouts);
        }
    }

    record Quiplash(
            List<Integer> winningOptions,
            Map<Integer, UUID> writers,
            int winningVoteCount,
            List<UUID> winningVoters,
            Map<UUID, Integer> payouts
    ) implements RoundResult {
        public Quiplash {
            winningOptions = List.copyOf(winningOptions);
            writers = Map.copyOf(writers);
            winningVoters = List.copyOf(winningVoters);
            payouts = Map.copyOf(payouts);
        }
    }

    /** Not enough participation to be worth scoring. Nobody is paid. */
    record Skipped(String reason) implements RoundResult {
        @Override
        public Map<UUID, Integer> payouts() {
            return Map.of();
        }
    }
}
