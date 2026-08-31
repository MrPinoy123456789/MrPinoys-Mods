package commandsign;

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
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * The vanilla dialog API (26.2's {@code net.minecraft.server.dialog}), narrowed
 * to the shapes this mod uses.
 *
 * <p>Every dialog is a runtime value sent with {@link Holder#direct}: no
 * registry entry, no datapack JSON, nothing a client has to install. That is
 * why this mod stays server-side only. Shape copied from
 * {@code pocketdungeons.DialogKit}, which shipped first against this API.
 */
final class DialogKit {

    private DialogKit() {}

    static final int WIDE = net.minecraft.server.dialog.Dialogs.BIG_BUTTON_WIDTH;

    static void show(ServerPlayer player, Dialog dialog) {
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
    }

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

    static DialogBody text(String line) {
        return new PlainMessage(Component.literal(line), PlainMessage.DEFAULT_WIDTH);
    }

    static ActionButton button(String label, String tooltip, net.minecraft.server.dialog.action.Action action) {
        return new ActionButton(
                new CommonButtonData(
                        Component.literal(label),
                        tooltip == null ? Optional.empty() : Optional.of(Component.literal(tooltip)),
                        WIDE),
                Optional.of(action));
    }

    static ActionButton closeButton(String label) {
        return new ActionButton(
                new CommonButtonData(Component.literal(label), Optional.empty(), WIDE),
                Optional.empty());
    }

    /**
     * A button press that comes back to the server as
     * {@code commandsign:<path>} with a payload, for the cases a fixed command
     * string cannot express: what was typed into the command field, whether the
     * toggle was on.
     *
     * <p>Input values overwrite context keys on collision, so context keys are
     * prefixed to keep out of the input's way. {@link DialogRouter} reads the
     * result.
     */
    static net.minecraft.server.dialog.action.Action submit(String path, CompoundTag context) {
        return new CustomAll(
                Identifier.fromNamespaceAndPath(CommandSignMod.MOD_ID, path),
                Optional.of(context));
    }

    static ConfirmationDialog confirm(String title, List<DialogBody> body,
                                      List<Input> inputs,
                                      ActionButton yes, ActionButton no) {
        return new ConfirmationDialog(common(title, body, inputs), yes, no);
    }
}
