package charter.mc.book;

import charter.mc.cmd.CharterCommands;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
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
import net.minecraft.server.dialog.action.CommandTemplate;
import net.minecraft.server.dialog.action.ParsedTemplate;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Draws a {@link BookScreen} as a vanilla dialog (26.2's {@code net.minecraft.server.dialog}): what Java players see,
 * and what Bedrock players see through Geyser when no native form is available. Shapes copied from Pocket Dungeons'
 * {@code DialogKit} (a reference, never a dependency).
 *
 * <p>Every dialog is a runtime value sent with {@link Holder#direct}; nothing a client has to install. Every button that
 * does something binds a one-shot token to the player the screen is shown to ({@link CharterCommands#issue}), so a
 * stale or borrowed button does nothing.
 */
final class JavaDialogRenderer {

    static final int WIDE = net.minecraft.server.dialog.Dialogs.BIG_BUTTON_WIDTH;

    private JavaDialogRenderer() {
    }

    static void show(ServerPlayer player, BookScreen screen) {
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(render(player, screen))));
    }

    /** The dialog for a screen. It issues the tokens its buttons run. */
    static Dialog render(ServerPlayer player, BookScreen screen) {
        List<DialogBody> body = new ArrayList<>();
        for (Component line : screen.body()) body.add(new PlainMessage(line, PlainMessage.DEFAULT_WIDTH));
        CommonDialogData data = new CommonDialogData(Component.literal(screen.title()), Optional.empty(), true, false,
                DialogAction.CLOSE, body, inputs(screen));
        ActionButton exit = button(player, screen.exit());
        return switch (screen.kind()) {
            case NOTICE -> new NoticeDialog(data, exit);
            case CONFIRM -> new ConfirmationDialog(data, button(player, screen.buttons().get(0)), exit);
            case LIST -> new MultiActionDialog(data, buttons(player, screen), Optional.of(exit), 1);
            case FORM -> new MultiActionDialog(data, List.of(submit(player, screen)), Optional.of(exit), 1);
        };
    }

    private static List<ActionButton> buttons(ServerPlayer player, BookScreen screen) {
        List<ActionButton> out = new ArrayList<>();
        for (BookButton b : screen.buttons()) out.add(button(player, b));
        return out;
    }

    private static CommonButtonData common(BookButton b) {
        return new CommonButtonData(Component.literal(b.label()),
                b.tooltip() == null ? Optional.empty() : Optional.of(Component.literal(b.tooltip())), WIDE);
    }

    private static ActionButton button(ServerPlayer player, BookButton b) {
        if (b.closes()) return new ActionButton(common(b), Optional.empty());
        String token = CharterCommands.issue(player, b.label(), b.run());
        return new ActionButton(common(b), Optional.of(new StaticAction(new ClickEvent.RunCommand("/charter act " + token))));
    }

    /** The button that sends a form's inputs back: {@code /charter form <token> =<a> | <b>}, one template. */
    private static ActionButton submit(ServerPlayer player, BookScreen screen) {
        BookButton b = screen.submit();
        String token = CharterCommands.issueForm(player, b.label(), b.onForm());
        String command = FormValues.template(token, screen.inputs().stream().map(BookInput::key).toList());
        ParsedTemplate template = ParsedTemplate.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(command))
                .getOrThrow(IllegalArgumentException::new);
        return new ActionButton(common(b), Optional.of(new CommandTemplate(template)));
    }

    private static List<Input> inputs(BookScreen screen) {
        List<Input> out = new ArrayList<>();
        for (BookInput in : screen.inputs()) {
            switch (in) {
                case BookInput.Text t -> out.add(new Input(t.key(), new TextInput(WIDE, Component.literal(t.label()), true,
                        t.initial(), t.maxLength(), Optional.empty())));
                case BookInput.Choice c -> {
                    List<SingleOptionInput.Entry> entries = new ArrayList<>();
                    for (int i = 0; i < c.ids().size(); i++) {
                        entries.add(new SingleOptionInput.Entry(c.ids().get(i), Optional.of(Component.literal(c.shown().get(i))), i == 0));
                    }
                    out.add(new Input(c.key(), new SingleOptionInput(WIDE, entries, Component.literal(c.label()), true)));
                }
            }
        }
        return out;
    }
}
