package pocketdungeons.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * PD-194: a trial spawner in the dungeon dimension may spawn any mob its config names, wherever the room's
 * floor is. Vanilla runs the mob's natural spawn rules first (a wolf needs grass and light above 8), so a
 * Kennels spawner rolling a wolf in a stone hall retried forever and held the floor gate shut. The spawner has
 * already checked room to stand and a line of sight; the natural rules are skipped for it here, in the
 * dungeon dimension only, so every restricted mob (not just the wolf) can finish a spawner.
 */
@Mixin(SpawnPlacements.class)
public abstract class TrialSpawnPlacementMixin {

    @Inject(method = "checkSpawnRules", at = @At("HEAD"), cancellable = true)
    private static <T extends net.minecraft.world.entity.Entity> void pocketdungeons$trialSpawnersIgnoreNaturalRules(
            EntityType<T> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos,
            RandomSource random, CallbackInfoReturnable<Boolean> cir) {
        if (reason == EntitySpawnReason.TRIAL_SPAWNER
                && pocketdungeons.DungeonWorldRules.applies(level.getLevel())) {
            cir.setReturnValue(true);
        }
    }
}
