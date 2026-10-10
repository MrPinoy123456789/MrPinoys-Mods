package pocketdungeons.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.warden.SonicBoom;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Warden whelp (see {@code Whelp}) fights in melee only: it never starts a sonic boom. */
@Mixin(SonicBoom.class)
public class SonicBoomWhelpMixin {

    @Inject(method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/warden/Warden;)Z",
            at = @At("HEAD"), cancellable = true)
    private void pocketdungeons_noWhelpBoom(ServerLevel level, Warden warden, CallbackInfoReturnable<Boolean> cir) {
        if (warden.entityTags().contains(pocketdungeons.PocketDungeonsMod.WHELP_TAG)) {
            cir.setReturnValue(false);
        }
    }
}
