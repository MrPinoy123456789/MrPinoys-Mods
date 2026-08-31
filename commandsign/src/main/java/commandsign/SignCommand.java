package commandsign;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/**
 * The two operations on a command sign: apply (stamp the text and flag) and
 * run (execute the stored command as the server).
 *
 * <p>"As the server" means with {@link PermissionSet#ALL_PERMISSIONS}, the
 * same pattern {@code tpasserver} uses for /tp. The command source carries the
 * clicking player's name and position, so {@code @s} and selectors resolve
 * against the right player, but the permission level is the server's own.
 */
final class SignCommand {

    private SignCommand() {}

    /** The line index that carries "Right-Click". */
    private static final int LINE_LABEL = 0;
    /** The line index that carries the command. */
    private static final int LINE_COMMAND = 1;

    private static final Component RIGHT_CLICK_LABEL = Component.literal("Right-Click")
            .withStyle(ChatFormatting.LIGHT_PURPLE);

    /**
     * Stamps or removes the command sign.
     *
     * <p>When enabling: writes "Right-Click" on line 1 and the command on
     * line 2, on the face the op is looking at, and sets the hidden flag.
     * When disabling: clears the flag only; the text is left for the op to
     * edit as an ordinary sign.
     */
    static void apply(SignBlockEntity sign, ServerLevel level, ServerPlayer op,
                      boolean enabled, String command) {
        ((CommandSignAccess) sign).commandsign$setCommandSign(enabled);

        if (enabled) {
            boolean front = sign.isFacingFrontText(op);
            SignText text = sign.getText(front);
            text = text.setMessage(LINE_LABEL, RIGHT_CLICK_LABEL);
            text = text.setMessage(LINE_COMMAND, Component.literal(command));
            sign.setText(text, front);

            BlockPos pos = sign.getBlockPos();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        }
    }

    /**
     * Reads the command off the sign. The command lives on line 2 of the
     * front face by default, falling back to the back face.
     */
    static String commandFrom(SignBlockEntity sign) {
        String front = sign.getFrontText().getMessage(LINE_COMMAND, false).getString();
        if (!front.isEmpty()) {
            return front;
        }
        return sign.getBackText().getMessage(LINE_COMMAND, false).getString();
    }

    /**
     * Runs the stored command as the server on behalf of the clicking player.
     *
     * <p>The command source carries the player's name, position, and rotation,
     * so {@code @s} resolves to them and position-relative commands work, but
     * the permission set is {@link PermissionSet#ALL_PERMISSIONS}, so the
     * command runs with full server permissions regardless of the player's
     * own level. This is the {@code tpasserver} pattern.
     */
    static void run(SignBlockEntity sign, Level level, ServerPlayer player) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        String command = commandFrom(sign);
        if (command.isEmpty()) {
            return;
        }

        // Strip a leading slash if present; performPrefixedCommand does not
        // expect one.
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        CommandSourceStack source = new CommandSourceStack(
                CommandSource.NULL,
                player.position(),
                player.getRotationVector(),
                serverLevel,
                PermissionSet.ALL_PERMISSIONS,
                player.getPlainTextName(),
                player.getDisplayName(),
                serverLevel.getServer(),
                player);

        Commands commands = serverLevel.getServer().getCommands();
        commands.performPrefixedCommand(source, command);
    }
}
