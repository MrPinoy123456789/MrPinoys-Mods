package pocketdungeons.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pocketdungeons.SculkHearing;

/**
 * The calibrated sensor overrides {@code canReceiveVibration} itself, so the
 * base filter ({@link SculkSensorPlayersOnlyMixin}) never runs for it. Same
 * rule: in the dungeon, only players (and their projectiles) are heard.
 */
@Mixin(targets = "net.minecraft.world.level.block.entity.CalibratedSculkSensorBlockEntity$VibrationUser")
public abstract class CalibratedSculkSensorPlayersOnlyMixin {

    @Inject(method = "canReceiveVibration", at = @At("HEAD"), cancellable = true)
    private void pocketdungeons$onlyPlayersAreHeard(ServerLevel level, BlockPos pos,
                                                    Holder<GameEvent> event, GameEvent.Context context,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (SculkHearing.ignores(level, context)) {
            cir.setReturnValue(false);
        }
    }
}
