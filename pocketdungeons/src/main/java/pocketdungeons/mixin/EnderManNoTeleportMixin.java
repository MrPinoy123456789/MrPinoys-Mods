package pocketdungeons.mixin;

import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pocketdungeons.DungeonWorldRules;

/**
 * An enderman in the dungeon dimension never teleports (owner, 2026-10-31). A teleported enderman can land on top of the
 * dungeon or outside it, out of every player's reach, and its trial spawner then waits for it forever. All three of its
 * teleports (the random one, the one towards a target and the one on being hit) end in this method, so cancelling here
 * covers them. Outside the dungeon dimension the vanilla behaviour is untouched.
 */
@Mixin(EnderMan.class)
public class EnderManNoTeleportMixin {

    @Inject(method = "teleport(DDD)Z", at = @At("HEAD"), cancellable = true)
    private void pocketdungeons_noTeleportInDungeon(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        EnderMan self = (EnderMan) (Object) this;
        if (DungeonWorldRules.applies(self.level())) {
            cir.setReturnValue(false);
        }
    }
}
