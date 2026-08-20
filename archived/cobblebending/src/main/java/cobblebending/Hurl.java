package cobblebending;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Resolves hurl charge tiers and spawns the boulder projectile.
 */
public final class Hurl {

    private Hurl() {}

    /** Selects the charge tier, consumes its cost, applies cooldown, and launches it. */
    public static void fire(ServerPlayer player, int chargeTicks) {
        BendConfig.Data config = CobbleBendingMod.config().data();
        BendConfig.Hurl h = config.hurl();

        int cobble;
        float damage;
        float speed;
        float gravity;
        float scale;
        int cooldown;
        float pitch;

        if (chargeTicks < h.lightThreshold()) {
            cobble = h.lightCobble();
            damage = h.lightDamage();
            speed = h.lightSpeed();
            gravity = h.lightGravity();
            scale = h.lightScale();
            cooldown = h.lightCooldown();
            pitch = 1.2f;
        } else if (chargeTicks < h.heavyThreshold()) {
            cobble = h.mediumCobble();
            damage = h.mediumDamage();
            speed = h.mediumSpeed();
            gravity = h.mediumGravity();
            scale = h.mediumScale();
            cooldown = h.mediumCooldown();
            pitch = 1.0f;
        } else {
            cobble = h.heavyCobble();
            damage = h.heavyDamage();
            speed = h.heavySpeed();
            gravity = h.heavyGravity();
            scale = h.heavyScale();
            cooldown = h.heavyCooldown();
            pitch = 0.8f;
        }

        if (!Ammo.canPay(player, cobble)) {
            return;
        }

        ItemStack held = player.getMainHandItem();
        if (!Focus.is(held)) {
            held = player.getOffhandItem();
        }

        Ammo.consume(player, cobble);
        player.getCooldowns().addCooldown(held, cooldown);
        Chime.hurlReleased(player, pitch);

        Boulder.spawn(player, speed, gravity, scale, damage, cooldown, h.heavySlownessDuration(), chargeTicks >= h.heavyThreshold());
    }
}
