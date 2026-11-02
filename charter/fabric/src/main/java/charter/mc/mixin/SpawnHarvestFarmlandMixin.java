package charter.mc.mixin;

import charter.mc.land.SpawnRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Spawn rules: farmer villagers do not harvest, replant or trample the spawn's crops. */
@Mixin(HarvestFarmland.class)
public abstract class SpawnHarvestFarmlandMixin {

    @Inject(method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;)Z",
            at = @At("HEAD"), cancellable = true)
    private void charter$noFarmingInSpawn(ServerLevel level, Villager villager, CallbackInfoReturnable<Boolean> cir) {
        if (SpawnRules.refusesMobGriefing(villager)) cir.setReturnValue(false);
    }
}
