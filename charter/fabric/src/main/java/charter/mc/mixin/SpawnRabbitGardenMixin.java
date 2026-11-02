package charter.mc.mixin;

import charter.mc.land.SpawnRules;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Spawn rules: rabbits do not raid the spawn's carrots. */
@Mixin(targets = "net.minecraft.world.entity.animal.rabbit.Rabbit$RaidGardenGoal")
public abstract class SpawnRabbitGardenMixin {

    @Shadow @Final private Rabbit rabbit;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void charter$noRaidInSpawn(CallbackInfoReturnable<Boolean> cir) {
        if (SpawnRules.refusesMobGriefing(rabbit)) cir.setReturnValue(false);
    }
}
