package charter.mc.book;

import java.util.List;

/**
 * An input of a form screen. The value a submit button receives for each one is a string: the id of a picked entry,
 * the typed text, or the number as digits. Pages check every value again before they act.
 */
public sealed interface BookInput {

    String key();

    String label();

    /** A dropdown; the value is the id of the picked entry. The first entry starts picked. */
    record Choice(String key, String label, List<String> ids, List<String> shown) implements BookInput {
    }

    /** A slider over whole numbers; the value is the number as digits. Java draws a number range, Bedrock a slider. */
    record Slider(String key, String label, int min, int max, int step, int initial) implements BookInput {
    }

    /** A one-line text box. */
    record Text(String key, String label, String initial, int maxLength) implements BookInput {
    }
}
