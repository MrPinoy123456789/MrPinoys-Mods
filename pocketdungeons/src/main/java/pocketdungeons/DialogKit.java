package pocketdungeons;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.resources.Identifier;
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
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * The vanilla dialog API (26.2's {@code net.minecraft.server.dialog}), narrowed
 * to the shapes this mod actually uses.
 *
 * <p>Every dialog is a runtime value sent with {@link Holder#direct}: no registry
 * entry, no datapack JSON, and nothing a client has to install. That is the whole
 * reason this mod can have menus at all without breaking {@code docs/VISION.md} section 9's
 * server-only rule -- {@code fabric.mod.json}'s {@code "environment": "server"}
 * and the absent {@code assets/} directory stay exactly as true as before.
 *
 * <p><b>Dialogs are presentation, not a second set of rules.</b> Every button
 * built here ends up calling the same {@code Instances}/{@code RoomWhitelist}
 * method the equivalent {@code /dungeon ...} command calls. No command node was
 * removed to make room for a screen; the chat interface still does everything it
 * did, for console and for anyone who prefers typing.
 *
 * <p>Named {@code DialogKit} and not {@code Dialogs} because vanilla already has
 * a {@code net.minecraft.server.dialog.Dialogs}, and two classes with one name in
 * one file is how import mistakes happen. Shape copied from
 * {@code quizengine.mc.dialog.DialogKit}, which shipped first against this API.
 */
final class DialogKit {

    private DialogKit() {}

    /** Vanilla's own "big button" measure -- wide enough for a player name plus a verb. */
    static final int WIDE = net.minecraft.server.dialog.Dialogs.BIG_BUTTON_WIDTH;

    // ---- sending ----------------------------------------------------------

    /**
     * Pushes a dialog onto a player's screen.
     *
     * <p>Only ever call this as the direct response to something the player just
     * did -- a click, a command they ran, a door they used. A dialog is modal on
     * the client: an unprompted one seizes the screen of somebody who was mining.
     *
     * <p>A dialog does <b>not</b> pause the server. The player's entity keeps
     * ticking while the screen is open -- mobs keep swinging, fall damage still
     * lands. That is accepted here, deliberately: opening one of these mid-fight
     * is the player's own risk, exactly as opening the inventory mid-fight
     * already is in vanilla. The alternative -- refusing the command while the
     * player stands in a live cell -- would be a second rule that can disagree
     * with the command it guards, which is precisely what this class exists not
     * to introduce.
     */
    static void show(ServerPlayer player, Dialog dialog) {
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
    }

    /**
     * A dialog hung off a chat component. The preferred door: the message stays
     * exactly what it was and grows a button, so no screen is seized and the
     * underlying command still works untouched.
     */
    static ClickEvent open(Dialog dialog) {
        return new ClickEvent.ShowDialog(Holder.direct(dialog));
    }

    // ---- building ---------------------------------------------------------

    /**
     * {@code canCloseWithEscape} is always true and {@code pause} always false --
     * the house convention across every sibling mod that has adopted dialogs.
     * Escape is never the <i>only</i> way out, though: every multi-button screen
     * built here also carries a visible exit button, because a first-time player
     * does not necessarily know a dialog can be escaped, and {@code E} does not
     * close a screen that is not a container.
     */
    static CommonDialogData common(String title, List<DialogBody> body, List<Input> inputs) {
        return new CommonDialogData(
                Component.literal(title),
                Optional.empty(),
                true,
                false,
                DialogAction.CLOSE,
                body,
                inputs);
    }

    static CommonDialogData common(String title, List<DialogBody> body) {
        return common(title, body, List.of());
    }

    static DialogBody text(String line) {
        return text(Component.literal(line));
    }

    /** For lines that carry styling the chat version already had -- an affix colour, say. */
    static DialogBody text(Component line) {
        return new PlainMessage(line, PlainMessage.DEFAULT_WIDTH);
    }

    static ActionButton button(String label, String tooltip, Action action) {
        return new ActionButton(
                new CommonButtonData(
                        Component.literal(label),
                        tooltip == null ? Optional.empty() : Optional.of(Component.literal(tooltip)),
                        WIDE),
                Optional.of(action));
    }

    /** A button with no action at all: it closes the screen and does nothing else. */
    static ActionButton closeButton(String label) {
        return new ActionButton(
                new CommonButtonData(Component.literal(label), Optional.empty(), WIDE),
                Optional.empty());
    }

    /**
     * A button that runs a {@code /dungeon ...} command as the clicking player.
     *
     * <p>This is the whole of "tier A": the server already knows every value the
     * button needs when it builds the screen, so the click can be a fixed command
     * string and no round trip through {@link DialogRouter} is needed. The command
     * runs as the player, with the player's own permissions, so a button can never
     * be a privilege escalation -- it is a shortcut for typing, nothing more.
     */
    static ActionButton command(String label, String tooltip, String command) {
        return button(label, tooltip, new StaticAction(new ClickEvent.RunCommand(command)));
    }

    /**
     * A button press that comes back to the server as {@code pocketdungeons:<path>}
     * with a payload, for the cases a fixed command string cannot express -- which
     * entry of a list was clicked, what was typed into a field.
     *
     * <p>{@code context} is merged with every input value into one flat compound,
     * and <b>input keys overwrite context keys on collision</b>, so context keys
     * are named to keep out of their way. {@link DialogRouter} reads the result.
     */
    static Action submit(String path, CompoundTag context) {
        return new CustomAll(
                Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, path),
                Optional.of(context));
    }

    static NoticeDialog notice(String title, List<DialogBody> body, ActionButton action) {
        return new NoticeDialog(common(title, body), action);
    }

    /** A notice whose one button does nothing but close -- a read-only screen. */
    static NoticeDialog notice(String title, List<DialogBody> body) {
        return notice(title, body, closeButton("Close"));
    }

    static ConfirmationDialog confirm(String title, List<DialogBody> body,
                                      ActionButton yes, ActionButton no) {
        return new ConfirmationDialog(common(title, body), yes, no);
    }

    /**
     * A list of buttons plus an explicit exit. One column: these lists are player
     * names and short verbs, and a single column reads as a list rather than as a
     * grid of unrelated choices.
     */
    static MultiActionDialog list(String title, List<DialogBody> body,
                                  List<ActionButton> actions, String exitLabel) {
        return new MultiActionDialog(common(title, body), actions,
                Optional.of(closeButton(exitLabel)), 1);
    }
}
