package charter.mc.justice;

import charter.id.PlayerId;
import charter.mc.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The hook behind Mixin 11, {@code Commands.performCommand}. A player who is in
 * custody or held by a sentence may run {@code /charter} (the sentence pages, a
 * safe deposit) and nothing else. Operators' console and command blocks have no
 * player, so they pass.
 */
public final class CommandGate {

    private CommandGate() {
    }

    /** True if this command must not run; the player is told why. */
    public static boolean blocked(CommandSourceStack source, String command) {
        ServerPlayer player = source.getPlayer();
        if (player == null || !charter.mc.CharterServer.running()) return false;
        if (CommandRule.allowed(command)) return false;
        if (!Jail.held(new PlayerId(player.getUUID()))) return false;
        player.sendSystemMessage(Component.literal(Text.get("jail.no_commands")).withStyle(ChatFormatting.RED));
        return true;
    }
}
