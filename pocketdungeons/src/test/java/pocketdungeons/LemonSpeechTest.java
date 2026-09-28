package pocketdungeons;

import java.util.List;

/**
 * Pure regression for Lemon's chat routing prefix rule, bubble splitting and
 * bubble timing ({@link LemonSpeech}). Run from {@code tasks.test}.
 */
public class LemonSpeechTest {

    public static void main(String[] args) {
        testSoloRouting();
        testPartyPrefix();
        testBubbles();
        testBubbleTicks();
        System.out.println("LemonSpeechTest passed");
    }

    private static void testSoloRouting() {
        checkEquals(LemonSpeech.addressed("where is the bank?", true), "where is the bank?");
        checkEquals(LemonSpeech.addressed("  hi  ", true), "hi");
        // Solo, the name is still stripped when used.
        checkEquals(LemonSpeech.addressed("Lemon, what is omen?", true), "what is omen?");
        check(LemonSpeech.addressed(null, true) == null, "null is not a message");
    }

    private static void testPartyPrefix() {
        checkEquals(LemonSpeech.addressed("Lemon what now", false), "what now");
        checkEquals(LemonSpeech.addressed("lemon, what now", false), "what now");
        checkEquals(LemonSpeech.addressed("LEMON: what now", false), "what now");
        checkEquals(LemonSpeech.addressed("  Lemon ,  what now", false), "what now");
        checkEquals(LemonSpeech.addressed("Lemon", false), "");
        checkEquals(LemonSpeech.addressed("lemon!", false), "!");
        check(LemonSpeech.addressed("lemonade anyone?", false) == null, "lemonade is not Lemon");
        check(LemonSpeech.addressed("ask Lemon about it", false) == null, "Lemon must come first");
        check(LemonSpeech.addressed("where is the bank?", false) == null, "party chat stays party chat");
    }

    private static void testBubbles() {
        check(LemonSpeech.bubbles("").isEmpty(), "nothing to say, no bubbles");
        checkEquals(String.join("|", LemonSpeech.bubbles("Short line.")), "Short line.");
        // Sentences are kept whole while they fit together.
        List<String> two = LemonSpeech.bubbles("One. Two.", 20);
        checkEquals(String.join("|", two), "One. Two.");
        List<String> split = LemonSpeech.bubbles("The first sentence is here. The second one follows it.", 30);
        checkEquals(String.join("|", split), "The first sentence is here.|The second one follows it.");
        // A long sentence breaks between words, and every bubble fits.
        String longLine = "the quick brown fox jumps over the lazy dog and keeps on running far away";
        List<String> words = LemonSpeech.bubbles(longLine, 20);
        for (String b : words) {
            check(b.length() <= 20, "bubble too long: " + b);
        }
        checkEquals(String.join(" ", words), longLine);
        // A single word longer than a bubble is cut, not lost.
        List<String> cut = LemonSpeech.bubbles("abcdefghijklmnopqrstuvwxyz", 10);
        checkEquals(String.join("", cut), "abcdefghijklmnopqrstuvwxyz");
        check(cut.size() == 3, "cut into three");
        // Decimal points and file names do not end a sentence.
        checkEquals(String.join("|", LemonSpeech.bubbles("Version 1.5 is out.", 90)), "Version 1.5 is out.");
    }

    private static void testBubbleTicks() {
        check(LemonSpeech.bubbleTicks("hi") == 60, "a floor of three seconds");
        check(LemonSpeech.bubbleTicks("x".repeat(90)) == 200, "a ceiling of ten seconds");
        check(LemonSpeech.bubbleTicks("x".repeat(40)) == 120, "two ticks a character in between");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void checkEquals(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected '" + expected + "' but was '" + actual + "'");
        }
    }
}
