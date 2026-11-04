package foundry.mixin;

import foundry.mc.DoubleJump;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Fae's wing height is no mace smash: as a player's attack starts, before vanilla reads the fall for the smash, a
 * Fae holding a mace has the fall cut to the drop below where their wings first climbed this time in the air
 * (DoubleJump.creditFall). Server side; every other player and weapon is untouched.
 */
@Mixin(Player.class)
abstract class PlayerMaceDive {
    @Inject(method = "attack", at = @At("HEAD"))
    private void foundry$wingHeightIsNoSmash(Entity target, CallbackInfo ci) {
        if ((Object) this instanceof net.minecraft.server.level.ServerPlayer player) DoubleJump.creditFall(player);
    }
}
