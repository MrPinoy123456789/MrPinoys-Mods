package charter.mc.book;

import charter.mc.cmd.CharterCommands;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import charter.mc.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CommandTemplate;
import net.minecraft.server.dialog.action.ParsedTemplate;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The vanilla dialog API (26.2's {@code net.minecraft.server.dialog}), narrowed to
 * the shapes the Charter book uses. Shapes copied from Pocket Dungeons'
 * {@code DialogKit} (a reference, never a dependency).
 *
 * <p>Every dialog is a runtime value sent with {@link Holder#direct}; nothing a
 * client has to install. Every button that does something is built by
 * {@link #act}, which binds a one-shot token to the player the screen was built for.
 */
public final class BookDialogs {

    static final int WIDE = net.minecraft.server.dialog.Dialogs.BIG_BUTTON_WIDTH;

    /** The last dialog sent to each player, for gametests (there is no client to look at). Cleared on leave. */
    public static final Map<UUID, Dialog> LAST = new ConcurrentHashMap<>();

    private BookDialogs() {
    }

    /** Pushes a dialog onto a player's screen. Only as the direct answer to something the player did. */
    static void show(ServerPlayer player, Dialog dialog) {
        LAST.put(player.getUUID(), dialog);
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
    }

    static CommonDialogData common(String title, List<DialogBody> body) {
        return new CommonDialogData(Component.literal(title), Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());
    }

    static DialogBody text(String line) {
        return text(Component.literal(line));
    }

    static DialogBody text(Component line) {
        return new PlainMessage(line, PlainMessage.DEFAULT_WIDTH);
    }

    static ActionButton button(String label, String tooltip, Action action) {
        return new ActionButton(new CommonButtonData(Component.literal(label),
                tooltip == null ? Optional.empty() : Optional.of(Component.literal(tooltip)), WIDE),
                Optional.of(action));
    }

    /** A button with no action: it closes the screen and does nothing else. */
    static ActionButton closeButton(String label) {
        return new ActionButton(new CommonButtonData(Component.literal(label), Optional.empty(), WIDE), Optional.empty());
    }

    /** A button whose click runs a one-shot token bound to this player (C1). */
    static ActionButton act(ServerPlayer player, String label, String tooltip, Consumer<ServerPlayer> run) {
        String token = CharterCommands.issue(player, label, run);
        return button(label, tooltip, new StaticAction(new ClickEvent.RunCommand("/charter act " + token)));
    }

    // ---- forms: inputs the player fills in, sent back through /charter form ----

    /** A one-line text box. */
    static Input textInput(String key, String label, String initial, int maxLength) {
        return new Input(key, new TextInput(WIDE, Component.literal(label), true, initial, maxLength, Optional.empty()));
    }

    /** A dropdown; the value sent back is the id. The first entry starts picked. At least one entry is needed. */
    static Input choice(String key, String label, List<String> ids, List<String> shown) {
        List<SingleOptionInput.Entry> entries = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            entries.add(new SingleOptionInput.Entry(ids.get(i), Optional.of(Component.literal(shown.get(i))), i == 0));
        }
        return new Input(key, new SingleOptionInput(WIDE, entries, Component.literal(label), true));
    }

    /** A screen with inputs and one button that sends them, and an exit that Esc also runs. */
    static Dialog form(String title, List<DialogBody> body, List<Input> inputs, ActionButton submit, ActionButton exit) {
        if (exit == null) throw new IllegalArgumentException("a Charter dialog needs an exit button: " + title);
        CommonDialogData data = new CommonDialogData(Component.literal(title), Optional.empty(), true, false,
                DialogAction.CLOSE, body, inputs);
        return new MultiActionDialog(data, List.of(submit), Optional.of(exit), 1);
    }

    /**
     * The button that sends a form's inputs back. It runs {@code /charter form <token>} with every input's value;
     * the token binds it to this player and to {@code run}, which must check the values again.
     */
    static ActionButton submit(ServerPlayer player, String label, String tooltip, List<Input> inputs,
                               Consumer<CharterCommands.Form> run) {
        String token = CharterCommands.issueForm(player, label, run);
        String command = FormValues.template(token, inputs.stream().map(Input::key).toList());
        ParsedTemplate template = ParsedTemplate.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(command))
                .getOrThrow(IllegalArgumentException::new);
        return button(label, tooltip, new CommandTemplate(template));
    }

    static NoticeDialog notice(String title, List<DialogBody> body, ActionButton exit) {
        return new NoticeDialog(common(title, body), exit);
    }

    static ConfirmationDialog confirm(String title, List<DialogBody> body, ActionButton yes, ActionButton no) {
        return new ConfirmationDialog(common(title, body), yes, no);
    }

    /**
     * A list of buttons in one column, with an explicit exit that Esc also runs. A
     * dialog is never sent without a button: with no actions the encoder cannot send
     * a list, so the screen becomes a notice that says there is nothing here and
     * keeps its exit (the Pocket Dungeons dialog-screens rule 1).
     */
    static Dialog list(String title, List<DialogBody> body, List<ActionButton> actions, ActionButton exit) {
        if (exit == null) throw new IllegalArgumentException("a Charter dialog needs an exit button: " + title);
        if (actions.isEmpty()) {
            List<DialogBody> lines = new ArrayList<>(body);
            if (body.isEmpty()) lines.add(text(Text.get("book.nothing_here")));
            return notice(title, lines, exit);
        }
        return new MultiActionDialog(common(title, body), actions, Optional.of(exit), 1);
    }

    /** A Back button: runs a screen rebuilt from live state, never a stored one. */
    static ActionButton back(ServerPlayer player, Consumer<ServerPlayer> parent) {
        return act(player, Text.get("book.button.back"), null, parent);
    }

    static ActionButton close() {
        return closeButton(Text.get("book.button.close"));
    }

    /** A line shown at the top of a page: the result of the last press, in red for a refusal. */
    static List<DialogBody> withNotice(String notice, List<DialogBody> body) {
        if (notice == null || notice.isBlank()) return body;
        List<DialogBody> out = new ArrayList<>();
        out.add(text(Component.literal(notice).withStyle(ChatFormatting.RED)));
        out.addAll(body);
        return out;
    }

    /** Every button of a dialog, exit included, for tests. */
    public static List<ActionButton> buttons(Dialog dialog) {
        List<ActionButton> out = new ArrayList<>();
        switch (dialog) {
            case MultiActionDialog m -> {
                out.addAll(m.actions());
                m.exitAction().ifPresent(out::add);
            }
            case NoticeDialog n -> out.add(n.action());
            case ConfirmationDialog c -> {
                out.add(c.yesButton());
                out.add(c.noButton());
            }
            default -> {
            }
        }
        return out;
    }
}
