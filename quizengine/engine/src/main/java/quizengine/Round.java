package quizengine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The complete state of one round. Immutable, serializable as-is, and free of any
 * dependency on how it is being presented or scheduled.
 *
 * <p>{@code submissions} preserves insertion order, and that ordering is load-bearing:
 * the first player to pick an option is that option's writer. There is no separate
 * authorship field.
 *
 * <p>Trivia rounds carry a fixed option list from the start. Quiplash rounds begin
 * with an empty list and grow one option per submission, so a player's own words
 * become the thing everyone later votes on — and authorship is direct rather than
 * inferred.
 */
public record Round(
        String roundId,
        RoundType type,
        String prompt,
        List<Option> options,
        int correctOption,
        Phase phase,
        Map<UUID, Integer> submissions,
        Map<UUID, Integer> votes,
        ScoringConfig config
) {

    public Round {
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(config, "config");
        options = List.copyOf(options);
        submissions = Collections.unmodifiableMap(new LinkedHashMap<>(submissions));
        votes = Collections.unmodifiableMap(new LinkedHashMap<>(votes));
        if (type == RoundType.TRIVIA && options.size() < 2) {
            throw new IllegalArgumentException("a trivia round needs at least two options");
        }
        if (type == RoundType.TRIVIA && (correctOption < 0 || correctOption >= options.size())) {
            throw new IllegalArgumentException("trivia round needs a valid correctOption");
        }
    }

    // ---- construction -----------------------------------------------------

    public static Round trivia(String roundId, String prompt, List<Option> options,
                               int correctOption, ScoringConfig config) {
        return new Round(roundId, RoundType.TRIVIA, prompt, options, correctOption,
                Phase.SUBMITTING, Map.of(), Map.of(), config);
    }

    /** Options are empty at the start; players write them. */
    public static Round quiplash(String roundId, String prompt, ScoringConfig config) {
        return new Round(roundId, RoundType.QUIPLASH, prompt, List.of(), -1,
                Phase.SUBMITTING, Map.of(), Map.of(), config);
    }

    // ---- queries ----------------------------------------------------------

    public boolean isValidOption(int index) {
        return index >= 0 && index < options.size();
    }

    /** Options that at least one player picked. Voting is restricted to these. */
    public List<Integer> submittedOptions() {
        return submissions.values().stream().distinct().sorted().toList();
    }

    /**
     * True if this text has already been written, ignoring case, surrounding space
     * and repeated inner spaces. Two players independently landing on the same joke
     * makes the ballot confusing and authorship arbitrary, so the second is asked
     * for something new.
     */
    public boolean hasSubmissionText(String text) {
        String candidate = normalize(text);
        return options.stream().anyMatch(o -> normalize(o.text()).equals(candidate));
    }

    static String normalize(String text) {
        return text.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    /** The first player to have picked this option, if anyone did. */
    public Optional<UUID> writerOf(int option) {
        return submissions.entrySet().stream()
                .filter(e -> e.getValue() == option)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Vote counts by option. Options with zero votes are absent. */
    public Map<Integer, Integer> tally() {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int option : votes.values()) {
            counts.merge(option, 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    public String optionText(int index) {
        return options.get(index).text();
    }

    // ---- transitions ------------------------------------------------------

    Round withPhase(Phase next) {
        return new Round(roundId, type, prompt, options, correctOption, next,
                submissions, votes, config);
    }

    Round withSubmission(UUID player, int option) {
        Map<UUID, Integer> next = new LinkedHashMap<>(submissions);
        next.put(player, option);
        return new Round(roundId, type, prompt, options, correctOption, phase,
                next, votes, config);
    }

    /** Appends the text as a new option and records the player as its submitter. */
    Round withWrittenSubmission(UUID player, String text) {
        List<Option> nextOptions = new java.util.ArrayList<>(options);
        int index = nextOptions.size();
        nextOptions.add(new Option(index, text.trim()));

        Map<UUID, Integer> nextSubmissions = new LinkedHashMap<>(submissions);
        nextSubmissions.put(player, index);

        return new Round(roundId, type, prompt, nextOptions, correctOption, phase,
                nextSubmissions, votes, config);
    }

    Round withVote(UUID player, int option) {
        Map<UUID, Integer> next = new LinkedHashMap<>(votes);
        next.put(player, option);
        return new Round(roundId, type, prompt, options, correctOption, phase,
                submissions, next, config);
    }
}
