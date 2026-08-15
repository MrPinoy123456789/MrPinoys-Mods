package tpasserver.mixin;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.regex.Pattern;

/**
 * Lets any player use /tp and /teleport, regardless of their own permission
 * level, by re-running the command with the server's own permission set.
 *
 * <p>The reference-equality check against {@link PermissionSet#ALL_PERMISSIONS}
 * doubles as the recursion guard: {@link Commands#performPrefixedCommand} loops
 * back through {@link Commands#performCommand}, and without it the re-dispatch
 * below would trigger itself forever.
 */
@Mixin(Commands.class)
public class CommandsMixin {

    private static final Pattern TP_COMMAND = Pattern.compile("^(tp|teleport)(\\s|$)");

    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void tpasserver$runAsServer(ParseResults<CommandSourceStack> parseResults, String commandString, CallbackInfo ci) {
        CommandSourceStack source = parseResults.getContext().getSource();
        if (source.permissions() == PermissionSet.ALL_PERMISSIONS) {
            return; // already elevated -- this is our own re-dispatch
        }

        Entity entity = source.getEntity();
        if (!(entity instanceof Player)) {
            return; // console, command blocks, etc. already run with full permissions
        }

        if (!TP_COMMAND.matcher(commandString.strip()).find()) {
            return;
        }

        ci.cancel();
        CommandSourceStack elevated = source.withPermission(PermissionSet.ALL_PERMISSIONS);
        ((Commands) (Object) this).performPrefixedCommand(elevated, commandString);
    }
}
