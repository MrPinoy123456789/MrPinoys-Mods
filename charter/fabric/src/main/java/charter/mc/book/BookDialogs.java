package charter.mc.book;

import charter.mc.Text;
import charter.mc.cmd.CharterCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The page-facing kit of the Charter book: pages build a {@link BookScreen} here and {@link #show} it. Nothing a page
 * calls knows about Minecraft dialogs or Bedrock forms; {@link JavaDialogRenderer} and the native Bedrock renderer
 * draw the screen, and own tokens and callbacks.
 */
public final class BookDialogs {

    /** Buttons per page of a long list; with Previous and Next that makes the 8 a screen may hold. */
    static final int PAGE = 6;

    /** The last screen sent to each player, for gametests (there is no client to look at). Cleared on leave. */
    public static final Map<UUID, BookScreen> LAST = new ConcurrentHashMap<>();

    /** When true a screen that breaks a rule throws, so a gametest finds it. In play it is only logged. */
    private static volatile boolean strict;

    private BookDialogs() {
    }

    public static void strict(boolean on) {
        strict = on;
    }

    /** Pushes a screen onto a player's display. Only as the direct answer to something the player did. */
    public static void show(ServerPlayer player, BookScreen screen) {
        LAST.put(player.getUUID(), screen);
        List<String> broken = screen.violations();
        if (!broken.isEmpty()) {
            String what = "Charter book screen '" + screen.title() + "' breaks the screen rules: " + broken;
            if (strict) throw new IllegalStateException(what);
            charter.mc.CharterMod.LOG.warn(what);
        }
        if (BedrockForms.show(player, screen)) return;
        JavaDialogRenderer.show(player, screen);
    }

    // ---- pieces ----

    public static Component text(String line) {
        return Component.literal(line);
    }

    public static Component text(Component line) {
        return line;
    }

    /** A button whose press runs {@code run}. The renderer binds it to the player the screen is shown to. */
    public static BookButton act(ServerPlayer player, String label, String tooltip, Consumer<ServerPlayer> run) {
        return BookButton.act(label, tooltip, run);
    }

    public static BookButton closeButton(String label) {
        return BookButton.close(label);
    }

    /** A Back button: runs a screen rebuilt from live state, never a stored one. */
    public static BookButton back(ServerPlayer player, Consumer<ServerPlayer> parent) {
        return act(player, Text.get("book.button.back"), null, parent);
    }

    public static BookButton close() {
        return closeButton(Text.get("book.button.close"));
    }

    /** A name cut to fit a label: at most {@code max} characters, ending in "..." when it was cut. */
    public static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, Math.max(1, max - 3)) + "...";
    }

    // ---- forms: inputs the player fills in ----

    /** A one-line text box. */
    public static BookInput textInput(String key, String label, String initial, int maxLength) {
        return new BookInput.Text(key, label, initial, maxLength);
    }

    /** A dropdown; the value sent back is the id. The first entry starts picked. At least one entry is needed. */
    public static BookInput choice(String key, String label, List<String> ids, List<String> shown) {
        return new BookInput.Choice(key, label, ids, shown);
    }

    /**
     * The button that sends a form's inputs back with the values joined as {@link FormValues} describes. {@code run}
     * must check the values again.
     */
    public static BookButton submit(ServerPlayer player, String label, String tooltip, List<BookInput> inputs,
                             Consumer<CharterCommands.Form> run) {
        return BookButton.submit(label, tooltip, run);
    }

    // ---- screens ----

    /** A screen with inputs and one button that sends them, and an exit that Esc also runs. */
    public static BookScreen form(String title, List<Component> body, List<BookInput> inputs, BookButton submit, BookButton exit) {
        return new BookScreen(BookScreen.Kind.FORM, title, body, List.of(), inputs, submit, exit, false);
    }

    public static BookScreen notice(String title, List<Component> body, BookButton exit) {
        return new BookScreen(BookScreen.Kind.NOTICE, title, body, List.of(), List.of(), null, exit, false);
    }

    /** Two choices: the verb button and the way out. */
    public static BookScreen confirm(String title, List<Component> body, BookButton yes, BookButton no) {
        return new BookScreen(BookScreen.Kind.CONFIRM, title, body, List.of(yes), List.of(), null, no, false);
    }

    /**
     * A list of buttons in one column, with an explicit exit that Esc also runs. A screen is never sent without a
     * button: with no actions it becomes a notice that says there is nothing here and keeps its exit (the Pocket
     * Dungeons dialog-screens rule 1). More than {@value BookScreen#MAX_BUTTONS} buttons are paged,
     * {@value #PAGE} to a page with Previous and Next.
     */
    public static BookScreen list(String title, List<Component> body, List<BookButton> actions, BookButton exit) {
        if (actions.isEmpty()) {
            List<Component> lines = new ArrayList<>(body);
            if (body.isEmpty()) lines.add(text(Text.get("book.nothing_here")));
            return notice(title, lines, exit);
        }
        if (actions.size() > BookScreen.MAX_BUTTONS) return page(title, body, actions, exit, 0);
        return new BookScreen(BookScreen.Kind.LIST, title, body, actions, List.of(), null, exit, false);
    }

    /** An operator tool list: Java only, so it is never paged and the button and label limits do not apply. */
    public static BookScreen operatorList(String title, List<Component> body, List<BookButton> actions, BookButton exit) {
        if (actions.isEmpty()) return list(title, body, actions, exit);
        return new BookScreen(BookScreen.Kind.LIST, title, body, actions, List.of(), null, exit, true);
    }

    private static BookScreen page(String title, List<Component> body, List<BookButton> all, BookButton exit, int index) {
        int pages = (all.size() + PAGE - 1) / PAGE;
        List<BookButton> shown = new ArrayList<>(all.subList(index * PAGE, Math.min(all.size(), (index + 1) * PAGE)));
        if (index > 0) {
            shown.add(act(null, Text.get("book.page.prev"), null, p -> show(p, page(title, body, all, exit, index - 1))));
        }
        if (index < pages - 1) {
            shown.add(act(null, Text.get("book.page.next"), null, p -> show(p, page(title, body, all, exit, index + 1))));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(text(Component.literal(Text.get("book.page.line", "page", index + 1, "pages", pages)).withStyle(ChatFormatting.GRAY)));
        lines.addAll(body);
        return new BookScreen(BookScreen.Kind.LIST, title, lines, shown, List.of(), null, exit, false);
    }

    /**
     * A line shown at the top of a page: the result of the last press, green for a success ({@link BookResult#ok}) and
     * red for a refusal.
     */
    public static List<Component> withNotice(String notice, List<Component> body) {
        if (notice == null || notice.isBlank()) return body;
        List<Component> out = new ArrayList<>();
        out.add(text(Component.literal(BookResult.text(notice))
                .withStyle(BookResult.isOk(notice) ? ChatFormatting.GREEN : ChatFormatting.RED)));
        out.addAll(body);
        return out;
    }

    /** Every button of a screen, exit included, for tests. */
    public static List<BookButton> buttons(BookScreen screen) {
        return screen.allButtons();
    }
}
