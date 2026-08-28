package thingy;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * Five aura-passive wondrous items that keep vanilla {@code MobEffectInstance}s
 * applied while their condition holds.
 */
public final class AuraEffects {

    private static final int DURATION = 200;

    private AuraEffects() {}

    public static void register() {
        // Owl Eye Goggles — head slot — Night Vision
        Aura.add((player, carried) -> {
            if (carried.wornAt(ItemRegistry.namespaced("owl_eye_goggles"), EquipmentSlot.HEAD)) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, DURATION, 0, false, false, true));
            }
        });

        // Fishy Necklace — either hand — Water Breathing + Dolphins Grace
        Aura.add((player, carried) -> {
            if (carried.held(ItemRegistry.namespaced("fishy_necklace"))) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.WATER_BREATHING, DURATION, 0, false, false, true));
                player.addEffect(new MobEffectInstance(
                        MobEffects.DOLPHINS_GRACE, DURATION, 0, false, false, true));
            }
        });

        // Toasty Scarf — chest slot — Fire Resistance
        Aura.add((player, carried) -> {
            if (carried.wornAt(ItemRegistry.namespaced("toasty_scarf"), EquipmentSlot.CHEST)) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.FIRE_RESISTANCE, DURATION, 0, false, false, true));
            }
        });

        // Floaty Feet — legs slot — Slow Falling
        Aura.add((player, carried) -> {
            if (carried.wornAt(ItemRegistry.namespaced("floaty_feet"), EquipmentSlot.LEGS)) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.SLOW_FALLING, DURATION, 0, false, false, true));
            }
        });

        // Zoomies Boots — feet slot while sprinting — Speed
        Aura.add((player, carried) -> {
            if (carried.wornAt(ItemRegistry.namespaced("zoomies_boots"), EquipmentSlot.FEET) && player.isSprinting()) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.SPEED, DURATION, 0, false, false, true));
            }
        });
    }
}
