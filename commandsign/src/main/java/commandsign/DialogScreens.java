package commandsign;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * The one screen this mod has: a command string, a "Command Sign?" toggle,
 * and a Save button.
 *
 * <p>Context keys are prefixed {@code cs_} so the dialog's input keys
 * ({@code command}, {@code enabled}) cannot collide with them. The sign's
 * position and the clicking player's UUID ride in the context; the input
 * values overwrite on collision, which is why they have different names.
 */
final class DialogScreens {

    private DialogScreens() {}

    static final String KEY_OWNER = "cs_owner";
    static final String KEY_DIM = "cs_dim";
    static final String KEY_X = "cs_x";
    static final String KEY_Y = "cs_y";
    static final String KEY_Z = "cs_z";

    /** The one key the player's command typing lands under. */
    static final String KEY_COMMAND = "command";
    /** The one key the toggle's value lands under. */
    static final String KEY_ENABLED = "enabled";

    static final String ACTION_SAVE = "save";

    /**
     * The setup/edit dialog for a sign.
     *
     * @param player   the op who shift-right-clicked
     * @param dim      the sign's dimension id, e.g. "minecraft:overworld"
     * @param x,y,z    the sign's block position
     * @param currentCommand  the command already on the sign, or "" if none
     * @param currentEnabled  whether the sign is already a command sign
     */
    static Dialog setup(ServerPlayer player, String dim, int x, int y, int z,
                        String currentCommand, boolean currentEnabled) {
        CompoundTag context = new CompoundTag();
        context.putString(KEY_OWNER, player.getUUID().toString());
        context.putString(KEY_DIM, dim);
        context.putInt(KEY_X, x);
        context.putInt(KEY_Y, y);
        context.putInt(KEY_Z, z);

        Input command = new Input(KEY_COMMAND, new TextInput(
                DialogKit.WIDE, Component.literal("Command"), true,
                currentCommand, 256, Optional.empty()));
        Input enabled = new Input(KEY_ENABLED, new BooleanInput(
                Component.literal("Command Sign?"), currentEnabled, "true", "false"));

        return DialogKit.confirm(
                "Command Sign",
                List.of(DialogKit.text("Type the command the sign should run."),
                        DialogKit.text("Toggle on to make it a command sign.")),
                List.of(command, enabled),
                DialogKit.button("Save", null, DialogKit.submit(ACTION_SAVE, context)),
                DialogKit.closeButton("Cancel"));
    }

}
