package foundry.mixin;

import foundry.mc.RaceTraitsRuntime;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A race's lighter landing (the Fae): the fall damage multiplier is cut by the race's {@code fall_damage} share before
 * vanilla works out the damage, server side, so Bedrock players land as lightly. A double jump resets the fall on its
 * own (DoubleJump), so the two do not stack into anything odd: the cut applies to whatever fall is left. The Fairies'
 * no fall damage is Charter's, separate, and untouched.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityFall {
    @ModifyVariable(method = "causeFallDamage", at = @At("HEAD"), argsOnly = true)
    private float foundry$lighterLanding(float multiplier) {
        LivingEntity self = (LivingEntity) (Object) this;
        return self instanceof net.minecraft.server.level.ServerPlayer player ? RaceTraitsRuntime.landing(player, multiplier) : multiplier;
    }
}
