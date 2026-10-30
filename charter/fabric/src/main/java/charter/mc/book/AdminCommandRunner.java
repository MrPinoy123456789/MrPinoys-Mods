package charter.mc.book;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs an admin command exactly as if the operator had typed it, and keeps what it said so the menu can show it.
 * The menu owns no rules: permission, validation, the siege log line and the effect all come from the command.
 */
final class AdminCommandRunner {

    /** Whether the command did something, and every line it printed. */
    record Result(boolean ok, List<Component> lines) {
    }

    private AdminCommandRunner() {
    }

    /** Runs {@code command} (no leading slash) with the player's own position, permissions and name. */
    static Result run(ServerPlayer player, String command) {
        List<Component> lines = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                lines.add(message);
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack source = player.createCommandSourceStack().withSource(sink);
        try {
            int result = source.getServer().getCommands().getDispatcher().execute(command, source);
            return new Result(result > 0, lines);
        } catch (CommandSyntaxException e) {
            lines.add(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
            return new Result(false, lines);
        }
    }
}
