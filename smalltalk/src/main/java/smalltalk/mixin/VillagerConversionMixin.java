package smalltalk.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smalltalk.identity.IdentityDeriver;
import smalltalk.social.FamiliarityAttachment;
import smalltalk.social.FamiliarityEntry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SPEC.md section 15.2: a villager's UUID changes on zombie conversion and
 * curing, which loses both the derived {@link smalltalk.identity.Identity}
 * (hashed from UUID, section 2) and the
 * {@link smalltalk.social.FamiliarityAttachment} (keyed on the villager
 * entity, section 7) across the cure. This mixin hooks
 * {@link Mob#convertTo(EntityType, ConversionParams, EntitySpawnReason, ConversionParams.AfterConversion)}
 * at the conversion boundary and copies both pieces of state from the old
 * entity to the new one, in both directions.
 *
 * <p>Requires {@code smalltalk.mixins.json} (see that file) and a
 * {@code "mixins"} entry in {@code fabric.mod.json}, both already wired to
 * point at this class.
 */
@Mixin(Mob.class)
public abstract class VillagerConversionMixin {

    // Inject after Mob.convertTo(...) returns so the replacement entity is fully
    // constructed and available from CallbackInfoReturnable. This is the exact
    // conversion boundary where villager/zombie-villager identity and familiarity
    // can be copied before callers continue with the new entity.
    @Inject(
            method = "convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;",
            at = @At("RETURN")
    )
    private void smalltalk$onConvertTo(
            EntityType<?> entityType,
            ConversionParams params,
            EntitySpawnReason spawnReason,
            ConversionParams.AfterConversion<?> afterConversion,
            CallbackInfoReturnable<Mob> cir
    ) {
        Mob converted = cir.getReturnValue();
        if (converted == null) {
            return;
        }
        if ((Object) this instanceof Villager oldVillager && converted instanceof ZombieVillager newZombie) {
            copyFamiliarity(oldVillager, newZombie);
            IdentityDeriver.pinIdentity(newZombie, identitySource(oldVillager));
        } else if ((Object) this instanceof ZombieVillager oldZombie && converted instanceof Villager newVillager) {
            copyFamiliarity(oldZombie, newVillager);
            IdentityDeriver.pinIdentity(newVillager, identitySource(oldZombie));
        }
    }

    private static UUID identitySource(Entity entity) {
        return IdentityDeriver.identitySource(entity).orElse(entity.getUUID());
    }

    private static void copyFamiliarity(Entity from, Entity to) {
        Map<UUID, FamiliarityEntry> map = from.getAttached(FamiliarityAttachment.FAMILIARITY);
        if (map != null && !map.isEmpty()) {
            to.setAttached(FamiliarityAttachment.FAMILIARITY, new LinkedHashMap<>(map));
        }
    }
}
