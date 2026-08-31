package commandsign.mixin;

import commandsign.CommandSignAccess;
import commandsign.CommandSignMod;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persists the hidden "command sign" flag in the sign's own block-entity NBT.
 *
 * <p>Without this, a non-op could type "Right-Click" and a command on a sign
 * and it would look identical to a real command sign. The flag is the only
 * thing that distinguishes a marked sign from an ordinary one, so it must
 * survive restarts. {@code saveAdditional}/{@code loadAdditional} are 26.2's
 * block-entity persistence hooks (the old {@code saveAdditional}/
 * {@code load} NBT pair was replaced by {@link ValueOutput}/{@link ValueInput}).
 *
 * <p>The flag is never sent to the client: {@code getUpdateTag} calls
 * {@code saveCustomOnly} which calls {@code saveAdditional}, so the flag does
 * ride the update packet. That is harmless; a vanilla client ignores unknown
 * keys, and the flag's only consumer is this mod's own interaction handler.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin implements CommandSignAccess {

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void commandsign$saveFlag(ValueOutput output, CallbackInfo ci) {
        output.putBoolean(CommandSignMod.NBT_KEY, this.commandsign$isCommandSign);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void commandsign$loadFlag(ValueInput input, CallbackInfo ci) {
        this.commandsign$isCommandSign = input.getBooleanOr(CommandSignMod.NBT_KEY, false);
    }

    // The field lives on the mixin so vanilla's SignBlockEntity never grows a
    // public field. Accessed through the accessors below.
    private boolean commandsign$isCommandSign = false;

    public boolean commandsign$isCommandSign() {
        return this.commandsign$isCommandSign;
    }

    public void commandsign$setCommandSign(boolean value) {
        this.commandsign$isCommandSign = value;
        ((SignBlockEntity) (Object) this).setChanged();
    }
}
