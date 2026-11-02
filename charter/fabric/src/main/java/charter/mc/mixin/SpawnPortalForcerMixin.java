package charter.mc.mixin;

import charter.mc.land.SpawnRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Spawn rules: walking through a nether portal in the Nether never builds a new portal frame inside the spawn. A portal
 * that already stands there is still found and used; with none, the trip does not happen.
 */
@Mixin(PortalForcer.class)
public abstract class SpawnPortalForcerMixin {

    @Shadow @Final private ServerLevel level;

    @Inject(method = "createPortal", at = @At("HEAD"), cancellable = true)
    private void charter$noPortalInSpawn(BlockPos pos, Direction.Axis axis, CallbackInfoReturnable<Optional<BlockUtil.FoundRectangle>> cir) {
        if (SpawnRules.refusesPortal(level, pos)) cir.setReturnValue(Optional.empty());
    }
}
