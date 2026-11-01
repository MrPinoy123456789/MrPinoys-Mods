package charter.mc.book;

import charter.mc.CharterMod;
import charter.mc.CharterServer;
import charter.mc.cmd.CharterCommands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Draws a {@link BookScreen} as a native Bedrock form for a player who joined through Geyser and Floodgate. This class
 * knows nothing of Floodgate: it turns a screen into a neutral {@link Form} and hands it to a {@link Sender}, which
 * {@link FloodgateAdapter} supplies when the server runs Floodgate. Without a sender (or with the knob off, or for a
 * Java player) {@link #show} returns false and the Java dialog is used, so Charter never needs Floodgate and its tests
 * never load it.
 *
 * <p>The mapping: a list is a simple form (a button per action, each with an icon, the exit last), a confirmation is a
 * modal form with exactly two buttons, and a screen with inputs is a custom form (dropdowns, text boxes, sliders).
 * Closing a form, with B or the X, runs the screen's exit, which only ever goes back. A press runs the page's action
 * directly on the server thread, with no token and no command: the page rebuilds itself from live state and the engine
 * re-checks every power, exactly as for a Java press.
 */
public final class BedrockForms {

    /** One button of a simple form. {@code icon} is a vanilla Bedrock texture path, or null. */
    public record Entry(String label, String icon) {
    }

    /** One part of a custom form, in order. */
    public sealed interface Field {
        record Label(String text) implements Field {
        }

        /** The value is the picked entry's id. */
        record Dropdown(String label, List<String> shown, int initial) implements Field {
        }

        record Input(String label, String initial, int maxLength) implements Field {
        }

        /** The value is the number as digits. */
        record Slider(String label, int min, int max, int step, int initial) implements Field {
        }
    }

    public sealed interface Form {
        String title();

        record Simple(String title, String content, List<Entry> entries) implements Form {
        }

        record Modal(String title, String content, String yes, String no) implements Form {
        }

        record Custom(String title, List<Field> fields) implements Form {
        }
    }

    /** What the sender calls back with, from whatever thread Floodgate answers on. */
    public interface Handler {
        /** A button of a simple form or a modal form was pressed: the index of its entry, or 0 for yes and 1 for no. */
        void pressed(int index);

        /** The player closed the form (B or the X), or the answer was not valid. */
        void closed();

        /** A custom form was sent; one value per input field, labels left out. */
        void submitted(List<String> values);
    }

    /** The Floodgate side: whether this player is on Bedrock, and the form to put on their screen. */
    public interface Sender {
        boolean isBedrock(UUID id);

        /** Returns false when the form could not be sent, and the Java dialog is used instead. */
        boolean send(UUID id, Form form, Handler handler);
    }

    private static volatile Sender sender;
    private static volatile Executor onServer;

    /** The form each player was last sent; an answer to any other form is stale and ignored. */
    private static final Map<UUID, Handler> CURRENT = new ConcurrentHashMap<>();

    private BedrockForms() {
    }

    public static void install(Sender s) {
        sender = s;
    }

    /** For tests: how an answer reaches the server thread (a test runs it at once). */
    public static void executor(Executor e) {
        onServer = e;
    }

    public static boolean active() {
        return sender != null;
    }

    public static void forget(UUID id) {
        CURRENT.remove(id);
    }

    /** Sends the screen as a native form. Returns false when the player is not on Bedrock or the path is off. */
    static boolean show(ServerPlayer player, BookScreen screen) {
        Sender s = sender;
        if (s == null || !CharterServer.running() || !CharterServer.runtime().knobs.bedrockNativeForms) return false;
        UUID id = player.getUUID();
        try {
            if (!s.isBedrock(id)) return false;
            Handler handler = handler(player, screen);
            CURRENT.put(id, handler);
            if (s.send(id, form(screen), handler)) return true;
            CURRENT.remove(id);
        } catch (RuntimeException e) {
            CURRENT.remove(id);
            CharterMod.LOG.warn("The native form for '{}' could not be sent, so the Java dialog is used: {}", screen.title(), e.toString());
        }
        return false;
    }

    // ---- the screen as a form ----

    static Form form(BookScreen screen) {
        String content = content(screen);
        return switch (screen.kind()) {
            case CONFIRM -> new Form.Modal(screen.title(), content, screen.buttons().get(0).label(), screen.exit().label());
            case FORM -> new Form.Custom(screen.title(), fields(screen, content));
            default -> {
                List<Entry> entries = new ArrayList<>();
                for (BookButton b : screen.buttons()) entries.add(new Entry(b.label(), b.icon()));
                entries.add(new Entry(screen.exit().label(), screen.exit().icon()));
                yield new Form.Simple(screen.title(), content, entries);
            }
        };
    }

    private static String content(BookScreen screen) {
        List<String> lines = new ArrayList<>();
        for (Component line : screen.body()) lines.add(BedrockText.legacy(line));
        return String.join("\n", lines);
    }

    private static List<Field> fields(BookScreen screen, String content) {
        List<Field> out = new ArrayList<>();
        if (!content.isBlank()) out.add(new Field.Label(content));
        for (BookInput in : screen.inputs()) {
            switch (in) {
                case BookInput.Choice c -> out.add(new Field.Dropdown(c.label(), c.shown(), 0));
                case BookInput.Text t -> out.add(new Field.Input(t.label(), t.initial(), t.maxLength()));
                case BookInput.Slider s -> out.add(new Field.Slider(s.label(), s.min(), s.max(), s.step(), s.initial()));
            }
        }
        return out;
    }

    // ---- what a press does ----

    private static Handler handler(ServerPlayer player, BookScreen screen) {
        UUID id = player.getUUID();
        MinecraftServer server = player.level().getServer();
        return new Handler() {
            @Override
            public void pressed(int index) {
                BookButton button;
                if (screen.kind() == BookScreen.Kind.CONFIRM) {
                    button = index == 0 ? screen.buttons().get(0) : screen.exit();
                } else {
                    List<BookButton> all = new ArrayList<>(screen.buttons());
                    all.add(screen.exit());
                    if (index < 0 || index >= all.size()) return;
                    button = all.get(index);
                }
                BookButton chosen = button;
                answer(server, id, this, p -> {
                    if (chosen.run() != null) chosen.run().accept(p);
                });
            }

            @Override
            public void closed() {
                // B and the X go back, never forward: the exit only ever navigates.
                answer(server, id, this, p -> {
                    if (screen.exit().run() != null) screen.exit().run().accept(p);
                });
            }

            @Override
            public void submitted(List<String> values) {
                answer(server, id, this, p -> {
                    if (screen.submit() == null || screen.submit().onForm() == null) return;
                    screen.submit().onForm().accept(new CharterCommands.Form(p, FormValues.join(valuesOf(screen, values))));
                });
            }
        };
    }

    /**
     * The form's answers in the shape pages already read: a dropdown's picked index becomes the id of that entry (an
     * index out of range becomes an empty value, which the page refuses), text and slider values pass through.
     */
    static List<String> valuesOf(BookScreen screen, List<String> raw) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < screen.inputs().size(); i++) {
            String value = i < raw.size() && raw.get(i) != null ? raw.get(i) : "";
            if (screen.inputs().get(i) instanceof BookInput.Choice c) {
                try {
                    int at = Integer.parseInt(value.strip());
                    value = at >= 0 && at < c.ids().size() ? c.ids().get(at) : "";
                } catch (NumberFormatException e) {
                    value = "";
                }
            }
            out.add(value);
        }
        return out;
    }

    /** Runs an answer on the server thread, once, for the player it was sent to, and only if no newer screen replaced it. */
    private static void answer(MinecraftServer server, UUID id, Handler handler, java.util.function.Consumer<ServerPlayer> run) {
        Executor executor = onServer != null ? onServer : server::execute;
        executor.execute(() -> {
            if (!CURRENT.remove(id, handler)) return;
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || !CharterServer.running()) return;
            run.accept(player);
        });
    }
}
