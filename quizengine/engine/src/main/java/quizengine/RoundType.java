package quizengine;

public enum RoundType {
    /** One phase. Correctness is known the moment an answer arrives. */
    TRIVIA,
    /** Two phases. Players pick an option, then vote among the options that were picked. */
    QUIPLASH
}
