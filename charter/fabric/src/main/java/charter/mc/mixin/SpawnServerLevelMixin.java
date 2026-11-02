package charter.mc.mixin;

import charter.mc.land.SpawnRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spawn rules: no hostile mob is added inside the spawn, whatever brought it (natural spawning, spawners, patrols, raids,
 * phantoms; every fresh entity passes through {@code addEntity}), and the weather piles no snow and freezes no water there.
 * Fabric API has no event that can refuse either.
 */
@Mixin(ServerLevel.class)
public abstract class SpawnServerLevelMixin {

    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void charter$noHostilesInSpawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (SpawnRules.refusesHostile((ServerLevel) (Object) this, entity)) cir.setReturnValue(false);
    }

    @Inject(method = "tickPrecipitation", at = @At("HEAD"), cancellable = true)
    private void charter$noWeatherInSpawn(BlockPos pos, CallbackInfo ci) {
        if (SpawnRules.refusesWeather((ServerLevel) (Object) this, pos)) ci.cancel();
    }
}
