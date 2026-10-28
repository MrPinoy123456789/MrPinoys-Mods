package charter.mc.book;

import charter.mc.cmd.CharterCommands;
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
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

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

    static NoticeDialog notice(String title, List<DialogBody> body) {
        return new NoticeDialog(common(title, body), closeButton("Close"));
    }

    static ConfirmationDialog confirm(String title, List<DialogBody> body, ActionButton yes, ActionButton no) {
        return new ConfirmationDialog(common(title, body), yes, no);
    }

    /** A list of buttons in one column, with an explicit exit. */
    static MultiActionDialog list(String title, List<DialogBody> body, List<ActionButton> actions, ActionButton exit) {
        return new MultiActionDialog(common(title, body), actions, Optional.of(exit), 1);
    }

    /** B0 only: a list with one "Hello" button whose run sends a chat line. */
    static void spike(ServerPlayer player) {
        ActionButton hello = act(player, "Hello", "Say hello",
                p -> p.sendSystemMessage(Component.literal("Hello from the Charter book.")));
        show(player, list("Charter spike", List.of(text("Press the button.")), List.of(hello), closeButton("Close")));
    }
}
