package pocketdungeons.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses all death loot (items and experience) from mobs spawned by an omen
 * rise. These waves are meant to add danger, not free loot.
 */
@Mixin(LivingEntity.class)
public class OmenWaveNoDropsMixin {

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void pocketdungeons_cancelOmenWaveDrops(ServerLevel level, DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        // A haunt body is a rule to learn, not a mob to farm: it pays nothing either.
        if (self.entityTags().contains(pocketdungeons.PocketDungeonsMod.OMEN_WAVE_TAG)
                || self.entityTags().contains(pocketdungeons.PocketDungeonsMod.HAUNT_TAG)) {
            ci.cancel();
        }
    }
}
