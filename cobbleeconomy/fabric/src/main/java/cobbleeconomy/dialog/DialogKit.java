package cobbleeconomy.dialog;

import cobbleeconomy.CobbleEconomyMod;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * The vanilla dialog API, narrowed to the shapes this mod uses.
 *
 * <p>Dialogs are built from live state and sent with {@link Holder#direct}, so nothing
 * here is registered and there is no datapack JSON anywhere in the mod. A dialog is a
 * value, constructed per player per click and thrown away afterwards. Ported from
 * {@code quizengine.mc.dialog.DialogKit}, which proved this shape against 26.2 first --
 * see {@code DIALOGS_SPEC.md} Part 1.
 */
public final class DialogKit {

    private DialogKit() {}

    public static final int WIDE = net.minecraft.server.dialog.Dialogs.BIG_BUTTON_WIDTH;

    // ---- sending ------------------------------------------------------------

    /** Only ever call this in direct response to something the player clicked. */
    public static void show(ServerPlayer player, Dialog dialog) {
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
    }

    public static void clear(ServerPlayer player) {
        player.connection.send(ClientboundClearDialogPacket.INSTANCE);
    }

    public static ClickEvent open(Dialog dialog) {
        return new ClickEvent.ShowDialog(Holder.direct(dialog));
    }

    // ---- building -------------------------------------------------------------

    public static CommonDialogData common(String title, List<DialogBody> body, List<Input> inputs) {
        return new CommonDialogData(
                Component.literal(title),
                Optional.empty(),
                true,   // escape always backs out
                false,  // pause is meaningless on a dedicated server
                DialogAction.CLOSE,
                body,
                inputs);
    }

    public static DialogBody text(String line) {
        return new PlainMessage(Component.literal(line), PlainMessage.DEFAULT_WIDTH);
    }

    public static ActionButton button(String label, String tooltip, Action action) {
        return new ActionButton(
                new CommonButtonData(
                        Component.literal(label),
                        tooltip == null ? Optional.empty() : Optional.of(Component.literal(tooltip)),
                        WIDE),
                Optional.of(action));
    }

    /**
     * A button press that comes straight back to the server as
     * {@code cobbleeconomy:<path>}, with no command in between. {@code context} is
     * merged with every input value into one flat compound -- input keys win on a
     * collision, so name context keys to avoid clashing with input keys.
     */
    public static Action submit(String path, CompoundTag context) {
        return new CustomAll(
                Identifier.fromNamespaceAndPath(CobbleEconomyMod.MOD_ID, path),
                Optional.of(context));
    }
}
