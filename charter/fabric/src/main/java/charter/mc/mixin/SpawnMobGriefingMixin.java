package charter.mc.mixin;

import charter.mc.land.SpawnRules;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Spawn rules: sheep (and any mob with the goal) do not eat the spawn's grass. */
@Mixin(EatBlockGoal.class)
public abstract class SpawnMobGriefingMixin {

    @Shadow @Final private Mob mob;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void charter$noGrazingInSpawn(CallbackInfoReturnable<Boolean> cir) {
        if (SpawnRules.refusesMobGriefing(mob)) cir.setReturnValue(false);
    }
}
