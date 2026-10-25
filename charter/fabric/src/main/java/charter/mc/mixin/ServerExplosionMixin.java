package charter.mc.mixin;

import charter.mc.land.Hazards;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin 2: explosions. They fire no break event, so the list of blocks an
 * explosion will destroy is filtered on its way out: protected land and the mod's
 * objects are removed, unless a permitted player lit it. Entity damage is untouched.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Entity source;

    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"), cancellable = true)
    private void charter$spareClaims(CallbackInfoReturnable<List<BlockPos>> cir) {
        cir.setReturnValue(Hazards.explodable(level, source, cir.getReturnValue()));
    }
}
