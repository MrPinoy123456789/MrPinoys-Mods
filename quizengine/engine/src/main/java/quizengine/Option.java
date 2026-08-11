package quizengine;

import java.util.List;
import java.util.Objects;

/** One selectable answer. {@code index} is its position in the round's option list. */
public record Option(int index, String text) {
    public Option {
        Objects.requireNonNull(text, "text");
    }

    /** Convenience for building a round from plain strings. */
    public static List<Option> of(String... texts) {
        return java.util.stream.IntStream.range(0, texts.length)
                .mapToObj(i -> new Option(i, texts[i]))
                .toList();
    }
}
