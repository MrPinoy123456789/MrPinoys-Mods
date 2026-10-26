package charter.mc.mixin;

import charter.mc.justice.CommandGate;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 11: commands run by a held player (PLAN.md §7.4, DESIGN.md §15.4: "teleports,
 * pearls and commands are blocked"). Every command a player types reaches
 * {@code Commands.performCommand}; no Fabric API event sees all of them, so the
 * Mixin asks {@link CommandGate} first. Only {@code /charter} passes for a prisoner.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void charter$gate(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
        if (CommandGate.blocked(parse.getContext().getSource(), command)) ci.cancel();
    }
}
