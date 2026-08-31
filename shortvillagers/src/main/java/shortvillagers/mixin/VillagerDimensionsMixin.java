package shortvillagers.mixin;

import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import shortvillagers.ShortVillagersConfig;

/**
 * The entire mod. Intercepts {@link EntityType#getDimensions()} at RETURN and,
 * when the entity type is the villager type, replaces the returned
 * {@link EntityDimensions} with one whose height is shrunk to
 * {@link ShortVillagersConfig#targetHeight()} (default 1.0 for testing; will
 * be 1.9 for production). Width, attachments, and the fixed flag are preserved
 * or recomputed; eye height is scaled proportionally so the villager's gaze
 * point stays at the same relative position.
 *
 * <p>Targeting {@code EntityType.getDimensions()} rather than
 * {@code Entity.getDimensions(Pose)} is deliberate. The Entity constructor
 * sets its {@code dimensions} field by calling {@code this.type.getDimensions()}
 * directly, bypassing {@code Entity.getDimensions(Pose)} entirely. The
 * collision and pathfinding systems read from that cached field, not from the
 * method. So the mixin must intercept at the EntityType level to catch the
 * construction-time call.
 *
 * <p>The render model is untouched. Only the collision/pathfinding hitbox
 * shrinks.
 */
@Mixin(EntityType.class)
public abstract class VillagerDimensionsMixin {

    @Inject(
            method = "getDimensions()Lnet/minecraft/world/entity/EntityDimensions;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void shortvillagers$shrinkDimensions(CallbackInfoReturnable<EntityDimensions> cir) {
        if (!((Object) this == EntityTypes.VILLAGER)) {
            return;
        }
        EntityDimensions original = cir.getReturnValue();
        float targetHeight = (float) ShortVillagersConfig.targetHeight();
        if (original.height() <= targetHeight) {
            return;
        }
        float scaledEyeHeight = original.eyeHeight() * (targetHeight / original.height());
        EntityDimensions replacement = new EntityDimensions(
                original.width(),
                targetHeight,
                scaledEyeHeight,
                net.minecraft.world.entity.EntityAttachments.createDefault(original.width(), targetHeight),
                original.fixed()
        );
        cir.setReturnValue(replacement);
    }
}
