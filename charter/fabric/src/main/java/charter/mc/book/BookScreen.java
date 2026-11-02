package charter.mc.book;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * What the book shows a player, as data. Every page builds one and {@link BookDialogs#show} hands it to a renderer:
 * the Java dialog renderer, or a native Bedrock form. A screen never holds a Minecraft dialog and never issues a
 * token; the renderer does both, at the moment it is shown.
 *
 * <p>The screen rules of the Bedrock plan live here, so a page cannot break them: a screen always has an exit
 * (B and Esc run it), a list has at most {@value #MAX_BUTTONS} buttons (a longer one is paged, see
 * {@link BookDialogs#list}), a label is at most {@value #MAX_LABEL} characters ({@link #violations} names the ones
 * that are not), and a result is green or red ({@link BookResult}).
 *
 * @param buttons the actions, the exit not counted. For a CONFIRM this is the one verb button; the exit is the other.
 * @param exit    runs on Back, B and Esc. It never changes anything; a null run means it only closes.
 * @param operator true for an operator tool screen (Java only, never paged, not held to the button and label limits)
 */
public record BookScreen(Kind kind, String title, List<Component> body, List<BookButton> buttons, List<BookInput> inputs,
                         BookButton submit, BookButton exit, boolean operator) {

    public static final int MAX_BUTTONS = 8;
    public static final int MAX_LABEL = 24;
    public static final int MAX_BODY_LINES = 3;

    public enum Kind { LIST, NOTICE, CONFIRM, FORM }

    public BookScreen {
        if (exit == null) throw new IllegalArgumentException("a Charter screen needs an exit: " + title);
        body = List.copyOf(body);
        buttons = List.copyOf(buttons);
        inputs = List.copyOf(inputs);
    }

    /** Every button including a form's submit and the exit, in the order they are drawn. */
    public List<BookButton> allButtons() {
        List<BookButton> out = new ArrayList<>(buttons);
        if (submit != null) out.add(submit);
        out.add(exit);
        return out;
    }

    /** The body as plain strings, for tests and for the chat copy. */
    public List<String> bodyText() {
        return body.stream().map(Component::getString).toList();
    }

    /** A result line: the green or red line {@link BookDialogs#withNotice} puts on top. */
    static boolean result(Component line) {
        var color = line.getStyle().getColor();
        return color != null && (color.equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.GREEN))
                || color.equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.RED)));
    }

    /** The rules this screen breaks, empty when it is fine. A list over the button limit is paged, so it never gets here. */
    public List<String> violations() {
        List<String> out = new ArrayList<>();
        if (operator) return out;
        if (buttons.size() > MAX_BUTTONS) out.add("more than " + MAX_BUTTONS + " buttons (" + buttons.size() + ")");
        for (BookButton b : allButtons()) {
            if (b.label().length() > MAX_LABEL) out.add("label over " + MAX_LABEL + " characters: " + b.label());
        }
        // The result of the last press sits above the body (BookDialogs.withNotice) and is not counted against it.
        int lines = body.size() - (!body.isEmpty() && result(body.get(0)) ? 1 : 0);
        if (lines > MAX_BODY_LINES) out.add("more than " + MAX_BODY_LINES + " body lines (" + lines + ")");
        return out;
    }
}
