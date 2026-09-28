package pocketdungeons;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lemon's pure rules, tested without a server ({@code LemonSpeechTest}):
 * which chat lines are addressed to it, how a long line is split into
 * speech bubbles, and how long each bubble stays up.
 */
final class LemonSpeech {

    private LemonSpeech() {}

    /** The longest bubble, in characters. A text display wraps it over two or three lines. */
    static final int BUBBLE_CHARS = 90;

    /**
     * "Lemon" as the first word, any case, then an optional comma or colon.
     * {@code \b} keeps "lemonade" from matching.
     */
    private static final Pattern PREFIX = Pattern.compile("^\\s*lemon\\b\\s*[,:]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * What a chat line says to Lemon, or {@code null} when it is not addressed
     * to Lemon. Solo, everything is; in a party, only a line that starts with
     * "Lemon". The name itself is stripped either way; a bare "Lemon" comes
     * back as an empty string (a summons with no question).
     */
    static String addressed(String message, boolean solo) {
        if (message == null) {
            return null;
        }
        Matcher m = PREFIX.matcher(message);
        if (m.matches()) {
            return m.group(1).trim();
        }
        return solo ? message.trim() : null;
    }

    /**
     * Splits {@code text} into bubbles of at most {@link #BUBBLE_CHARS}
     * characters: whole sentences where they fit, otherwise whole words, and
     * a single word longer than a bubble is cut.
     */
    static List<String> bubbles(String text) {
        return bubbles(text, BUBBLE_CHARS);
    }

    static List<String> bubbles(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences(text.trim())) {
            if (fits(current, sentence, max)) {
                append(current, sentence);
                continue;
            }
            flush(current, out);
            if (sentence.length() <= max) {
                current.append(sentence);
                continue;
            }
            for (String word : sentence.split("\\s+")) {
                while (word.length() > max) {
                    flush(current, out);
                    out.add(word.substring(0, max));
                    word = word.substring(max);
                }
                if (!fits(current, word, max)) {
                    flush(current, out);
                }
                append(current, word);
            }
        }
        flush(current, out);
        return out;
    }

    /** Ticks a bubble stays up: long enough to read, never a wall. */
    static int bubbleTicks(String bubble) {
        int ticks = 40 + 2 * (bubble == null ? 0 : bubble.length());
        return Math.max(60, Math.min(200, ticks));
    }

    /** Sentences, each keeping its closing punctuation. */
    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean end = (c == '.' || c == '!' || c == '?')
                    && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)));
            if (end) {
                out.add(text.substring(start, i + 1).trim());
                start = i + 1;
            }
        }
        if (start < text.length() && !text.substring(start).isBlank()) {
            out.add(text.substring(start).trim());
        }
        return out;
    }

    private static boolean fits(StringBuilder current, String next, int max) {
        return current.isEmpty() ? next.length() <= max : current.length() + 1 + next.length() <= max;
    }

    private static void append(StringBuilder current, String next) {
        if (!current.isEmpty()) {
            current.append(' ');
        }
        current.append(next);
    }

    private static void flush(StringBuilder current, List<String> out) {
        if (!current.isEmpty()) {
            out.add(current.toString());
            current.setLength(0);
        }
    }
}
